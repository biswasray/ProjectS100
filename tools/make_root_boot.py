#!/usr/bin/env python3
"""Build a rooted boot image for the JioPhone LF-2403N.

Takes the device's own boot image and produces one that:
  * default.prop: ro.secure=0, ro.adb.secure=0, ro.debuggable=1 (+adb in usb.config)
  * kernel cmdline: androidboot.selinux=permissive
  * ramdisk /adb_keys   : this host's ADB public key (authorises adb, no prompt)
  * ramdisk /sbin/s60su : setuid-root shell launcher (mode 06755) -> real root

Usage:
  python make_root_boot.py <in_boot.img> <out_boot.img> <adbkey.pub> <s60su_arm_binary>
"""
import sys
import patch_boot as pb

if len(sys.argv) != 5:
    sys.exit(__doc__)
src, dst, keypath, supath = sys.argv[1:5]

img = pb.BootImage(open(src, "rb").read())

# cmdline -> permissive
cl = img.cmdline.rstrip(b"\0")
if b"androidboot.selinux=enforcing" in cl:
    cl = cl.replace(b"androidboot.selinux=enforcing", b"androidboot.selinux=permissive")
elif b"androidboot.selinux=permissive" not in cl:
    cl = cl + b" androidboot.selinux=permissive"
img.cmdline = cl.ljust(512, b"\0")

kind, cpio = pb.ramdisk_open(img.ramdisk)
entries = pb.cpio_parse(cpio)

# default.prop
idx = next(i for i, e in enumerate(entries) if e[1] == b"default.prop")
entries[idx][2] = pb.patch_props(entries[idx][2].decode()).encode()


def put(name, data, mode):
    fields = [0, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(name) + 1, 0]
    i = next((j for j, e in enumerate(entries) if e[1] == name), None)
    if i is None:
        entries.append([fields, name, data])
        print("added %s (%d bytes, mode %o)" % (name.decode(), len(data), mode))
    else:
        entries[i] = [fields, name, data]
        print("replaced %s (%d bytes, mode %o)" % (name.decode(), len(data), mode))


put(b"adb_keys", open(keypath, "rb").read(), 0o100644)
# Place at ramdisk root (/ is 0755, shell-traversable); /sbin is 0750 root-only.
put(b"s60su", open(supath, "rb").read(), 0o104755)  # setuid root, rwxr-xr-x

img.ramdisk = pb.ramdisk_close(kind, pb.cpio_build(entries))
out = img.build()
open(dst, "wb").write(out)
print("cmdline -> %r" % cl.decode(errors="replace"))
print("wrote %s: %d bytes" % (dst, len(out)))
