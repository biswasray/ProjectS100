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

run_vm() {
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
    sh)
        b2g_stop
        trap b2g_start EXIT INT TERM
        /system/bin/sh
        ;;
    *)
        echo "usage: j2me.sh [ams|run <suite> [class]|install <jad|jar>|list|remove <id>|sh]" >&2
        exit 2
        ;;
esac
