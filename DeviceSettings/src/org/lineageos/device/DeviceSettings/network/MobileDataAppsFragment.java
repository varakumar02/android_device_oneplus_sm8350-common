/*
 * SPDX-FileCopyrightText: 2026 The AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.device.DeviceSettings.network;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivitySettingsManager;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import org.lineageos.device.DeviceSettings.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apps that use mobile data even while Wi-Fi is connected
 * (ConnectivitySettingsManager mobile-data-preferred UIDs; ConnectivityService applies and
 * keeps the setting itself). Apps sharing a UID move together.
 */
public class MobileDataAppsFragment extends SettingsBasePreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getPreferenceManager().getContext();
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);

        Preference hint = new Preference(context);
        hint.setSummary(R.string.mobile_data_apps_hint);
        hint.setSelectable(false);
        screen.addPreference(hint);

        // ponytail: labels and icons load on the main thread, like IrisAppsFragment
        PackageManager pm = context.getPackageManager();
        Map<String, ApplicationInfo> apps = new ArrayMap<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            apps.put(info.activityInfo.packageName, info.activityInfo.applicationInfo);
        }
        Set<Integer> uids = ConnectivitySettingsManager.getMobileDataPreferredUids(context);
        List<ApplicationInfo> sorted = new ArrayList<>(apps.values());
        Map<String, String> labels = new ArrayMap<>();
        for (ApplicationInfo app : sorted) {
            labels.put(app.packageName, app.loadLabel(pm).toString());
        }
        sorted.sort((a, b) -> {
            boolean aOn = uids.contains(a.uid), bOn = uids.contains(b.uid);
            return aOn != bOn ? (aOn ? -1 : 1)
                    : labels.get(a.packageName).compareToIgnoreCase(labels.get(b.packageName));
        });
        for (ApplicationInfo app : sorted) {
            SwitchPreferenceCompat pref = new SwitchPreferenceCompat(context);
            pref.setPersistent(false);
            pref.setTitle(labels.get(app.packageName));
            pref.setIcon(app.loadIcon(pm));
            pref.setChecked(uids.contains(app.uid));
            final int uid = app.uid;
            pref.setOnPreferenceChangeListener((p, value) -> {
                Set<Integer> current = new ArraySet<>(
                        ConnectivitySettingsManager.getMobileDataPreferredUids(context));
                if ((Boolean) value) {
                    current.add(uid);
                } else {
                    current.remove(uid);
                }
                ConnectivitySettingsManager.setMobileDataPreferredUids(context, current);
                return true;
            });
            screen.addPreference(pref);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() != null) {
            getActivity().setTitle(R.string.mobile_data_apps_title);
        }
    }
}
