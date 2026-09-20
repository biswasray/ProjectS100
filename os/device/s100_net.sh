#!/system/bin/sh
# s100_net.sh - connectivity backend for the S100 shell (Settings >
# Connectivity). Installed as /data/j2me/s100_net.sh; the Java side runs it
# through Sys.exec() and parses the "key=value" lines it prints.
#
# Everything here has to work from a root shell that has NO capabilities
# (see device/gsu.c): interfaces, routes and daemons are therefore driven
# through the daemons that do have them - wpa_supplicant (wpa_cli), netd
# (ndc), init (setprop ctl.start/stop, sys.usb.config) - never with
# ifconfig/iptables directly.
#
#   wifi status|on|off|scan|saved|disconnect
#   wifi connect <ssid> [password]      (empty password = open network)
#   wifi forget <ssid>
#   hotspot status|off
#   hotspot on <ssid> <password|-> [channel]
#   usb status
#   usb set <mtp|mass_storage|charging> <adb:0|1>
#   bt status|on|off
#   bt name <name>
#
# Exit status is 0 when the request was carried out; error text goes to
# stdout as "error=..." so the UI can show it.

WPA="/system/xbin/wpa_cli -p /data/misc/wifi/sockets -i wlan0"
IFACE=wlan0
AP_ADDR=192.168.43.1
AP_START=192.168.43.2
AP_END=192.168.43.254
STATE_DIR=/data/j2me/tmp
LEASES=/data/misc/dhcp/dnsmasq.leases

mkdir -p "$STATE_DIR" 2>/dev/null

err() { echo "error=$*"; exit 1; }
prop() { getprop "$1" 2>/dev/null; }

# wpa_cli prints "Selected interface..." and blank lines: keep the payload
wpa() { $WPA "$@" 2>/dev/null | grep -v '^Selected interface' | grep -v '^$'; }

wpa_running() {
    case "$(prop init.svc.wpa_supplicant)" in
        running) return 0 ;;
    esac
    pgrep -x wpa_supplicant >/dev/null 2>&1
}

# ---------------------------------------------------------------- wifi ----

wifi_status() {
    if [ -f "$STATE_DIR/hotspot.on" ]; then
        echo "state=hotspot"; return 0
    fi
    if ! wpa_running; then
        echo "state=off"; return 0
    fi
    st=$(wpa status)
    if [ -z "$st" ]; then
        echo "state=off"; return 0
    fi
    wpa_state=$(echo "$st" | grep '^wpa_state=' | cut -d= -f2)
    ssid=$(echo "$st" | grep '^ssid=' | cut -d= -f2-)
    ip=$(echo "$st" | grep '^ip_address=' | cut -d= -f2)
    [ -z "$ip" ] && ip=$(prop dhcp.wlan0.ipaddress)
    case "$wpa_state" in
        COMPLETED)
            if [ -n "$ip" ]; then echo "state=connected"; else echo "state=connecting"; fi ;;
        ASSOCIATING|ASSOCIATED|AUTHENTICATING|4WAY_HANDSHAKE|GROUP_HANDSHAKE|SCANNING)
            echo "state=connecting" ;;
        *)
            echo "state=disconnected" ;;
    esac
    if [ "$wpa_state" != "COMPLETED" ]; then
        ip=""
    fi
    echo "ssid=$ssid"
    echo "ip=$ip"
    echo "gateway=$([ -n "$ip" ] && prop dhcp.wlan0.gateway)"
    echo "dns=$([ -n "$ip" ] && prop dhcp.wlan0.dns1)"
    bssid=$(echo "$st" | grep '^bssid=' | cut -d= -f2)
    sig=""
    if [ -n "$bssid" ]; then
        # signal from the last scan for the current BSS
        sig=$(wpa scan_results | grep "^$bssid" | head -n 1 | cut -f3)
    fi
    echo "signal=$sig"
    echo "mac=$(echo "$st" | grep '^address=' | cut -d= -f2)"
}

