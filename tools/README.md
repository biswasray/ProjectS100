# ProjectS100 — JioPhone LF-2403N root / EDL toolkit

Full working toolchain to back up, root, and re-flash a **JioPhone LF-2403N**
(Qualcomm MSM8905/8909, KaiOS 2.5, Gecko 48, Android 6/Gonk base). Everything
here is proven working as of 2026-09-19: the phone currently runs a **rooted
boot** and gives a `uid=0` shell via `/s60su`.

Original goal: run J2ME apps. Foundation (EDL + root) is **done**; the J2ME
runtime itself is not built yet — see "Next steps".

---

## Device facts

| | |
|---|---|
| SoC | Qualcomm MSM8905 (Sahara reports 8905; fastboot 8909 — same family) |
| HWID / PK-hash | `0x000940e101390000` / `911b8f01c36f8a87…` (OEM key, secure boot ON) |
| OS | KaiOS 2.5 (`quoin_2_5`), Gecko 48, Android 6.0.1 / Gonk |
| EDL combo | power off → **hold `*` + `#`** → plug USB (screen stays black = EDL) |
| EDL USB id | `05C6:9008` (QHSUSB__BULK) |
| boot partition | sector 396384, 32 MB |

The bootloader is **secure** but does **not** verify the `boot` image signature,
so a modified boot boots fine. It **does** verify EDL loaders, so only the
OEM-signed firehose loader works — extract it from stock firmware into
`../firmware/` with `fetch_firmware.py` (it is **not** shipped in this repo).

---

## One-time host setup

> **New PC / fresh clone?** Run `powershell -ExecutionPolicy Bypass -File setup.ps1`
> — it installs deps, clones+patches `edl`, and fetches Zadig (idempotent). See
> `HOW_TO_START.md`. The manual list below is what it automates.

- Python 3.12 at `%LOCALAPPDATA%\Programs\Python\Python312`
- `pip install libusb-package gdown` (libusb-package gives pyusb a WinUSB backend)
- bkerler `edl` in `tools/edl/` (patched — see below)
- ARM cross-compiler in WSL Ubuntu: `apt install gcc-arm-linux-gnueabi`
- **Zadig**: the `05C6:9008` device must use the **WinUSB** driver.
  libusb-win32/libusbK let the device open but *drop the Sahara HELLO*; only
  WinUSB preserves it. (UsbDk was removed — it conflicts with everything.)

### edl patches (in `tools/edl/`, needed on Windows)
- `edlclient/Library/Connection/usblib.py`: on Windows build the libusb1 backend
  via `libusb_package.find_library`; do **not** set the USE_USBDK option.
- `edlclient/Library/sahara.py connect()`: read the HELLO with a 5 s window.
- `tools/edl.cmd` wrapper sets `PYTHONUNBUFFERED=1` and `PYTHONIOENCODING=utf-8`
  (the `█` progress char otherwise crashes a redirected console and desyncs
  firehose mid-transfer).

---

## Everyday use

All `edl.cmd` commands need the phone **in EDL** (black screen). The firehose
loader stays running after a *successful* command, so you can chain commands
without re-entering EDL — but any crashed/aborted command desyncs it and you
must re-enter EDL (battery pull + `*`+`#`).

```powershell
cd C:\path\to\ProjectS100\tools
set LDR=C:\path\to\ProjectS100\firmware\prog_emmc_firehose_8909_ddr.mbn

.\edl.cmd printgpt                       --loader=%LDR%   # partition table
.\edl.cmd r  boot  ..\firmware\boot.img  --loader=%LDR%   # read a partition
.\edl.cmd w  boot  ..\firmware\boot.img  --loader=%LDR%   # write a partition
.\edl.cmd rf full_backup.bin             --loader=%LDR%   # whole eMMC dump
.\edl.cmd reset                                           # reboot the phone
```

Always pass `--loader=%LDR%` (the signed loader). Give file args as **absolute
paths**.

---

## Rebuild / flash the rooted boot

```powershell
# 1. build a rooted boot from the stock backup
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" make_root_boot.py ^
    ..\firmware\boot_device.img ..\firmware\boot_rooted.img ^
    ..\firmware\adbkey.pub s60su

# 2. enter EDL, then flash it
.\edl.cmd w boot ..\firmware\boot_rooted.img --loader=%LDR%
.\edl.cmd reset
```

`make_root_boot.py` (uses `patch_boot.py`) produces a boot that:
- sets `ro.secure=0 / ro.adb.secure=0 / ro.debuggable=1`, adds `adb` to usb.config
- forces kernel cmdline `androidboot.selinux=permissive`
- injects `/adb_keys` (this host's `~/.android/adbkey.pub`) → adb auto-authorised
- injects `/s60su` (setuid-root, mode 06755) at the ramdisk root → real root

`s60su` is built from `s60su.c`:
```bash
wsl -d Ubuntu-24.04 -u root -- bash -lc \
  "cd /mnt/c/.../tools && arm-linux-gnueabi-gcc -static -O2 -o s60su s60su.c && arm-linux-gnueabi-strip s60su"
```

---

## Getting a root shell

adbd is hardcoded to `shell`; use the setuid helper instead. In Git Bash,
disable path mangling first:

```bash
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
adb shell /s60su -c id                       # uid=0(root)
adb shell /s60su -c 'cat /data/local/webapps/webapps.json'
```

`/system` is dm-verity protected (can't remount rw). To modify `/system`,
rebuild boot dropping the `verify` flag from the `fstab.qcom` `/system` line.

---

## Restore to stock (undo root)

```powershell
# enter EDL, then:
.\edl.cmd w boot ..\firmware\boot_device.img --loader=%LDR%
.\edl.cmd reset
```

`../firmware/boot_device.img` is the exact original boot pulled from *this*
device. IMEI/EFS partitions (`modemst*`, `fsg`, `persist`) were never touched.

---

## Next steps (J2ME runtime — not started)

Install a J2ME VM as a sideloaded KaiOS app in `/data/local/webapps/`
(writable as root, no verity). Plan: package **PluotSorbet** (JS MIDP VM, built
for Firefox OS / Gecko 48) as a packaged webapp with a MIDlet picker and
JioPhone keypad → MIDP keycode mapping; register it in `webapps.json`. See the
project memory for full context.

## Files

Committed (the toolkit):
- `tools/setup.ps1`, `tools/apply_edl_patch.py` — env setup + edl patcher
- `tools/fetch_firmware.py`, `tools/prepare_adbkey.ps1`, `tools/build_s60su.ps1` — generators
- `tools/patch_boot.py`, `tools/make_root_boot.py`, `tools/s60su.c`, wrappers, diagnostics
- `README.md`, `HOW_TO_START.md`

Local only (gitignored — generated/downloaded, never committed):
- `firmware/prog_emmc_firehose_8909_ddr.mbn` — signed EDL loader (`fetch_firmware.py`)
- `firmware/boot_device.img` — **your stock boot backup** (restore point; `edl.cmd r boot`)
- `firmware/boot_rooted.img` — rooted boot (`make_root_boot.py`)
- `firmware/adbkey.pub` — your ADB key (`prepare_adbkey.ps1`)
- `tools/s60su` — built binary (`build_s60su.ps1`); `tools/zadig.exe`; `tools/edl/` (patched clone)
