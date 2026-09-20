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
#   sim status                          (Settings > Network > SIM card management)
#   radio on|off                        modem power through rild's debug socket
#   data on <apn> | data off | data status
#   airplane status|on|off
#   dns status | dns set <name> <ip1> [ip2] | dns clear
#   vpn status | vpn disconnect
#   vpn connect <type> <server> <user> <password> <psk> <name>
#
# Exit status is 0 when the request was carried out; error text goes to
# stdout as "error=..." so the UI can show it.

WPA="/system/xbin/wpa_cli -p /data/misc/wifi/sockets -i wlan0"
IFACE=wlan0
AP_ADDR=192.168.43.1
AP_START=192.168.43.2
AP_END=192.168.43.254
STATE_DIR=/data/j2me/tmp
CONF_DIR=/data/j2me/appdb
LEASES=/data/misc/dhcp/dnsmasq.leases
# rild's debug socket client (device/sockctl.c) - radio power, data calls
SOCKCTL=/data/j2me/bin/sockctl
DNS_CONF=$CONF_DIR/s100_dns.txt
VPN_STATE=/data/misc/vpn/state

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
        # Settings > Network > Private DNS overrides the DHCP servers
        [ -f "$DNS_CONF" ] && dns_apply >/dev/null 2>&1
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

# --------------------------------------------------------------- radio ----
#
# KaiOS drives the modem from Gecko's RIL worker, which is gone in Java
# mode, and there is no radiooptions binary. rild still runs, and its
# rild-debug socket (radio:system 660, we hold group system) accepts the
# same local requests radiooptions would send: 0 reset, 1 radio off,
# 5 radio on, 6 setup data call <apn>, 7 deactivate data call <cid>,
# 8 dial <number>, 9 answer, 10 hang up. There is no answer channel, so
# state is read from properties and interfaces afterwards.

ril_cmd() {
    [ -x "$SOCKCTL" ] || err "sockctl helper missing"
    [ -S /dev/socket/rild-debug ] || err "rild is not running"
    "$SOCKCTL" ril "$@" || err "rild did not accept the request"
}

radio_state() {
    if [ -f "$STATE_DIR/radio.off" ]; then echo off; else echo on; fi
}

radio_on() {
    ril_cmd 5
    rm -f "$STATE_DIR/radio.off"
    echo "state=on"
}

radio_off() {
    ril_cmd 1
    : > "$STATE_DIR/radio.off"
    echo "state=off"
}

# first rmnet interface with an address = the mobile data bearer
data_iface() {
    for d in rmnet_data0 rmnet_data1 rmnet_data2 rmnet0 rmnet1; do
        a=$(ndc interface getcfg "$d" 2>/dev/null | cut -d' ' -f4)
        [ -n "$a" ] && [ "$a" != "0.0.0.0" ] && { echo "$d $a"; return; }
    done
}

data_status() {
    set -- $(data_iface)
    if [ -n "$1" ]; then
        echo "state=connected"
        echo "iface=$1"
        echo "ip=$2"
    else
        echo "state=disconnected"
    fi
    echo "apn=$(cat "$STATE_DIR/data.apn" 2>/dev/null)"
}

data_on() {
    apn=$1
    [ -n "$apn" ] || err "no access point name"
    [ "$(radio_state)" = "on" ] || err "the radio is off"
    ril_cmd 6 "$apn"
    echo "$apn" > "$STATE_DIR/data.apn"
    i=0
    while [ $i -lt 30 ]; do
        set -- $(data_iface)
        [ -n "$1" ] && break
        sleep 0.5; i=$((i + 1))
    done
    set -- $(data_iface)
    [ -n "$1" ] || err "no data connection from the network"
    ndc interface setcfg "$1" "$2" 32 up >/dev/null 2>&1
    ndc network create 100 >/dev/null 2>&1
    ndc network interface add 100 "$1" >/dev/null 2>&1
    ndc network default set 100 >/dev/null 2>&1
    data_status
}

data_off() {
    ril_cmd 7 1
    rm -f "$STATE_DIR/data.apn"
    sleep 1
    data_status
}

# What Gecko normally publishes (gsm.sim.*, gsm.operator.*) is not set in
# Java mode; qcril leaves the last NITZ operator names and the PLMN.
sim_status() {
    n=1
    [ -S /dev/socket/rild-debug2 ] || [ -S /dev/socket/rild2 ] && n=2
    echo "slots=$n"
    echo "radio=$(radio_state)"
    echo "rild=$(prop init.svc.ril-daemon)"
    echo "ril=$(prop gsm.version.ril-impl)"
    echo "baseband=$(prop gsm.version.baseband)"
    echo "ecc=$(prop ril.ecclist)"
    echo "subscription=$(prop ril.subscription.types)"
    i=0
    while [ $i -lt $n ]; do
        s=$((i + 1))
        st=$(prop gsm.sim.state | cut -d, -f$s)
        echo "sim$s.state=${st:-unknown}"
        op=$(prop persist.radio.nitz_lons_${i}_0)
        [ -z "$op" ] && op=$(prop persist.radio.nitz_sons_${i}_0)
        [ -z "$op" ] && op=$(prop gsm.operator.alpha | cut -d, -f$s)
        echo "sim$s.operator=$op"
        echo "sim$s.plmn=$(prop persist.radio.nitz_plmn_$i)"
        echo "sim$s.numeric=$(prop gsm.sim.operator.numeric | cut -d, -f$s)"
        echo "sim$s.type=$(prop gsm.network.type | cut -d, -f$s)"
        i=$((i + 1))
    done
    echo "apm_sim_powered=$(prop persist.radio.apm_sim_not_pwdn)"
}

