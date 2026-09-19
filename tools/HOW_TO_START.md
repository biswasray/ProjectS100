# HOW TO START — JioPhone LF-2403N (KaiOS) root / EDL toolkit

A from-scratch setup guide: everything you need to install on a **Windows PC**,
plus the procedures and scripts, to back up, root, and re-flash a **JioPhone
LF-2403N** (KaiOS 2.5). Follow the sections in order. For deep command
reference after setup, see [`README.md`](./README.md).

> ⚠️ Rooting replaces the boot partition and lowers device security
> (SELinux permissive, root shell). It is fully reversible — you keep a stock
> boot backup. IMEI/EFS partitions are never touched. Proceed at your own risk.

---

## 0. Quick setup (recommended)

Most of §2–§3 below is automated. From the project's `tools\` folder:

```powershell
powershell -ExecutionPolicy Bypass -File setup.ps1
```

`setup.ps1` is idempotent (safe to re-run). It locates/installs Python, clones
`edl` if missing, **applies the edl patches** (via `apply_edl_patch.py`),
installs all pip dependencies, fetches Zadig, and warns if UsbDk is present.
Flags: `-WithDevTools` (also set up WSL2 + ARM cross-compiler, §8),
`-SkipDrivers`. Two things it can't automate and will remind you about: the
**WinUSB driver** assignment in Zadig (§4) and putting the phone in **EDL** (§5).

The manual sections below document what `setup.ps1` does, for reference or if you
prefer to do it by hand.

---

## 1. What you need (hardware)

| Item | Notes |
|---|---|
| JioPhone **LF-2403N** | Qualcomm MSM8905/8909, KaiOS 2.5, Gecko 48. Removable battery (required). |
| USB data cable | Must carry data, not charge-only. |
| Windows 10 / 11 PC | 64-bit, with **Administrator** rights. |
| Free disk space | ~5 GB (688 MB firmware zip + extracted files + tools). |
| Internet | To download Python, drivers, firmware. |

This device's identity (for reference): HWID `0x000940e101390000`,
PK-hash `911b8f01c36f8a87…`, EDL USB id `05C6:9008`. Secure boot is **on**, but
the bootloader does **not** verify the `boot` image — so a modified boot runs.

---

## 2. System requirements & dependencies to install

Install these on the PC (versions are the tested baseline):

| Dependency | Version | Purpose | Get it |
|---|---|---|---|
| **Python** | 3.12.x | runs `edl` and the project scripts | python.org (tick "Add to PATH"), or `winget install Python.Python.3.12` |
| **Git** | any | clone `edl` if not bundled | git-scm.com |
| **Android platform-tools** | latest | `adb` (and `fastboot`) | developer.android.com/tools/releases/platform-tools |
| **Zadig** | 2.9 | install the WinUSB driver on the EDL device | zadig.akeo.ie (`setup.ps1` downloads it to `tools/zadig.exe`) |
| **bkerler `edl`** | 3.6x | Qualcomm Sahara/Firehose client | `setup.ps1` clones + patches it into `tools/edl/` (see §3) |
| Python: `edl` requirements | — | `pyusb`, `pyserial`, `docopt`, `pycryptodome(x)`, `lxml`, `colorama`, `capstone`, `keystone-engine`, `qrcode`, `requests`, `passlib`, `Exscript`, `paramiko` | `pip install -r tools/edl/requirements.txt` |
| Python: **`libusb-package`** | latest | gives PyUSB a libusb-1.0 backend for the WinUSB driver | `pip install libusb-package` |
| Python: `gdown` | latest | only if downloading firmware from Google Drive | `pip install gdown` |

> ❌ **Do NOT install UsbDk.** It is incompatible with this toolchain and
> destabilises USB. If present, remove it (Apps → UsbDk → Uninstall, or
> `msiexec /x`) before starting.

### Install commands (PowerShell)

```powershell
winget install Python.Python.3.12
$py = "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
& $py -m pip install --upgrade pip
& $py -m pip install -r "C:\...\ProjectS100\tools\edl\requirements.txt"
& $py -m pip install libusb-package gdown
```

If `edl/` is not present, clone it (then re-apply the §3 patches):
```powershell
git clone --recurse-submodules https://github.com/bkerler/edl tools\edl
```

---

## 3. The `edl` patches (already applied in `tools/edl/`)

These are **required on Windows** and are the reason the bundled copy is used
instead of a fresh clone. **`setup.ps1` (or `apply_edl_patch.py`) applies them
for you** — this section just documents what they do:

- `edlclient/Library/Connection/usblib.py` — on Windows, build the libusb1
  backend via `libusb_package.find_library`; do **not** set the USE_USBDK option.
- `edlclient/Library/sahara.py` `connect()` — read the Sahara HELLO with a ~5 s
  window (default is too short and misses it on Windows).
- `tools/edl.cmd` wrapper — sets `PYTHONUNBUFFERED=1` and `PYTHONIOENCODING=utf-8`
  (the `█` progress char crashes a redirected console and desyncs firehose).

