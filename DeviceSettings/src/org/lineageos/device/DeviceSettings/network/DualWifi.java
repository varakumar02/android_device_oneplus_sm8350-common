/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.util.Log;

import androidx.preference.PreferenceManager;

/**
 * Dual Wi-Fi: a second internet Wi-Fi connection next to the primary one, through the
 * framework's multi-internet STA+STA mode. The mode is not persisted by Wi-Fi, so it is set
 * again at boot. The framework only brings up the second STA for a band request from a
 * caller with NETWORK_SETTINGS (MultiInternetWifiNetworkFactory); a request per band is held,
 * and the band the primary already uses counts as connected, so the other band gets the
 * second connection. PendingIntent requests outlive this process.
 */
public final class DualWifi {

    private static final String TAG = "DualWifi";
    public static final String KEY_MODE = "dual_wifi_mode";
    private static final String ACTION = "org.lineageos.device.DeviceSettings.DUAL_WIFI";
    private static final int[] BANDS = { ScanResult.WIFI_BAND_24_GHZ, ScanResult.WIFI_BAND_5_GHZ };

    private DualWifi() {
    }

    public static boolean isSupported(Context context) {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        return wifi != null && wifi.isStaConcurrencyForMultiInternetSupported();
    }

    /** Applies the saved mode: WifiManager.WIFI_MULTI_INTERNET_MODE_* (0 off). */
    public static void apply(Context context) {
        if (!isSupported(context)) {
            return;
        }
        int mode = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_MODE, "0"));
        context.getSystemService(WifiManager.class).setStaConcurrencyForMultiInternetMode(mode);

        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (int band : BANDS) {
            PendingIntent pi = PendingIntent.getBroadcast(context, band,
                    new Intent(ACTION).setPackage(context.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            cm.releaseNetworkRequest(pi);
            if (mode == WifiManager.WIFI_MULTI_INTERNET_MODE_DISABLED) {
                continue;
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(new WifiNetworkSpecifier.Builder().setBand(band).build())
                    .build();
            cm.requestNetwork(request, pi);
        }
        Log.i(TAG, "mode " + mode);
    }
}
