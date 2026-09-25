#!/system/bin/sh
# j2me.sh - run phoneME natively on the JioPhone framebuffer.
#
# Installed as /data/j2me/j2me.sh by scripts/deploy.sh; must run as root
# (adb shell /s60su -c "/data/j2me/j2me.sh"). It stops the KaiOS/Gecko stack
# (b2g) so the display and keypad are free, launches the MIDP application
# manager (or one MIDlet / an install), and restarts b2g when the VM exits.
#
#   j2me.sh                      start the app manager UI (MVMManager)
#   j2me.sh install <jad|jar>    install a suite from a local file or URL
#   j2me.sh list                 list installed suites
#   j2me.sh run <suite#|id> [class]  run one installed suite
#   j2me.sh sh                   stop b2g, drop into a shell, restart b2g on exit
#   j2me.sh boot                 the phone's OS: run by init as service "s100"
#                                (tools/make_s100_boot.py) on a boot with KaiOS
#                                removed; restarts the app manager whenever it
#                                exits, powers off / reboots on request from
#                                the shell (tmp/power.req), never starts b2g
#   J2ME_KEEP_B2G=1 j2me.sh ...  don't stop/start b2g (for debugging over adb
#                                while KaiOS keeps the screen; expect fighting)
#   J2ME_TZ="IST-5:30" j2me.sh   POSIX time zone for the VM (see below)
#
# Environment understood by the runtime (see os/README.md):
#   MIDP_FB_DEV, MIDP_KEYPAD_DEV, MIDP_KEYMAP, MIDP_FB_NOPAN, MIDP_FB_DEVICE

J2ME_HOME=${J2ME_HOME:-/data/j2me}
export MIDP_HOME="$J2ME_HOME"
export MIDP_KEYMAP=${MIDP_KEYMAP:-$J2ME_HOME/keymap.txt}
export MIDP_FB_DEVICE=${MIDP_FB_DEVICE:-jiophone}
# Gonk provides no writable TMPDIR by default
export TMPDIR=${TMPDIR:-$J2ME_HOME/tmp}
export HOME="$J2ME_HOME"
# LCD backlight level while Java runs (0-255)
J2ME_BACKLIGHT=${J2ME_BACKLIGHT:-128}

# Local time. The runtime is a static glibc binary with no zoneinfo files,
# so it needs a POSIX TZ string or everything shows UTC. Precedence:
#   1. $J2ME_TZ (POSIX form, e.g. "IST-5:30")
#   2. appdb/s100_tz.txt, written by Settings > Date and time ("GMT+5:30",
#      Java sign convention: east of Greenwich is +)
#   3. KaiOS's persist.sys.timezone (Olson name) for a few common zones
#   4. India (the JioPhone's home market)
tz_from_settings() {
    f=$J2ME_HOME/appdb/s100_tz.txt
    [ -f "$f" ] || return 1
    v=$(head -n 1 "$f" 2>/dev/null | tr -d ' \r\n')
    case "$v" in
        GMT)  echo "UTC0" ;;
        GMT+*) echo "UTC-${v#GMT+}" ;;      # POSIX offsets are west-positive
        GMT-*) echo "UTC+${v#GMT-}" ;;
        *) return 1 ;;
    esac
}
tz_from_kaios() {
    z=$(getprop persist.sys.timezone 2>/dev/null)
    case "$z" in
        Asia/Kolkata|Asia/Calcutta) echo "IST-5:30" ;;
        Asia/Dubai)                 echo "GST-4" ;;
        Asia/Karachi)               echo "PKT-5" ;;
        Asia/Dhaka)                 echo "BDT-6" ;;
        Asia/Kathmandu)             echo "NPT-5:45" ;;
        Asia/Colombo)               echo "IST-5:30" ;;
        Asia/Singapore|Asia/Kuala_Lumpur) echo "SGT-8" ;;
        Asia/Shanghai|Asia/Hong_Kong) echo "CST-8" ;;
        Asia/Tokyo)                 echo "JST-9" ;;
        Europe/London)              echo "GMT0BST,M3.5.0/1,M10.5.0" ;;
        Europe/Berlin|Europe/Paris|Europe/Rome|Europe/Madrid|Europe/Amsterdam)
                                    echo "CET-1CEST,M3.5.0,M10.5.0/3" ;;
        America/New_York)           echo "EST5EDT,M3.2.0,M11.1.0" ;;
        America/Chicago)            echo "CST6CDT,M3.2.0,M11.1.0" ;;
        America/Los_Angeles)        echo "PST8PDT,M3.2.0,M11.1.0" ;;
        UTC|Etc/UTC|GMT)            echo "UTC0" ;;
        *) return 1 ;;
    esac
}
if [ -z "$J2ME_TZ" ]; then
    J2ME_TZ=$(tz_from_settings) || J2ME_TZ=$(tz_from_kaios) || J2ME_TZ="IST-5:30"
