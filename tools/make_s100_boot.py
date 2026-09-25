#!/usr/bin/env python3
"""Build an S100 boot image: KaiOS removed, the Java OS starts at boot.

Takes a *rooted* boot (make_root_boot.py output, or the one read back from
the phone) and changes only the ramdisk's init.b2g.rc:
  * drops the KaiOS services: b2g (Gecko + UI), api-daemon, metrics-daemon,
    rilproxy (Gecko's RIL bridge). Nothing from /system/b2g or /system/kaios
    is ever started again.
  * adds service "s100" = /data/j2me/j2me.sh boot, started with class main
    (after /data is mounted) as root with full capabilities and the hardware
    groups, so it no longer needs gsu for fb0/input/sysfs.

adbd (class core), rild, wpa_supplicant, media, audio etc. are untouched.
The rooted bits (adb_keys, /s60su, permissive SELinux) are kept, so adb
stays the repair path. Restore = flash the input image back.

Usage:
  python make_s100_boot.py <rooted_boot.img> <out_boot.img>
"""
import re
import sys
import patch_boot as pb

KAIOS_SERVICES = ("b2g", "api-daemon", "metrics-daemon", "rilproxy")

S100_SERVICE = """
# S100: the Java OS replaces KaiOS (tools/make_s100_boot.py)
service s100 /system/bin/sh /data/j2me/j2me.sh boot
    class main
    user root
    group root system graphics input audio camera radio inet net_admin net_raw wifi bluetooth net_bt net_bt_admin sdcard_rw sdcard_r media_rw log shell
    seclabel u:r:shell:s0
"""


def drop_service(text, name):
    """Removes the 'service <name> ...' section (header + indented lines)."""
    out, skipping, found = [], False, False
    for line in text.splitlines(keepends=True):
        if re.match(r"service\s+%s\s" % re.escape(name), line):
            skipping = found = True
            continue
        if skipping and (line[:1] in (" ", "\t") or not line.strip()):
            continue
        skipping = False
        out.append(line)
    if not found:
        print("  (service %s not present)" % name)
    else:
        print("removed service %s" % name)
    return "".join(out)


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src, dst = sys.argv[1:3]
    img = pb.BootImage(open(src, "rb").read())
    kind, cpio = pb.ramdisk_open(img.ramdisk)
    entries = pb.cpio_parse(cpio)
    names = [e[1] for e in entries]
    if b"s60su" not in names or b"adb_keys" not in names:
        sys.exit("%s is not a rooted boot (no /s60su or /adb_keys): build it "
                 "with make_root_boot.py first, adb is the only repair path" % src)

    i = names.index(b"init.b2g.rc")
    rc = entries[i][2].decode()
    for name in KAIOS_SERVICES + ("s100",):
        rc = drop_service(rc, name)
    rc = re.sub(r"\n# S100: the Java OS replaces KaiOS.*\n", "\n", rc)
    rc = rc.rstrip("\n") + "\n" + S100_SERVICE
    entries[i][2] = rc.encode()
    print("added service s100 -> /data/j2me/j2me.sh boot")

    img.ramdisk = pb.ramdisk_close(kind, pb.cpio_build(entries))
    out = img.build()
    open(dst, "wb").write(out)
    print("wrote %s: %d bytes" % (dst, len(out)))


if __name__ == "__main__":
    main()
