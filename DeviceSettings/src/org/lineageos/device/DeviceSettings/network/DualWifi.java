/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import org.lineageos.device.DeviceSettings.R;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Dual Wi-Fi: a main Wi-Fi plus a ready backup Wi-Fi on the other band, through the framework's
 * multi-internet STA+STA mode (MULTI_AP). Wi-Fi picks the main network as usual; this sticky
 * service only drives the backup:
 * - Wi-Fi does not persist the mode, so apply() sets it at boot and on every change.
 * - MultiInternetWifiNetworkFactory only brings up the second STA while an INTERNET request
 *   with a band WifiNetworkSpecifier from a NETWORK_SETTINGS holder is active: one callback
 *   request per band (PendingIntent requests are dropped 5 s after their intent fires).
 * - A chosen backup pins the request of its band to its strongest BSSID on a band the main
 *   network is not on. Once any band is pinned WifiConnectivityManager only takes pinned
 *   BSSIDs as the secondary (getSpecifiedBssids), so a pin on the main network's band would
 *   leave none; such networks are offered greyed out on the page and never pinned.
 * - Fast switch: ConnectivityService only notices a dead main network when NetworkMonitor sees
 *   a data stall (DNS timeouts from app traffic) or revalidates, about 2 minutes in the
 *   failover test. While a backup is connected, the screen is on and apps are on the main
 *   network, the main network is probed every 10 s; two failed probes report it with
 *   reportNetworkConnectivity(main, false), which makes NetworkMonitor revalidate at once.
 * - Save battery when locked: the band requests are released while the screen is off (the
 *   backup disconnects) and made again when it turns on.
 * - Toasts only when apps move between the two, or as feedback right after the user changes
 *   something. The page (DualWifiFragment) and the tile read state().
 */
public class DualWifi extends Service {

    private static final String TAG = "DualWifi";
    public static final String KEY = "dual_wifi";
    /** SSID of the wanted backup, "" for automatic. */
    public static final String KEY_BACKUP = "dual_wifi_secondary";
    public static final String KEY_FAST = "dual_wifi_fast";
    public static final String KEY_SAVE_LOCKED = "dual_wifi_save_locked";
    private static final String EXTRA_USER = "user";
    private static final int[] BANDS = { ScanResult.WIFI_BAND_24_GHZ, ScanResult.WIFI_BAND_5_GHZ };

    // Channel of the ongoing notification older builds posted; deleted on start.
    private static final String OLD_CHANNEL = "dual_wifi";
    private static final long ANNOUNCE_DELAY_MS = 1500;
    private static final long FEEDBACK_MS = 60_000;
    private static final long PROBE_INTERVAL_MS = 10_000;
    private static final long PROBE_BACKOFF_MS = 30_000;
    private static final int PROBE_TIMEOUT_MS = 3000;
    private static final int PROBE_FAILS = 2;
    private static final String PROBE_URL = "https://www.google.com/generate_204";
    private static final int LOG_SIZE = 8;

    /** A snapshot for the page and the tile; labels are null when that connection is absent. */
    public static final class State {
        public final boolean running;
        public final String main, mainSsid, mainDetail, backup, backupDetail;
        public final int mainBand;
        public final boolean onBackup, paused;

        State(boolean running, String main, String mainSsid, String mainDetail, int mainBand,
                String backup, String backupDetail, boolean onBackup, boolean paused) {
            this.running = running;
            this.main = main;
            this.mainSsid = mainSsid;
            this.mainDetail = mainDetail;
            this.mainBand = mainBand;
            this.backup = backup;
            this.backupDetail = backupDetail;
            this.onBackup = onBackup;
            this.paused = paused;
        }
    }

    /** A saved network as a backup choice. */
    public static final class Choice {
        public final String ssid;
        /** null when it can be the backup now, else why not. */
        public final String unavailable;

        Choice(String ssid, String unavailable) {
            this.ssid = ssid;
            this.unavailable = unavailable;
        }
    }

