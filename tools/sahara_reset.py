#!/usr/bin/env python3
"""Resync a stuck Sahara device: send RESET_REQ, then read the fresh HELLO."""
import struct
import sys
import usb.core

VID, PID = 0x05c6, 0x9008
EP_IN, EP_OUT = 0x81, 0x01
names = {1: "HELLO_REQ", 2: "HELLO_RESP", 3: "READ_DATA", 4: "END_IMAGE_TX",
         5: "DONE_REQ", 6: "DONE_RESP", 7: "RESET_REQ", 8: "RESET_RESP",
         0x0b: "CMD_READY", 0x0c: "CMD_SWITCH_MODE", 0x0d: "CMD_EXEC",
         0x11: "MEMORY_DEBUG64", 0x12: "READ_DATA64"}

dev = usb.core.find(idVendor=VID, idProduct=PID)
if dev is None:
    sys.exit("9008 not found")
try:
    dev.get_active_configuration()
except Exception:
    dev.set_configuration()


def rd(n=512, t=3000):
    try:
        return bytes(dev.read(EP_IN, n, timeout=t))
    except Exception:
        return b""


def wr(b, t=3000):
    try:
        dev.write(EP_OUT, b, timeout=t)
        return True
    except Exception as e:
        print("write err:", e)
        return False


def show(tag, b):
    if not b:
        print(f"{tag}: <empty>")
        return None
    cmd, ln = struct.unpack_from("<II", b, 0) if len(b) >= 8 else (b[0], 0)
    extra = ""
    if cmd == 1 and len(b) >= 0x30:
        # HELLO: cmd,len,version,version_min,max_cmd_len,mode,...
        _, _, ver, vmin, maxlen, mode = struct.unpack_from("<6I", b, 0)
        extra = f"  version={ver} version_min={vmin} max_cmd_len={maxlen} mode={mode}"
    print(f"{tag}: cmd=0x{cmd:x} ({names.get(cmd,'?')}) len={ln} raw={b[:48].hex()}{extra}")
    return cmd


print("--- draining ---")
for _ in range(2):
    show("drain", rd(512, 300))

print("\n--- sending RESET_REQ (cmd=7, len=8) ---")
wr(struct.pack("<II", 7, 8))
show("reply1", rd(512, 3000))     # expect RESET_RESP (cmd=8)
show("reply2", rd(512, 3000))     # sometimes empty

print("\n--- now polling for a fresh HELLO (device should re-emit) ---")
for i in range(6):
    r = rd(512, 2000)
    c = show(f"poll{i}", r)
    if c == 1:
        print(">>> Got HELLO. We are resynced; note the version above.")
        break
