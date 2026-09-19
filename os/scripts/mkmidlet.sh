#!/usr/bin/env bash
# mkmidlet.sh - compile, preverify and package a MIDlet suite with the
# toolchain this project already has (JDK 8 + the rebuilt preverifier), no
# WTK needed. Runs inside WSL after build.sh.
#
#   wsl -d Ubuntu-24.04 -- bash os/scripts/mkmidlet.sh <srcdir> <Name> <MainClass> [out.jar]
#   e.g. bash os/scripts/mkmidlet.sh os/examples/Hello Hello HelloMIDlet
#
# Produces <srcdir>/<Name>.jar and <Name>.jad (installable with
# device/j2me.sh install or the emulator, see README).
set -euo pipefail

OS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$HOME/.cache/s100}"
JDK_DIR="${JDK_DIR:-/usr/lib/jvm/java-8-openjdk-amd64}"
MIDP_CLASSES="$WORK/build/midp/classes.zip"
PREVERIFY="$WORK/build/tools/preverify"

src="${1:?srcdir}"; name="${2:?suite name}"; main="${3:?main class}"
jar="${4:-$src/$name.jar}"
jad="${jar%.jar}.jad"

[[ -f "$MIDP_CLASSES" ]] || { echo "[x] $MIDP_CLASSES missing: run build.sh midp" >&2; exit 1; }
[[ -x "$PREVERIFY" ]]    || { echo "[x] $PREVERIFY missing: run build.sh cldc" >&2; exit 1; }

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/cls" "$tmp/pv"

# -bootclasspath = CLDC+MIDP API only, so nothing from the JDK leaks in
"$JDK_DIR/bin/javac" -source 1.3 -target 1.3 -nowarn \
    -bootclasspath "$MIDP_CLASSES" -d "$tmp/cls" "$src"/*.java 2>&1 | grep -v "^warning: \[options\]" || true
"$PREVERIFY" -classpath "$MIDP_CLASSES" -d "$tmp/pv" "$tmp/cls"

cat > "$tmp/MANIFEST.MF" <<EOF
MIDlet-1: $name, , $main
MIDlet-Name: $name
MIDlet-Vendor: ProjectS100
MIDlet-Version: 1.0
MicroEdition-Configuration: CLDC-1.1
MicroEdition-Profile: MIDP-2.1
EOF
# copy any non-source resources (icons, data) into the jar as well
find "$src" -type f ! -name '*.java' ! -name '*.jar' ! -name '*.jad' -exec cp --parents -t "$tmp/pv" {} + 2>/dev/null || true
(cd "$tmp/pv" && "$JDK_DIR/bin/jar" cfm "$tmp/out.jar" "$tmp/MANIFEST.MF" .)
cp "$tmp/out.jar" "$jar"
size=$(stat -c %s "$jar")
{
    cat "$tmp/MANIFEST.MF"
    echo "MIDlet-Jar-URL: $(basename "$jar")"
    echo "MIDlet-Jar-Size: $size"
} > "$jad"
echo "[+] $jar ($size bytes), $jad"