    private static final State OFF = new State(false, null, null, null, 0, null, null, false, false);
    private static volatile State sState = OFF;
    // Listeners and the log are used on the main thread only.
    private static final CopyOnWriteArraySet<Runnable> sListeners = new CopyOnWriteArraySet<>();
    private static final List<String> sLog = new ArrayList<>();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<ConnectivityManager.NetworkCallback> mCallbacks = new ArrayList<>();
    // Everything below is touched on the main thread only, except the probe fields.
    private final List<ConnectivityManager.NetworkCallback> mBandCallbacks = new ArrayList<>();
    private final Map<Network, WifiInfo> mWifi = new HashMap<>();
    private Network mDefault;
    private Network mSeenPrimary;
    private ScanResult mPin;
    private String mPinnedBssid;
    private boolean mRequested;
    private boolean mPaused;
    // The backup went away because of the pause: its comeback is not logged either.
    private boolean mQuiet;
    private long mFeedbackUntil;
    private String mBackup, mMain;
    private boolean mOnBackup;
    private String mShownBackup;
    private boolean mShownOnBackup, mBaseline;
    private final Runnable mAnnounce = this::announce;
    private BroadcastReceiver mScreenReceiver;

    private HandlerThread mProbeThread;
    private Handler mProbeHandler;
    private volatile Network mProbeTarget;
    private int mProbeFails;
    private final Runnable mProbe = this::probeTick;