fi
export TZ="$J2ME_TZ"

cd "$J2ME_HOME" || exit 1
mkdir -p "$J2ME_HOME/appdb" "$TMPDIR"
LOG=$J2ME_HOME/j2me.log
# session state of s100_net.sh / s100_media.sh from a previous run: KaiOS
# has had the radios and the audio codec in between, start from "normal"
rm -f "$TMPDIR/radio.off" "$TMPDIR/airplane.on" "$TMPDIR/vpn.on" \
      "$TMPDIR/audio.route" "$TMPDIR/data.apn" 2>/dev/null

if [ "$(id -u)" != "0" ]; then
    echo "j2me.sh: must run as root (use /s60su)" >&2
    exit 1
fi

# An adb-spawned root shell has no CAP_DAC_OVERRIDE (see device/gsu.c), so
# fb0 (system:graphics) is unreadable until we join that group: re-exec
# through bin/gsu once.
case "$(id)" in
    *"(graphics)"*) ;;
    *)  if [ -x "$J2ME_HOME/bin/gsu" ]; then
            exec "$J2ME_HOME/bin/gsu" "$0" "$@"
        fi
        echo "j2me.sh: not in group graphics and $J2ME_HOME/bin/gsu missing" >&2
        exit 1 ;;
esac

b2g_stop() {
    [ -n "$J2ME_KEEP_B2G" ] && return
    # b2g is the KaiOS compositor + Gecko; stopping it releases fb0 and evdev
    stop b2g 2>/dev/null
    # Wait until every b2g process is really gone: when the last one releases
    # fb0 the mdss driver powers the panel down, and if that happens after
    # runMidlet has already unblanked it the LCD stays dark (panel_status=dead,
    # fb0 reads fail with EPERM) while the shell runs blind.
    i=0
    while [ $i -lt 40 ] && ps | grep -v grep | grep -q '/system/b2g/b2g'; do
        sleep 0.5; i=$((i + 1))
    done
    sleep 1
    echo 0 > /sys/class/graphics/fb0/blank 2>/dev/null
    # Turn the backlight on: KaiOS usually left it at 0 (screen timed out).
    # The sysfs node is system:system 644 and our root lacks
    # CAP_DAC_OVERRIDE, so the write has to run as uid 1000 (gsu -u).
    for b in /sys/class/leds/lcd-backlight/brightness /sys/class/backlight/*/brightness; do
        [ -f "$b" ] || continue
        if [ -w "$b" ]; then
            echo "$J2ME_BACKLIGHT" > "$b"
        else
            "$J2ME_HOME/bin/gsu" -u 1000 -c "echo $J2ME_BACKLIGHT > $b"
        fi
    done
}

b2g_start() {
    [ -n "$J2ME_KEEP_B2G" ] && return
    start b2g 2>/dev/null
}

# The static runtime cannot use the phone's resolver (no NSS), so it does
# its own DNS from tmp/resolv.conf: seed it with the servers Gonk knows
# about now; s100_net.sh rewrites it on every Wi-Fi/DNS change.
write_resolv() {
    d1=$(getprop net.dns1 2>/dev/null); d2=$(getprop net.dns2 2>/dev/null)
    [ -n "$d1" ] || d1=$(getprop dhcp.wlan0.dns1 2>/dev/null)
    [ -n "$d2" ] || d2=$(getprop dhcp.wlan0.dns2 2>/dev/null)
    mkdir -p "$J2ME_HOME/tmp"
    {
        [ -n "$d1" ] && echo "nameserver $d1"
        [ -n "$d2" ] && echo "nameserver $d2"
    } > "$J2ME_HOME/tmp/resolv.conf"
}

# Wi-Fi joined in an earlier session is still associated, but the KaiOS
# stack that ran in between may have killed dhcpcd and the route is gone:
# s100_net.sh puts both back (no-op when Wi-Fi is off).
wifi_up() {
    [ "$(getprop init.svc.wpa_supplicant 2>/dev/null)" = "running" ] || return
    [ -x "$J2ME_HOME/s100_net.sh" ] || return
    echo "[$(date)] wifi up: $("$J2ME_HOME/s100_net.sh" wifi up 2>&1 | tr '
' ' ')" >> "$LOG"
}

# --- things KaiOS (Gecko) used to do at boot, needed by "j2me.sh boot" ---

# System time. The PMIC RTC is read-only (it counts from 1970 at boot);
# Qualcomm's time_daemon keeps "real time - RTC" in ms in /data/time/ats_*.
# Gecko applied it, now we do. mksh arithmetic is 32 bit, so the ms are cut
# to seconds as a string and the date is built by hand (toybox date has no
# "@epoch"): days -> civil date after H. Hinnant.
clock_restore() {
    [ "$(date +%Y)" -lt 2020 ] || return 0
    rtc=$(cat /sys/class/rtc/rtc0/since_epoch 2>/dev/null)
    off=
    for f in /data/time/ats_12 /data/time/ats_13 /data/time/ats_2; do
        [ -f "$f" ] || continue
        off=$(od -A n -t d8 "$f" 2>/dev/null | tr -d ' ')
        [ -n "$off" ] && [ "$off" != "0" ] && break
        off=
    done
    [ -n "$rtc" ] && [ -n "$off" ] || return 1
    case "$off" in ????????????*) ;; *) return 1 ;; esac
    t=$(( ${off%???} + rtc ))
    s=$((t % 60)); t=$((t / 60)); mi=$((t % 60)); t=$((t / 60))
    h=$((t % 24)); z=$((t / 24 + 719468))
    era=$((z / 146097)); doe=$((z - era * 146097))
    yoe=$(( (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 ))
    y=$((yoe + era * 400))
    doy=$((doe - (365 * yoe + yoe / 4 - yoe / 100)))
    mp=$(( (5 * doy + 2) / 153 ))
    d=$((doy - (153 * mp + 2) / 5 + 1))
    if [ $mp -lt 10 ]; then m=$((mp + 3)); else m=$((mp - 9)); fi
    [ $m -le 2 ] && y=$((y + 1))
    # toybox printf prints garbage for %d and "date -s +FORMAT" rejects
    # what "date -d" accepts, so: hand padding + the default SET format
    date -u "$(p2 $m)$(p2 $d)$(p2 $h)$(p2 $mi)$y.$(p2 $s)" >/dev/null
}

p2() {
    if [ "$1" -lt 10 ]; then echo "0$1"; else echo "$1"; fi
}

boot_fixups() {
    clock_restore || echo "[$(date)] clock: could not restore from /data/time" >> "$LOG"
    # init recreates /data/local/tmp as root:root 0771 on every boot and
    # KaiOS handed it to the shell user; adb push (deploy.sh) needs that,
    # and the capability-less /s60su root (screenshot.sh) must write too
    chown shell:shell /data/local/tmp 2>/dev/null
    chmod 1777 /data/local/tmp 2>/dev/null
    # Wi-Fi on unless the user switched it off (s100_net.sh wifi off)
    if [ ! -f "$J2ME_HOME/appdb/s100_wifi.off" ] && [ -x "$J2ME_HOME/s100_net.sh" ]; then
        (
            "$J2ME_HOME/s100_net.sh" wifi on >/dev/null 2>&1
            i=0
            while [ $i -lt 30 ]; do
                r=$("$J2ME_HOME/s100_net.sh" wifi up 2>&1 | tr '\n' ' ')
                case "$r" in *state=connected*) break ;; esac
                sleep 2; i=$((i + 1))
                # rescan now and then (wifi on = reconnect + scan)
                [ $((i % 8)) -eq 0 ] &&
                    "$J2ME_HOME/s100_net.sh" wifi on >/dev/null 2>&1
            done
            echo "[$(date)] boot wifi: $r" >> "$LOG"
        ) &
    fi
}

run_vm() {
    wifi_up
    write_resolv
    echo "[$(date)] runMidlet $*" >> "$LOG"
    "$J2ME_HOME/bin/runMidlet" "$@" >> "$LOG" 2>&1
    rc=$?
    echo "[$(date)] exit $rc" >> "$LOG"
    return $rc
}

cmd=${1:-ams}
[ $# -gt 0 ] && shift

case "$cmd" in
    ams)
        b2g_stop
        trap b2g_start EXIT INT TERM
        run_vm -1 com.sun.midp.appmanager.MVMManager
        ;;
    run)
        b2g_stop
        trap b2g_start EXIT INT TERM
        run_vm "$@"
        ;;
    install)
        # The installer is itself a MIDlet: it needs the display, so b2g
        # must be stopped for it too. Local paths need a file:// URL.
        src=$1
        case "$src" in
            /*) src="file://$src" ;;
        esac
        b2g_stop
        trap b2g_start EXIT INT TERM
        run_vm -1 com.sun.midp.scriptutil.CommandLineInstaller I "$src"
        ;;
    list)
        run_vm -1 com.sun.midp.scriptutil.SuiteLister
        tail -n 40 "$LOG"
        ;;
    remove)
        run_vm -1 com.sun.midp.scriptutil.SuiteRemover "$@"
        ;;
    boot)
        # Started by init at boot (service s100): root with full
        # capabilities and the hardware groups, no KaiOS anywhere. The
        # bootloader splash is still on the panel; make sure it is lit.
        J2ME_KEEP_B2G=
        [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 1048576 ] && mv -f "$LOG" "$LOG.old"
        echo "[$(date)] boot" >> "$LOG"
        boot_fixups
        echo "[$(date)] boot fixups done" >> "$LOG"
        b2g_stop
        # Gecko used to announce this; qcom post-boot tuning waits for it
        setprop sys.boot_completed 1
        setprop dev.bootcomplete 1
        fast=0
        while :; do
            rm -f "$TMPDIR/power.req"
            t0=$(date +%s)
            run_vm -1 com.sun.midp.appmanager.MVMManager
            req=$(cat "$TMPDIR/power.req" 2>/dev/null)
            case "$req" in
                off)    echo "[$(date)] power off" >> "$LOG"
                        sync; setprop sys.powerctl shutdown; exit 0 ;;
                reboot) echo "[$(date)] reboot" >> "$LOG"
                        sync; setprop sys.powerctl reboot; exit 0 ;;
            esac
            # crashed or plain exit: start again, backing off if it keeps
            # dying right away (adb stays usable for repairs)
            if [ $(( $(date +%s) - t0 )) -lt 20 ]; then
                fast=$((fast + 1))
            else
                fast=0
            fi
            if [ $fast -ge 5 ]; then
                echo "[$(date)] VM keeps exiting, waiting 60 s" >> "$LOG"
                sleep 60; fast=0
            else
                sleep 1
            fi
        done
        ;;
    sh)
        b2g_stop
        trap b2g_start EXIT INT TERM
        /system/bin/sh
        ;;
    *)
        echo "usage: j2me.sh [ams|boot|run <suite> [class]|install <jad|jar>|list|remove <id>|sh]" >&2
        exit 2
        ;;
esac