# ------------------------------------------------------------ airplane ----

airplane_status() {
    if [ "$(prop persist.radio.airplane_mode_on)" = "1" ] || [ -f "$STATE_DIR/airplane.on" ]; then
        echo "state=on"
    else
        echo "state=off"
    fi
    echo "radio=$(radio_state)"
    if wpa_running; then echo "wifi=on"; else echo "wifi=off"; fi
    if [ -f "$STATE_DIR/hotspot.on" ]; then echo "hotspot=on"; else echo "hotspot=off"; fi
    echo "bt=$(prop bluetooth.status)"
}

airplane_on() {
    keepwifi=$1
    [ -f "$STATE_DIR/hotspot.on" ] && hotspot_off >/dev/null 2>&1
    if [ "$keepwifi" != "1" ]; then
        wifi_off >/dev/null 2>&1
    fi
    bt_off >/dev/null 2>&1
    setprop persist.radio.airplane_mode_on 1
    : > "$STATE_DIR/airplane.on"
    radio_off >/dev/null
    airplane_status
}

airplane_off() {
    setprop persist.radio.airplane_mode_on 0
    rm -f "$STATE_DIR/airplane.on"
    radio_on >/dev/null
    airplane_status
}

# ----------------------------------------------------------------- dns ----
#
# "Private DNS" in Java mode = the resolver servers of the phone: netd's
# resolver for the Wi-Fi network (both the netId and the old per-interface
# syntax are tried), the net.dns* properties Gonk daemons read, and the
# hotspot's forwarders. There is no DNS-over-TLS stub here, so the
# provider is reached in the clear on port 53.

dns_status() {
    if [ -f "$DNS_CONF" ]; then
        echo "mode=custom"
        cat "$DNS_CONF"
    else
        echo "mode=auto"
    fi
    echo "dhcp1=$(prop dhcp.wlan0.dns1)"
    echo "dhcp2=$(prop dhcp.wlan0.dns2)"
    echo "net1=$(prop net.dns1)"
    echo "net2=$(prop net.dns2)"
}

dns_push() {
    d1=$1; d2=$2
    setprop net.dns1 "$d1"
    setprop net.dns2 "$d2"
    setprop net.wlan0.dns1 "$d1"
    setprop net.wlan0.dns2 "$d2"
    # Android 5.x netd: per netId; older: per interface
    for id in 100 101 102 103 104 105; do
        ndc resolver setnetdns $id "" $d1 $d2 >/dev/null 2>&1
    done
    ndc resolver setifdns $IFACE "" $d1 $d2 >/dev/null 2>&1
    ndc resolver setdefaultif $IFACE >/dev/null 2>&1
    ndc resolver flushnet 100 >/dev/null 2>&1
    ndc resolver flushdefaultif >/dev/null 2>&1
    if [ -f "$STATE_DIR/hotspot.on" ]; then
        ndc tether dns set 0 $d1 $d2 >/dev/null 2>&1
    fi
}

dns_apply() {
    [ -f "$DNS_CONF" ] || return 1
    d1=$(grep '^dns1=' "$DNS_CONF" | cut -d= -f2)
    d2=$(grep '^dns2=' "$DNS_CONF" | cut -d= -f2)
    [ -n "$d1" ] || return 1
    dns_push "$d1" "${d2:-$d1}"
}

dns_set() {
    name=$1; d1=$2; d2=$3
    case "$d1" in
        *.*.*.*) ;;
        *) err "bad server address" ;;
    esac
    [ -n "$d2" ] || d2=$d1
    mkdir -p "$CONF_DIR"
    {
        echo "name=$name"
        echo "dns1=$d1"
        echo "dns2=$d2"
    } > "$DNS_CONF"
    dns_apply
    dns_status
}

dns_clear() {
    rm -f "$DNS_CONF"
    d1=$(prop dhcp.wlan0.dns1); d2=$(prop dhcp.wlan0.dns2)
    if [ -n "$d1" ]; then
        dns_push "$d1" "${d2:-$d1}"
    fi
    dns_status
}

# ----------------------------------------------------------------- vpn ----
#
# Android's legacy VPN client is mtpd (PPTP, L2TP) plus racoon (IPsec).
# The JioPhone's KaiOS build ships racoon only, without pppd or mtpd, so
# the one type that can work is IPsec Xauth PSK/RSA: racoon is started as
# the init service of that name (if the build defines it) and gets its
# arguments over /dev/socket/racoon with the framework's protocol
# (sockctl send). The tunnel's addresses then appear in
# /data/misc/vpn/state (interface, addresses, routes, dns) and are set
# with ndc. Everything else reports why it cannot work.