    public static boolean isSupported(Context context) {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        return wifi != null && wifi.isStaConcurrencyForMultiInternetSupported();
    }

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false);
    }

    public static State state() {
        return sState;
    }

    /** Recent events, newest first, "19:22  Switched to Binny (2.4 GHz)". */
    public static List<String> log() {
        return new ArrayList<>(sLog);
    }

    public static void addListener(Runnable r) {
        sListeners.add(r);
    }

    public static void removeListener(Runnable r) {
        sListeners.remove(r);
    }

    /**
     * MULTI_AP, not DBS_AP: DBS_AP only takes a second network with the main network's SSID
     * (WifiConnectivityManager), so routers that name their bands apart ("X" / "X 5G") never
     * get one. MULTI_AP takes any saved network on the other band, same router included.
     */
    private static int mode(Context context) {
        return isEnabled(context) ? WifiManager.WIFI_MULTI_INTERNET_MODE_MULTI_AP
                : WifiManager.WIFI_MULTI_INTERNET_MODE_DISABLED;
    }

    /** Applies the saved settings (boot). */
    public static void apply(Context context) {
        apply(context, false);
    }

    /** Applies the saved settings; {@code user}: a change the user just made, gets feedback. */
    public static void apply(Context context, boolean user) {
        if (!isSupported(context)) {
            return;
        }
        int mode = mode(context);
        context.getSystemService(WifiManager.class).setStaConcurrencyForMultiInternetMode(mode);
        Intent service = new Intent(context, DualWifi.class);
        // restart so the requests are made under the new settings
        context.stopService(service);
        if (mode != WifiManager.WIFI_MULTI_INTERNET_MODE_DISABLED) {
            context.startService(service.putExtra(EXTRA_USER, user));
        }
        Log.i(TAG, "mode " + mode);
    }

    /** Saved networks, with why each cannot be the backup right now. */
    public static List<Choice> choices(Context context) {
        WifiManager wm = context.getSystemService(WifiManager.class);
        List<ScanResult> scan = wm.getScanResults();
        State s = sState;
        TreeSet<String> ssids = new TreeSet<>();
        List<WifiConfiguration> saved = wm.getConfiguredNetworks();
        if (saved != null) {
            for (WifiConfiguration c : saved) {
                if (c.SSID != null) {
                    ssids.add(unquote(c.SSID));
                }
            }
        }
        List<Choice> out = new ArrayList<>();
        for (String ssid : ssids) {
            String why = null;
            if (strongest(scan, ssid, s.mainBand) == null) {
                if (ssid.equals(s.mainSsid)) {
                    why = context.getString(R.string.dual_wifi_choice_main);
                } else if (strongest(scan, ssid, 0) != null) {
                    why = context.getString(R.string.dual_wifi_choice_same_band);
                } else {
                    why = context.getString(R.string.dual_wifi_choice_out_of_range);
                }
            }
            out.add(new Choice(ssid, why));
        }
        return out;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        getSystemService(NotificationManager.class).deleteNotificationChannel(OLD_CHANNEL);

        if (prefs().getBoolean(KEY_SAVE_LOCKED, false)) {
            mPaused = !getSystemService(PowerManager.class).isInteractive();
            mScreenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mPaused = Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
                    if (mPaused && mBackup != null) {
                        mQuiet = true;
                    }
                    syncRequests();
                    update();
                }
            };
            IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            screen.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(mScreenReceiver, screen);
        }
        syncRequests();

        // Every Wi-Fi network, the backup included (it may not be NOT_RESTRICTED);
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

        if (prefs().getBoolean(KEY_FAST, true)) {
            mProbeThread = new HandlerThread(TAG);
            mProbeThread.start();
            mProbeHandler = new Handler(mProbeThread.getLooper());
            mProbeHandler.postDelayed(mProbe, PROBE_INTERVAL_MS);
        }
        update();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_USER, false)) {
            mFeedbackUntil = SystemClock.elapsedRealtime() + FEEDBACK_MS;
            // A new choice is checked against the current main network right away.
            mSeenPrimary = null;
            update();
        }
        return START_STICKY;
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    private boolean feedback() {
        return SystemClock.elapsedRealtime() < mFeedbackUntil;
    }

    /** Decides the pin when a new main network connects (scan results exist by then). */
    private void onNewPrimary(WifiInfo primary) {
        String chosen = prefs().getString(KEY_BACKUP, "");
        if (chosen.isEmpty()) {
            mPin = null;
        } else {
            mPin = strongest(getSystemService(WifiManager.class).getScanResults(), chosen,
                    band(primary.getFrequency()));
            if (mPin == null) {
                Log.w(TAG, chosen + " not usable as backup next to " + primary.getSSID());
                if (feedback()) {
                    toast(getString(R.string.dual_wifi_toast_unavailable, chosen));
                }
            }
        }
        syncRequests();
    }

    /**
     * Makes the band requests match the pin and the pause state. Requests are only remade when
     * that changes: dropping a band request disconnects a backup connected on that band
     * (MultiInternetManager.setMultiInternetConnectionWorksource(band, null, null)).
     */
    private void syncRequests() {
        String bssid = mPin != null ? mPin.BSSID : null;
        boolean want = !mPaused;
        if (want == mRequested && (!want || TextUtils.equals(bssid, mPinnedBssid))) {
            return;
        }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (ConnectivityManager.NetworkCallback cb : mBandCallbacks) {
            cm.unregisterNetworkCallback(cb);
        }
        mBandCallbacks.clear();
        mRequested = want;
        mPinnedBssid = want ? bssid : null;
        if (!want) {
            Log.i(TAG, "backup paused");
            return;
        }
        for (int band : BANDS) {
            WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setBand(band);
            if (mPin != null && band(mPin.frequency) == band) {
                spec.setBssid(MacAddress.fromString(mPin.BSSID));
                Log.i(TAG, "band " + band + " pinned to " + mPin.SSID);
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

    /** Strongest BSSID of {@code ssid} on a Dual Wi-Fi band other than {@code exclude}. */
    private static ScanResult strongest(List<ScanResult> scan, String ssid, int exclude) {
        ScanResult best = null;
        for (ScanResult r : scan) {
            int b = band(r.frequency);
            if (ssid.equals(r.SSID) && b != exclude && (b == BANDS[0] || b == BANDS[1])
                    && (best == null || r.level > best.level)) {
                best = r;
            }
        }
        return best;
    }

    private static String label(WifiInfo info) {
        int mhz = info.getFrequency();
        String band = mhz < 3000 ? "2.4 GHz" : mhz < 5925 ? "5 GHz" : "6 GHz";
        return unquote(info.getSSID()) + " (" + band + ")";
    }

    private static String detail(WifiInfo info) {
        return info.getRssi() + " dBm · " + info.getLinkSpeed() + " Mbps";
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
        mMain = primaryInfo != null ? label(primaryInfo) : null;
        mBackup = secondaryInfo != null ? label(secondaryInfo) : null;
        mOnBackup = secondary != null && secondary.equals(mDefault);
        mProbeTarget = secondary != null && !mOnBackup ? primary : null;

        sState = new State(true, mMain, primaryInfo != null ? unquote(primaryInfo.getSSID()) : null,
                primaryInfo != null ? detail(primaryInfo) : null,
                primaryInfo != null ? band(primaryInfo.getFrequency()) : 0,
                mBackup, secondaryInfo != null ? detail(secondaryInfo) : null, mOnBackup, mPaused);
        notifyListeners(this);
        mHandler.removeCallbacks(mAnnounce);
        mHandler.postDelayed(mAnnounce, ANNOUNCE_DELAY_MS);
    }

    private static void notifyListeners(Context context) {
        for (Runnable r : sListeners) {
            r.run();
        }
        TileService.requestListeningState(context,
                new ComponentName(context, DualWifiTileService.class));
    }

    /** Logs what changed since the last call; toasts switches and feedback only. */
    private void announce() {
        if (mBaseline) {
            if (!TextUtils.equals(mBackup, mShownBackup)) {
                if (mBackup != null) {
                    if (feedback()) {
                        toast(getString(R.string.dual_wifi_toast_ready, mBackup));
                        mFeedbackUntil = 0;
                    }
                    if (!mQuiet) {
                        addLog(getString(R.string.dual_wifi_log_backup_on, mBackup));
                    }
                    mQuiet = false;
                } else if (!mQuiet) {
                    addLog(getString(R.string.dual_wifi_log_backup_off, mShownBackup));
                }
            }
            if (mOnBackup && !mShownOnBackup) {
                String s = getString(R.string.dual_wifi_toast_switched, mBackup);
                addLog(s);
                toast(s);
            } else if (!mOnBackup && mShownOnBackup && mMain != null) {
                String s = getString(R.string.dual_wifi_toast_back, mMain);
                addLog(s);
                toast(s);
            }
        }
        mBaseline = true;
        mShownBackup = mBackup;
        mShownOnBackup = mOnBackup;
    }

    private void toast(String s) {
        Log.i(TAG, "toast: " + s);
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void addLog(String s) {
        Log.i(TAG, "log: " + s);
        sLog.add(0, DateFormat.getTimeFormat(this).format(new Date()) + "  " + s);
        while (sLog.size() > LOG_SIZE) {
            sLog.remove(sLog.size() - 1);
        }
        notifyListeners(this);
    }

    /** Probe thread: checks the main network while the backup could take over. */
    private void probeTick() {
        long next = PROBE_INTERVAL_MS;
        Network target = mProbeTarget;
        if (target != null && getSystemService(PowerManager.class).isInteractive()) {
            if (probe(target)) {
                mProbeFails = 0;
            } else if (++mProbeFails >= PROBE_FAILS) {
                mProbeFails = 0;
                next = PROBE_BACKOFF_MS;
                mHandler.post(() -> {
                    Log.i(TAG, "main network not responding, asking for revalidation");
                    addLog(getString(R.string.dual_wifi_log_fast));
                    getSystemService(ConnectivityManager.class)
                            .reportNetworkConnectivity(target, false);
                });
            }
        } else {
            mProbeFails = 0;
        }
        mProbeHandler.postDelayed(mProbe, next);
    }

    private boolean probe(Network network) {
        String url = Settings.Global.getString(getContentResolver(), "captive_portal_https_url");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) network.openConnection(new URL(
                    TextUtils.isEmpty(url) ? PROBE_URL : url));
            c.setConnectTimeout(PROBE_TIMEOUT_MS);
            c.setReadTimeout(PROBE_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            return c.getResponseCode() == 204;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
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
        if (mScreenReceiver != null) {
            unregisterReceiver(mScreenReceiver);
        }
        if (mProbeThread != null) {
            mProbeHandler.removeCallbacks(mProbe);
            mProbeThread.quitSafely();
        }
        mHandler.removeCallbacks(mAnnounce);
        mWifi.clear();
        sState = OFF;
        notifyListeners(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
