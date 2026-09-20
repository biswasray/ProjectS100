#!/usr/bin/env bash
# check_native.sh - build os/port/ams/appmanager_ui/native/s100_native.c as
# a host program (-DS100_HOST_TEST) and run its self test: a synthetic
# NV21 frame becomes os/out/native_test.jpg and a ten-frame MJPEG AVI
# os/out/native_test.avi, and the exec/spawn/list helpers are exercised.
# Run inside WSL:
#
#   wsl -d Ubuntu-24.04 -- bash os/scripts/check_native.sh
set -euo pipefail

OS_DIR="${OS_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
WORK="${WORK:-$HOME/.cache/s100}"
SRC="$OS_DIR/port/ams/appmanager_ui/native/s100_native.c"
OUT="$WORK/native_test"
mkdir -p "$OUT" "$OS_DIR/out"
tmp="$OUT/s100_native.c"
sed 's/\r$//' "$SRC" > "$tmp"
echo "[*] gcc -DS100_HOST_TEST"
gcc -DS100_HOST_TEST -O2 -Wall -Wextra -Wno-unused-parameter -o "$OUT/s100_test" "$tmp" -lm -lpthread
echo "[*] running"
"$OUT/s100_test" "$OS_DIR/out/native_test.jpg" "$OS_DIR/out/native_test.avi"
if command -v ffprobe >/dev/null; then
    ffprobe -hide_banner "$OS_DIR/out/native_test.avi" 2>&1 | grep -E "Stream|Duration" || true
fi
echo "[+] ok"
