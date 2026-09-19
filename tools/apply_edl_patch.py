#!/usr/bin/env python3
"""apply_edl_patch.py - apply the ProjectS100 patches to a bkerler `edl` clone.

Two functional patches are needed to make `edl` talk to the JioPhone LF-2403N
on Windows (see tools/HOW_TO_START.md #3):

  1. usblib.py  - on Windows, build the libusb1 backend via `libusb_package`
                  (WinUSB driver) instead of `backend=None`, and do NOT force
                  the USE_USBDK libusb option.
  2. sahara.py  - read the Sahara HELLO with a ~5s window in connect() instead
                  of the default ~55ms, which misses it on Windows.

Idempotent and marker-guarded: files already containing "ProjectS100 patch" are
left untouched. If an expected anchor is missing (e.g. edl changed upstream) the
script stops with a clear error instead of silently mis-patching.

Usage:  python apply_edl_patch.py [<path-to-edl-dir>]
        (defaults to the ./edl next to this script)
"""
import os
import sys

MARKER = "ProjectS100 patch"

# --- Patch 1: edlclient/Library/Connection/usblib.py ------------------------
USBLIB_ANCHOR = """\
        if sys.platform.startswith('freebsd') or sys.platform.startswith('linux') or sys.platform.startswith('darwin'):
            self.backend = usb.backend.libusb1.get_backend(find_library=lambda x: "libusb-1.0.so")
        elif is_windows():
            self.backend = None
        if self.backend is not None:
            try:
                self.backend.lib.libusb_set_option.argtypes = [c_void_p, c_int]
                self.backend.lib.libusb_set_option(self.backend.ctx, 1)
            except:
                self.backend = None
"""

USBLIB_REPLACEMENT = """\
        if sys.platform.startswith('freebsd') or sys.platform.startswith('linux') or sys.platform.startswith('darwin'):
            self.backend = usb.backend.libusb1.get_backend(find_library=lambda x: "libusb-1.0.so")
            if self.backend is not None:
                try:
                    self.backend.lib.libusb_set_option.argtypes = [c_void_p, c_int]
                    self.backend.lib.libusb_set_option(self.backend.ctx, 1)
                except:
                    self.backend = None
        elif is_windows():
            # ProjectS100 patch: drive the device through the WinUSB (Zadig) driver
            # using the bundled libusb-1.0 from libusb-package. WinUSB (unlike
            # libusb-win32) preserves the buffered Sahara HELLO on open. Do NOT
            # set the USE_USBDK option here - UsbDk is not installed.
            try:
                import libusb_package
                self.backend = usb.backend.libusb1.get_backend(find_library=libusb_package.find_library)
            except Exception:
                self.backend = None
"""

# --- Patch 2: edlclient/Library/sahara.py -----------------------------------
SAHARA_ANCHOR = """\
    def connect(self):
        try:
            v = self.cdc.read(length=0xC * 0x4, timeout=1)
            if len(v) > 1:
"""

SAHARA_REPLACEMENT = """\
    def connect(self):
        try:
            # ProjectS100 patch: the Qualcomm PBL sends the Sahara HELLO once and
            # then waits. edl's default read only listens ~55ms (timeout=1) and
            # frequently misses it, then fires an XML nop that desyncs Sahara.
            # Read the HELLO with a real multi-second timeout first.
            v = b""
            try:
                import time as _t
                _deadline = _t.time() + 5.0
                while _t.time() < _deadline:
                    try:
                        chunk = bytes(self.cdc.EP_IN.read(0xC * 0x4, 1000))
                    except Exception:
                        chunk = b""
                    if len(chunk) > 1:
                        v = chunk
                        break
            except Exception:
                v = b""
            if len(v) <= 1:
                v = self.cdc.read(length=0xC * 0x4, timeout=1)
            if len(v) > 1:
"""

PATCHES = [
    ("edlclient/Library/Connection/usblib.py", USBLIB_ANCHOR, USBLIB_REPLACEMENT),
    ("edlclient/Library/sahara.py", SAHARA_ANCHOR, SAHARA_REPLACEMENT),
]


def apply_one(path, anchor, replacement):
    name = os.path.basename(path)
    if not os.path.isfile(path):
        print("  ERROR: %s not found" % path)
        return False
    with open(path, "rb") as f:
        raw = f.read()
    # Line-ending agnostic: match/replace on LF-normalised text, then restore
    # the file's original CRLF/LF style on write (git autocrlf checks out CRLF).
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8").replace("\r\n", "\n")
    if MARKER in text:
        print("  %s: already patched (skipped)" % name)
        return True
    if anchor not in text:
        print("  ERROR: %s: anchor not found - upstream edl may have changed; "
              "patch manually (see HOW_TO_START.md #3)" % name)
        return False
    text = text.replace(anchor, replacement, 1)
    if crlf:
        text = text.replace("\n", "\r\n")
    with open(path, "wb") as f:
        f.write(text.encode("utf-8"))
    print("  %s: patched" % name)
    return True


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    edl_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, "edl")
    edl_dir = os.path.abspath(edl_dir)
    if not os.path.isfile(os.path.join(edl_dir, "edl.py")):
        sys.exit("Not an edl clone (no edl.py): %s" % edl_dir)
    print("Applying ProjectS100 edl patches in: %s" % edl_dir)
    ok = True
    for rel, anchor, repl in PATCHES:
        ok = apply_one(os.path.join(edl_dir, rel), anchor, repl) and ok
    if not ok:
        sys.exit(1)
    print("Done.")


if __name__ == "__main__":
    main()
