# os/port — the S100 shell (Nokia Series 40 style UI for the JioPhone port)

Everything in this directory is a plain file that `os/scripts/build.sh`
overlays onto the phoneME build (it is rsync'ed to `~/.cache/s100/port` and
handed to `make` as `AMS_APPMANAGER_UI_IMPL_DIR` / `S100_PORT_DIR`). Unlike
`os/patches/*.patch` nothing here is a diff: edit, rebuild, deploy.

```
os/port/
  ams/appmanager_ui/lib.gmk        file list handed to the MIDP AMS build
  ams/appmanager_ui/classes/com/sun/midp/appmanager/
      AppManagerUIImpl.java        AMS <-> shell bridge (replaces the reference "Java MIDlets" Form)
      Shell.java                   full-screen Canvas: screen stack, popups, status/soft bars, long press
      Screen.java                  base class of every screen
      HomeScreen.java              idle screen: wallpaper, clock, Go to / Menu / Names
      MenuManager.java  MenuItem.java  MenuScreen.java   the menu tree, grid/list rendering
      Keymap.java                  MIDP key codes -> shell keys, idle shortcuts
      Messaging.java  Contacts.java  CallLog.java  SettingsMenu.java  Organiser.java  AppsMenu.java
      DialerScreen.java  TextInputScreen.java  ListScreen.java  TextViewScreen.java  Popup.java
      Theme.java  Icons.java  StatusBar.java  Clock.java  Prefs.java  SysInfo.java
      Camera.java                  viewfinder, photo/video capture, album, camera settings
      FileManager.java             phone memory / memory card browser, package installer entry
      ImageViewScreen.java         JPEG (native, scaled) and PNG/GIF viewer
      Connectivity.java            Settings > Connectivity: Bluetooth, Wi-Fi, Hotspot, USB
      Sys.java                     files, processes, camera frames: the Java side of the native helper
  ams/appmanager_ui/native/s100_native.c   KNI natives behind Sys.java (see below)
  ams/icons/                       40x40 PNG menu icons and 20x20 file icons (+ gen_icons.py, lib.gmk)
```

Two helper scripts live on the phone next to `j2me.sh` (from `os/device/`):
`s100_net.sh` (Wi-Fi/hotspot/USB/Bluetooth, driven through `wpa_cli`,
`ndc` and `setprop`) and `s100_cam.sh` (runs Qualcomm's `mm-qcamera-app`
for the camera). They are plain shell scripts and can be fixed on the
device without rebuilding.

## What it looks like / does

