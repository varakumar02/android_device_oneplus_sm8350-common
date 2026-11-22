#!/bin/bash
#
# apply_patch() mechanism cherry-picked from Jammy555/android_device_oneplus_sm8350-common
# @Sakura (aox doesn't carry it). Auto-sourced by build/envsetup.sh for every
# device dir on the search path -- no manual step needed once synced.

PATCH_DIR="device/oneplus/sm8350-common/patches"

apply_patch() {
    local target_dir=$1
    local patch_file=$2
    local name=$3

    if [ -d "$target_dir" ]; then
        pushd "$target_dir" >/dev/null
        if git apply --reverse --check "$patch_file" >/dev/null 2>&1; then
            echo "${target_dir}: Already applied ($name)"
        elif git apply "$patch_file" >/dev/null 2>&1; then
            echo "${target_dir}: Successfully applied ($name)"
        else
            echo "========================================================================"
            echo " [!] MERGE CONFLICT / PATCH ERROR"
            echo " Target: ${target_dir}"
            echo " Patch Name: ${name}"
            echo " Patch File: ${patch_file}"
            echo " Details of failure:"
            git apply --check "$patch_file"
            echo "========================================================================"
        fi
        popd >/dev/null
    fi
}

apply_patch "packages/apps/AxDiagnostics" "../../../$PATCH_DIR/packages_apps_axdiagnostics_thermal_sanity_bound.patch" "AxDiagnostics: exclude non-temperature/sentinel thermal zones (socd/vbat/BCL/ibat, kernel THERMAL_TEMP_INVALID) from maxTemperature/hottest so a bogus reading can't trigger a false Thermal-emergency insight, and label them in the exported zone dump"
apply_patch "packages/apps/AxionParts" "../../../$PATCH_DIR/packages_apps_axionparts_kernel_manager_performance_profile.patch" "Kernel Manager: add a governor-driven Performance profile picker (one profile per available CPU governor, Custom when the clusters disagree)"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_allow_app_downgrade.patch" "PackageInstaller/PMS: allow installing a lower versionCode over an existing app instead of failing INSTALL_FAILED_VERSION_DOWNGRADE"
apply_patch "lineage-sdk" "../$PATCH_DIR/lineage_sdk_advanced_reboot_default_on.patch" "PowerMenuUtils: default advanced_reboot to enabled (no def_advanced_reboot resource exists to seed it any other way)"
apply_patch "packages/apps/LineageParts" "../../../$PATCH_DIR/lineageparts_advanced_reboot_default_on.patch" "Power menu settings: default the Advanced restart switch preference to checked, matching the PowerMenuUtils default"
apply_patch "packages/apps/Settings" "../../../$PATCH_DIR/packages_apps_settings_fixed_refresh_rate.patch" "Settings Display: the refresh rate list sets a fixed rate (min = peak = the chosen mode), titled Screen refresh rate"
apply_patch "packages/apps/Updater" "../../../$PATCH_DIR/packages_apps_updater_stale_download_progress_ui.patch" "UpdaterViewModel: clear downloadProgress/downloadedMB/totalMB when status is DELETED/UNKNOWN so the progress bar and Delete button don't stay stuck on screen after deleting a download"
apply_patch "packages/apps/Updater" "../../../$PATCH_DIR/packages_apps_updater_resume_uses_resume_download.patch" "UpdaterViewModel.resumeDownload(): call UpdaterController.resumeDownload(), not startDownload() -- startDownload() always builds a fresh DownloadClient with the update's (possibly null) download URL"
apply_patch "packages/apps/Updater" "../../../$PATCH_DIR/packages_apps_updater_persist_download_url.patch" "UpdatesDbHelper: persist download_url so an update restored from the DB after a process restart keeps a usable URL instead of null"
apply_patch "packages/apps/Updater" "../../../$PATCH_DIR/packages_apps_updater_null_url_not_fatal.patch" "UpdaterController: catch IllegalStateException alongside IOException when building a DownloadClient, so a missing download URL routes to PAUSED_ERROR/Retry instead of crashing the app"
apply_patch "packages/apps/Updater" "../../../$PATCH_DIR/packages_apps_updater_delete_button_keyed_on_status.patch" "UpdateCard: show the Delete action for any status with a file on disk, not just downloadProgress > 0f, so a restored PAUSED entry a user chose to keep (auto-delete-on-install off) gets a working Delete button instead of only a crashing Resume"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_systemui_wifi_tile_state_follows_enabled.patch" "SystemUI Wi-Fi tile: state follows isWifiEnabled (ENABLING counts as on), disableWifi() clears the Scanning state, so tap and tile colour agree"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_systemui_wifi_tile_long_press_wifi_settings.patch" "SystemUI Wi-Fi tile: long-press opens Settings.ACTION_WIFI_SETTINGS instead of the Internet dialog"
apply_patch "packages/apps/AxDiagnostics" "../../../$PATCH_DIR/packages_apps_axdiagnostics_wakelocks_from_sysfs.patch" "AxDiagnostics: WakelockCollector reads /sys/class/wakeup/* when debugfs wakeup_sources is unreadable, before falling back to /proc/wakelocks"
apply_patch "packages/apps/AxDiagnostics" "../../../$PATCH_DIR/packages_apps_axdiagnostics_system_server_export_window.patch" "AxDiagnostics: report export primes SystemServerAnalyzer with the other collectors so its per-thread CPU has a window, and prints PSS as unavailable when it can't be read"
apply_patch "packages/apps/AxDiagnostics" "../../../$PATCH_DIR/packages_apps_axdiagnostics_memory_hog_persistent_threshold.patch" "AxDiagnostics: HealthAnalyzer flags persistent processes (oom_score_adj <= -800) as memory hogs only above 1.5 GB instead of 500 MB"
apply_patch "packages/apps/AxDiagnostics" "../../../$PATCH_DIR/packages_apps_axdiagnostics_socd_as_battery_depletion.patch" "AxDiagnostics: thermal zones that aren't temperatures (socd/vbat/ibat/bcl) move to ThermalSnapshot.nonTemperatureZones; the report shows socd as battery depletion in the Battery section"
apply_patch "packages/apps/AxionParts" "../../../$PATCH_DIR/packages_apps_axionparts_personalize_advanced_protection_oneplus_settings.patch" "Personalize: add Advanced protection (org.aox.privacy) and OnePlus settings (DeviceSettings) cards, launched by explicit component"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_locksettings_duress_credential.patch" "LockSettingsService: a credential matching the stored ax_duress_credential hash wipes user data instead of being verified; the hash is cleared whenever the lockscreen credential changes"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_camera2_high_speed_ranges_from_supported_target.patch" "camera2: createHighSpeedRequestList() takes the fps ranges from a target whose size is an advertised high speed size instead of whichever target getTargets() lists first, so a privileged camera app recording a larger surface in a constrained high speed session no longer fails at random"
apply_patch "frameworks/native" "../../$PATCH_DIR/frameworks_native_ahardwarebuffer_qti_p010_venus.patch" "libnativewindow: treat QTI YCbCr_420_P010_VENUS (0x7FA30C0A) as a YUV format in AHardwareBuffer_lockPlanes so it returns the chroma plane (OplusCamera Movie LOG/HDR preview reads it)"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_camera2_remember_missing_vendor_tags.patch" "camera2: CameraMetadataNative remembers for 5 s that a key's tag lookup failed instead of repeating the native lookup, error log line and exception on every get()/set() of a vendor key the HAL does not define"
apply_patch "hardware/qcom-caf/sm8350/audio" "../../../../$PATCH_DIR/hardware_qcom_caf_sm8350_audio_spkr_prot_thermalclient_soname.patch" "audio HAL spkr_prot_init(): dlopen libthermalclient.so by soname instead of the fixed /vendor/lib path, so a 64-bit libspkrprot finds the lib64 copy"
apply_patch "hardware/qcom-caf/sm8350/audio" "../../../../$PATCH_DIR/hardware_qcom_caf_sm8350_audio_spatializer_stream_hifi_usecase.patch" "audio HAL adev_open_output_stream(): an output opened FAST|DEEP_BUFFER (what libaudiohal sends for the spatializer stream to HALs below 7.1) takes the hifi usecase instead of the low-latency one the primary output holds"
apply_patch "hardware/qcom-caf/sm8350/audio" "../../../../$PATCH_DIR/hardware_qcom_caf_sm8350_audio_close_pcm_on_failed_start.patch" "audio HAL start_output_stream(): close out->pcm when the start fails after the PCM was opened, so the next start does not find the device busy"
apply_patch "hardware/qcom-caf/sm8350/audio" "../../../../$PATCH_DIR/hardware_qcom_caf_sm8350_audio_a2dp_lost_write_silence.patch" "audio HAL route_output_stream(): a PCM stream that loses its A2DP device writes silence until it is routed again, instead of playing on the speaker it is parked on"
apply_patch "prebuilts/openeuicc-deps" "../../$PATCH_DIR/prebuilts_openeuicc_deps_sdk_version_36.patch" "OpenEUICC prebuilt deps: sdk_version 37 -> 36, the newest finalized SDK this tree has"
apply_patch "frameworks/av" "../../$PATCH_DIR/frameworks_av_camera_hidl_bt2020_override_is_hlg.patch" "cameraservice: complete the bare BT.2020 dataspace (0x60000) the OPLUS SM8350 HAL returns for an HLG video stream to BT.2020 HLG limited, so the recording carries the HLG transfer characteristic"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_wm_hide_screen_capture_status.patch" "WindowManagerService: apps are never told about screen capture (no screenshot callbacks, screen recording callbacks report not recorded)"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_alarm_manager_wake_only_epollwakeup.patch" "AlarmManagerService JNI: only RTC_WAKEUP and ELAPSED_REALTIME_WAKEUP timerfds get EPOLLWAKEUP, the non-wake ones get plain EPOLLIN, so non-wake alarms no longer arm a kernel wakeup source and wake the AP"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_clipboard_strip_link_tracking.patch" "ClipboardService: strip tracking parameters (utm_*, fbclid, gclid, si, ...) from http(s) links in every copied clip, switchable with Settings.Secure aox_strip_link_tracking (default on)"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_permission_revoke_ad_id.patch" "AppIdPermissionPolicy: never grant com.google.android.gms.permission.AD_ID or android.permission.ACCESS_ADSERVICES_AD_ID, so apps get a zeroed advertising ID"
# Earlier revisions of this patch left the Android.bp files in a different state: start from HEAD
[ -d vendor/qcom/opensource/interfaces ] && git -C vendor/qcom/opensource/interfaces checkout -q -- bluetooth_audio/2.0/Android.bp bluetooth_audio/2.1/Android.bp
apply_patch "vendor/qcom/opensource/interfaces" "../../../../$PATCH_DIR/vendor_qcom_opensource_interfaces_bluetooth_audio_apex_bt.patch" "bluetooth_audio@2.0/2.1: make the HIDL libs available to com.android.bt instead of com.android.btservices (the pre-Android 16 Bluetooth APEX name, no longer a module), so the Bluetooth module's A2DP offload client can link them"
apply_patch "packages/modules/common" "../../../$PATCH_DIR/packages_modules_common_allowed_deps_qti_bluetooth_audio.patch" "allowed_deps.txt: allow vendor.qti.hardware.bluetooth_audio@2.0/2.1 in updatable APEXes, linked by the Bluetooth module A2DP offload client"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_audio_bt_a2dp_sharing_software_route.patch" "BtHelper.getCodec(): while bluetooth.a2dp_sharing.active is set, report the A2DP device as AUDIO_FORMAT_OPUS (not in the offload port's encodedFormats) and count the sharing on/off change as a codec change, so audio policy moves the sink between the DSP offload port and the software Bluetooth module without a Bluetooth restart"
apply_patch "vendor/qcom/opensource/healthd-ext" "../../../../$PATCH_DIR/vendor_qcom_opensource_healthd_ext_disable_periodic_chores_slow.patch" "healthd-ext: periodic_chores_interval_slow = -1, so healthd no longer arms its 10-minute timerfd alarm that wakes the AP from suspend while on battery; battery changes still arrive as power_supply uevents"
apply_patch "frameworks/base" "../../$PATCH_DIR/frameworks_base_pdb_oem_unlock_default_on.patch" "PersistentDataBlockService: default OEM unlock to allowed when the FRP partition is blank/invalid (first boot or after a wipe), since there is no build-time seed for the OEM-unlock bit; the user can still turn it off in Settings"