wifi_on() {
    if [ -f "$STATE_DIR/hotspot.on" ]; then
        hotspot_off >/dev/null
    fi
    if ! wpa_running; then
        setprop ctl.start wpa_supplicant
        i=0
        while [ $i -lt 20 ]; do
            wpa_running && break
            sleep 0.5; i=$((i + 1))
        done
        sleep 1
    fi
    wpa_running || err "wpa_supplicant did not start"
    wpa reconnect >/dev/null
    wpa enable_network all >/dev/null
    echo "state=on"
}

wifi_off() {
    if [ -f "$STATE_DIR/hotspot.on" ]; then
        hotspot_off >/dev/null
    fi
    setprop ctl.stop dhcpcd_wlan0
    if wpa_running; then
        wpa disconnect >/dev/null
        setprop ctl.stop wpa_supplicant
    fi
    echo "state=off"
}

wifi_scan() {
    wpa_running || err "Wi-Fi is off"
    wpa scan >/dev/null
    sleep 4
    # bssid freq signal flags ssid (tab separated); drop the header line
    wpa scan_results | grep -v '^bssid'
}

wifi_saved() {
    wpa_running || err "Wi-Fi is off"
    wpa list_networks | grep -v '^network id'
}

# id of the saved network with this ssid, or empty
wifi_find() {
    wpa list_networks | grep -v '^network id' | while IFS="$(printf '	')" read -r id s rest; do
        if [ "$s" = "$1" ]; then echo "$id"; break; fi
    done
}

wifi_dhcp() {
    setprop ctl.stop dhcpcd_wlan0
    setprop dhcp.wlan0.result ""
    setprop ctl.start dhcpcd_wlan0:wlan0
    i=0
    while [ $i -lt 30 ]; do
        r=$(prop dhcp.wlan0.result)
        [ "$r" = "ok" ] && return 0
        [ "$r" = "failed" ] && return 1
        sleep 0.5; i=$((i + 1))
    done
    return 1
}

wifi_connect() {
    ssid=$1; psk=$2
    [ -n "$ssid" ] || err "no network name"
    wpa_running || wifi_on >/dev/null
    id=$(wifi_find "$ssid")
    if [ -z "$id" ]; then
        id=$(wpa add_network | tail -n 1)
        case "$id" in
            ''|*[!0-9]*) err "could not add network" ;;
        esac
        wpa set_network "$id" ssid "\"$ssid\"" >/dev/null
        if [ -n "$psk" ]; then
            wpa set_network "$id" key_mgmt WPA-PSK >/dev/null
            wpa set_network "$id" psk "\"$psk\"" >/dev/null || err "bad password"
        else
            wpa set_network "$id" key_mgmt NONE >/dev/null
        fi
    elif [ -n "$psk" ]; then
        # a saved network with a new password; without one the saved key stays
        wpa set_network "$id" key_mgmt WPA-PSK >/dev/null
        wpa set_network "$id" psk "\"$psk\"" >/dev/null || err "bad password"
    fi
    wpa enable_network "$id" >/dev/null
    wpa select_network "$id" >/dev/null
    wpa save_config >/dev/null
    i=0
    while [ $i -lt 40 ]; do
        s=$(wpa status | grep '^wpa_state=' | cut -d= -f2)
        [ "$s" = "COMPLETED" ] && break
        sleep 0.5; i=$((i + 1))
    done
    # let the other saved networks be used again later
    wpa enable_network all >/dev/null
    [ "$s" = "COMPLETED" ] || err "could not join $ssid"
    if wifi_dhcp; then
        echo "state=connected"
        echo "ip=$(prop dhcp.wlan0.ipaddress)"
    else
        err "no address from $ssid"
    fi
}

wifi_forget() {
    wpa_running || err "Wi-Fi is off"
    id=$(wifi_find "$1")
    [ -n "$id" ] || err "not saved"
    wpa remove_network "$id" >/dev/null
    wpa save_config >/dev/null
    echo "state=ok"
}

wifi_disconnect() {
    wpa_running || err "Wi-Fi is off"
    setprop ctl.stop dhcpcd_wlan0
    wpa disconnect >/dev/null
    echo "state=disconnected"
}

# ------------------------------------------------------------- hotspot ----

# netd replies "2xx 0 ..." on success; unsolicited "6xx" event lines
# (interface added/removed) can precede the reply
ndc_ok() {
    echo "$1" | grep -qE '^2[0-9][0-9] '
}

