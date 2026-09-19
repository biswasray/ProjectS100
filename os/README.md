# os/ — native phoneME on the JioPhone framebuffer

Sun's **phoneME Feature** (CLDC-HI VM + MIDP 2.1, plain C/C++) cross-compiled
for the JioPhone LF-2403N and run **directly on `/dev/graphics/fb0` and the
evdev keypad**, with KaiOS/Gecko (`b2g`) stopped. No browser, no JS VM: the
Java bytecode runs on a JIT-ing ARM VM and paints straight into the LCD. It
feels like a J2ME feature phone OS — the phoneME application manager is the
"home screen", you install `.jad`/`.jar` suites into it and launch them from
there.

This is the "much more work" option from the project plan, so read the
**Status** section before expecting a finished product.

```
                 PC (Windows + WSL Ubuntu 24.04)                 JioPhone (rooted boot, adb)
  ┌───────────────────────────────────────────────┐    ┌────────────────────────────────────┐
  │ os/phoneME  (magicus/phoneME clone + patches) │    │ /data/j2me/                        │
  │   pcsl  → libpcsl_*.a           (ARM)         │    │   j2me.sh   stop b2g, run VM, ...  │
  │   cldc  → libcldc_vm.a + romgen (ARM + host)  │ →  │   bin/runMidlet   static ARM, ~4MB │
  │   midp  → runMidlet (static ARM, romized)     │adb │   lib/     skin, policy, keystore  │
  │ os/out/j2me  ← package                        │    │   appdb/   installed suites (RMS)  │
  └───────────────────────────────────────────────┘    │   keymap.txt  evdev code → MIDP key│
                                                       └────────────────────────────────────┘
```

## Status

| Piece | State |
|---|---|
| Host toolchain (WSL, ARM gcc 13, JDK 8, 32-bit host tools, qemu-user) | **done**, `scripts/setup_wsl.sh` |
| PCSL for ARM | **builds** |
| CLDC-HI VM for ARMv5+/VFP, romized, isolates on | **builds and runs** Java under `qemu-arm` (JIT works) |
| MIDP 2.1 + Chameleon UI, `linux_fb` port, 240x320 | **builds**; one static 2.4 MB `runMidlet` |
| JioPhone framebuffer + evdev port (`fb_port/jiophone`) | **verified under emulation** (file-backed fb + FIFO keypad, see below); **untested on hardware** |
| App manager, installer, running a user MIDlet, key events | **verified under emulation**: install `Hello.jad` -> run -> Canvas paints, D-pad/keypad arrive with the right MIDP codes |
| Key map | plausible defaults + runtime override file; **must be verified with `probe.sh`/`keyprobe`** |
| On-device launcher, deploy, probe scripts | written; **untested on hardware** |
| Networking | sockets are compiled in, but `gethostbyname` in a static glibc binary has no NSS -> **DNS will not resolve** on the phone until PCSL gets its own resolver (IP literals work) |
| Telephony / SMS / audio (JSR-120/135) | not started |
| Boot straight into Java (init.rc service) | not started; `j2me.sh` is started from adb for now |

The phone was not connected while this was written; everything past the
emulator needs one bring-up session with the device.

<p>
<img src="docs/emu-ams.png" width="240" alt="phoneME app manager, emulated">
<img src="docs/emu-camanager.png" width="240" alt="Certificate manager MIDlet">
<img src="docs/emu-hello.png" width="240" alt="HelloMIDlet showing key events">
</p>

*The ARM `runMidlet` binary running under qemu-arm on the PC with the
file-backed framebuffer: the application manager with the installed `Hello`
suite, a built-in MIDlet, and the example MIDlet reporting a key press.*

## Layout

