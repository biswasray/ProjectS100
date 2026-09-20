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
# the player: demux/decode a synthetic WAV (and the AVI above) without ALSA
MSRC="$OS_DIR/port/ams/appmanager_ui/native/s100_media.c"
sed 's/\r$//' "$MSRC" > "$OUT/s100_media.c"
cp "$OS_DIR/port/ams/appmanager_ui/native/minimp3.h" "$OUT/"
echo "[*] gcc -DS100_HOST_TEST -DS100_NO_ALSA (player)"
gcc -DS100_HOST_TEST -DS100_NO_ALSA -O2 -Wall -Wextra -Wno-unused-parameter -Wno-unused-function \
    -o "$OUT/s100_media_test" "$OUT/s100_media.c" -lm -lpthread
python3 - "$OUT/test.wav" <<'PY'
import struct, math, sys
r = 8000; n = r * 4
d = b''.join(struct.pack('<h', int(12000 * math.sin(2 * math.pi * 440 * i / r))) for i in range(n))
h = (b'RIFF' + struct.pack('<I', 36 + len(d)) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, 1, r, r * 2, 2, 16)
     + b'data' + struct.pack('<I', len(d)))
open(sys.argv[1], 'wb').write(h + d)
PY
"$OUT/s100_media_test" "$OUT/test.wav" "$OUT/test.pcm" | tr '\r' '\n' | grep -v '^pos'
"$OUT/s100_media_test" "$OS_DIR/out/native_test.avi" || true
echo "[+] ok"
