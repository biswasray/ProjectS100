# HOW TO START — putting the phoneME "J2ME OS" on a rooted JioPhone

This is the newcomer's guide for `os/`: what the pieces are, which machine each
command runs on, and the exact order of scripts from a fresh PC to Java running
on the phone's LCD. `README.md` in this folder is the reference (design, port
internals, why the patches exist); this file is the walkthrough.

> Everything here was done on a JioPhone **LF-2403N** (KaiOS 2.5, MSM8909,
> kernel 3.10, 240x320 LCD) on 2026-09-20. Other JioPhone models should be
> close, but run `probe.sh` (§5) before trusting the key map or fb format.

---

## 0. What you are deploying

Sun phoneME (CLDC-HI Java VM + MIDP 2.1) cross-compiled into **one static ARM
binary, `runMidlet`**, that draws straight into `/dev/graphics/fb0` and reads
the keypad from `/dev/input/event0`. While it runs, KaiOS's `b2g` process is
stopped; when it exits, `b2g` is started again, so the phone is never lost.

```
 PC (Windows + WSL)                              JioPhone (rooted boot, adb)
 ───────────────────────────────────────         ─────────────────────────────────
 os/phoneME/  sources + patches                   /data/j2me/
   └─ build.sh (in WSL)  → os/out/j2me/  ──adb──▶   j2me.sh      launcher
                                                    bin/runMidlet the OS (VM+MIDP+AMS)
 os/scripts/*.sh (in Git Bash) talk to the phone    bin/gsu       root helper
                                                    lib/ appdb/   skin, policy, suites
```

The user experience on the phone: a blue **"Java MIDlets"** application
manager is the home screen; you install `.jad/.jar` suites into it and launch
them with the keypad. Left soft key = menu/Exit, right soft key = Launch/Menu,
OK = select, power key = END.

---

## 1. Two shells — know which one you are in

| Shell | Used for | How to open |
|---|---|---|
| **Git Bash** (Windows) | `adb`, every `os/scripts/*.sh` that talks to the phone (`probe`, `deploy`, `ams_start`, `ams_stop`, `screenshot`), `git`, `fetch_phoneme.sh` | Git Bash from the Start menu, `cd` to the repo |
| **WSL Ubuntu-24.04** | everything that compiles: `setup_wsl.sh`, `build.sh`, `rebuild_vm.sh`, `mkmidlet.sh`, `emu.sh` | from Git Bash: `wsl -d Ubuntu-24.04 -- bash /mnt/c/<repo>/os/scripts/<script>.sh` |

Two Windows-specific traps, both already handled inside the scripts but
relevant if you type commands by hand:

- **Git Bash rewrites `/mnt/c/...` into `C:/Program Files/Git/mnt/c/...`** when
  it is an argument to a Windows program such as `wsl`. Prefix with
  `MSYS_NO_PATHCONV=1`:
  ```bash
  export MSYS_NO_PATHCONV=1
  REPO=/mnt/c/Users/$USERNAME/Documents/Projects/ProjectS100     # adjust
  wsl -d Ubuntu-24.04 -- bash $REPO/os/scripts/build.sh
  ```
- **Windows `adb.exe` drops the quotes of a separate `-c "..."` argument**, so
  `adb shell /s60su -c "cat /x"` reaches the phone as `sh -c cat /x` (a bare
  `cat` that hangs). Always hand the phone one pre-quoted string:
  ```bash
  adb shell "/s60su -c '/data/j2me/bin/gsu -c \"<command>\"'"
  ```

Builds happen in WSL's own filesystem (`~/.cache/s100`), **never** on `/mnt/c`
(9p is ~1 file/minute). `build.sh` rsyncs the sources there and copies only
the packaged result back to `os/out/`.

---

## 2. Prerequisites

### PC

| Need | Notes |
|---|---|
| Windows 10/11 with **WSL2** and **Ubuntu-24.04** | `wsl --install -d Ubuntu-24.04` (reboot if asked) |
| **Git for Windows** (Git Bash) | git-scm.com |
| **Android platform-tools** on `PATH` | `adb` — developer.android.com/tools/releases/platform-tools |
| **Python 3.12** on Windows | for `fbdump.py` (screenshots) and the EDL tools in `tools/` |
| ~2 GB free in WSL | sources ~150 MB, build tree ~1.5 GB |

Inside WSL the toolchain (ARM gcc 13, JDK 8, 32-bit host libs, qemu-user,
rsync…) is installed by one script (idempotent):