```
os/
  README.md                 this file
  scripts/
    setup_wsl.sh            apt installs inside WSL (run as root, idempotent)
    fetch_phoneme.sh        clone github.com/magicus/phoneME into os/phoneME + apply patches
    build.sh                sync | pcsl | cldc | midp | package | all | clean
    deploy.sh               adb push os/out/j2me -> /data/j2me (needs tools/ root)
    probe.sh                dump fb geometry, input devices, Gonk .kl key layouts
    emu.sh                  run the ARM runtime on the PC (qemu + fake framebuffer)
    fbdump.py, sendkey.py   screenshot the fake fb / inject keypad events
    mkmidlet.sh             javac + preverify + jar a MIDlet suite (no WTK needed)
  examples/Hello/           HelloMIDlet: screen size, colour ramp, last key pressed
  docs/                     emulator screenshots
  patches/
    0001-toolchain-...      cldc/preverifier fixes for gcc 13 / x86_64 hosts / glibc 2.39
    0002-midp-jiophone-...  the JioPhone linux_fb port + device config
  device/
    j2me.sh                 on-phone launcher (stops b2g, runs the AMS / a suite / installer)
    keymap.txt              evdev keycode -> MIDP key table, editable on the phone
    keyprobe.c              tiny static tool that prints keypad event codes
  toolchain/mk_shim.sh      creates toolchain/bin/{gcc,g++,as,...} -> arm-linux-gnueabi-*
  phoneME/                  (gitignored) the patched source checkout
  out/j2me/                 (gitignored) what deploy.sh pushes
```

Builds run in WSL's own filesystem (`~/.cache/s100`): compiling from `/mnt/c`
goes at roughly one file a minute over 9p, so `build.sh` rsyncs the tree there
first and only the packaged result comes back to `os/out/`.

## Build

```powershell
# 0. once: WSL Ubuntu 24.04 (see tools/HOW_TO_START.md §8), then
wsl -d Ubuntu-24.04 -u root -- bash /mnt/c/.../ProjectS100/os/scripts/setup_wsl.sh

# 1. sources + patches (Git Bash or WSL; ~150 MB shallow clone)
bash os/scripts/fetch_phoneme.sh

# 2. build everything (first run ~15 min: pcsl 10 s, cldc ~4 min, midp ~10 min)
wsl -d Ubuntu-24.04 -- bash /mnt/c/.../ProjectS100/os/scripts/build.sh
```

`build.sh` stages can be run individually (`build.sh cldc`, `build.sh midp
package`). `FORCE=1` rebuilds a stage whose output exists. Logs are just
stdout; redirect them.

What the build produces (`os/out/j2me/`):

- `bin/runMidlet` — the whole runtime in one **static** ARM executable: VM,
  romized MIDP classes, Chameleon LCDUI, application manager, installer.
- `bin/keyprobe` — keypad code dumper.
- `lib/` — `skin.bin` (Chameleon look), `_main.ks` (CA keystore),
  `_policy.txt`/`_function_groups.txt` (permission policy), properties.
- `appdb/` — "internal storage": AMS icons/splash `.raw` images, `_main.ks`;
  installed suites land here too.
- `j2me.sh`, `keymap.txt`.

### Try it without the phone

```bash
wsl -d Ubuntu-24.04
bash os/scripts/mkmidlet.sh os/examples/Hello Hello HelloMIDlet      # -> Hello.jar/.jad
cp os/examples/Hello/Hello.* /tmp/
EMU_SECONDS=10 bash os/scripts/emu.sh -1 com.sun.midp.scriptutil.CommandLineInstaller I file:///tmp/Hello.jad
EMU_KEEP=1 EMU_SECONDS=8 EMU_KEYS="5 up" bash os/scripts/emu.sh 2       # run suite 2, press 5 then UP
EMU_KEEP=1 EMU_SECONDS=8 bash os/scripts/emu.sh                          # the app manager
```

Each timed run leaves `os/out/emu-N.png`. Without `EMU_SECONDS` the VM runs
in the foreground; from a second shell `sendkey.py ~/.cache/s100/emu/keys
down ok` drives it and `fbdump.py ~/.cache/s100/emu/fb.raw shot.png` takes a
picture. The emulation is the real ARM binary: only `MIDP_FB_FAKE=WxHxBPP`
swaps the fb device for a plain file and the keypad for a FIFO.