vpn_status() {
    if [ -f "$STATE_DIR/vpn.on" ]; then
        cat "$STATE_DIR/vpn.on"
        if [ -f "$VPN_STATE" ]; then
            echo "state=connected"
            echo "iface=$(sed -n 1p "$VPN_STATE")"
            echo "address=$(sed -n 2p "$VPN_STATE")"
            echo "dns=$(sed -n 4p "$VPN_STATE")"
        elif pgrep -x racoon >/dev/null 2>&1 || [ "$(prop init.svc.racoon)" = "running" ]; then
            echo "state=connecting"
        else
            echo "state=failed"
        fi
    else
        echo "state=disconnected"
    fi
    [ -x /system/bin/mtpd ] && echo "mtpd=1" || echo "mtpd=0"
    [ -x /system/bin/racoon ] && echo "racoon=1" || echo "racoon=0"
    [ -x /system/bin/pppd ] && echo "pppd=1" || echo "pppd=0"
}

vpn_connect() {
    type=$1; server=$2; user=$3; pass=$4; psk=$5; name=$6
    [ -n "$server" ] || err "no server"
    case "$type" in
        pptp|l2tp)
            [ -x /system/bin/mtpd ] || err "PPTP and L2TP need the mtpd service, which this phone's software does not include"
            ;;
        xauthpsk|xauthrsa)
            [ -x /system/bin/racoon ] || err "no IPsec client (racoon) on this phone"
            ;;
        *) err "unknown VPN type $type" ;;
    esac
    [ -x "$SOCKCTL" ] || err "sockctl helper missing"
    rm -f "$VPN_STATE" 2>/dev/null
    setprop ctl.stop racoon
    setprop ctl.start racoon
    i=0
    while [ $i -lt 20 ] && [ ! -S /dev/socket/racoon ]; do
        sleep 0.25; i=$((i + 1))
    done
    [ -S /dev/socket/racoon ] || err "the racoon service is not defined in this init.rc"
    up=$(upstream)
    [ -n "$up" ] || up=$IFACE
    case "$type" in
        xauthpsk) "$SOCKCTL" send /dev/socket/racoon "$up" "$server" xauthpsk "$user" "$psk" "$user" "$pass" ;;
        xauthrsa) "$SOCKCTL" send /dev/socket/racoon "$up" "$server" xauthrsa "$psk" "$user" "$pass" ;;
    esac
    {
        echo "name=$name"
        echo "server=$server"
        echo "type=$type"
    } > "$STATE_DIR/vpn.on"
    i=0
    while [ $i -lt 60 ]; do
        [ -f "$VPN_STATE" ] && break
        pgrep -x racoon >/dev/null 2>&1 || break
        sleep 0.5; i=$((i + 1))
    done
    if [ ! -f "$VPN_STATE" ]; then
        rm -f "$STATE_DIR/vpn.on"
        err "could not establish the VPN (check server, user and key)"
    fi
    ifc=$(sed -n 1p "$VPN_STATE"); addr=$(sed -n 2p "$VPN_STATE")
    routes=$(sed -n 3p "$VPN_STATE"); dns=$(sed -n 4p "$VPN_STATE")
    for a in $addr; do
        ndc interface setcfg "$ifc" ${a%/*} ${a#*/} up >/dev/null 2>&1
    done
    for r in $routes; do
        ndc interface route add "$ifc" default ${r%/*} ${r#*/} 0.0.0.0 >/dev/null 2>&1
    done
    set -- $dns
    [ -n "$1" ] && dns_push "$1" "${2:-$1}"
    vpn_status
}

vpn_disconnect() {
    setprop ctl.stop racoon
    pkill -x racoon 2>/dev/null
    rm -f "$STATE_DIR/vpn.on" "$VPN_STATE" 2>/dev/null
    dns_apply >/dev/null 2>&1 || dns_clear >/dev/null 2>&1
    echo "state=disconnected"
}

# ----------------------------------------------------------------- main ----

group=$1; cmd=$2
[ $# -ge 2 ] && shift 2
case "$group.$cmd" in
    sim.status)       sim_status ;;
    radio.on)         radio_on ;;
    radio.off)        radio_off ;;
    radio.status)     echo "state=$(radio_state)" ;;
    data.status)      data_status ;;
    data.on)          data_on "$1" ;;
    data.off)         data_off ;;
    airplane.status)  airplane_status ;;
    airplane.on)      airplane_on "$1" ;;
    airplane.off)     airplane_off ;;
    dns.status)       dns_status ;;
    dns.set)          dns_set "$1" "$2" "$3" ;;
    dns.clear)        dns_clear ;;
    vpn.status)       vpn_status ;;
    vpn.connect)      vpn_connect "$1" "$2" "$3" "$4" "$5" "$6" ;;
    vpn.disconnect)   vpn_disconnect ;;
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
    *) err "usage: s100_net.sh wifi|hotspot|usb|bt|sim|radio|data|airplane|dns|vpn <command>" ;;
esac