```bash
export MSYS_NO_PATHCONV=1
wsl -d Ubuntu-24.04 -u root -- bash $REPO/os/scripts/setup_wsl.sh
```

### Phone — must already be rooted

The runtime needs root on the phone: `/data/j2me` and `/dev/graphics/fb0`
are not reachable from the normal `shell` user. Root comes from the **rooted
boot image** built with the toolkit in `tools/` — follow
`tools/HOW_TO_START.md` (§4 Zadig driver, §5 EDL mode, §6 back up + root +
flash). When that is done you have:

```bash
adb devices                    # <serial>  device        (authorised, no "unauthorized")
adb shell "/s60su -c id"       # uid=0(root) gid=0(root) ...
```

If either line is not what you see, stop here and finish `tools/HOW_TO_START.md`
first. Also enable **USB debugging** in KaiOS (Settings → Device → Developer →
Debugger: ADB only) if `adb devices` shows nothing.

#### About "root" on this phone (read once, it explains `gsu`)

`adbd` on the JioPhone runs as `shell` with a capability bounding set of just
`CAP_SETUID|CAP_SETGID`. A bounding set survives every `exec`, including
setuid ones, so `/s60su` gives you uid 0 **without `CAP_DAC_OVERRIDE`**: root
is still refused by anything not owned by root (`/data` is `system:system 771`,
`fb0` is `system:graphics 660`, the backlight node is `system:system 644`).
`adb root` is refused too (production build).

The workaround is `os/device/gsu.c` ("group su"), built into
`/data/j2me/bin/gsu`: it joins the owning groups (`system`, `graphics`,
`input`, `inet`, …) and can switch to another uid (`gsu -u 1000` for the
`system`-owned backlight file). `deploy.sh` and `j2me.sh` use it
automatically; you only need it for ad-hoc commands (see §1).

---

## 3. Get the sources (Git Bash, once)

```bash
cd /c/Users/$USERNAME/Documents/Projects/ProjectS100
bash os/scripts/fetch_phoneme.sh
```

This shallow-clones `github.com/magicus/phoneME` into `os/phoneME/`
(gitignored) and applies `os/patches/0001-*.patch` (VM/toolchain fixes,
including the EABI `cacheflush` fix the phone needs) and `0002-*.patch` (the
JioPhone framebuffer/keypad port). The **patches are the source of truth**:
if you edit anything under `os/phoneME/`, regenerate them with
`bash os/scripts/fetch_phoneme.sh --diff` and commit the `.patch` files.

---

## 4. Build (WSL, ~15 min the first time)

```bash
export MSYS_NO_PATHCONV=1
wsl -d Ubuntu-24.04 -- bash $REPO/os/scripts/build.sh            # = sync pcsl cldc midp package
```

Stages can be run alone (`build.sh midp package`); `FORCE=1` rebuilds a stage
whose output already exists. Typical times: pcsl 10 s, cldc ~4 min, midp
~10 min. The result is `os/out/j2me/`:

```
os/out/j2me/
  bin/runMidlet   the OS, static ARM, ~2.4 MB     bin/gsu   root helper     bin/keyprobe
  lib/            skin.bin, _policy.txt, _function_groups.txt, _main.ks, properties, images
  appdb/          AMS icons/splash; installed suites will land here
  j2me.sh  keymap.txt
```

`build.sh` refuses to package anything that is not statically linked — Gonk
has bionic, not glibc, so a dynamic binary would not start.

### Optional: try it on the PC first (WSL)

The real ARM binary runs under `qemu-arm` with a file-backed framebuffer:

```bash
bash os/scripts/mkmidlet.sh os/examples/Hello Hello HelloMIDlet      # -> Hello.jar/.jad
cp os/examples/Hello/Hello.* /tmp/
EMU_SECONDS=10 bash os/scripts/emu.sh -1 com.sun.midp.scriptutil.CommandLineInstaller I file:///tmp/Hello.jad
EMU_KEEP=1 EMU_SECONDS=8 EMU_KEYS="5 up" bash os/scripts/emu.sh 2   # run suite 2 -> os/out/emu-N.png
```

Do this once so you know what a healthy `j2me.log` looks like.

---

## 5. Deploy to the phone (Git Bash, phone booted into KaiOS, USB)

### 5a. Probe the hardware (once per phone model)

```bash
bash os/scripts/probe.sh
```

Saves `os/out/probe/{framebuffer,input_devices,platform}.txt` and the Gonk
key layout files under `os/out/probe/keylayout/`. Expected on the LF-2403N:

