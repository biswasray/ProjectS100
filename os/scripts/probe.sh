#!/usr/bin/env bash
# probe.sh - collect the device facts the framebuffer port depends on and
# save them under os/out/probe/: framebuffer geometry/depth, input devices,
# and the Gonk key layout files (the authoritative keycode -> key tables).
#
#   bash os/scripts/probe.sh
#
# Needs adb + the rooted boot (/s60su). Read the summary it prints, then
# adjust device/keymap.txt if your unit's codes differ.
set -uo pipefail

OS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$OS_DIR/out/probe"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
mkdir -p "$OUT/keylayout"

adb get-state >/dev/null 2>&1 || { echo "[x] no adb device" >&2; exit 1; }
# Windows adb.exe drops the quoting of a separate -c argument (sh -c cat /x
# turns into a bare cat), so hand the device shell one pre-quoted string.
su() { adb shell "/s60su -c '$*'"; }

echo "[*] framebuffer"
su "ls -la /dev/graphics/ /dev/fb* 2>/dev/null; for f in modes bits_per_pixel virtual_size stride name; do echo \"fb0/\$f: \$(cat /sys/class/graphics/fb0/\$f 2>/dev/null)\"; done" | tee "$OUT/framebuffer.txt"

echo "[*] input devices"
su "cat /proc/bus/input/devices" | tee "$OUT/input_devices.txt" | grep -E "^N|^H" || true

echo "[*] key layouts"
for f in $(su "ls /system/usr/keylayout/ /vendor/usr/keylayout/ 2>/dev/null" | tr -d '\r'); do
    case "$f" in */*) continue;; esac
    su "cat /system/usr/keylayout/$f 2>/dev/null || cat /vendor/usr/keylayout/$f" > "$OUT/keylayout/$f" 2>/dev/null
done
ls "$OUT/keylayout"

echo "[*] platform"
su "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.display.id; uname -a; cat /proc/cpuinfo | grep -i 'hardware\|processor\|features'" | tee "$OUT/platform.txt"

echo
echo "[+] saved under $OUT. Key layouts of interest (non-Generic ones are the phone's keypad):"
grep -H "^key " "$OUT"/keylayout/*.kl 2>/dev/null | grep -v Generic.kl | head -60