# the interface carrying the default route (mobile data), for NAT
upstream() {
    for t in main rmnet_data0 rmnet_data1 rmnet0; do
        d=$(ip route show table "$t" 2>/dev/null | grep '^default' | head -n 1 | sed 's/.* dev \([^ ]*\).*/\1/')
        [ -n "$d" ] && [ "$d" != "$IFACE" ] && { echo "$d"; return; }
    done
    for d in rmnet_data0 rmnet_data1 rmnet0; do
        a=$(ndc interface getcfg "$d" 2>/dev/null | cut -d' ' -f4)
        [ -n "$a" ] && [ "$a" != "0.0.0.0" ] && { echo "$d"; return; }
    done
}

hotspot_status() {
    if [ -f "$STATE_DIR/hotspot.on" ]; then
        echo "state=on"
        cat "$STATE_DIR/hotspot.on"
        n=0
        [ -f "$LEASES" ] && n=$(wc -l < "$LEASES")
        echo "clients=$n"
    else
        echo "state=off"
    fi
}

hotspot_on() {
    ssid=$1; psk=$2; chan=${3:-6}
    [ -n "$ssid" ] || err "no network name"
    if [ "$psk" != "-" ] && [ ${#psk} -lt 8 ]; then
        err "password needs 8+ characters"
    fi
    up=$(upstream)
    # leave station mode
    setprop ctl.stop dhcpcd_wlan0
    if wpa_running; then
        wpa disconnect >/dev/null
        setprop ctl.stop wpa_supplicant
        sleep 1
    fi
    ndc softap fwreload $IFACE AP >/dev/null 2>&1
    if [ "$psk" = "-" ]; then
        out=$(ndc softap set $IFACE "$ssid" broadcast "$chan" open)
    else
        out=$(ndc softap set $IFACE "$ssid" broadcast "$chan" wpa2-psk "$psk")
    fi
    ndc_ok "$out" || err "softap set: $out"
    out=$(ndc softap startap)
    ndc_ok "$out" || err "softap start: $out"
    ndc interface setcfg $IFACE $AP_ADDR 24 up >/dev/null
    ndc tether interface add $IFACE >/dev/null
    ndc tether start $AP_START $AP_END >/dev/null
    ndc ipfwd enable >/dev/null
    ndc tether dns set 0 8.8.8.8 8.8.4.4 >/dev/null 2>&1
    if [ -n "$up" ]; then
        ndc nat enable $IFACE "$up" >/dev/null 2>&1
    fi
    {
        echo "ssid=$ssid"
        echo "security=$([ "$psk" = "-" ] && echo open || echo wpa2)"
        echo "channel=$chan"
        echo "upstream=$up"
        echo "address=$AP_ADDR"
    } > "$STATE_DIR/hotspot.on"
    hotspot_status
}

hotspot_off() {
    up=""
    [ -f "$STATE_DIR/hotspot.on" ] && up=$(grep '^upstream=' "$STATE_DIR/hotspot.on" | cut -d= -f2)
    [ -n "$up" ] && ndc nat disable $IFACE "$up" >/dev/null 2>&1
    ndc ipfwd disable >/dev/null 2>&1
    ndc tether stop >/dev/null 2>&1
    ndc tether interface remove $IFACE >/dev/null 2>&1
    ndc softap stopap >/dev/null 2>&1
    ndc interface clearaddrs $IFACE >/dev/null 2>&1
    ndc softap fwreload $IFACE STA >/dev/null 2>&1
    rm -f "$STATE_DIR/hotspot.on"
    # back to station mode
    setprop ctl.start wpa_supplicant
    i=0
    while [ $i -lt 20 ]; do
        wpa_running && [ -n "$(wpa status)" ] && break
        sleep 0.5; i=$((i + 1))
    done
    wpa enable_network all >/dev/null 2>&1
    wpa reassociate >/dev/null 2>&1
    echo "state=off"
}

# ----------------------------------------------------------------- usb ----

usb_status() {
    cfg=$(prop sys.usb.config)
    echo "config=$cfg"
    echo "state=$(prop sys.usb.state)"
    echo "connected=$(cat /sys/class/android_usb/android0/state 2>/dev/null)"
    case "$cfg" in
        *adb*) echo "adb=1" ;;
        *) echo "adb=0" ;;
    esac
    case "$cfg" in
        mtp*) echo "mode=mtp" ;;
        mass_storage*) echo "mode=mass_storage" ;;
        charging*|none|adb) echo "mode=charging" ;;
        *) echo "mode=other" ;;
    esac
}

