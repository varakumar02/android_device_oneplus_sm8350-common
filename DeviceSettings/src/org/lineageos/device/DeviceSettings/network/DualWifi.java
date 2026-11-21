/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.lineageos.device.DeviceSettings.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Dual Wi-Fi: a second internet Wi-Fi connection next to the primary one, through the
 * framework's multi-internet STA+STA mode. Wi-Fi does not persist the mode, so it is set
 * again at boot. MultiInternetWifiNetworkFactory only brings up the second STA while an
 * INTERNET request with a band-only WifiNetworkSpecifier from a NETWORK_SETTINGS holder is
 * active; this sticky service holds one per band (the primary's band counts as connected, so
 * the other band gets the second connection). Callback requests, not PendingIntent ones:
 * ConnectivityService drops a PendingIntent request 5 s after its intent fires.
 * The switch summary shows which network is primary and which secondary, and a toast (like
 * the MEMC one) pops up when the secondary connects or drops, or apps move between the two;
 * the primary is what Wi-Fi shows anyway, so its changes get no toast.
 * One toast per change: events are collected for ANNOUNCE_DELAY_MS, since a single failover
 * is several network callbacks within a second.
 * A chosen secondary network pins the band request of the band it is seen on to its strongest
 * BSSID; Wi-Fi accepts a BSSID in a multi-internet request only from a NETWORK_SETTINGS holder
 * (MultiInternetWifiNetworkFactory.isWifiMultiInternetRequest). Once any band is pinned,
 * WifiConnectivityManager only takes pinned candidates as the secondary (getSpecifiedBssids),
 * so a pin on the primary's own band leaves none ("No secondary cmm candidate"). When the chosen
 * network is on the primary's band (usually it is the primary), the primary is moved to the
 * strongest other saved network on the other band first, as a tap in Wi-Fi settings would.
 */
public class DualWifi extends Service {

    private static final String TAG = "DualWifi";
    public static final String KEY = "dual_wifi";
    /** SSID of the network wanted as secondary, "" for automatic. */
    public static final String KEY_SECONDARY = "dual_wifi_secondary";
    private static final int[] BANDS = { ScanResult.WIFI_BAND_24_GHZ, ScanResult.WIFI_BAND_5_GHZ };

    // Channel of the ongoing notification older builds posted; deleted on start.
    private static final String OLD_CHANNEL = "dual_wifi";
    private static final long ANNOUNCE_DELAY_MS = 1500;

    private static volatile String sStatus;

    private final List<ConnectivityManager.NetworkCallback> mCallbacks = new ArrayList<>();
    // Band requests and the pin state below are only touched on the main thread.
    private final List<ConnectivityManager.NetworkCallback> mBandCallbacks = new ArrayList<>();
    // BSSID the band requests are pinned to, null = automatic.
    private String mPinnedBssid;
    // Primary the pin was last decided for: decided once per new primary.
    private Network mSeenPrimary;
    // Only touched on the main thread: every callback below is registered with mHandler.
    private final Map<Network, WifiInfo> mWifi = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Network mDefault;
    // Current state (labels, null = none) and the state the last toast described.
    private String mPrimary, mSecondary, mShownSecondary;
    private boolean mOnSecondary, mShownOnSecondary, mBaseline;
    private final Runnable mAnnounce = this::announce;

    /** "Primary: X (5 GHz) · Secondary: Y (2.4 GHz)", or null while the mode is off. */
    public static String status() {
        return sStatus;
    }

    public static boolean isSupported(Context context) {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        return wifi != null && wifi.isStaConcurrencyForMultiInternetSupported();
    }

