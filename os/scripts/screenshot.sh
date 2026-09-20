#!/usr/bin/env bash
# screenshot.sh - grab the phone's framebuffer as a PNG.
#
#   bash os/scripts/screenshot.sh [out.png]      default: os/out/phone.png
#
# Dumps /dev/graphics/fb0 as root (via /s60su + gsu, since the adb shell
# user cannot read it), pulls it, and renders it with fbdump.py using the
# geometry the LF-2403N reports (stride 512 = 256 px, 2 pages of 320 rows:
# the picture shows both pages, msm_fb displays whichever was last panned to).
set -euo pipefail
OS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$OS_DIR/out/phone.png}"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
RAW=/data/local/tmp/fb.raw
GSU=/data/j2me/bin/gsu

adb shell "/s60su -c '$GSU -c \"dd if=/dev/graphics/fb0 of=$RAW bs=512 count=640 2>/dev/null; chmod 644 $RAW\"'"
tmp="$(mktemp)"
adb pull "$RAW" "$(cygpath -m "$tmp" 2>/dev/null || echo "$tmp")" >/dev/null
adb shell "/s60su -c '$GSU -c \"rm -f $RAW\"'"
w() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
python "$(w "$OS_DIR/scripts/fbdump.py")" "$(w "$tmp")" "$(w "$OUT")" 256x640x16
rm -f "$tmp"
echo "$OUT"
