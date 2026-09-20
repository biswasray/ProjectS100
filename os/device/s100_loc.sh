#!/system/bin/sh
# s100_loc.sh - positioning backend for the S100 shell (Settings >
# Location). Installed as /data/j2me/s100_loc.sh.
#
# The JioPhone's GPS is Qualcomm's gpsone behind loc_launcher/lowi; the
# gps HAL is normally loaded by Gecko, which is stopped in Java mode. The
# ODM build ships Qualcomm's HAL test client /system/bin/garden_app,
# which starts a session through the same HAL and, with -n, prints the
# receiver's NMEA sentences. The Java side (LocationSettings) spawns
# "gps start", reads the growing output file and parses $GPGGA/$GPRMC
# itself, then kills the process group.
#
#   s100_loc.sh gps start <seconds> <mode> <outfile>
#         mode 0 standalone, 1 MS-based (assisted), 2 MS-assisted
#   s100_loc.sh gps stop
#   s100_loc.sh gps status        gps=0|1 (garden_app present), loc=..., agps=...
#   s100_loc.sh cell status       last registered network from the modem props

GARDEN=/system/bin/garden_app
GSU=/data/j2me/bin/gsu

prop() { getprop "$1" 2>/dev/null; }

case "$1.$2" in
    gps.start)
        secs=${3:-90}; mode=${4:-0}; out=$5
        [ -x "$GARDEN" ] || { echo "error=no GPS test client (garden_app) on this phone"; exit 1; }
        [ -n "$out" ] || { echo "error=no output file"; exit 2; }
        pkill -9 garden_app 2>/dev/null
        rm -f "$out"
        # -l 1 one session, -r 0 periodic fixes every -i ms, -n NMEA on
        # stdout, -y satellites; the process ends by itself after -t seconds.
        # garden_app wants the gps group for the QMI socket (gsu grants it).
        exec "$GARDEN" -l 1 -r 0 -t "$secs" -m "$mode" -i 1000 -n -y > "$out" 2>&1
        ;;
    gps.stop)
        pkill -9 garden_app 2>/dev/null
        echo "state=stopped"
        ;;
    gps.status)
        [ -x "$GARDEN" ] && echo "gps=1" || echo "gps=0"
        echo "enabled=$(prop ro.gps.enabled)"
        echo "agps=$(prop ro.assisted_gps_enabled)"
        echo "loc=$(prop init.svc.loc_launcher)"
        echo "nlp=$(prop persist.loc.nlp_name)"
        echo "supl=$(grep -i '^SUPL_HOST' /etc/gps.conf 2>/dev/null | cut -d= -f2)"
        echo "xtra=$(grep -i '^XTRA_SERVER_1' /etc/gps.conf 2>/dev/null | cut -d= -f2)"
        pgrep -x garden_app >/dev/null 2>&1 && echo "running=1" || echo "running=0"
        ;;
    cell.status)
        op=$(prop persist.radio.nitz_lons_0_0)
        [ -z "$op" ] && op=$(prop persist.radio.nitz_sons_0_0)
        [ -z "$op" ] && op=$(prop gsm.operator.alpha | cut -d, -f1)
        echo "operator=$op"
        echo "plmn=$(prop persist.radio.nitz_plmn_0)"
        echo "numeric=$(prop gsm.sim.operator.numeric | cut -d, -f1)"
        echo "country=$(prop gsm.operator.iso-country | cut -d, -f1)"
        echo "type=$(prop gsm.network.type | cut -d, -f1)"
        echo "tz=$(prop persist.sys.timezone)"
        ;;
    *)
        echo "usage: s100_loc.sh gps start <secs> <mode> <out> | gps stop | gps status | cell status" >&2
        exit 2
        ;;
esac
