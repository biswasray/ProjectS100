#!/usr/bin/env bash
# phonekey.sh - press keypad keys on the phone over adb (for driving the
# S100 shell from the PC while it runs on the LCD).
#
#   bash os/scripts/phonekey.sh ok 7            press centre, then '7'
#   bash os/scripts/phonekey.sh sleep=3 ok      wait 3 s, then press centre
#
# Key names are those of sendkey.py (0-9 * # up down left right ok soft1
# soft2 send end clear); the codes are the built-in ones of
# device/keymap.txt (the phone's matrix_keypad.kl). Uses sendevent on
# /dev/input/event0 through /s60su + gsu.
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
DEV=${PHONE_KEYPAD_DEV:-/dev/input/event0}
GSU=/data/j2me/bin/gsu

code() {
    case "$1" in
        0) echo 11 ;; 1) echo 2 ;; 2) echo 3 ;; 3) echo 4 ;; 4) echo 5 ;; 5) echo 6 ;;
        6) echo 7 ;; 7) echo 8 ;; 8) echo 9 ;; 9) echo 10 ;;
        '*'|star) echo 522 ;; '#'|pound) echo 523 ;;
        up) echo 103 ;; down) echo 108 ;; left) echo 105 ;; right) echo 106 ;;
        ok|select) echo 352 ;; soft1) echo 139 ;; soft2) echo 158 ;;
        send) echo 231 ;; end) echo 116 ;; clear) echo 14 ;; home) echo 102 ;;
        *) echo "unknown key $1" >&2; exit 2 ;;
    esac
}

cmd=""
for k in "$@"; do
    case "$k" in
        sleep=*) cmd="$cmd sleep ${k#sleep=};" ;;
        *:down)  c=$(code "${k%:down}"); cmd="$cmd sendevent $DEV 1 $c 1; sendevent $DEV 0 0 0;" ;;
        *:up)    c=$(code "${k%:up}");   cmd="$cmd sendevent $DEV 1 $c 0; sendevent $DEV 0 0 0;" ;;
        *)       c=$(code "$k")
                 cmd="$cmd sendevent $DEV 1 $c 1; sendevent $DEV 0 0 0; sleep 0.08; sendevent $DEV 1 $c 0; sendevent $DEV 0 0 0; sleep 0.35;" ;;
    esac
done
adb shell "/s60su -c '$GSU -c \"$cmd\"'"
