/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.TrafficStats;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.List;

/**
 * Smart 5G: with the screen off and mobile data idle, the modem is kept on LTE (5G NR
 * disallowed for the POWER reason); turning the screen on allows NR again at once. Band lock
 * uses the USER reason, telephony ANDs the reasons, so the two never overwrite each other.
 */
public class Smart5gService extends Service {

    private static final String TAG = "Smart5g";
    public static final String KEY = "smart_5g";
    private static final long IDLE_CHECK_MS = 60_000;
    private static final long IDLE_BYTES = 512 * 1024;  // less than this in the minute = idle

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mBytesAtScreenOff;

    private final Runnable mIdleCheck = () -> {
        long used = mobileBytes() - mBytesAtScreenOff;
        // ponytail: one check per screen-off; a download that starts later stays on LTE
        // until the screen turns on.
        if (used < IDLE_BYTES) {
            setNrAllowed(this, false);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.removeCallbacks(mIdleCheck);
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mBytesAtScreenOff = mobileBytes();
                mHandler.postDelayed(mIdleCheck, IDLE_CHECK_MS);
            } else {
                setNrAllowed(context, true);
            }
        }
    };

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false);
    }

    /** Starts or stops the service to match the user's setting. */
    public static void sync(Context context) {
        Intent intent = new Intent(context, Smart5gService.class);
        if (isEnabled(context)) {
            context.startService(intent);
        } else {
            context.stopService(intent);
            setNrAllowed(context, true);
        }
    }

    private static long mobileBytes() {
        return TrafficStats.getMobileRxBytes() + TrafficStats.getMobileTxBytes();
    }

    static void setNrAllowed(Context context, boolean allowed) {
        SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        TelephonyManager tm = context.getSystemService(TelephonyManager.class);
        List<SubscriptionInfo> subs = sm != null ? sm.getActiveSubscriptionInfoList() : null;
        if (subs == null || tm == null) {
            return;
        }
        for (SubscriptionInfo info : subs) {
            TelephonyManager sub = tm.createForSubscriptionId(info.getSubscriptionId());
            long mask = sub.getAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER);
            long wanted = allowed ? mask | TelephonyManager.NETWORK_TYPE_BITMASK_NR
                    : mask & ~TelephonyManager.NETWORK_TYPE_BITMASK_NR;
            if (wanted != mask) {
                sub.setAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER, wanted);
                Log.i(TAG, "sub " + info.getSubscriptionId() + " NR " + (allowed ? "on" : "off"));
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mReceiver, filter, null, mHandler, Context.RECEIVER_NOT_EXPORTED);
        // Restarted while the screen is on (or after a crash with NR left off): allow it.
        if (getSystemService(PowerManager.class).isInteractive()) {
            setNrAllowed(this, true);
        }
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mReceiver);
        mHandler.removeCallbacks(mIdleCheck);
        setNrAllowed(this, true);
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
