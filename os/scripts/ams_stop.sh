#!/usr/bin/env bash
# ams_stop.sh - kill a running runMidlet on the phone and bring KaiOS (b2g) back.
# (Gonk's toybox has no pkill, hence the ps/awk dance.)
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
pids=$(adb shell "ps | grep runMidlet | grep -v grep" | awk '{print $2}' | tr -d '\r' | tr '\n' ' ')
if [ -n "$pids" ]; then
    echo "[*] killing runMidlet: $pids"
    adb shell "/s60su -c '/data/j2me/bin/gsu -c \"kill $pids; sleep 1; kill -9 $pids 2>/dev/null; true\"'"
fi
adb shell "/s60su -c '/data/j2me/bin/gsu -c \"start b2g\"'"
sleep 2
adb shell "ps | grep -E 'runMidlet|b2g' | grep -v grep | head -3"
