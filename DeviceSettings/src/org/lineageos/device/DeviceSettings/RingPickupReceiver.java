/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

/** Starts RingPickupService while a call rings, stops it when the call is answered or ends. */
public class RingPickupReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        Intent service = new Intent(context, RingPickupService.class);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(
                intent.getStringExtra(TelephonyManager.EXTRA_STATE))) {
            if (RingPickupService.isEnabled(context) && RingPickupService.isSupported(context)) {
                context.startService(service);
            }
        } else {
            context.stopService(service);
            // Restore only once the call is over (also covers a service process that died
            // while the phone rang); during the call it would land on the call device.
            if (TelephonyManager.EXTRA_STATE_IDLE.equals(
                    intent.getStringExtra(TelephonyManager.EXTRA_STATE))) {
                RingPickupService.restoreAfterCall(context, goAsync());
            }
        }
    }
}
