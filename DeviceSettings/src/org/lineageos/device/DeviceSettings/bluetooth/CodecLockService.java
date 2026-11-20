/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.bluetooth;

import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecType;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;

/**
 * Keeps the codec the user picked (Developer options: codec, sample rate, bits, LDAC quality)
 * per Bluetooth device and sets it again each time that device becomes the active A2DP output.
 * Codec changes in the first seconds after a device turns active are the stack's own default
 * pick and are not saved.
 */
public class CodecLockService extends Service {

    private static final String TAG = "CodecLock";
    public static final String KEY = "bt_codec_lock";
    private static final String PREFIX = "bt_codec_lock_";
    private static final long SETTLE_MS = 5_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter mAdapter;
    private BluetoothA2dp mA2dp;
    private long mIgnoreUntil;

    private final BluetoothProfile.ServiceListener mListener =
            new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mA2dp = (BluetoothA2dp) proxy;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            mA2dp = null;
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice.class);
            if (device == null) {
                return;
            }
            if (BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED.equals(intent.getAction())) {
                mIgnoreUntil = SystemClock.elapsedRealtime() + SETTLE_MS;
                mHandler.postDelayed(() -> restore(device), SETTLE_MS);
            } else if (SystemClock.elapsedRealtime() > mIgnoreUntil) {
                BluetoothCodecStatus status = intent.getParcelableExtra(
                        BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus.class);
                if (status != null && status.getCodecConfig() != null) {
                    save(device, status.getCodecConfig());
                }
            }
        }
    };

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false);
    }

    /** Starts or stops the service to match the user's setting. */
    public static void sync(Context context) {
        Intent intent = new Intent(context, CodecLockService.class);
        if (isEnabled(context)) {
            context.startService(intent);
        } else {
            context.stopService(intent);
        }
    }

    private SharedPreferences prefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    private void save(BluetoothDevice device, BluetoothCodecConfig c) {
        BluetoothCodecType type = c.getExtendedCodecType();
        if (type == null) {
            return;
        }
        String value = type.getCodecId() + "," + c.getSampleRate() + "," + c.getBitsPerSample()
                + "," + c.getChannelMode() + "," + c.getCodecSpecific1() + ","
                + c.getCodecSpecific2() + "," + c.getCodecSpecific3() + ","
                + c.getCodecSpecific4();
        prefs().edit().putString(PREFIX + device.getAddress(), value).apply();
        Log.i(TAG, "saved " + device.getAddress() + " " + type.getCodecName() + " " + value);
    }

    private void restore(BluetoothDevice device) {
        String saved = prefs().getString(PREFIX + device.getAddress(), null);
        if (saved == null || mA2dp == null || mAdapter == null
                || !mAdapter.getActiveDevices(BluetoothProfile.A2DP).contains(device)) {
            return;
        }
        String[] v = saved.split(",");
        BluetoothCodecType type = null;
        for (BluetoothCodecType t : mA2dp.getSupportedCodecTypes()) {
            if (t.getCodecId() == Long.parseLong(v[0])) {
                type = t;
            }
        }
        if (type == null) {
            return;
        }
        BluetoothCodecConfig config = new BluetoothCodecConfig.Builder()
                .setExtendedCodecType(type)
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .setSampleRate(Integer.parseInt(v[1]))
                .setBitsPerSample(Integer.parseInt(v[2]))
                .setChannelMode(Integer.parseInt(v[3]))
                .setCodecSpecific1(Long.parseLong(v[4]))
                .setCodecSpecific2(Long.parseLong(v[5]))
                .setCodecSpecific3(Long.parseLong(v[6]))
                .setCodecSpecific4(Long.parseLong(v[7]))
                .build();
        // our own change comes back as a codec-config broadcast: don't save it again
        mIgnoreUntil = SystemClock.elapsedRealtime() + SETTLE_MS;
        mA2dp.setCodecConfigPreference(device, config);
        Log.i(TAG, "restored " + device.getAddress() + " " + type.getCodecName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        mAdapter = bm != null ? bm.getAdapter() : null;
        if (mAdapter != null) {
            mAdapter.getProfileProxy(this, mListener, BluetoothProfile.A2DP);
        }
        IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED);
        registerReceiver(mReceiver, filter, null, mHandler, Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mReceiver);
        mHandler.removeCallbacksAndMessages(null);
        if (mAdapter != null && mA2dp != null) {
            mAdapter.closeProfileProxy(BluetoothProfile.A2DP, mA2dp);
        }
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