To (re-)apply the first two after a fresh `git clone` of `edl` (idempotent,
marker-guarded — skips already-patched files and stops with a clear error if
upstream `edl` drifted):

```powershell
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" apply_edl_patch.py edl
```

---

## 4. Driver setup (one time)

The EDL device must use the **WinUSB** driver — *not* libusb-win32/libusbK
(those open the device but drop the Sahara HELLO).

1. Put the phone in **EDL** (see §5).
2. Run `tools\zadig.exe` (as admin).
3. **Options → List All Devices**, select **`QHSUSB__BULK`** (`05C6 9008`).
4. Choose **WinUSB** in the driver box → **Install/Replace Driver**.

(For `fastboot`, if ever needed, Windows may need the Google USB driver
force-bound to the `18D1:D00D` interface — not required for rooting.)

---

## 5. Procedure: entering EDL mode

Every `edl.cmd` command needs the phone in EDL:

1. Unplug USB. Power the phone off.
2. **Remove the battery**, wait ~10 s, reinsert. Do **not** press Power.
3. **Hold `*` and `#` together**, then plug in USB while holding (~5 s).
4. The **screen stays black** = you are in EDL. A logo/charging screen = retry.

The firehose loader stays running after a *successful* `edl.cmd`, so you can
chain commands without re-entering EDL. A **crashed/aborted** command desyncs
it → re-enter EDL before the next command.

---

## 6. Procedure: back up, root, restore

Set the loader path once per PowerShell session:
```powershell
cd C:\...\ProjectS100\tools
set LDR=C:\...\ProjectS100\firmware\prog_emmc_firehose_8909_ddr.mbn
```
> The signed loader `prog_emmc_firehose_8909_ddr.mbn` is the OEM-signed firehose
> programmer extracted from stock firmware into `firmware/` (§7 / `fetch_firmware.py`;
> it is **not** shipped in this repo). It is the only loader that passes secure
> boot. Always pass `--loader=%LDR%` and give file arguments as **absolute paths**.

### 6a. Read the partition table (sanity check)
```powershell
.\edl.cmd printgpt --loader=%LDR%
```

### 6b. Back up your stock boot (do this on YOUR device first)
```powershell
.\edl.cmd r boot ..\firmware\boot_device.img --loader=%LDR%
```
`boot_device.img` is your restore point. (Optional full dump:
`.\edl.cmd rf full_backup.bin --loader=%LDR%`.)

### 6c. Build the rooted boot
```powershell
"%LOCALAPPDATA%\Programs\Python\Python312\python.exe" make_root_boot.py ^
    ..\firmware\boot_device.img ..\firmware\boot_rooted.img ^
    ..\firmware\adbkey.pub s60su
```
This produces a boot that sets `ro.secure=0 / ro.adb.secure=0 / ro.debuggable=1`,
forces SELinux **permissive**, bakes in your ADB key as `/adb_keys`, and injects
the setuid-root helper `/s60su`. Your ADB key is
`%USERPROFILE%\.android\adbkey.pub` — copy it to `..\firmware\adbkey.pub` first
(generated automatically the first time you run any `adb` command).

### 6d. Flash it (enter EDL first)
```powershell
.\edl.cmd w boot ..\firmware\boot_rooted.img --loader=%LDR%
.\edl.cmd reset
```
Then reboot normally (battery pull + Power, **no** key combo). If it hangs at
the logo, restore (§6f).

### 6e. Get a root shell
```bash
# Git Bash: disable path mangling so /device/paths reach adb intact
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
adb devices                 # should show: <serial>  device  (authorised)
adb shell /s60su -c id      # -> uid=0(root) gid=0(root)
```

### 6f. Restore to stock (undo root)
```powershell
# enter EDL, then:
.\edl.cmd w boot ..\firmware\boot_device.img --loader=%LDR%
.\edl.cmd reset
```

---

## 7. Getting the firmware / signed loader

`firmware/` already contains the extracted signed loader and boot images. To
obtain them from scratch (a different unit, or a clean copy):

1. Download the stock firmware zip, e.g. `LYF-LF2403N-001-02-45-240221` (~688 MB)
   from a firmware mirror (MediaFire mirrors are reliable; Google Drive mirrors
   are often download-quota-blocked). `gdown <drive-id> -O out.zip` for Drive.
2. Extract from inside the zip:
   - Signed loader: `…/boot_images/build/ms/bin/8909/emmc/prog_emmc_firehose_8909_ddr.mbn`
     (use the one **not** under `unsigned/`).
   - Stock boot (reference): `…/LINUX/android/out/target/product/msm8909_512/boot.img`
   - Flashing XMLs (optional, for full QFIL): `common/build/rawprogram0.xml`, `patch0.xml`
3. The signed loader is the same across LF-2403N units (shared OEM key), but
   **`boot_device.img` is per-device** — always read it from *your* phone (§6b).

---

## 8. Optional / advanced: rebuild the `s60su` root helper

The prebuilt `tools/s60su` (static ARM binary) already exists — **most users can
skip this**. Rebuild only if you change `s60su.c`. Requires WSL2 + an ARM
cross-compiler.