- `fb0/modes: U:240x320p-0`, `bits_per_pixel: 16`, `stride: 512`,
  `virtual_size: 240,640`
- `matrix_keypad` on `event0`, `qpnp_pon` (power button) on `event1`
- `matrix_keypad.kl` identical to `os/device/keymap.txt`

If your `.kl` differs, edit `os/device/keymap.txt` (or later, directly
`/data/j2me/keymap.txt` on the phone — no rebuild needed).

### 5b. Push the runtime

```bash
bash os/scripts/deploy.sh
```

What it does: pushes `os/out/j2me` to `/data/local/tmp/j2me_stage` (the only
place the `shell` user may write), checks that `gsu` grants the `graphics`
group, then copies everything to **`/data/j2me`** as root and fixes modes.
Re-run it after every rebuild. It fails with "text file busy" if `runMidlet`
is running — run `ams_stop.sh` first.

### 5c. Start the OS on the LCD

```bash
bash os/scripts/ams_start.sh          # stops b2g, turns the backlight on, starts the app manager, returns
bash os/scripts/screenshot.sh         # -> os/out/phone.png : what fb0 holds right now
adb shell "/s60su -c 'tail -n 30 /data/j2me/j2me.log'"     # VM output
adb shell "/s60su -c 'cat /data/j2me/ams.out'"             # launcher output (should be empty)
```

The phone now shows the blue **Java MIDlets** screen and reacts to the
keypad. `screenshot.sh` renders both fb0 pages (256x640); the top one is
normally what is on the panel.

### 5d. Stop it / get KaiOS back

```bash
bash os/scripts/ams_stop.sh           # kills runMidlet, `start b2g`
```

Pressing **Exit** (left soft key) in the app manager does the same through
the launcher's exit trap. Either way `b2g` restarts and **re-enumerates USB,
so adb disappears for ~5 s** — that is normal, not a reboot.

---

## 6. Install and run a MIDlet

The installer is itself a MIDlet and needs the display, so stop the app
manager first. Files must be under `/data/j2me` (the `shell` user cannot
write there → copy via `gsu`).

```bash
# build the suite (WSL)
bash os/scripts/mkmidlet.sh os/examples/Hello Hello HelloMIDlet

# push + install (Git Bash)
bash os/scripts/ams_stop.sh
adb push os/examples/Hello/Hello.jar /data/local/tmp/
adb push os/examples/Hello/Hello.jad /data/local/tmp/
adb shell "/s60su -c '/data/j2me/bin/gsu -c \"cp /data/local/tmp/Hello.ja? /data/j2me/\"'"
adb shell "/s60su -c '/data/j2me/j2me.sh install /data/j2me/Hello.jad'"     # -> "installed, ID: 2"
adb shell "/s60su -c '/data/j2me/j2me.sh list'"

# run it directly, or start the AMS and pick it there
bash os/scripts/ams_start.sh run 2
bash os/scripts/ams_start.sh
```

`j2me.sh` sub-commands: `ams` (default) · `run <id> [class]` · `install <jad|jar>`
· `list` · `remove <id>` · `sh` (root shell with b2g stopped). Environment
knobs: `J2ME_BACKLIGHT` (0–255, default 128), `J2ME_KEEP_B2G=1` (do not stop
b2g — expect the two to fight over the screen), `MIDP_FB_NOPAN=1`,
`MIDP_KEYMAP`, `MIDP_KEYPAD_DEV`, `MIDP_FB_DEV`.

---

## 7. The daily loop: change → rebuild → redeploy

| You changed | Rebuild (WSL) | Then (Git Bash) |
|---|---|---|
| `os/device/j2me.sh`, `keymap.txt` | `build.sh package` | `ams_stop.sh`, `deploy.sh` |
| `os/device/gsu.c`, `keyprobe.c` | `build.sh package` | same |
| anything under `os/phoneME/midp/` (port C code, XML config, skin, AMS Java) | `build.sh midp package` | same, then `fetch_phoneme.sh --diff` (patch 0002) |
| anything under `os/phoneME/cldc/` (VM) | `rebuild_vm.sh` (incremental cldc + MIDP relink + package) | same, then `fetch_phoneme.sh --diff` (patch 0001) |
| `os/phoneME/pcsl/` | `FORCE=1 build.sh pcsl midp package` | same |
| a MIDlet | `mkmidlet.sh <dir> <Name> <MainClass>` | §6 |

Where things live inside `os/phoneME/` (see `README.md` for the full list):

