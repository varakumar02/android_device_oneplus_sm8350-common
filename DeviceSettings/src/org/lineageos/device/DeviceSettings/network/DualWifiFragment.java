/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import org.lineageos.device.DeviceSettings.R;

import java.util.List;

/** The Dual Wi-Fi page: main switch, both connections live, backup choice, options, log. */
public class DualWifiFragment extends Fragment {

    private static final String GREEN = "#4ADE80";
    private static final String GRAY = "#9CA3AF";

    private SharedPreferences mPrefs;
    private Switch mSwitch, mFast, mSave;
    private TextView mMainName, mMainDetail, mMainState;
    private TextView mBackupName, mBackupDetail, mBackupState;
    private RadioGroup mGroup;
    private TextView mActivity;
    // Backup list as last drawn, so RSSI updates do not rebuild it.
    private String mDrawnChoices;
    private final Runnable mRefresh = this::refresh;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_dual_wifi, container, false);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        mSwitch = v.findViewById(R.id.dual_switch);
        mFast = v.findViewById(R.id.fast_switch);
        mSave = v.findViewById(R.id.save_switch);
        mMainName = v.findViewById(R.id.main_name);
        mMainDetail = v.findViewById(R.id.main_detail);
        mMainState = v.findViewById(R.id.main_state);
        mBackupName = v.findViewById(R.id.backup_name);
        mBackupDetail = v.findViewById(R.id.backup_detail);
        mBackupState = v.findViewById(R.id.backup_state);
        mGroup = v.findViewById(R.id.backup_group);
        mActivity = v.findViewById(R.id.activity_text);

        mSwitch.setChecked(DualWifi.isEnabled(requireContext()));
        mFast.setChecked(mPrefs.getBoolean(DualWifi.KEY_FAST, true));
        mSave.setChecked(mPrefs.getBoolean(DualWifi.KEY_SAVE_LOCKED, false));
        bind(mSwitch, DualWifi.KEY);
        bind(mFast, DualWifi.KEY_FAST);
        bind(mSave, DualWifi.KEY_SAVE_LOCKED);
        return v;
    }

    private void bind(Switch s, String key) {
        s.setOnCheckedChangeListener((view, checked) -> {
            if (mPrefs.getBoolean(key, !checked) == checked) {
                return; // refresh() mirroring a change made elsewhere (the tile)
            }
            mPrefs.edit().putBoolean(key, checked).apply();
            DualWifi.apply(requireContext().getApplicationContext(), true);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        DualWifi.addListener(mRefresh);
        mDrawnChoices = null;
        refresh();
    }

    @Override
    public void onPause() {
        DualWifi.removeListener(mRefresh);
        super.onPause();
    }

    private void refresh() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        boolean on = DualWifi.isEnabled(context);
        DualWifi.State s = DualWifi.state();
        if (mSwitch.isChecked() != on) {
            mSwitch.setChecked(on);
        }

        mMainName.setText(s.main != null ? s.main : getString(R.string.dual_wifi_not_connected));
        mMainDetail.setText(s.mainDetail != null ? s.mainDetail : "");
        mBackupName.setText(s.backup != null ? s.backup : getString(R.string.dual_wifi_none));
        mBackupDetail.setText(s.backupDetail != null ? s.backupDetail : backupHint(on, s));
        if (s.main != null) {
            pill(mMainState, s.onBackup ? R.string.dual_wifi_state_standby
                    : R.string.dual_wifi_state_in_use, !s.onBackup);
        } else {
            mMainState.setVisibility(View.GONE);
        }
        if (s.backup != null) {
            pill(mBackupState, s.onBackup ? R.string.dual_wifi_state_in_use
                    : R.string.dual_wifi_state_ready, s.onBackup);
        } else {
            mBackupState.setVisibility(View.GONE);
        }

        drawChoices(context);

        List<String> log = DualWifi.log();
        mActivity.setText(log.isEmpty() ? getString(R.string.dual_wifi_activity_empty)
                : TextUtils.join("\n", log));
    }

    private String backupHint(boolean on, DualWifi.State s) {
        if (!on) {
            return getString(R.string.dual_wifi_hint_off);
        }
        if (s.paused) {
            return getString(R.string.dual_wifi_hint_paused);
        }
        return getString(R.string.dual_wifi_hint_searching);
    }

    private void pill(TextView v, int text, boolean green) {
        v.setVisibility(View.VISIBLE);
        v.setText(text);
        v.setBackgroundResource(green ? R.drawable.pill_connected_green
                : R.drawable.pill_connected_gray);
        v.setTextColor(Color.parseColor(green ? GREEN : GRAY));
    }

    private void drawChoices(Context context) {
        List<DualWifi.Choice> choices = DualWifi.choices(context);
        String chosen = mPrefs.getString(DualWifi.KEY_BACKUP, "");
        StringBuilder key = new StringBuilder(chosen);
        for (DualWifi.Choice c : choices) {
            key.append('|').append(c.ssid).append('/').append(c.unavailable);
        }
        if (key.toString().equals(mDrawnChoices)) {
            return;
        }
        mDrawnChoices = key.toString();

        mGroup.setOnCheckedChangeListener(null);
        mGroup.removeAllViews();
        addChoice(context, "", getString(R.string.dual_wifi_auto),
                getString(R.string.dual_wifi_auto_summary), true, chosen.isEmpty());
        for (DualWifi.Choice c : choices) {
            addChoice(context, c.ssid, c.ssid, c.unavailable, c.unavailable == null,
                    c.ssid.equals(chosen));
        }
        mGroup.setOnCheckedChangeListener((group, id) -> {
            View b = group.findViewById(id);
            if (b == null || !((RadioButton) b).isChecked()) {
                return;
            }
            mPrefs.edit().putString(DualWifi.KEY_BACKUP, (String) b.getTag()).apply();
            mDrawnChoices = null;
            DualWifi.apply(context.getApplicationContext(), true);
        });
    }

    private void addChoice(Context context, String value, String title, String summary,
            boolean enabled, boolean checked) {
        RadioButton b = new RadioButton(context);
        b.setId(View.generateViewId());
        b.setTag(value);
        b.setText(summary == null ? title : title + "\n" + summary);
        b.setTextColor(context.getColor(enabled ? R.color.text_primary_color
                : R.color.text_secondary_color));
        b.setTextSize(13);
        b.setPadding(8, 12, 8, 12);
        // The saved choice stays selectable to keep it visible even when it is not usable now.
        b.setEnabled(enabled || checked);
        mGroup.addView(b);
        if (checked) {
            b.setChecked(true);
        }
    }
}
