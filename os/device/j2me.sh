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
    # give surfaceflinger-less panels a moment, then make sure it is lit
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
