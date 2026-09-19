#!/usr/bin/env bash
# mk_shim.sh - create an unprefixed GNU toolchain directory for phoneME.
# phoneME's makefiles expect $(GNU_TOOLS_DIR)/bin/{gcc,g++,as,ar,ld,strip}
# (an old-style "arm-linux" sysroot layout); Ubuntu ships the cross tools as
# arm-linux-gnueabi-*. This creates os/toolchain/bin/ with symlinks.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
prefix="${CROSS_PREFIX:-arm-linux-gnueabi-}"
mkdir -p "$here/bin"
for t in gcc g++ cpp as ar ld nm objcopy objdump ranlib strip; do
    src="$(command -v "${prefix}${t}" || true)"
    [[ -n "$src" ]] || { echo "missing ${prefix}${t}" >&2; exit 1; }
    ln -sfn "$src" "$here/bin/$t"
done
echo "[+] toolchain shim at $here/bin -> ${prefix}*"
