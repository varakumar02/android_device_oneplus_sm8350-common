#!/vendor/bin/sh
# Log why the ADSP, CDSP and SLPI are not sleeping (kernel subsys_sleep_monitor,
# /proc/subsys_sleepmon) to logcat, tag SubsysSleepMon. Userspace is frozen in
# suspend, so this only runs (and logs) while the phone is awake.
# At sys.boot_completed the DSP sleep data is not readable yet; give it time.
sleep 120
while true; do
    for d in adsp cdsp slpi; do
        f=/proc/subsys_sleepmon/${d}_compact
        [ -r "$f" ] && log -t SubsysSleepMon -p i "$d: $(tr '\n' ' ' < "$f")"
    done
    sleep 1800
done
