#!/usr/bin/env bash
# build.sh - cross-build phoneME Feature (PCSL + CLDC-HI VM + MIDP 2.x) for the
# JioPhone LF-2403N (ARMv7 Cortex-A7, Linux 3.18 / Gonk userland) inside WSL.
#
#   wsl -d Ubuntu-24.04 -- bash os/scripts/build.sh [stage...]
#
# Stages (default: all). Each is skipped if its output exists unless FORCE=1:
#   sync     mirror os/phoneME into WSL's own filesystem (see WORK below)
#   pcsl     Portable C Standard Library  -> $WORK/build/pcsl/linux_arm/{lib,inc}
#   cldc     CLDC HotSpot VM (romized)    -> $WORK/build/cldc/linux_arm_vfp/dist
#   midp     MIDP + jiophone fb port + S100 shell (os/port, with its native
#            helper and the IJG JPEG decoder) -> $WORK/build/midp/{bin/arm,lib}
#   package  static ARM binaries + config + device scripts -> os/out/j2me/
#   clean    remove $WORK/build
#
# Environment knobs (all optional):
#   OS_DIR        this os/ directory (auto-detected)
#   PHONEME_SRC   phoneME checkout (default $OS_DIR/phoneME, see fetch_phoneme.sh)
#   WORK          WSL-native scratch dir (default ~/.cache/s100). Compiling
#                 straight from /mnt/c runs at ~1 file/minute (9p), so the
#                 tree is rsync'ed here and built on ext4; only os/out/ lands
#                 back on the Windows side.
#   JDK_DIR       JDK 8 home (default /usr/lib/jvm/java-8-openjdk-amd64)
#   JOBS          make parallelism (default nproc)
#   CLDC_PLATFORM cldc/build/<platform> (default linux_arm_vfp)
set -euo pipefail

OS_DIR="${OS_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
PHONEME_SRC="${PHONEME_SRC:-$OS_DIR/phoneME}"
WORK="${WORK:-$HOME/.cache/s100}"
SRC="$WORK/phoneME"                 # mirrored sources actually compiled
PORT_SRC="$WORK/port"               # mirrored os/port (S100 shell UI + icons)
BUILD_DIR="$WORK/build"
OUT_DIR="$OS_DIR/out/j2me"
JDK_DIR="${JDK_DIR:-/usr/lib/jvm/java-8-openjdk-amd64}"
JOBS="${JOBS:-$(nproc)}"
CLDC_PLATFORM="${CLDC_PLATFORM:-linux_arm_vfp}"
GNU_TOOLS_DIR="$OS_DIR/toolchain"
CROSS="${CROSS_PREFIX:-arm-linux-gnueabi-}"

PCSL_OUTPUT_DIR="$BUILD_DIR/pcsl"
CLDC_OUTPUT_DIR="$BUILD_DIR/cldc"
CLDC_DIST_DIR="$CLDC_OUTPUT_DIR/$CLDC_PLATFORM/dist"
MIDP_OUTPUT_DIR="$BUILD_DIR/midp"
TOOLS_OUTPUT_DIR="$BUILD_DIR/tools"
PREVERIFY="$TOOLS_OUTPUT_DIR/preverify"

log()  { printf '\033[36m[*] %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m[+] %s\033[0m\n' "$*"; }
die()  { printf '\033[31m[x] %s\033[0m\n' "$*" >&2; exit 1; }

[[ -d "$PHONEME_SRC/cldc" ]] || die "phoneME sources not found at $PHONEME_SRC (run scripts/fetch_phoneme.sh)"
[[ -x "$JDK_DIR/bin/javac" ]]  || die "JDK not found at $JDK_DIR (run scripts/setup_wsl.sh)"
[[ -x "$GNU_TOOLS_DIR/bin/gcc" ]] || bash "$GNU_TOOLS_DIR/mk_shim.sh"
command -v "${CROSS}gcc" >/dev/null || die "${CROSS}gcc not found (run scripts/setup_wsl.sh)"

# Keep any user-level JVM options out of the build's javac/java runs.
export JAVA_TOOL_OPTIONS=""

sync_sources() {
    log "mirroring $PHONEME_SRC -> $SRC"
    mkdir -p "$SRC" "$PORT_SRC"
    rsync -a --delete --exclude .git "$PHONEME_SRC/" "$SRC/"
    # os/port holds the parts of the port that are plain files rather than
    # patches: the S100 (Series 40 style) AMS shell and its icons
    rsync -a --delete "$OS_DIR/port/" "$PORT_SRC/"
    find "$PORT_SRC" -type f \( -name '*.java' -o -name '*.gmk' -o -name '*.c' -o -name '*.h' \) -exec sed -i 's/\r$//' {} +
    ok "sources in sync"
}

