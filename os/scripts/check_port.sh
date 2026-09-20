#!/usr/bin/env bash
# check_port.sh - fast type check of os/port Java sources against the last
# MIDP build (no ROM build, ~5 s). Run inside WSL:
#
#   wsl -d Ubuntu-24.04 -- bash os/scripts/check_port.sh
#
# Uses the classes of ~/.cache/s100/build/midp (build.sh midp must have run
# once) as the boot class path, so CLDC/MIDP API misuse (String.split,
# generics, ...) is caught before the 10 minute rebuild.
set -euo pipefail

OS_DIR="${OS_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
WORK="${WORK:-$HOME/.cache/s100}"
JDK_DIR="${JDK_DIR:-/usr/lib/jvm/java-8-openjdk-amd64}"
CLASSES="$WORK/build/midp/classes"
OUT="$WORK/portcheck"

[[ -d "$CLASSES/javax/microedition/lcdui" ]] || {
    echo "[x] $CLASSES missing: run build.sh midp first" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"
mapfile -t SRC < <(find "$OS_DIR/port/ams/appmanager_ui/classes" -name '*.java' | sort)
echo "[*] javac -source 1.3 on ${#SRC[@]} files"
"$JDK_DIR/bin/javac" -source 1.3 -target 1.3 -nowarn -encoding ascii \
    -bootclasspath "$CLASSES" -classpath "$CLASSES" -d "$OUT" "${SRC[@]}"
echo "[+] ok: $(find "$OUT" -name '*.class' | wc -l) classes"