| Screen | Keys |
|---|---|
| **Idle** — wallpaper, big clock, date, operator line, profile | centre = Menu, left soft = Go to, right soft = Names, Call = dialled numbers, digits = dialer, Up/Down/Left/Right = My shortcuts, hold `#` = Silent, hold End = Switch off (exit to KaiOS), Menu then `*` = key lock |
| **Menu** — 3x2 icon grid (or list) | arrows, 1–9 opens item n, Select / Exit |
| **Messaging** — Create message, Inbox, Drafts, Outbox, Sent items | multitap editor; Send files to Outbox (no SMS stack in this build) |
| **Contacts** — Names, Add new, Memory status, Delete all | keypad letters jump in the list; Options: Call, Send message, Edit, Delete |
| **Log** — Missed, Received, Dialled, Clear | Dialled is fed by the dialer |
| **Settings** — Profiles, Display (menu view, wallpaper, idle text), **Connectivity** (below), Date and time (format, time zone), My shortcuts, Phone (info, memory, key map, factory reset), Exit to KaiOS | |
| **Settings > Connectivity > Wi-Fi** — on/off, status, available networks (scan, join with password), saved networks (connect, forget), details (IP, gateway, DNS, signal, MAC) | joins run in the background behind a "please wait" note |
| **Settings > Connectivity > Hotspot** — on/off, network name, WPA2/open, password, channel, connected devices | uses netd's soft AP + tethering (hostapd, dnsmasq); Internet sharing needs a mobile data connection (upstream `rmnet_data0`) |
| **Settings > Connectivity > USB** — mode (MTP, mass storage of the memory card, charging only), USB debugging on/off, cable state | sets `sys.usb.config`; MTP file transfer itself needs KaiOS's media server |
| **Settings > Connectivity > Bluetooth** — on/off (radio + firmware), phone name, visibility | pairing is not available: the Bluedroid stack runs inside KaiOS's `bluetoothd`, which only Gecko drives |
| **Organiser** — Calculator, Stopwatch, Notes | calculator: `*` cycles + − × ÷, `#` decimal, centre = equals |
| **Applications** — Collection (installed suites: Open, Details, Update, Application settings, Delete), Install application (URL), **Install from file** (pick a .jad/.jar), **File manager**, **Album**, Running applications (Foreground, End), Certificates | this is the old "Java MIDlets" app manager plus the file side |
| **File manager** — Phone memory (`/storage/emulated/0`), Memory card (when mounted), Java runtime (`/data/j2me`) | Options: Open, Details, New folder, Rename, Copy, Move, Paste here, Delete, Install (for .jad/.jar). Files open by extension: pictures → viewer (Left/Right = next/previous), text/log/xml/jad → text view, .jad/.jar → package installer, audio/video → info only (no player in Java mode) |
| **Camera** — viewfinder (~8 fps), Photo / Video modes, back/front camera, album, settings (photo size 1 MP or VGA, quality, video sound, save to phone/card, rotation, mirror, colour format) | centre = capture / start-stop recording, left soft = Options, Left/Right = mode, `*` = switch camera. Photos: `DCIM/Camera/IMG_<stamp>.jpg` (1 MP via a 5 s snapshot, VGA instantly from the preview). Video: `VID_<stamp>.avi`, Motion-JPEG 240x320 @ 10 fps, optional 48 kHz mono sound from the microphone |

Contacts, messages, notes, the call log and settings are RMS record stores
of the internal suite (`s100_*`), i.e. files under `/data/j2me/appdb/`.

## How it plugs into phoneME

`com.sun.midp.appmanager.AppManagerPeer` (unchanged) instantiates
`AppManagerUIImpl` and drives it through the `AppManagerUI` interface
(suite appended/removed, MIDlet started/exited, "show the switcher", ...).
Our implementation keeps the list of `RunningMIDletSuiteInfo`s, owns the
`Shell` canvas and forwards launch/remove/update/settings requests to
`ApplicationManager` (`MVMManager`) and `AppManagerPeer` exactly like the
reference class did. `lib.gmk` swaps only `AppManagerUIImpl.java`; the
reference `AppInfo`, `AppSettingsUIImpl`, `MIDletSelector` and
`SplashScreen` are still compiled from `midp/src/ams/appmanager_ui/reference`.

### The native helper (`native/s100_native.c`)

`Sys.java` declares a handful of KNI natives that the AMS makefile compiles
from `native/s100_native.c` (romized classes resolve natives at link time,
so the file is simply added to `SUBSYSTEM_AMS_NATIVE_FILES` in `lib.gmk`):

- directory listing (`opendir`), `stat`, mkdir/unlink/rename/copy, `statfs`;
- `exec` (fork + `sh -c`, output captured, with a timeout — it blocks the
  whole VM, so the Java side keeps it for sub-second queries), `spawn`
  (detached session, returns the pid), `alive`, `kill` (the whole process
  group). `Sys.run()` builds a polled background job on top of `spawn`;
- camera: YV12/I420/NV21/NV12 frame file → RGB ints for `Graphics.drawRGB` (rotate,
  mirror, fill-and-crop scaling), frame file → baseline 4:2:0 JPEG (own
  encoder, standard tables), and a Motion-JPEG AVI recorder on a pthread
  that re-reads the preview dump at the requested frame rate and appends an
  optional WAV as one audio chunk;
