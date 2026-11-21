/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.preference.PreferenceManager;

import org.lineageos.device.DeviceSettings.R;

/**
 * Dual Wi-Fi tile: tap toggles it, long press opens the page (QS_TILE_PREFERENCES on
 * DualWifiActivity). The subtitle says what the backup is doing.
 */
public class DualWifiTileService extends TileService {

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(DualWifi.KEY, !DualWifi.isEnabled(this))
                .apply();
        DualWifi.apply(this, true);
        update();
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean on = DualWifi.isEnabled(this);
        DualWifi.State s = DualWifi.state();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        String subtitle;
        if (!on) {
            subtitle = getString(R.string.dual_wifi_tile_off);
        } else if (s.onBackup) {
            subtitle = getString(R.string.dual_wifi_tile_using_backup);
        } else if (s.paused) {
            subtitle = getString(R.string.dual_wifi_tile_paused);
        } else if (s.backup != null) {
            subtitle = getString(R.string.dual_wifi_tile_backup_ready);
        } else {
            subtitle = getString(R.string.dual_wifi_tile_no_backup);
        }
        tile.setSubtitle(subtitle);
        tile.updateTile();
    }
}