- **hardware port**: `midp/src/highlevelui/fb_port/jiophone/native/jiophone_port.c`
  (framebuffer), `midp/src/events/input_port/fb/native/fb_{read_key,keymapping,handle_input}.c` (keys)
- **device config / look**: `midp/src/configuration/configuration_xml/linux_fb/`
  `constants_jiophone.xml`, `files_jiophone.lst`, `properties.xml`, `skin.xml`;
  splash in `midp/src/ams/appmanager_ui_resources/linux_fb/`
- **home screen / system apps (Java, romized into runMidlet)**:
  `midp/src/ams/appmanager_ui/reference/classes/com/sun/midp/appmanager/`
  (`MVMManager.java` is what `j2me.sh ams` starts, `AppManagerUIImpl.java` is
  the list you see), installer in `midp/src/ams/installer/`
- **VM**: `cldc/src/vm/cpu/arm/` (JIT/stubs), `cldc/src/vm/os/linux/OS_linux.cpp`

---

## 8. When something goes wrong

| Symptom | Look at / do |
|---|---|
| `deploy.sh`: "no adb device" | `adb devices`; USB debugging on; phone booted into KaiOS (not EDL) |
| `deploy.sh`: "/s60su not giving root" | rooted boot not flashed / stock boot restored — `tools/HOW_TO_START.md` §6 |
| `deploy.sh`: "gsu did not grant the graphics group" | the pushed `bin/gsu` is not executable or not static; rebuild with `build.sh package` |
| `runMidlet` dies at once, `j2me.log` says `Fatal signal SIGILL ... code=4` | that is a **bad syscall**, not a bad opcode (kernel is EABI-only). Patch 0001 fixes the known one (`swi 0x9f0002`); a new one means another OABI `swi` — `objdump -d` at the reported `addr` |
| `SIGSEGV` / VM aborts | run the same command under `emu.sh` on the PC first; compare logs |
| LCD black but `screenshot.sh` shows content | backlight: KaiOS left `/sys/class/leds/lcd-backlight/brightness` at 0. `j2me.sh` sets `J2ME_BACKLIGHT` via `gsu -u 1000`; check `ams.out` for a permission error |
| LCD black and `screenshot.sh` black too | `ps \| grep b2g` — b2g still running? `cat /sys/class/graphics/fb0/blank` (should be 0); try `MIDP_FB_NOPAN=1` |
| Colours/geometry wrong | `os/out/probe/framebuffer.txt` vs. what `jiophone_port.c` assumes (RGB565, stride 512) |
| Keys do nothing / wrong keys | `adb shell "/s60su -c '/data/j2me/bin/gsu -c /data/j2me/bin/keyprobe'"` and press keys; fix `/data/j2me/keymap.txt` |
| adb vanished right after exiting the AMS | normal: b2g restart re-enumerates USB; wait ~5 s |
| Phone stuck with b2g stopped (no KaiOS, no Java) | `adb shell "/s60su -c '/data/j2me/bin/gsu -c \"start b2g\"'"` or `ams_stop.sh`; worst case hold Power to reboot — nothing here survives a reboot except the files in `/data/j2me` |
| Text file busy on deploy | `ams_stop.sh` first |
| A Java exception in `j2me.log` mentioning DNS / UnknownHost | expected for now: static glibc has no NSS; use IP literals (roadmap item) |

Nothing in this folder touches the boot partition, modem or KaiOS files —
undoing everything is `adb shell "/s60su -c '/data/j2me/bin/gsu -c \"rm -rf /data/j2me\"'"`
(and, if you want the stock boot back, `tools/HOW_TO_START.md` §6f).

---

## 9. Cheat sheet

```bash
# Git Bash, from the repo root
export MSYS_NO_PATHCONV=1; REPO=/mnt/c/Users/$USERNAME/Documents/Projects/ProjectS100
wsl -d Ubuntu-24.04 -u root -- bash $REPO/os/scripts/setup_wsl.sh   # once
bash os/scripts/fetch_phoneme.sh                                       # once (+ --diff to refresh patches)
wsl -d Ubuntu-24.04 -- bash $REPO/os/scripts/build.sh                  # build everything -> os/out/j2me
bash os/scripts/probe.sh                                               # once per phone model
bash os/scripts/deploy.sh                                              # -> /data/j2me
bash os/scripts/ams_start.sh                                           # Java on the LCD
bash os/scripts/screenshot.sh                                          # -> os/out/phone.png
bash os/scripts/ams_stop.sh                                            # KaiOS back
adb shell "/s60su -c '/data/j2me/bin/gsu -c \"<any root command>\"'"
```