    /**
     * MULTI_AP, not DBS_AP: DBS_AP only takes a second network with the primary's SSID
     * (WifiConnectivityManager), so routers that name their bands differently ("X" / "X 5G")
     * never get one. MULTI_AP takes any saved network on the other band, same router included.
     */
    private static int mode(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)
                ? WifiManager.WIFI_MULTI_INTERNET_MODE_MULTI_AP
                : WifiManager.WIFI_MULTI_INTERNET_MODE_DISABLED;
    }

    /** Applies the saved setting. */
    public static void apply(Context context) {
        if (!isSupported(context)) {
            return;
        }
        int mode = mode(context);
        context.getSystemService(WifiManager.class).setStaConcurrencyForMultiInternetMode(mode);
        Intent service = new Intent(context, DualWifi.class);
        // restart so the requests are made under the new mode
        context.stopService(service);
        if (mode != WifiManager.WIFI_MULTI_INTERNET_MODE_DISABLED) {
            context.startService(service);
        }
        Log.i(TAG, "mode " + mode);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        requestBands(null);

        getSystemService(NotificationManager.class).deleteNotificationChannel(OLD_CHANNEL);
        // Every Wi-Fi network, the secondary included (it may not be NOT_RESTRICTED);
        // location info so WifiInfo keeps the SSID.
        ConnectivityManager.NetworkCallback wifi = new ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.getTransportInfo() instanceof WifiInfo) {
                    mWifi.put(network, (WifiInfo) caps.getTransportInfo());
                    update();
                }
            }

            @Override
            public void onLost(Network network) {
                mWifi.remove(network);
                update();
            }
        };
        cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build(), wifi, mHandler);
        mCallbacks.add(wifi);
        ConnectivityManager.NetworkCallback def = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mDefault = network;
                update();
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(mDefault)) {
                    mDefault = null;
                    update();
                }
            }
        };
        cm.registerDefaultNetworkCallback(def, mHandler);
        mCallbacks.add(def);
        update();
    }

    /**
     * Decides the pin when a new primary connects (scan results exist by then). Requests are
     * only remade when the pin changes: dropping a band request disconnects a secondary on it.
     */
    private void onNewPrimary(WifiInfo primary) {
        String chosen = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(KEY_SECONDARY, "");
        ScanResult pin = strongest(chosen);
        int primaryBand = band(primary.getFrequency());
        if (pin != null && band(pin.frequency) == primaryBand) {
            pin = null;
            movePrimaryOffBand(chosen, primaryBand);
        }
        if (!TextUtils.equals(pin != null ? pin.BSSID : null, mPinnedBssid)) {
            requestBands(pin);
        }
    }

    /** Connects the primary to the strongest saved network seen on the other Dual Wi-Fi band. */
    private void movePrimaryOffBand(String chosen, int primaryBand) {
        WifiManager wm = getSystemService(WifiManager.class);
        List<WifiConfiguration> saved = wm.getConfiguredNetworks();
        ScanResult best = null;
        WifiConfiguration target = null;
        for (ScanResult r : wm.getScanResults()) {
            int b = band(r.frequency);
            if (b == primaryBand || (b != BANDS[0] && b != BANDS[1]) || chosen.equals(r.SSID)
                    || (best != null && r.level <= best.level)) {
                continue;
            }
            for (WifiConfiguration c : saved) {
                if (r.SSID.equals(unquote(c.SSID))) {
                    best = r;
                    target = c;
                    break;
                }
            }
        }
        if (target == null) {
            Log.w(TAG, "no saved network on the other band, " + chosen + " stays primary");
            return;
        }
        Log.i(TAG, "primary to " + best.SSID + " so " + chosen + " can be secondary");
        wm.connect(target.networkId, null);
    }

    /** One band request per band; the band of {@code pin}, if any, carries its BSSID. */
    private void requestBands(ScanResult pin) {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (ConnectivityManager.NetworkCallback cb : mBandCallbacks) {
            cm.unregisterNetworkCallback(cb);
        }
        mBandCallbacks.clear();
        mPinnedBssid = pin != null ? pin.BSSID : null;
        for (int band : BANDS) {
            WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setBand(band);
            if (pin != null && band(pin.frequency) == band) {
                spec.setBssid(MacAddress.fromString(pin.BSSID));
                Log.i(TAG, "band " + band + " pinned to " + pin.SSID);
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(spec.build())
                    .build();
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "band " + band + " connected: " + network);
                }

                @Override
                public void onLost(Network network) {
                    Log.i(TAG, "band " + band + " lost: " + network);
                }
            };
            cm.requestNetwork(request, cb);
            mBandCallbacks.add(cb);
        }
    }

    private static String unquote(String ssid) {
        if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private static int band(int mhz) {
        return mhz < 3000 ? ScanResult.WIFI_BAND_24_GHZ
                : mhz < 5925 ? ScanResult.WIFI_BAND_5_GHZ : ScanResult.WIFI_BAND_6_GHZ;
    }

    /** Saved network SSIDs for the secondary picker, sorted. */
    public static String[] savedSsids(Context context) {
        TreeSet<String> ssids = new TreeSet<>();
        List<WifiConfiguration> saved =
                context.getSystemService(WifiManager.class).getConfiguredNetworks();
        if (saved != null) {
            for (WifiConfiguration c : saved) {
                if (c.SSID != null) {
                    ssids.add(unquote(c.SSID));
                }
            }
        }
        return ssids.toArray(new String[0]);
    }

    /**
     * Strongest BSSID of {@code ssid} on a Dual Wi-Fi band in the last scan, or null.
     */
    private ScanResult strongest(String ssid) {
        if (ssid.isEmpty()) {
            return null;
        }
        ScanResult best = null;
        for (ScanResult r : getSystemService(WifiManager.class).getScanResults()) {
            int b = band(r.frequency);
            if (ssid.equals(r.SSID) && (b == BANDS[0] || b == BANDS[1])
                    && (best == null || r.level > best.level)) {
                best = r;
            }
        }
        if (best == null) {
            Log.w(TAG, ssid + " not in scan results, automatic secondary");
        }
        return best;
    }

    private static String label(WifiInfo info) {
        int mhz = info.getFrequency();
        String band = mhz < 3000 ? "2.4 GHz" : mhz < 5925 ? "5 GHz" : "6 GHz";
        return unquote(info.getSSID()) + " (" + band + ")";
    }

    private void update() {
        Network primary = null;
        Network secondary = null;
        WifiInfo primaryInfo = null;
        WifiInfo secondaryInfo = null;
        for (Map.Entry<Network, WifiInfo> e : mWifi.entrySet()) {
            if (e.getValue().isPrimary()) {
                primary = e.getKey();
                primaryInfo = e.getValue();
            } else {
                secondary = e.getKey();
                secondaryInfo = e.getValue();
            }
        }
        if (primary != null && !primary.equals(mSeenPrimary)) {
            mSeenPrimary = primary;
            onNewPrimary(primaryInfo);
        }
        String none = getString(R.string.dual_wifi_none);
        String status = getString(R.string.dual_wifi_status,
                primaryInfo != null ? label(primaryInfo) : none,
                secondaryInfo != null ? label(secondaryInfo) : none);
        if (secondary != null && secondary.equals(mDefault)) {
            status += "\n" + getString(R.string.dual_wifi_apps_on, label(secondaryInfo));
        }
        if (!status.equals(sStatus)) {
            Log.i(TAG, status.replace('\n', ' '));
        }
        sStatus = status;

        mPrimary = primaryInfo != null ? label(primaryInfo) : null;
        mSecondary = secondaryInfo != null ? label(secondaryInfo) : null;
        mOnSecondary = secondary != null && secondary.equals(mDefault);
        mHandler.removeCallbacks(mAnnounce);
        mHandler.postDelayed(mAnnounce, ANNOUNCE_DELAY_MS);
    }

    /** Toasts what changed since the last toast; the first run only records the state. */
    private void announce() {
        StringBuilder msg = new StringBuilder();
        if (mBaseline) {
            if (!TextUtils.equals(mSecondary, mShownSecondary)) {
                if (mSecondary != null) {
                    line(msg, getString(R.string.dual_wifi_toast_secondary_on, mSecondary));
                } else {
                    line(msg, getString(R.string.dual_wifi_toast_secondary_off));
                }
            }
            if (mOnSecondary && !mShownOnSecondary) {
                line(msg, getString(R.string.dual_wifi_toast_apps_secondary, mSecondary));
            } else if (!mOnSecondary && mShownOnSecondary && mPrimary != null) {
                line(msg, getString(R.string.dual_wifi_toast_apps_primary, mPrimary));
            }
        }
        mBaseline = true;
        mShownSecondary = mSecondary;
        mShownOnSecondary = mOnSecondary;
        if (msg.length() > 0) {
            Log.i(TAG, "toast: " + msg.toString().replace('\n', ' '));
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    private static void line(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(s);
    }

    @Override
    public void onDestroy() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (ConnectivityManager.NetworkCallback cb : mCallbacks) {
            cm.unregisterNetworkCallback(cb);
        }
        mCallbacks.clear();
        for (ConnectivityManager.NetworkCallback cb : mBandCallbacks) {
            cm.unregisterNetworkCallback(cb);
        }
        mBandCallbacks.clear();
        mWifi.clear();
        sStatus = null;
        mHandler.removeCallbacks(mAnnounce);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
