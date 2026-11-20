/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.device.DeviceSettings.DeviceSettingsActivity;
import org.lineageos.device.DeviceSettings.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dual Wi-Fi: a second internet Wi-Fi connection next to the primary one, through the
 * framework's multi-internet STA+STA mode. Wi-Fi does not persist the mode, so it is set
 * again at boot. MultiInternetWifiNetworkFactory only brings up the second STA while an
 * INTERNET request with a band-only WifiNetworkSpecifier from a NETWORK_SETTINGS holder is
 * active; this sticky service holds one per band (the primary's band counts as connected, so
 * the other band gets the second connection). Callback requests, not PendingIntent ones:
 * ConnectivityService drops a PendingIntent request 5 s after its intent fires.
 * While the second connection is up it shows which network is primary and which secondary,
 * in a silent notification and in the switch summary.
 */
public class DualWifi extends Service {

    private static final String TAG = "DualWifi";
    public static final String KEY = "dual_wifi";
    private static final int[] BANDS = { ScanResult.WIFI_BAND_24_GHZ, ScanResult.WIFI_BAND_5_GHZ };

    private static final String CHANNEL = "dual_wifi";
    private static final int NOTIFICATION_ID = 1;

    private static volatile String sStatus;

    private final List<ConnectivityManager.NetworkCallback> mCallbacks = new ArrayList<>();
    // Only touched on the main thread: every callback below is registered with mHandler.
    private final Map<Network, WifiInfo> mWifi = new HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private Network mDefault;

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
        for (int band : BANDS) {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(new WifiNetworkSpecifier.Builder().setBand(band).build())
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
            mCallbacks.add(cb);
        }

        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.dual_wifi_title), NotificationManager.IMPORTANCE_LOW));
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

    private static String label(WifiInfo info) {
        String ssid = info.getSSID();
        if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        int mhz = info.getFrequency();
        String band = mhz < 3000 ? "2.4 GHz" : mhz < 5925 ? "5 GHz" : "6 GHz";
        return ssid + " (" + band + ")";
    }

    private void update() {
        Network secondary = null;
        WifiInfo primaryInfo = null;
        WifiInfo secondaryInfo = null;
        for (Map.Entry<Network, WifiInfo> e : mWifi.entrySet()) {
            if (e.getValue().isPrimary()) {
                primaryInfo = e.getValue();
            } else {
                secondary = e.getKey();
                secondaryInfo = e.getValue();
            }
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

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (secondaryInfo == null) {
            nm.cancel(NOTIFICATION_ID);
            return;
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, DeviceSettingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_dual_wifi)
                .setContentTitle(getString(R.string.dual_wifi_notif_title))
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    @Override
    public void onDestroy() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (ConnectivityManager.NetworkCallback cb : mCallbacks) {
            cm.unregisterNetworkCallback(cb);
        }
        mCallbacks.clear();
        mWifi.clear();
        sStatus = null;
        getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
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
