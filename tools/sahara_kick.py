#!/usr/bin/env python3
"""Send a Sahara HELLO_RESP to a 9008 device that isn't advertising a HELLO,
and print what it asks for next. Purely diagnostic — sends no image data."""
import struct
import sys
import usb.core
import usb.util

VID, PID = 0x05c6, 0x9008
EP_IN, EP_OUT = 0x81, 0x01

# Sahara command ids
HELLO_REQ, HELLO_RESP, READ_DATA, END_IMAGE_TX = 1, 2, 3, 4
DONE_REQ, DONE_RESP, RESET_REQ, RESET_RESP = 5, 6, 7, 8
CMD_READY, CMD_SWITCH_MODE, CMD_EXEC = 0x0b, 0x0c, 0x0d
READ_DATA64 = 0x12

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


def rd(n=512, t=2000):
    try:
        return bytes(dev.read(EP_IN, n, timeout=t))
    except Exception as e:
        return b""


def wr(b, t=2000):
    return dev.write(EP_OUT, b, timeout=t)


def show(tag, b):
    if not b:
        print(f"{tag}: <empty>")
        return None
    cmd, ln = struct.unpack_from("<II", b, 0) if len(b) >= 8 else (b[0], len(b))
    print(f"{tag}: cmd=0x{cmd:x} ({names.get(cmd,'?')}) len={ln} raw={b[:32].hex()}")
    return cmd


# 1) Drain anything pending
print("draining...")
for _ in range(3):
    show("drain", rd(512, 400))

# 2) Send HELLO_RESP: cmd=2 len=0x30 version=2 ver_supported=1 status=0 mode=0 + 6 reserved
for mode in (0, 1, 2):  # IMAGE_TX_PENDING, IMAGE_TX_COMPLETE, MEMORY_DEBUG
    pkt = struct.pack("<12I", HELLO_RESP, 0x30, 2, 1, 0, mode, 0, 0, 0, 0, 0, 0)
    print(f"\n--- sending HELLO_RESP mode={mode} ---")
    try:
        wr(pkt)
    except Exception as e:
        print("write err:", e)
        continue
    resp = rd(512, 2000)
    cmd = show("reply", resp)
    if cmd in (READ_DATA, READ_DATA64, CMD_READY):
        print(">>> Device is driving Sahara! It wants:", names.get(cmd))
        if cmd in (READ_DATA, READ_DATA64):
            if cmd == READ_DATA:
                _, _, img, off, dlen = struct.unpack_from("<IIIII", resp, 0)
            else:
                _, _, img, off, dlen = struct.unpack_from("<IIQQQ", resp, 0)[:5]
            print(f"    image_id={img} offset={off} length={dlen}  (this is the loader request)")
        break