### Why these patches were needed

The archive is 2007–2011 code that last built with gcc 3/4 on 32-bit Linux
and JDK 5/6. On Ubuntu 24.04 / gcc 13 / JDK 8:

- **CLDC build system** (`0001`): `x86_64` host was unknown; `-m32`/`--32`
  leaked into the ARM cross compile; the host generators (`loopgen`,
  `romgen`) need `-D_FILE_OFFSET_BITS=64` or their 32-bit `stat()` fails with
  `EOVERFLOW` on 64-bit inodes (/mnt/c, btrfs…) and every classpath lookup
  silently misses; `romgen` can't link statically because glibc 2.39's
  i386 `libm.a` lacks `fmod`; `.arch i486` in the x87 stub file is rejected by
  binutils ≥ 2.41; the generated `ROMImage.cpp` trips C++11 narrowing so the
  VM is compiled as `-std=gnu++98`; and `-fstrict-aliasing` had to become
  `-fno-strict-aliasing -fno-delete-null-pointer-checks -fwrapv` — with
  gcc 13 -O2 the romizer segfaulted at start otherwise.
- **preverifier** (`0001`): the shipped static binary has the same `stat()`
  problem, so it is rebuilt from source; the trunk source has a real bug
  (`file.c` frees the name/type ID hash after every class while loaded
  classes keep their IDs) that made it report
  `Class X overrides final method installSuite.()V`. Fixed by not freeing it.
- **MIDP** (`0002`): `-Werror` made optional (`USE_COMPILATION_WARNINGS`);
  `--end-group` passed through `-Xlinker` and `-lnsl` dropped (both rejected
  by modern g++/glibc); display geometry moved out of the shared
  `constants.xml` into the per-device `constants_<device>.xml` so a 240x320
  device can coexist with the 176x210 boards; 240x320 splash images added;
  `MIDP_HOME` handling fixed (the reference code returned the env pointer but
  appended `appdb`/`lib` to an empty buffer); `Installer.getUrlPath()` no
  longer strips the leading `/` of POSIX paths (it turned `file:///data/x`
  into a cwd-relative path and every file install failed with
  `ConnectionNotFoundException`); plus the port below.

## The JioPhone port (`TARGET_DEVICE=jiophone`)

`midp/src/highlevelui/fb_port/jiophone/native/jiophone_port.c` replaces the
stock `fb_port.c` when `TARGET_DEVICE=jiophone`:

- **Display**: opens `/dev/graphics/fb0` (falls back to `/dev/fb0`), reads the
  real geometry/stride/bitfields, accepts **16 or 32 bpp**, unblanks the panel
  (`FBIOBLANK`), converts MIDP's RGB565 back buffer into the device pixel
  format and issues `FBIOPAN_DISPLAY` after every refresh (msm_fb only pushes a
  frame to a command-mode DSI panel on pan). Portrait and rotated (landscape)
  MIDP modes are both handled. The MIDP screen is 240×320 and is centred if
  the panel is larger.
- **Keys**: scans `/dev/input/event*`, keeps every device with `EV_KEY`, and
  multiplexes them into one `epoll` descriptor so the existing select()-based
  master-mode loop still sees a single "keyboard fd". `fb_read_key.c` gained
  `read_evdev_key_event()`; auto-repeat comes from MIDP's timer (400 ms, 80 ms)
  rather than the driver.
- **Key map**: `fb_keymapping.c` has a `jiophone_keys[]` table of the usual
  Linux codes (KEY_0–9, KEY_NUMERIC_*, D-pad, KEY_MENU/KEY_BACK soft keys,
  KEY_SEND/KEY_PHONE, KEY_POWER as END, KEY_BACKSPACE as CLEAR) **and** a
  loader for a text file (`/data/j2me/keymap.txt` or `$MIDP_KEYMAP`) so the
  table can be corrected on the phone without rebuilding.
