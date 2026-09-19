#!/usr/bin/env python3
"""Catch the Qualcomm Sahara HELLO the instant we can read it.
Reads only (never writes), so it can't desync the protocol. Run this right
after a FRESH EDL entry, ideally as the first tool to touch the device."""
import struct
import time
import sys
import usb.core
import usb.util

VID, PID = 0x05c6, 0x9008
EP_IN = 0x81

# With the WinUSB driver, pyusb needs the libusb-1.0 backend. libusb-package
# ships a bundled libusb-1.0.dll; use it explicitly.
backend = None
try:
    import libusb_package
    import usb.backend.libusb1
    backend = usb.backend.libusb1.get_backend(find_library=libusb_package.find_library)
    print("using libusb-package backend:", bool(backend))
except Exception as e:
    print("libusb-package backend unavailable:", e)

dev = usb.core.find(idVendor=VID, idProduct=PID, backend=backend)
if dev is None:
    sys.exit("9008 not found (is it in EDL, black screen?)")

# Try to get endpoints WITHOUT forcing a re-configure (a set_configuration can
# reset the device and flush the buffered HELLO).
try:
    cfg = dev.get_active_configuration()
except Exception as e:
    print("no active config, setting one:", e)
    dev.set_configuration()
    cfg = dev.get_active_configuration()

print("driver path in use:", usb.util.get_string(dev, dev.iProduct) if dev.iProduct else "?")
print("reading EP 0x81 for up to 20s (no writes)...")

deadline = time.time() + 20
got = None
reads = 0
while time.time() < deadline:
    reads += 1
    try:
        data = bytes(dev.read(EP_IN, 48, timeout=1500))
    except usb.core.USBError as e:
        if "timed out" in str(e) or "timeout" in str(e):
            continue
        # other errors: report once, keep trying briefly
        print("read error:", e)
        continue
    if data:
        got = data
        break

print(f"total read attempts: {reads}")
if not got:
    print(">>> No HELLO seen. WinUSB did not preserve it either.")
    sys.exit(3)

cmd = struct.unpack_from("<I", got, 0)[0]
print("GOT %d bytes: %s" % (len(got), got.hex()))
if cmd == 1 and len(got) >= 0x30:
    _, ln, ver, vmin, maxlen, mode = struct.unpack_from("<6I", got, 0)
    print(f">>> SAHARA HELLO! version={ver} version_min={vmin} max_cmd_len={maxlen} mode={mode}")
    print(">>> WinUSB preserves the HELLO. edl will work natively on Windows now.")
else:
    print(f">>> Got cmd=0x{cmd:x} (not a HELLO). Still informative.")
