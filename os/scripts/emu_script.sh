#!/usr/bin/env bash
# emu_script.sh - drive the emulator through a timed key script and take
# screenshots along the way (for checking multi-step flows such as the
# installer). Run inside WSL:
#
#   bash os/scripts/emu_script.sh "3:ok 6 1" "8:shot" "10:end ok"
#
# Each argument is "<seconds after start>:<keys>" where keys are sendkey.py
# names; the key "shot" writes os/out/emu-<n>.png instead. EMU_KEEP=1 is
# implied (installed suites are kept). The run ends 3 s after the last step.
set -euo pipefail
OS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
EMU="${WORK:-$HOME/.cache/s100}/emu"
export EMU_KEEP=1
last=0
for step in "$@"; do
    t=${step%%:*}
    [[ $t -gt $last ]] && last=$t
done
EMU_SECONDS=$((last + 3)) bash "$OS_DIR/scripts/emu.sh" > "$EMU/script.log" 2>&1 &
runner=$!
start=$(date +%s)
for step in "$@"; do
    t=${step%%:*}
    keys=${step#*:}
    while (( $(date +%s) - start < t )); do sleep 0.05; done
    if [[ "$keys" == "shot" ]]; then
        n=$(find "$OS_DIR/out" -maxdepth 1 -name "emu-*.png" | wc -l)
        python3 "$OS_DIR/scripts/fbdump.py" "$EMU/fb.raw" "$OS_DIR/out/emu-$n.png" 240x320x16
    else
        # shellcheck disable=SC2086
        python3 "$OS_DIR/scripts/sendkey.py" "$EMU/keys" $keys
    fi
done
wait $runner || true
tail -n 5 "$EMU/script.log"
