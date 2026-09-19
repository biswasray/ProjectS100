#!/usr/bin/env python3
"""
patch_boot.py - enable insecure root ADB on a KaiOS / Qualcomm MSM8905 boot image.

Handles the legacy Qualcomm Android boot header (v0) *with* the appended
device-tree blob (dt_size at offset 40) that generic tools drop.

Usage:
  python3 patch_boot.py dump  boot.img                 # show header + default.prop, no changes
  python3 patch_boot.py patch boot.img boot_patched.img
"""
import gzip
import hashlib
import io
import struct
import sys

BOOT_MAGIC = b"ANDROID!"


def align(n, p):
    return (n + p - 1) // p * p


# Properties to force in the ramdisk's default.prop
PATCH_PROPS = {
    "ro.secure": "0",
    "ro.adb.secure": "0",
    "ro.debuggable": "1",
    "ro.allow.mock.location": "1",
}


# ----------------------------------------------------------------- boot.img --
class BootImage:
    HDR = "<8s10I16s512s32s1024s"  # magic, 10 u32, name, cmdline, id, extra_cmdline

    def __init__(self, data):
        (magic, ks, ka, rs, ra, ss, sa, ta, ps, dts, osv,
         self.name, self.cmdline, self.id, self.extra_cmdline) = struct.unpack_from(self.HDR, data, 0)
        if magic != BOOT_MAGIC:
            sys.exit("not an Android boot image (bad magic)")
        self.kernel_addr, self.ramdisk_addr = ka, ra
        self.second_addr, self.tags_addr = sa, ta
        self.page_size, self.dt_size, self.os_version = ps, dts, osv

        off = ps
        self.kernel = data[off:off + ks]
        off += align(ks, ps)
        self.ramdisk = data[off:off + rs]
        off += align(rs, ps)
        self.second = data[off:off + ss]
        off += align(ss, ps)
        self.dt = data[off:off + dts]

    def describe(self):
        name = self.name.rstrip(b"\0").decode(errors="replace")
        cmdline = self.cmdline.rstrip(b"\0").decode(errors="replace")
        print("page_size     : %d" % self.page_size)
        print("kernel        : %9d bytes @ 0x%08x" % (len(self.kernel), self.kernel_addr))
        print("ramdisk       : %9d bytes @ 0x%08x" % (len(self.ramdisk), self.ramdisk_addr))
        print("second        : %9d bytes @ 0x%08x" % (len(self.second), self.second_addr))
        print("device tree   : %9d bytes  (dt_size field)" % len(self.dt))
        print("tags_addr     : 0x%08x" % self.tags_addr)
        print("name          : %r" % name)
        print("cmdline       : %r" % cmdline)

    def build(self):
        ps = self.page_size
        dt = self.dt if self.dt_size else b""
        h = hashlib.sha1()
        for blob in (self.kernel, self.ramdisk, self.second):
            h.update(blob)
            h.update(struct.pack("<I", len(blob)))
        if self.dt_size:
            h.update(dt)
            h.update(struct.pack("<I", len(dt)))
        img_id = h.digest().ljust(32, b"\0")

        hdr = struct.pack(self.HDR, BOOT_MAGIC,
                          len(self.kernel), self.kernel_addr,
                          len(self.ramdisk), self.ramdisk_addr,
                          len(self.second), self.second_addr,
                          self.tags_addr, ps, len(dt),
                          self.os_version, self.name, self.cmdline, img_id, self.extra_cmdline)
        out = io.BytesIO()
        for blob in (hdr, self.kernel, self.ramdisk, self.second, dt):
            out.write(blob)
            out.write(b"\0" * (align(len(blob), ps) - len(blob)))
        return out.getvalue()


# --------------------------------------------------------------------- cpio --
def cpio_parse(data):
    """Parse a newc cpio archive into a list of [header_fields, name, body]."""
    entries, off = [], 0
    while off < len(data):
        if data[off:off + 6] != b"070701":
            sys.exit("unsupported cpio format at 0x%x: %r" % (off, data[off:off + 6]))
        fields = [int(data[off + 6 + i * 8: off + 14 + i * 8], 16) for i in range(13)]
        namesize, filesize = fields[11], fields[6]
        name_start = off + 110
        name = data[name_start:name_start + namesize - 1]
        body_start = align(name_start + namesize, 4)
        body = data[body_start:body_start + filesize]
        off = align(body_start + filesize, 4)
        if name == b"TRAILER!!!":
            break
        entries.append([fields, name, body])
    return entries


