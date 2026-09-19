#!/usr/bin/env python3
"""sendkey.py - inject keypad events into the emulated runtime (or a real
evdev node) as 32-bit struct input_event records.

    sendkey.py <fifo-or-eventN> KEY [KEY ...]     press+release each key
    sendkey.py <fifo> 28                          raw Linux keycode

KEY names: 0-9 * # up down left right ok soft1 soft2 send end clear
(these are the built-in codes of device/keymap.txt). A key may be suffixed
with :down or :up to send only one half, e.g. "ok:down".
"""
import struct
import sys
import time

CODES = {
    '0': 11, '1': 2, '2': 3, '3': 4, '4': 5, '5': 6, '6': 7, '7': 8, '8': 9, '9': 10,
    '*': 522, '#': 523, 'star': 522, 'pound': 523,
    'up': 103, 'down': 108, 'left': 105, 'right': 106,
    'ok': 28, 'select': 28, 'soft1': 139, 'soft2': 158,
    'send': 231, 'end': 116, 'clear': 14, 'home': 102,
}
EV_SYN, EV_KEY = 0, 1


def event(typ, code, value):
    now = time.time()
    # struct input_event for 32-bit ARM: timeval{long,long} + u16 + u16 + s32
    return struct.pack('<llHHi', int(now), int((now % 1) * 1e6), typ, code, value)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    dev = sys.argv[1]
    with open(dev, 'wb', buffering=0) as f:
        for arg in sys.argv[2:]:
            name, _, half = arg.partition(':')
            code = CODES.get(name.lower())
            if code is None:
                code = int(name)
            values = {'down': (1,), 'up': (0,)}.get(half, (1, 0))
            for v in values:
                f.write(event(EV_KEY, code, v) + event(EV_SYN, 0, 0))
                time.sleep(0.08)


if __name__ == '__main__':
    main()