- `jpegDecode`: the IJG decoder (`USE_JPEG=true`, `JPEG_DIR=phoneME/jpeg`,
  which also makes `Image.createImage` understand JPEG) with DCT scaling
  1/2–1/8 so a 1280x960 photo is decoded straight into a 320x240 buffer.

`os/scripts/check_native.sh` builds the file as a host program
(`-DS100_HOST_TEST`) and writes a test JPEG and AVI into `os/out/`.

### How the camera works on the JioPhone

The sensors are behind Qualcomm's `mm-qcamera-daemon`; the only client we
can drive without Gecko is the factory test tool `/system/bin/mm-qcamera-app`
(this ODM build reads one key from stdin: `2`/`3` back/front preview,
dumping every 640x480 frame (planar YV12: Y, V, U planes) to
`/data/back.yuv`/`front.yuv`; `4`/`5`
take a 1280x960 snapshot to `/data/camera_snap.yuv` and exit).
`s100_cam.sh preview` keeps it running until the Java side kills the
process group; the viewfinder timer converts the dump file eight times a
second. Frames are landscape from the sensor, hence the rotation settings
(back 90°, front 270° + mirror by default).

Two small things live outside this directory:

- `midp/src/highlevelui/fb_application/reference/native/fbapp_export.c`
  (patch 0002): the End/power key is delivered to the AMS isolate as a key
  event instead of a "destroy the foreground MIDlet" request, so End on the
  idle screen does not exit to KaiOS. MIDlets still get closed by End.
- `os/device/j2me.sh`: exports `TZ` (from `appdb/s100_tz.txt`, KaiOS's
  `persist.sys.timezone`, or IST) because the static glibc binary has no
  zoneinfo and would otherwise show UTC.
- `os/device/s100_net.sh`, `os/device/s100_cam.sh`: the connectivity and
  camera backends (see above); `build.sh package` copies them next to
  `j2me.sh`.

## Limits of the font

The linux_fb putpixel backend has a single 9x14 bitmap font for every
`Font` size and style. `Theme.bold()` double-strikes text, and the idle
clock and stopwatch use `Theme.bigDigit()` (5x7 glyphs scaled up). Anything
fancier needs a real font renderer in `gxjport_text.c`.

## Working on it

```bash
# fast type check (5 s) against the last MIDP build, before the 10 min rebuild
wsl -d Ubuntu-24.04 -- bash os/scripts/check_port.sh
# host build + self test of the native helper (JPEG/AVI/exec)
wsl -d Ubuntu-24.04 -- bash os/scripts/check_native.sh
# after editing Java sources with non-ASCII text: escape it as \uXXXX
python os/scripts/asciify.py

# rebuild + package, then emulate or deploy
wsl -d Ubuntu-24.04 -- bash os/scripts/build.sh midp package
EMU_KEEP=1 EMU_SECONDS=6 bash os/scripts/emu.sh              # -> os/out/emu-N.png (idle screen)
EMU_KEEP=1 EMU_SECONDS=6 EMU_KEYS="ok" bash os/scripts/emu.sh # -> main menu
bash os/scripts/ams_stop.sh && bash os/scripts/deploy.sh && bash os/scripts/ams_start.sh
```

Adding a menu entry: return another `MenuItem` from the relevant module's
`items()` (or add a module and list it in `MenuManager.root()`), give it a
stable id (`"organiser.timer"`), and it automatically becomes available as
an idle-screen shortcut. Icons: add a function to `gen_icons.py`, run it,
reference the name (`"timer"` → `s100_timer.png`) in the `MenuItem`.

Sources must stay Java 1.3 / CLDC 1.1: no generics, no autoboxing, no
`String.split`, `StringBuilder` or `String.format`; use `\uXXXX` escapes for
non-ASCII (the build compiles with the default encoding).
