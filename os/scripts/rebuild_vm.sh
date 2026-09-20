#!/usr/bin/env bash
# rebuild_vm.sh - incremental rebuild after a change in cldc/ (VM) sources:
# resync, remake the product VM, force MIDP to relink runMidlet, repackage.
#
#   wsl -d Ubuntu-24.04 -- bash /mnt/c/.../os/scripts/rebuild_vm.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
build="$here/build.sh"

bash "$build" sync
FORCE=1 bash "$build" cldc
# MIDP's link rule does not depend on libcldc_vm.a; drop the binary so it relinks
rm -f "$HOME/.cache/s100/build/midp/bin/arm/runMidlet"
bash "$build" midp package