# The archive ships a prebuilt static i386 preverify (glibc 2.2 era) whose
# 32-bit stat() overflows on 64-bit inode numbers. Rebuild it from the bundled
# sources (still 32-bit: the code assumes 32-bit longs) with LFS enabled. The
# sources also carry a fix for the per-class hash reset that made
# ResolveMethods mistake unrelated methods for final-method overrides.
build_preverify() {
    if [[ -x "$PREVERIFY" && -z "${FORCE:-}" ]]; then return; fi
    log "building host preverifier"
    mkdir -p "$TOOLS_OUTPUT_DIR"
    local src="$SRC/preverifier/src"
    gcc -m32 -O2 -w -fcommon -D_FILE_OFFSET_BITS=64 -I"$src" \
        -DUNIX -DLINUX -DJAVAVERIFY -DTRIMMED -Di386 -DBUILD_VERSION='"s100"' \
        -o "$PREVERIFY" "$src"/*.c
    ok "preverify -> $PREVERIFY"
}

build_pcsl() {
    if [[ -f "$PCSL_OUTPUT_DIR/linux_arm/lib/libpcsl_network.a" && -z "${FORCE:-}" ]]; then
        ok "pcsl already built"; return
    fi
    log "building PCSL (linux_arm_gcc, bsd/generic network)"
    make -C "$SRC/pcsl" -j"$JOBS" \
        PCSL_PLATFORM=linux_arm_gcc \
        NETWORK_MODULE=bsd/generic \
        GNU_TOOLS_DIR="$GNU_TOOLS_DIR" \
        PCSL_OUTPUT_DIR="$PCSL_OUTPUT_DIR"
    ok "pcsl -> $PCSL_OUTPUT_DIR/linux_arm"
}

build_cldc() {
    if [[ -f "$CLDC_DIST_DIR/bin/cldc_vm" && -z "${FORCE:-}" ]]; then
        ok "cldc already built"; return
    fi
    build_preverify
    log "building CLDC-HI VM ($CLDC_PLATFORM)"
    # The top-level cldc makefile is not parallel-safe (debug/release/product
    # share ../generated), so run it serially and let it fan out per target.
    # Only the product VM is needed: MIDP links libcldc_vm.a and re-romizes
    # with the dist romgen.
    make -C "$SRC/cldc/build/$CLDC_PLATFORM" product PARALLEL_ARGS="-j$JOBS" \
        JDK_DIR="$JDK_DIR" \
        ENABLE_PCSL=true PCSL_OUTPUT_DIR="$PCSL_OUTPUT_DIR" \
        ENABLE_ISOLATES=true \
        ENABLE_COMPILATION_WARNINGS=true \
        ENABLE_STATIC_ROMGEN=false \
        GNU_TOOLS_DIR="$GNU_TOOLS_DIR" \
        PREVERIFY_ORIGINAL="$PREVERIFY" \
        JVMWorkSpace="$SRC/cldc" \
        JVMBuildSpace="$CLDC_OUTPUT_DIR" \
        "$@"
    ok "cldc -> $CLDC_DIST_DIR"
}

build_midp() {
    log "building MIDP (linux_fb_gcc, TARGET_CPU=arm, TARGET_DEVICE=jiophone)"
    # The MIDP build never deletes class files it no longer compiles, and
    # the romizer takes everything in classes/: a stale inner class of a
    # replaced source (e.g. the reference AppManagerUIImpl$...) then fails
    # romization with a bare "IllegalAccessError". Start from a clean
    # classes tree every time; javac recompiles everything anyway.
    rm -rf "$MIDP_OUTPUT_DIR/classes" "$MIDP_OUTPUT_DIR/classes.zip" \
           "$MIDP_OUTPUT_DIR/tmpclasses" "$MIDP_OUTPUT_DIR/ROMImage.cpp"
    # The MIDP makefiles are not parallel-safe (generated sources race with
    # their consumers), so this stage runs serially.
    make -C "$SRC/midp/build/linux_fb_gcc" \
        JDK_DIR="$JDK_DIR" \
        PCSL_OUTPUT_DIR="$PCSL_OUTPUT_DIR" \
        CLDC_DIST_DIR="$CLDC_DIST_DIR" \
        TOOLS_DIR="$SRC/tools" \
        TOOLS_OUTPUT_DIR="$TOOLS_OUTPUT_DIR" \
        MIDP_OUTPUT_DIR="$MIDP_OUTPUT_DIR" \
        GNU_TOOLS_DIR="$GNU_TOOLS_DIR" \
        CPU=arm TARGET_DEVICE=jiophone \
        USE_MULTIPLE_ISOLATES=true \
        USE_COMPILATION_WARNINGS=true \
        USE_JPEG=true JPEG_DIR="$SRC/jpeg" \
        S100_PORT_DIR="$PORT_SRC" \
        AMS_APPMANAGER_UI_IMPL_DIR="$PORT_SRC/ams/appmanager_ui" \
        APPMANAGER_UI_RESOURCE_ADITIONAL_COMPONENTS="$PORT_SRC/ams/icons/lib.gmk" \
        "$@" 2>&1 | tee "$BUILD_DIR/midp-build.log"
    [[ ${PIPESTATUS[0]} -eq 0 ]] || die "midp make failed (see $BUILD_DIR/midp-build.log)"
    # the ROMImage rule swallows romizer errors and the link then reuses the
    # previous ROMImage.o: make that a hard failure
    if grep -q "ROMizing failed" "$BUILD_DIR/midp-build.log"; then
        die "romizer failed: grep -B5 'ROMizing failed' $BUILD_DIR/midp-build.log"
    fi
    [[ -f "$MIDP_OUTPUT_DIR/ROMImage.cpp" ]] || die "ROMImage.cpp was not generated"
    ok "midp -> $MIDP_OUTPUT_DIR"
}

package() {
    local bin="$MIDP_OUTPUT_DIR/bin/arm"
    [[ -f "$bin/runMidlet" ]] || die "$bin/runMidlet not built"
    log "packaging -> $OUT_DIR"
    rm -rf "$OUT_DIR"
    mkdir -p "$OUT_DIR/bin" "$OUT_DIR/lib" "$OUT_DIR/appdb" "$OUT_DIR/tmp"
    # executables (only the ARM ones; the *.jar and preverify are host tools)
    for f in "$bin"/*; do
        case "$(basename "$f")" in
            *.jar|preverify) ;;
            *) file "$f" | grep -q "ELF 32-bit LSB.*ARM" && cp "$f" "$OUT_DIR/bin/" ;;
        esac
    done
    "${CROSS}strip" "$OUT_DIR"/bin/* 2>/dev/null || true
    # runtime configuration: lib/ = Chameleon skin; appdb/ = "internal storage"
    # (AMS icons/splash .raw images, CA keystore _main.ks) that later also
    # holds the installed suites
    cp -r "$MIDP_OUTPUT_DIR/lib/." "$OUT_DIR/lib/"
    cp -r "$MIDP_OUTPUT_DIR/appdb/." "$OUT_DIR/appdb/"
    # device side: launcher, keymap, shell helper scripts, keypad probe,
    # group-su helper
    cp "$OS_DIR/device/j2me.sh" "$OS_DIR/device/keymap.txt" \
       "$OS_DIR/device/s100_net.sh" "$OS_DIR/device/s100_cam.sh" \
       "$OS_DIR/device/s100_media.sh" "$OS_DIR/device/s100_loc.sh" "$OUT_DIR/"
    sed -i 's/\r$//' "$OUT_DIR"/*.sh
    "${CROSS}gcc" -static -O2 -o "$OUT_DIR/bin/keyprobe" "$OS_DIR/device/keyprobe.c"
    "${CROSS}gcc" -static -O2 -o "$OUT_DIR/bin/gsu" "$OS_DIR/device/gsu.c"
    # rild-debug / VPN daemon socket client (Settings > Network)
    "${CROSS}gcc" -static -O2 -o "$OUT_DIR/bin/sockctl" "$OS_DIR/device/sockctl.c"
    "${CROSS}strip" "$OUT_DIR/bin/keyprobe" "$OUT_DIR/bin/gsu" "$OUT_DIR/bin/sockctl"
    chmod 755 "$OUT_DIR"/bin/* "$OUT_DIR"/*.sh
    # sanity: everything that runs on the phone must be static
    for f in "$OUT_DIR"/bin/*; do
        file "$f" | grep -q "statically linked" || die "$f is not statically linked"
    done
    du -sh "$OUT_DIR" | sed 's/^/    /'
    ls -la "$OUT_DIR/bin" | sed 's/^/    /'
    ok "package ready: $OUT_DIR (deploy with scripts/deploy.sh)"
}

stages=("$@")
[[ ${#stages[@]} -eq 0 ]] && stages=(all)
for stage in "${stages[@]}"; do
    case "$stage" in
        sync)    sync_sources ;;
        pcsl)    sync_sources; build_pcsl ;;
        cldc)    sync_sources; build_cldc ;;
        midp)    sync_sources; build_midp ;;
        package) package ;;
        all)     sync_sources; build_pcsl; build_cldc; build_midp; package ;;
        clean)   rm -rf "$BUILD_DIR"; ok "removed $BUILD_DIR" ;;
        *)       die "unknown stage '$stage' (sync|pcsl|cldc|midp|package|all|clean)" ;;
    esac
done