def cpio_build(entries):
    out = io.BytesIO()

    def emit(fields, name, body):
        fields = list(fields)
        fields[6] = len(body)
        fields[11] = len(name) + 1
        hdr = b"070701" + b"".join(("%08x" % f).encode() for f in fields)
        start = out.tell()
        out.write(hdr + name + b"\0")
        used = out.tell() - start
        out.write(b"\0" * (align(used, 4) - used))
        out.write(body)
        out.write(b"\0" * (align(len(body), 4) - len(body)))

    for fields, name, body in entries:
        emit(fields, name, body)
    emit([0, 0, 0o100644, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0], b"TRAILER!!!", b"")
    return out.getvalue()


# --------------------------------------------------------------- ramdisk ----
def ramdisk_open(blob):
    if blob[:2] == b"\x1f\x8b":
        return "gzip", gzip.decompress(blob)
    if blob[:4] == b"\x04\x22\x4d\x18":
        sys.exit("ramdisk is LZ4 - tell me and I'll add lz4 support")
    if blob[:6] == b"070701":
        return "raw", blob
    sys.exit("unknown ramdisk compression: %s" % blob[:4].hex())


def ramdisk_close(kind, cpio):
    if kind == "gzip":
        return gzip.compress(cpio, compresslevel=9, mtime=0)
    return cpio


def patch_props(text):
    lines = text.splitlines()
    seen = set()
    for i, line in enumerate(lines):
        key = line.split("=", 1)[0].strip()
        if key in PATCH_PROPS:
            lines[i] = "%s=%s" % (key, PATCH_PROPS[key])
            seen.add(key)
        elif key == "persist.sys.usb.config":
            modes = [m for m in line.split("=", 1)[1].split(",") if m]
            if "adb" not in modes:
                modes.append("adb")
            lines[i] = "persist.sys.usb.config=" + ",".join(modes)
    for key, val in PATCH_PROPS.items():
        if key not in seen:
            lines.append("%s=%s" % (key, val))
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------- main --
def main():
    if len(sys.argv) < 3 or sys.argv[1] not in ("dump", "patch"):
        sys.exit(__doc__)
    mode, src = sys.argv[1], sys.argv[2]
    raw = open(src, "rb").read()
    img = BootImage(raw)
    img.describe()

    kind, cpio = ramdisk_open(img.ramdisk)
    entries = cpio_parse(cpio)
    idx = next((i for i, e in enumerate(entries) if e[1] == b"default.prop"), None)
    if idx is None:
        sys.exit("default.prop not found in ramdisk")
    fields, name, body = entries[idx]
    print("\nramdisk       : %s, %d entries" % (kind, len(entries)))
    print("\n--- default.prop (current) ---")
    print(body.decode(errors="replace").rstrip())

    if mode == "dump":
        return

    dst = sys.argv[3] if len(sys.argv) > 3 else src.rsplit(".", 1)[0] + "_patched.img"

    # Kernel cmdline: force SELinux permissive so a root adbd isn't confined.
    cl = img.cmdline.rstrip(b"\0")
    if b"androidboot.selinux=enforcing" in cl:
        cl = cl.replace(b"androidboot.selinux=enforcing", b"androidboot.selinux=permissive")
    elif b"androidboot.selinux=permissive" not in cl:
        cl = cl + b" androidboot.selinux=permissive"
    img.cmdline = cl.ljust(512, b"\0")
    print("\ncmdline -> %r" % cl.decode(errors="replace"))

    new_body = patch_props(body.decode()).encode()
    entries[idx] = [fields, name, new_body]
    print("\n--- default.prop (patched) ---")
    print(new_body.decode().rstrip())

    # Optional 4th arg: an ADB public key to bake in as /adb_keys so adbd
    # authorises this host with no on-device prompt (KaiOS adbd still requires
    # a key even with ro.adb.secure=0). adbd reads /adb_keys from the ramdisk.
    if len(sys.argv) > 4:
        keydata = open(sys.argv[4], "rb").read()
        kfields = [0, 0o100644, 0, 0, 1, 0, len(keydata), 0, 0, 0, 0, len(b"adb_keys") + 1, 0]
        kidx = next((i for i, e in enumerate(entries) if e[1] == b"adb_keys"), None)
        if kidx is None:
            entries.append([kfields, b"adb_keys", keydata])
            print("\ninjected /adb_keys (%d bytes) into ramdisk" % len(keydata))
        else:
            entries[kidx] = [kfields, b"adb_keys", keydata]
            print("\nreplaced existing /adb_keys (%d bytes)" % len(keydata))

    img.ramdisk = ramdisk_close(kind, cpio_build(entries))
    out = img.build()
    open(dst, "wb").write(out)
    print("\nwrote %s: %d bytes (original %d bytes)" % (dst, len(out), len(raw)))
    print("Sanity: re-run 'dump' on the output before flashing.")


if __name__ == "__main__":
    main()
