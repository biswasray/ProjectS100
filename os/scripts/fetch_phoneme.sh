#!/usr/bin/env bash
# fetch_phoneme.sh - get the phoneME sources and apply this project's patches.
#
#   bash os/scripts/fetch_phoneme.sh          clone (shallow) + patch
#   bash os/scripts/fetch_phoneme.sh --diff   regenerate os/patches/*.patch from
#                                             the working tree in os/phoneME
#
# Sources: the git conversion of Sun's phoneME SVN archive,
#   https://github.com/magicus/phoneME  (GPLv2 + Classpath exception)
# cloned into os/phoneME (gitignored, like tools/edl). The JioPhone port and
# the modern-toolchain fixes live in os/patches/ and are applied on top, so
# the checkout is disposable.
set -euo pipefail

OS_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${PHONEME_SRC:-$OS_DIR/phoneME}"
PATCHES="$OS_DIR/patches"
REPO="${PHONEME_REPO:-https://github.com/magicus/phoneME.git}"

log()  { printf '\033[36m[*] %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m[+] %s\033[0m\n' "$*"; }
die()  { printf '\033[31m[x] %s\033[0m\n' "$*" >&2; exit 1; }

if [[ "${1:-}" == "--diff" ]]; then
    [[ -d "$SRC/.git" ]] || die "$SRC is not a git checkout"
    cd "$SRC"
    git add -A -N .                       # include new files in the diff
    mkdir -p "$PATCHES"
    git diff --no-color --binary -- cldc preverifier > "$PATCHES/0001-toolchain-modern-gcc-and-x86_64-host.patch"
    git diff --no-color --binary -- midp            > "$PATCHES/0002-midp-jiophone-linux-fb-port.patch"
    for p in "$PATCHES"/*.patch; do
        [[ -s "$p" ]] || rm -f "$p"
    done
    git reset -q                          # undo the intent-to-add
    wc -l "$PATCHES"/*.patch
    ok "patches regenerated"
    exit 0
fi

if [[ ! -d "$SRC/.git" ]]; then
    log "cloning $REPO (shallow) -> $SRC"
    git clone --depth 1 "$REPO" "$SRC"
else
    ok "sources present at $SRC"
fi

cd "$SRC"
git config core.autocrlf false
if [[ -n "$(git status --porcelain)" ]]; then
    ok "working tree already modified; not re-applying patches (use 'git checkout . && git clean -fd' first to redo)"
    exit 0
fi
for p in "$PATCHES"/*.patch; do
    [[ -f "$p" ]] || continue
    log "applying $(basename "$p")"
    git apply --whitespace=nowarn "$p"
done
ok "patched. Next: wsl -d Ubuntu-24.04 -- bash os/scripts/build.sh"
