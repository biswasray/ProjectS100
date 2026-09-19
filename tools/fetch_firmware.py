#!/usr/bin/env python3
"""fetch_firmware.py - pull the pieces this toolkit needs out of the official
JioPhone LF-2403N (LYF) stock firmware zip, into ../firmware/.

The firmware itself is copyrighted and is NOT distributed with this repo. You
download it yourself (see the top-level README.md), then run this to extract
only the non-secret, tool-required files:

    prog_emmc_firehose_8909_ddr.mbn   (signed EDL loader)
    stock_boot.img                    (reference stock boot)
    rawprogram0.xml, patch0.xml       (partition layout)
    emmc_appsboot.mbn                 (LK/aboot, reference)

Usage:
    python fetch_firmware.py -Zip "C:\\path\\LYF-LF2403N-...-QFT.zip"
    python fetch_firmware.py -Url  "https://.../firmware.zip"   (best-effort download)

Files are matched by path suffix inside the zip, so the exact firmware version /
top-level folder name doesn't matter.
"""
import argparse
import hashlib
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
FW_DIR = os.path.abspath(os.path.join(HERE, "..", "firmware"))

# (output name, [match predicates]) - first zip entry matching all predicates wins.
WANTED = [
    ("prog_emmc_firehose_8909_ddr.mbn",
     lambda n: n.endswith("prog_emmc_firehose_8909_ddr.mbn") and "/unsigned/" not in n),
    ("stock_boot.img",
     lambda n: n.endswith("/boot.img") and "msm8909" in n),
    ("rawprogram0.xml",
     lambda n: n.endswith("/rawprogram0.xml")),
    ("patch0.xml",
     lambda n: n.endswith("/patch0.xml")),
    ("emmc_appsboot.mbn",
     lambda n: n.endswith("/emmc_appsboot.mbn")),
]


def download(url, dest):
    print("Downloading %s ..." % url)
    try:
        import urllib.request
        with urllib.request.urlopen(url) as r, open(dest, "wb") as f:
            while True:
                chunk = r.read(1 << 20)
                if not chunk:
                    break
                f.write(chunk)
        return True
    except Exception as e:
        print("  download failed: %s" % e)
        print("  Firmware mirrors (MediaFire/Drive) are unstable and may need a")
        print("  browser. Download the zip manually and re-run with -Zip <path>.")
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Zip", "--zip", dest="zip", help="path to the firmware .zip")
    ap.add_argument("-Url", "--url", dest="url", help="URL to download the .zip from (best-effort)")
    args = ap.parse_args()

    os.makedirs(FW_DIR, exist_ok=True)
    zip_path = args.zip
    if not zip_path and args.url:
        zip_path = os.path.join(FW_DIR, "firmware_download.zip")
        if not download(args.url, zip_path):
            sys.exit(1)
    if not zip_path:
        sys.exit("Provide -Zip <path> (or -Url <url>). See top-level README for mirrors.")
    if not os.path.isfile(zip_path):
        sys.exit("Not found: %s" % zip_path)

    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
        ok = True
        for out_name, pred in WANTED:
            match = next((n for n in names if pred(n.replace("\\", "/"))), None)
            if not match:
                print("  MISS  %-32s (not found in zip)" % out_name)
                ok = False
                continue
            data = zf.read(match)
            out_path = os.path.join(FW_DIR, out_name)
            with open(out_path, "wb") as f:
                f.write(data)
            print("  OK    %-32s %8d bytes  sha1=%s" %
                  (out_name, len(data), hashlib.sha1(data).hexdigest()[:12]))
    print("\nExtracted into: %s" % FW_DIR)
    if not ok:
        print("Some files were not found - is this the LF-2403N (msm8909) firmware?")
        sys.exit(2)
    print("Next: prepare_adbkey.ps1, then read your device boot (see firmware/README.md).")


if __name__ == "__main__":
    main()