- **Device detection**: `LINUX_FB_JIOPHONE` is the compiled-in default for
  this target; `/proc/cpuinfo` containing `MSM8909`/`Qualcomm` selects it too,
  and `MIDP_FB_DEVICE=jiophone|omap730|zaurus|versatile` overrides.
- Everything is **statically linked** (`LD_FLAGS += -static`): Gonk has
  bionic, not glibc, and static glibc binaries run fine on its kernel
  (`tools/s60su` is the existing proof).

Runtime environment knobs: `MIDP_FB_DEV`, `MIDP_KEYPAD_DEV` (use one evdev
node only), `MIDP_KEYMAP`, `MIDP_FB_NOPAN=1`, `MIDP_FB_DEVICE`, `MIDP_HOME`.

## First run on the phone

Prerequisite: the rooted boot from `tools/` (adb authorised, `/s60su`).
Run the emulator steps above once first, so you know what a healthy run
looks like in `j2me.log`.

```bash
# 1. facts first: fb depth/stride, input devices, the Gonk key layouts
bash os/scripts/probe.sh                # -> os/out/probe/, prints the .kl tables
#    compare os/out/probe/keylayout/*.kl with device/keymap.txt and fix the codes

# 2. push the runtime
bash os/scripts/deploy.sh

# 3. see raw key codes (optional, if the .kl files were not conclusive)
adb shell /s60su -c /data/j2me/bin/keyprobe

# 4. start the Java application manager on the LCD (stops b2g, restarts it on exit)
adb shell /s60su -c /data/j2me/j2me.sh
adb shell /s60su -c "tail -f /data/j2me/j2me.log"     # in a second terminal

# 5. install and run a MIDlet
adb push Hello.jar /data/local/tmp/ && adb shell /s60su -c "cp /data/local/tmp/Hello.jar /data/j2me/"
adb shell /s60su -c "/data/j2me/j2me.sh install /data/j2me/Hello.jar"
adb shell /s60su -c "/data/j2me/j2me.sh list"
adb shell /s60su -c "/data/j2me/j2me.sh run 1"
```

Things to look at if the screen stays black: the runtime's log
(`/data/j2me/j2me.log`), `MIDP_FB_NOPAN=1` (if the driver dislikes the pan),
`echo 0 > /sys/class/graphics/fb0/blank`, and whether `b2g` really stopped
(`ps | grep b2g`). If keys do nothing: `keyprobe`, then `keymap.txt`.

## Roadmap to a "J2ME OS"

1. Bring-up on hardware: fb pixel format, key codes, backlight; fix
   `jiophone_port.c` against what `probe.sh` reports.
2. Boot into Java: add an `init.rc` service (`service j2me /data/j2me/j2me.sh`,
   `class late_start`) to the rooted boot via `tools/patch_boot.py`, and either
   disable `b2g` there or keep it as the fallback the AMS can switch back to.
3. Networking: sockets to IP literals should work over the data connection
   Gonk already brings up, but name resolution needs a resolver that does not
   depend on glibc NSS (static binary): either build a tiny DNS client into
   `pcsl/network/bsd/generic` (`gethostbyname` -> UDP query to the server in
   `/etc/resolv.conf` / `getprop net.dns1`) or link against bionic instead.
4. JSR-120 (SMS) / JSR-135 (audio) — phoneME has the API layers; the
   `javacall` backends would need Gonk `rild`/`tinyalsa` glue. Large.
5. Battery/idle: screen blanking timer and CPU governor handling in the port.

## References

- phoneME source archive (git): <https://github.com/magicus/phoneME>
- phoneME Feature MR4 build docs (mirror): <https://phonej2me.github.io/content/mr4/cldc_feature.html>
- Build notes for modern hosts: <https://minexew.github.io/2021/04/10/phoneme.html>
- License: phoneME is GPLv2 with Classpath exception (see `os/phoneME/legal`).
