#!/usr/bin/env bash
# ams_stop.sh - kill a running runMidlet on the phone and bring KaiOS (b2g) back.
# (Gonk's toybox has no pkill, hence the ps/awk dance.) On the S100 boot
# (no KaiOS) it stops init's service s100 instead; ams_start.sh restarts it.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
if [ -n "$(adb shell getprop init.svc.s100 | tr -d '\r')" ]; then
    adb shell "/s60su -c 'stop s100'"
    sleep 2
    adb shell "getprop init.svc.s100; ps | grep runMidlet | grep -v grep"
    exit 0
fi
pids=$(adb shell "ps | grep runMidlet | grep -v grep" | awk '{print $2}' | tr -d '\r' | tr '\n' ' ')
if [ -n "$pids" ]; then
    echo "[*] killing runMidlet: $pids"
    adb shell "/s60su -c '/data/j2me/bin/gsu -c \"kill $pids; sleep 1; kill -9 $pids 2>/dev/null; true\"'"
fi
adb shell "/s60su -c '/data/j2me/bin/gsu -c \"start b2g\"'"
sleep 2
adb shell "ps | grep -E 'runMidlet|b2g' | grep -v grep | head -3"