```powershell
wsl --install -d Ubuntu-24.04            # one-time; reboot if prompted
```
```bash
wsl -d Ubuntu-24.04 -u root -- bash -lc "apt-get update && apt-get install -y gcc-arm-linux-gnueabi"
wsl -d Ubuntu-24.04 -u root -- bash -lc "cd /mnt/c/.../ProjectS100/tools && \
  arm-linux-gnueabi-gcc -static -O2 -o s60su s60su.c && arm-linux-gnueabi-strip s60su"
```
Then rebuild and reflash the boot (§6c–6d). `s60su.c` simply does
`setresgid(0,0,0); setresuid(0,0,0); exec /system/bin/sh`; it is installed at the
ramdisk root (`/s60su`, mode 06755) because `/sbin` is root-only (0750) and `/`
is shell-traversable (0755), and the initramfs honors setuid under permissive
SELinux.

> WSL2 + `usbipd` to run `edl` under Linux was tried and **does not work** for
> this device: `usbip` resets the USB device on attach and the Qualcomm PBL
> hangs. Use the Windows + WinUSB path above.

---

## 9. Scripts & files reference

**`tools/`**
| File | What it does | Run |
|---|---|---|
| `setup.ps1` | one-command environment setup (deps + clone/patch edl + Zadig + adb key) | `powershell -ExecutionPolicy Bypass -File setup.ps1` |
| `fetch_firmware.py` | extract the signed loader + stock boot + XMLs from the firmware zip | `python fetch_firmware.py -Zip <zip>` |
| `prepare_adbkey.ps1` | generate `firmware/adbkey.pub` from your local adb key | `powershell -File prepare_adbkey.ps1` |
| `build_s60su.ps1` | build the `s60su` binary from `s60su.c` (WSL + ARM gcc) | `powershell -File build_s60su.ps1` |
| `apply_edl_patch.py` | idempotently apply the edl source patches to a clone | `python apply_edl_patch.py edl` |
| `edl.cmd` | wrapper: runs patched `edl.py` (UTF-8, unbuffered) | `.\edl.cmd <cmd> --loader=%LDR%` |
| `patch_boot.py` | dump/patch an Android boot image (props, cmdline, inject `/adb_keys`) | `.\patch_boot.cmd dump\|patch <in> <out> [pubkey]` |
| `make_root_boot.py` | build the full rooted boot (props+cmdline+`/adb_keys`+`/s60su`) | see §6c |
| `s60su.c` | setuid-root shell source; build with `build_s60su.ps1` -> `s60su` | baked into boot |
| `hello_catch.py`, `usbprobe.py`, `sahara_*.py` | low-level EDL/Sahara diagnostics (used during bring-up; handy if EDL misbehaves) | `python <script>` |
| `README.md` | full command reference | — |

> `zadig.exe` and `s60su` are **not** in the repo — `setup.ps1` downloads Zadig,
> and `build_s60su.ps1` builds `s60su`.

**`firmware/`** — empty in the repo; produced locally (all gitignored). See
`firmware/README.md`.
| File | What | How to produce |
|---|---|---|
| `prog_emmc_firehose_8909_ddr.mbn` | **signed EDL loader** (needed for every `edl.cmd`) | `fetch_firmware.py -Zip <zip>` |
| `stock_boot.img`, `emmc_appsboot.mbn`, `rawprogram0.xml`, `patch0.xml` | reference images/layout from firmware | `fetch_firmware.py` |
| `adbkey.pub` | ADB public key baked into the rooted boot | `prepare_adbkey.ps1` |
| `boot_device.img` | **your device's stock boot** (restore point) | `edl.cmd r boot ...` (§6b) |
| `boot_rooted.img` | the rooted boot you flash | `make_root_boot.py` (§6c) |

---

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `edl.py` waits forever "Waiting for the device" | Phone not in EDL (§5), or wrong driver → use **WinUSB** via Zadig (§4). |
| Detects device, exits with no partition table | HELLO not caught → wrong driver (libusb-win32/libusbK). Switch to **WinUSB**. Ensure `libusb-package` is installed. |
| Loader upload stalls / "device silent" after hash segment | Wrong/patched loader → use the **signed** `prog_emmc_firehose_8909_ddr.mbn`. |
| `edl` crashes mid read/write, then only gets "Mode detected: error" | firehose desynced → **re-enter EDL** and retry. |
| `adb` shows `unauthorized` | Reflash the rooted boot (bakes in `/adb_keys`); or the key changed — rebuild with this PC's `adbkey.pub`. |
| `adb shell /s60su` → "not found" / paths mangled | Git Bash path conversion → `export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`. |
| `mount -o remount,rw /system` → "Operation not permitted" (even as root) | `/system` has dm-verity. Rebuild boot dropping the `verify` flag from `fstab.qcom`'s `/system` line. |
| Google Drive firmware won't download ("too many users") | Use a MediaFire mirror instead. |
| Phone hangs at logo after flashing boot | Restore stock boot (§6f). |
