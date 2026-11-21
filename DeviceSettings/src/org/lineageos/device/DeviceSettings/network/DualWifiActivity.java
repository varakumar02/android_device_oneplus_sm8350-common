/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentTransaction;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import org.lineageos.device.DeviceSettings.R;

/** Hosts DualWifiFragment in the SettingsLib collapsing toolbar frame, like NetworkBandsActivity. */
public final class DualWifiActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        org.lineageos.device.DeviceSettings.Utils.applyAppTheme(this);
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        setTitle(R.string.dual_wifi_title);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new DualWifiFragment())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
