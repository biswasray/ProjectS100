# firmware/

This directory is **intentionally empty in the repository**. It holds
device-specific and copyrighted artifacts that are **not** distributed here.
Populate it locally before flashing.

Nothing in this folder is committed (see the repo `.gitignore`) except this
README.

## What goes here and how to produce it

| File | How to get it |
|---|---|
| `prog_emmc_firehose_8909_ddr.mbn` | **Signed EDL loader.** `python ../tools/fetch_firmware.py -Zip <LF-2403N firmware .zip>` extracts it (and the items below) from the official firmware. |
| `stock_boot.img` | Reference stock boot from the firmware (same command). |
| `rawprogram0.xml`, `patch0.xml`, `emmc_appsboot.mbn` | Also extracted by `fetch_firmware.py`. |
| `adbkey.pub` | **Your ADB public key.** `powershell -File ../tools/prepare_adbkey.ps1` copies it from `%USERPROFILE%\.android\adbkey.pub` (generating a key first if needed). |
| `boot_device.img` | **Your device's own stock boot** — read it yourself: enter EDL, then `..\tools\edl.cmd r boot boot_device.img --loader=prog_emmc_firehose_8909_ddr.mbn`. This is your restore point. |
| `boot_rooted.img` | Built by `python ../tools/make_root_boot.py boot_device.img boot_rooted.img adbkey.pub ../tools/s60su`. |

## Why the firmware isn't included

The JioPhone/LYF firmware is **copyrighted** by Reliance Jio / Qualcomm and is
not ours to redistribute. Download it yourself from a firmware mirror (see the
top-level `README.md`), then let `fetch_firmware.py` pull out only the pieces
this toolkit needs. The signed loader is also tied to the device's OEM key, so
treat it as device-family-specific.