usb_set() {
    mode=$1; adb=$2
    case "$mode" in
        mtp|mass_storage|charging) ;;
        *) err "unknown mode $mode" ;;
    esac
    if [ "$mode" = "mass_storage" ]; then
        # export the memory card (the internal storage is a directory,
        # not a block device, and cannot be shared this way)
        dev=""
        for d in /dev/block/mmcblk1p1 /dev/block/mmcblk1; do
            [ -e "$d" ] && { dev=$d; break; }
        done
        [ -n "$dev" ] || err "no memory card to share"
        lun=/sys/class/android_usb/android0/f_mass_storage/lun/file
        /data/j2me/bin/gsu -u 1000 -c "echo $dev > $lun" 2>/dev/null \
            || echo "$dev" > "$lun" 2>/dev/null
    fi
    # the compositions init.qcom.usb.rc knows: mtp[,adb], mass_storage[,adb],
    # charging (no adb) and plain "adb" (charging + debugging)
    cfg=$mode
    if [ "$adb" = "1" ]; then
        if [ "$mode" = "charging" ]; then cfg="adb"; else cfg="$mode,adb"; fi
    fi
    setprop sys.usb.config "$cfg"
    setprop persist.sys.usb.config "$cfg"
    sleep 1
    usb_status
}

# ------------------------------------------------------------------ bt ----

bt_status() {
    st=$(prop bluetooth.status)
    case "$st" in
        on) echo "state=on" ;;
        *) echo "state=off" ;;
    esac
    n=$(prop persist.sys.bt.name)
    [ -z "$n" ] && n=$(prop net.bt.name)
    [ -z "$n" ] && n="JioPhone"
    echo "name=$n"
    echo "address=$(prop persist.service.bdroid.bdaddr)"
}

bt_on() {
    # init.qcom.bt.sh downloads the WCNSS Bluetooth firmware and reports
    # bluetooth.status=on; the Bluedroid stack itself lives inside KaiOS's
    # bluetoothd, which only Gecko can drive.
    setprop bluetooth.hciattach true
    i=0
    while [ $i -lt 20 ]; do
        [ "$(prop bluetooth.status)" = "on" ] && break
        sleep 0.5; i=$((i + 1))
    done
    [ "$(prop bluetooth.status)" = "on" ] || err "Bluetooth did not power on"
    setprop bluetooth.isEnabled true
    bt_status
}

bt_off() {
    setprop bluetooth.isEnabled false
    setprop bluetooth.hciattach false
    setprop ctl.stop hciattach
    setprop bluetooth.status off
    bt_status
}

bt_name() {
    [ -n "$1" ] || err "no name"
    setprop persist.sys.bt.name "$1"
    setprop net.bt.name "$1"
    bt_status
}

# ----------------------------------------------------------------- main ----

group=$1; cmd=$2
[ $# -ge 2 ] && shift 2
case "$group.$cmd" in
    wifi.status)      wifi_status ;;
    wifi.on)          wifi_on ;;
    wifi.off)         wifi_off ;;
    wifi.scan)        wifi_scan ;;
    wifi.saved)       wifi_saved ;;
    wifi.connect)     wifi_connect "$1" "$2" ;;
    wifi.forget)      wifi_forget "$1" ;;
    wifi.disconnect)  wifi_disconnect ;;
    hotspot.status)   hotspot_status ;;
    hotspot.on)       hotspot_on "$1" "$2" "$3" ;;
    hotspot.off)      hotspot_off ;;
    usb.status)       usb_status ;;
    usb.set)          usb_set "$1" "$2" ;;
    bt.status)        bt_status ;;
    bt.on)            bt_on ;;
    bt.off)           bt_off ;;
    bt.name)          bt_name "$1" ;;
    *) err "usage: s100_net.sh wifi|hotspot|usb|bt <command>" ;;
esac
