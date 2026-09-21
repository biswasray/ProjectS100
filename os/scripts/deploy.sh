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
STAGE=/data/local/tmp/j2me_stage
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
# with path conversion off, adb.exe must be handed a Windows-style local path
OUT_LOCAL="$(cygpath -m "$OUT" 2>/dev/null || echo "$OUT")"

[[ -f "$OUT/bin/runMidlet" ]] || {
    echo "[x] $OUT/bin/runMidlet missing - run scripts/build.sh first" >&2; exit 1; }
[[ -f "$OUT/bin/gsu" ]] || {
    echo "[x] $OUT/bin/gsu missing - run scripts/build.sh package" >&2; exit 1; }

adb get-state >/dev/null 2>&1 || { echo "[x] no adb device (boot the phone normally, USB debugging via rooted boot)" >&2; exit 1; }
adb shell "/s60su -c id" | grep -q "uid=0" || { echo "[x] /s60su not giving root - is the rooted boot flashed?" >&2; exit 1; }

# Windows adb.exe drops the quoting of a separate -c argument (sh -c cat /x
# turns into a bare cat), so hand the device shell one pre-quoted string.
# The uid-0 shell adb gives us has no CAP_DAC_OVERRIDE, so every command also
# goes through gsu (device/gsu.c) to pick up the system/graphics/input groups.
GSU=$STAGE/bin/gsu
su() { adb shell "/s60su -c '$GSU -c \"$*\"'"; }

# the device-side scripts are plain files: take the current ones from
# device/ so a script fix never needs (or waits for) a repackage
for f in "$OS_DIR"/device/*.sh; do
    tr -d '' < "$f" > "$OUT/$(basename "$f")"
done
cp "$OS_DIR/device/keymap.txt" "$OUT/keymap.txt"

echo "[*] pushing runtime ($(du -sh "$OUT" | cut -f1)) to $STAGE"
# adb push runs as shell; /data/local/tmp is the only place it may write
adb shell "rm -rf $STAGE"
adb push "$OUT_LOCAL/." "$STAGE" >/dev/null
adb shell "chmod 755 $STAGE/bin/* $STAGE/*.sh"
adb shell "/s60su -c '$GSU -c id'" | grep -q "(graphics)" || {
    echo "[x] $GSU did not grant the graphics group" >&2; exit 1; }

echo "[*] installing into $DEST"
su "mkdir -p $DEST/bin $DEST/lib $DEST/appdb $DEST/tmp && chmod 755 $DEST"
su "cp -r $STAGE/* $DEST/ && chmod 755 $DEST/bin/* $DEST/*.sh"
adb shell "rm -rf $STAGE"
GSU=$DEST/bin/gsu

echo "[+] deployed. Try:"
echo "      adb shell \"/s60su -c $DEST/j2me.sh\"                       # app manager on the LCD"
echo "      adb shell \"/s60su -c '$DEST/j2me.sh install $DEST/Hello.jad'\""
echo "      adb shell \"/s60su -c '$DEST/j2me.sh sh'\"                  # shell with b2g stopped"

case "${1:-}" in
    --run)      su "$DEST/j2me.sh" ;;
    --keyprobe) su "$DEST/bin/keyprobe" ;;
esac
