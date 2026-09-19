#!/usr/bin/env python3
"""Directly probe the Qualcomm 9008 EDL device and surface the real USB errors."""
import sys
import usb.core
import usb.util

VID, PID = 0x05c6, 0x9008

dev = usb.core.find(idVendor=VID, idProduct=PID)
if dev is None:
    print("9008 device NOT found by pyusb")
    sys.exit(2)

print("Found 9008. bus=%s addr=%s" % (dev.bus, dev.address))
try:
    print("manufacturer/product:", usb.util.get_string(dev, dev.iManufacturer) if dev.iManufacturer else "-",
          "/", usb.util.get_string(dev, dev.iProduct) if dev.iProduct else "-")
except Exception as e:
    print("string desc err:", e)

try:
    cfg = dev.get_active_configuration()
    print("active config OK, bNumInterfaces =", cfg.bNumInterfaces)
except Exception as e:
    print("get_active_configuration FAILED:", repr(e))
    try:
        dev.set_configuration()
        cfg = dev.get_active_configuration()
        print("after set_configuration, bNumInterfaces =", cfg.bNumInterfaces)
    except Exception as e2:
        print("set_configuration FAILED:", repr(e2))
        sys.exit(3)

intf = cfg[(0, 0)]
print("interface class=0x%02x subclass=0x%02x, endpoints:" % (intf.bInterfaceClass, intf.bInterfaceSubClass))
ep_in = ep_out = None
for ep in intf:
    d = "IN" if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN else "OUT"
    print("  ep 0x%02x %s type=%d maxpkt=%d" % (ep.bEndpointAddress, d, usb.util.endpoint_type(ep.bmAttributes), ep.wMaxPacketSize))
    if d == "IN" and ep_in is None:
        ep_in = ep
    if d == "OUT" and ep_out is None:
        ep_out = ep

print("ep_in=%s ep_out=%s" % (ep_in and hex(ep_in.bEndpointAddress), ep_out and hex(ep_out.bEndpointAddress)))

# Sahara sends a HELLO on connect; try to read it.
print("--- reading 48 bytes (expect Sahara HELLO 0x01 ...) ---")
try:
    data = dev.read(ep_in.bEndpointAddress, 48, timeout=3000)
    print("READ OK, %d bytes:" % len(data), bytes(data).hex())
except Exception as e:
    print("READ FAILED:", repr(e))

print("--- writing an XML nop then reading (firehose probe) ---")
try:
    dev.write(ep_out.bEndpointAddress, b'<?xml version="1.0" ?><data><nop /></data>', timeout=3000)
    data = dev.read(ep_in.bEndpointAddress, 512, timeout=3000)
    print("PROBE READ OK, %d bytes:" % len(data), bytes(data)[:120])
except Exception as e:
    print("PROBE FAILED:", repr(e))
