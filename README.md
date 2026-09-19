# JioPhone LF-2403N — EDL / Root Toolkit

Tools and documentation to **back up, root, and re-flash** a **JioPhone
LF-2403N** (Qualcomm MSM8905/8909, KaiOS 2.5, Gecko 48) from a Windows PC over
Qualcomm **EDL (Emergency Download / firehose)**, with a reproducible one-command
setup.

It gets you: a full partition backup/restore workflow, **authorized ADB**, and a
persistent **root shell** — the foundation for further modding. Built on that,
[`os/`](os/README.md) cross-compiles Sun's **phoneME** (CLDC-HI + MIDP 2.1) into
a single static ARM binary that runs J2ME MIDlets **directly on the LCD
framebuffer and keypad** with KaiOS stopped — a native "J2ME OS" mode.

> ⚠️ **Disclaimer — read this.**
> Rooting/flashing a phone can **brick it** and **voids your warranty**. This
> replaces the boot partition and lowers device security (SELinux permissive,
> root, a baked-in ADB key). Everything here is **reversible** (you keep a stock
> boot backup) and the IMEI/EFS partitions are never touched — but **you do this
> at your own risk**. The authors are not liable for any damage.
>
> **No firmware is distributed here.** The JioPhone/LYF firmware and its signed
> loader are **copyrighted** by Reliance Jio / Qualcomm. You download the
> firmware yourself; the scripts extract only what's needed, locally. Use this
> only on **your own device**.

## What it does

- Talks to the phone in **EDL** on Windows via a patched [bkerler `edl`](https://github.com/bkerler/edl)
  (the key fix: use the **WinUSB** driver + `libusb-package` so the Sahara HELLO
  isn't dropped — the thing that makes EDL actually work here).
- Uses the **OEM-signed** firehose loader (from stock firmware) to pass secure
  boot, then reads/writes any partition.
- Builds a **rooted boot**: `ro.secure=0`, SELinux permissive, your ADB public
  key injected as `/adb_keys`, and a tiny **setuid-root** helper `/s60su` for a
  `uid=0` shell (KaiOS's adbd is hardened to stay `shell`, so we bring our own).

## Quick start

```powershell
# 1. Set up the environment (Python, edl clone + patches, deps, Zadig)
powershell -ExecutionPolicy Bypass -File tools\setup.ps1

# 2. Provide the firmware you downloaded (see "Firmware" below)
python tools\fetch_firmware.py -Zip "C:\path\LYF-LF2403N-...-QFT.zip"

# 3. Generate your local ADB key credential
powershell -File tools\prepare_adbkey.ps1
```

Then follow **[`tools/HOW_TO_START.md`](tools/HOW_TO_START.md)** (full setup +
requirements) and **[`tools/README.md`](tools/README.md)** (command reference):
put the phone in EDL, back up `boot`, build the rooted boot, flash, and get root.

## Firmware

Download the LF-2403N stock firmware (a `LYF-LF2403N-…-QFT.zip`, ~660–690 MB)
from a firmware mirror (search "LYF LF2403N flash file"; MediaFire mirrors are
more reliable than the quota-limited Google Drive ones). Then
`tools\fetch_firmware.py -Zip <zip>` extracts the signed loader, stock boot, and
partition XMLs into `firmware/` (which is gitignored).

## Repository layout

```
tools/          scripts + docs (this is the toolkit)
  setup.ps1              one-command environment setup
  fetch_firmware.py      extract loader/boot from the firmware zip
  prepare_adbkey.ps1     generate firmware/adbkey.pub from your adb key
  build_s60su.ps1        build the setuid-root helper (WSL + ARM gcc)
  apply_edl_patch.py     apply the edl source patches to a fresh clone
  patch_boot.py          dump/patch an Android boot image
  make_root_boot.py      build the rooted boot image
  s60su.c                setuid-root shell helper (source)
  edl.cmd, patch_boot.cmd, *.py   wrappers + EDL diagnostics
  README.md, HOW_TO_START.md      reference + from-scratch guide
firmware/       empty in the repo; populated locally (gitignored)
os/             native J2ME runtime: phoneME cross-built for the phone, running on
                the framebuffer with KaiOS stopped (see os/README.md)
```

Not included (regenerated or downloaded locally, and gitignored): the firmware
zip and extracted blobs, your `adbkey.pub`, boot images, the compiled `s60su`,
`zadig.exe`, and the `tools/edl/` clone.

## Credits

- [bkerler/edl](https://github.com/bkerler/edl) — the Qualcomm EDL client (GPLv3;
  cloned by `setup.ps1`, not vendored here).
- The [BananaHackers](https://wiki.bananahackers.net/) community — KaiOS/JioPhone
  reference.

## License

MIT — see [`LICENSE`](LICENSE).
