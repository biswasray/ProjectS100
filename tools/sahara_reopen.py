#!/usr/bin/env python3
"""Send Sahara RESET_REQ, then repeatedly RE-OPEN the device and read,
to catch a fresh HELLO that a re-enumeration would deliver to a new handle."""
import struct
import time
import sys
import usb.core
import usb.util

VID, PID = 0x05c6, 0x9008
EP_IN, EP_OUT = 0x81, 0x01
names = {1: "HELLO_REQ", 4: "END_IMAGE_TX", 8: "RESET_RESP"}


def find():
    d = usb.core.find(idVendor=VID, idProduct=PID)
    if d is None:
        return None
    try:
        d.get_active_configuration()
    except Exception:
        try:
            d.set_configuration()
        except Exception:
            return None
    return d


def show(tag, b):
    if not b:
        print(f"{tag}: <empty>"); return None
    cmd = struct.unpack_from("<I", b, 0)[0]
    extra = ""
    if cmd == 1 and len(b) >= 0x30:
        _, _, ver, vmin, maxlen, mode = struct.unpack_from("<6I", b, 0)
        extra = f"  version={ver} version_min={vmin} max_cmd_len={maxlen} mode={mode}"
    print(f"{tag}: cmd=0x{cmd:x} ({names.get(cmd,'?')}) raw={b[:48].hex()}{extra}")
    return cmd


d = find()
if d is None:
    sys.exit("9008 not found")

print("--- sending RESET_REQ ---")
try:
    d.write(EP_OUT, struct.pack("<II", 7, 8), timeout=2000)
    r = bytes(d.read(EP_IN, 512, timeout=2000))
    show("reset reply", r)
except Exception as e:
    print("reset err:", e)

usb.util.dispose_resources(d)
del d

print("--- reopening and reading for a fresh HELLO (up to ~15s) ---")
deadline = time.time() + 15
attempt = 0
while time.time() < deadline:
    attempt += 1
    d = find()
    if d is None:
        time.sleep(0.3)
        continue
    try:
        r = bytes(d.read(EP_IN, 512, timeout=800))
    except Exception:
        r = b""
    if r:
        c = show(f"attempt{attempt}", r)
        if c == 1:
            print(">>> HELLO captured after reopen! Note the version/mode above.")
            break
    usb.util.dispose_resources(d)
    del d
    time.sleep(0.2)
else:
    print("no HELLO captured after reopen loop")

# Also try a USB-level reset on a fresh handle
print("\n--- trying libusb device.reset() then read ---")
d = find()
if d is not None:
    try:
        d.reset()
        time.sleep(0.5)
    except Exception as e:
        print("usb reset err:", e)
    d2 = find()
    if d2 is not None:
        try:
            r = bytes(d2.read(EP_IN, 512, timeout=2000))
            show("post-usbreset", r)
        except Exception as e:
            print("read err:", e)
