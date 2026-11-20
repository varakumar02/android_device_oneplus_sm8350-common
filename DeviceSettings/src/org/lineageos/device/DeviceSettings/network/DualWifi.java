/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.IBinder;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Dual Wi-Fi: a second internet Wi-Fi connection next to the primary one, through the
 * framework's multi-internet STA+STA mode. Wi-Fi does not persist the mode, so it is set
 * again at boot. MultiInternetWifiNetworkFactory only brings up the second STA while an
 * INTERNET request with a band-only WifiNetworkSpecifier from a NETWORK_SETTINGS holder is
 * active; this sticky service holds one per band (the primary's band counts as connected, so
 * the other band gets the second connection). Callback requests, not PendingIntent ones:
 * ConnectivityService drops a PendingIntent request 5 s after its intent fires.
 */
public class DualWifi extends Service {

    private static final String TAG = "DualWifi";
    public static final String KEY = "dual_wifi";
    private static final int[] BANDS = { ScanResult.WIFI_BAND_24_GHZ, ScanResult.WIFI_BAND_5_GHZ };

    private final List<ConnectivityManager.NetworkCallback> mCallbacks = new ArrayList<>();

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
    }

    @Override
    public void onDestroy() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (ConnectivityManager.NetworkCallback cb : mCallbacks) {
            cm.unregisterNetworkCallback(cb);
        }
        mCallbacks.clear();
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
