#!/usr/bin/env bash
# setup_wsl.sh - install everything needed to cross-build phoneME for the
# JioPhone inside WSL (Ubuntu 24.04). Idempotent; run as root:
#   wsl -d Ubuntu-24.04 -u root -- bash os/scripts/setup_wsl.sh
#
# What and why:
#   gcc-N-multilib               the CLDC build runs its ROM/loop *generators*
#                                on the host as 32-bit i386 binaries
#   gcc-arm-linux-gnueabi        cross compiler for the phone (ARMv7, soft-float
#                                ABI + VFP; static glibc binaries run fine on
#                                the Android/Gonk kernel, as tools/s60su proves)
#   openjdk-8-jdk                last JDK whose javac still emits -target 1.1..1.4
#                                class files that the CLDC preverifier accepts
#   qemu-user-static             run the cross-built ARM binaries on the PC
#   make, zip, unzip, bison, flex, patch, git, python3  build plumbing
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
    echo "run as root: wsl -d Ubuntu-24.04 -u root -- bash $0" >&2
    exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
inst() { apt-get install -y -qq --no-install-recommends "$@"; }
inst build-essential make zip unzip bison flex patch git python3 ca-certificates
inst gcc-arm-linux-gnueabi g++-arm-linux-gnueabi binutils-arm-linux-gnueabi
inst openjdk-8-jdk-headless
inst qemu-user-static   # run the ARM binaries on the PC for smoke tests

# 32-bit host support. The `gcc-multilib` meta package Conflicts with every
# cross compiler (they fight over the /usr/include/asm symlink), so install the
# versioned multilib packages instead and create that symlink ourselves.
gccmaj="$(gcc -dumpversion | cut -d. -f1)"
inst "gcc-${gccmaj}-multilib" "g++-${gccmaj}-multilib"
if [[ ! -e /usr/include/asm ]]; then
    ln -s x86_64-linux-gnu/asm /usr/include/asm
    echo "[+] linked /usr/include/asm -> x86_64-linux-gnu/asm (for -m32 host tools)"
fi

echo
echo "[+] host gcc:    $(gcc --version | head -1)"
echo "[+] arm gcc:     $(arm-linux-gnueabi-gcc --version | head -1)"
echo "[+] javac:       $(/usr/lib/jvm/java-8-openjdk-amd64/bin/javac -version 2>&1)"
echo "[+] arm g++:     $(arm-linux-gnueabi-g++ --version | head -1)"
echo "[+] 32-bit host: $(echo 'int main(){return 0;}' | gcc -m32 -x c - -o /tmp/m32test && echo ok)"
