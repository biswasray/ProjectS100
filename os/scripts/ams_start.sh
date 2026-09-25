#!/usr/bin/env bash
# ams_start.sh - start the phoneME application manager on the phone's LCD
# and return immediately (the VM keeps running; b2g is stopped until it exits).
#
#   bash os/scripts/ams_start.sh            app manager
#   bash os/scripts/ams_start.sh run 2      any j2me.sh sub-command
#
# Stop it again with:  bash os/scripts/ams_stop.sh
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
args="$*"
# S100 boot (no KaiOS): the app manager is init's service s100
if [ -z "$args" ] && [ -n "$(adb shell getprop init.svc.s100 | tr -d '\r')" ]; then
    adb shell "/s60su -c 'start s100'"
    sleep 2
    adb shell "getprop init.svc.s100; ps | grep runMidlet | grep -v grep"
    exit 0
fi
# nohup + setsid so the VM survives this adb session ending
adb shell "/s60su -c '/data/j2me/bin/gsu -c \"cd /data/j2me; nohup setsid /data/j2me/j2me.sh $args >/data/j2me/ams.out 2>&1 </dev/null &\"'"
sleep 1
adb shell "ps | grep -E 'runMidlet|j2me.sh' | grep -v grep"
