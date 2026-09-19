#!/usr/bin/env bash
# deploy.sh - push the packaged runtime (os/out/j2me) to the phone over adb.
#
#   bash os/scripts/deploy.sh            push everything to /data/j2me
#   bash os/scripts/deploy.sh --run      ... and start the app manager
#   bash os/scripts/deploy.sh --keyprobe push + run the keypad probe only
#
# Needs the rooted boot from tools/ (adb authorised, /s60su present). Works
# from Git Bash or WSL; adb must be on PATH.
set -euo pipefail

OS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$OS_DIR/out/j2me"
DEST=/data/j2me
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

[[ -x "$OUT/bin/runMidlet" || -f "$OUT/bin/runMidlet" ]] || {
    echo "[x] $OUT/bin/runMidlet missing - run scripts/build.sh first" >&2; exit 1; }

adb get-state >/dev/null 2>&1 || { echo "[x] no adb device (boot the phone normally, USB debugging via rooted boot)" >&2; exit 1; }
adb shell /s60su -c id | grep -q "uid=0" || { echo "[x] /s60su not giving root - is the rooted boot flashed?" >&2; exit 1; }

su() { adb shell /s60su -c "$*"; }

echo "[*] preparing $DEST"
su "mkdir -p $DEST/bin $DEST/lib $DEST/appdb $DEST/tmp && chmod 755 $DEST"

echo "[*] pushing runtime ($(du -sh "$OUT" | cut -f1))"
# adb push to /data/j2me directly needs root; stage through /data/local/tmp
adb push "$OUT/." /data/local/tmp/j2me_stage >/dev/null
su "cp -r /data/local/tmp/j2me_stage/* $DEST/ && rm -rf /data/local/tmp/j2me_stage"
su "chmod 755 $DEST/bin/* $DEST/j2me.sh"

echo "[+] deployed. Try:"
echo "      adb shell /s60su -c $DEST/j2me.sh            # app manager on the LCD"
echo "      adb shell /s60su -c \"$DEST/j2me.sh install /data/j2me/Hello.jad\""
echo "      adb shell /s60su -c \"$DEST/j2me.sh sh\"       # shell with b2g stopped"

case "${1:-}" in
    --run)      su "$DEST/j2me.sh" ;;
    --keyprobe) su "$DEST/bin/keyprobe" ;;
esac
