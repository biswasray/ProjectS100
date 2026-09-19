#!/usr/bin/env bash
# emu.sh - run the packaged ARM runtime on the PC under qemu-arm with a
# file-backed framebuffer, so the MIDP UI can be exercised and screenshotted
# without the phone.
#
#   wsl -d Ubuntu-24.04 -- bash os/scripts/emu.sh [runMidlet args]
#     (default args: -1 com.sun.midp.appmanager.MVMManager  -> the app manager)
#
# While it runs (from another WSL shell):
#   python3 os/scripts/sendkey.py ~/.cache/s100/emu/keys down down ok
#   python3 os/scripts/fbdump.py  ~/.cache/s100/emu/fb.raw shot.png
# EMU_SECONDS=8 emu.sh ...  runs for a fixed time, then writes
#   os/out/emu-<n>.png and exits (used for automated checks).
# EMU_KEYS="down down ok" sends those keys 3 s after start.
# EMU_KEEP=1 keeps the previous emulator home (installed suites) instead of
#   starting from a fresh copy of os/out/j2me.
set -euo pipefail

OS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$OS_DIR/out/j2me"
EMU="${WORK:-$HOME/.cache/s100}/emu"
export MSYS_NO_PATHCONV=1

[[ -x "$PKG/bin/runMidlet" ]] || { echo "[x] run build.sh first" >&2; exit 1; }
command -v qemu-arm-static >/dev/null || { echo "[x] qemu-user-static missing (setup_wsl.sh)" >&2; exit 1; }

if [[ -z "${EMU_KEEP:-}" || ! -d "$EMU/home" ]]; then
    rm -rf "$EMU"; mkdir -p "$EMU"
    cp -r "$PKG/." "$EMU/home/"
    mkfifo "$EMU/keys"
else
    # EMU_KEEP=1: keep appdb (installed suites) but refresh the binaries
    cp -r "$PKG/bin/." "$EMU/home/bin/"
fi
: > "$EMU/fb.raw"

export MIDP_HOME="$EMU/home"
export MIDP_FB_FAKE="240x320x16"
export MIDP_FB_DEV="$EMU/fb.raw"
export MIDP_KEYPAD_DEV="$EMU/keys"
export MIDP_KEYMAP="$EMU/home/keymap.txt"
export MIDP_FB_DEVICE=jiophone
export TMPDIR="$EMU/tmp"; mkdir -p "$TMPDIR"

args=("$@")
[[ ${#args[@]} -eq 0 ]] && args=(-1 com.sun.midp.appmanager.MVMManager)

echo "[*] fb: $EMU/fb.raw  keys: $EMU/keys  home: $MIDP_HOME"
cd "$EMU/home"
if [[ -n "${EMU_SECONDS:-}" ]]; then
    qemu-arm-static bin/runMidlet "${args[@]}" > "$EMU/run.log" 2>&1 &
    pid=$!
    if [[ -n "${EMU_KEYS:-}" ]]; then
        sleep 3
        # shellcheck disable=SC2086
        python3 "$OS_DIR/scripts/sendkey.py" "$EMU/keys" $EMU_KEYS
    fi
    sleep "$EMU_SECONDS"
    n=$(find "$OS_DIR/out" -maxdepth 1 -name "emu-*.png" | wc -l)
    python3 "$OS_DIR/scripts/fbdump.py" "$EMU/fb.raw" "$OS_DIR/out/emu-$n.png" 240x320x16
    kill -9 "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    echo "[*] log tail:"; tail -n 15 "$EMU/run.log"
else
    exec qemu-arm-static bin/runMidlet "${args[@]}"
fi
