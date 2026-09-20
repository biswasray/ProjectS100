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
  ams/icons/                       40x40 PNG menu icons (+ gen_icons.py that draws them, lib.gmk)
```

## What it looks like / does

| Screen | Keys |
|---|---|
| **Idle** — wallpaper, big clock, date, operator line, profile | centre = Menu, left soft = Go to, right soft = Names, Call = dialled numbers, digits = dialer, Up/Down/Left/Right = My shortcuts, hold `#` = Silent, hold End = Switch off (exit to KaiOS), Menu then `*` = key lock |
| **Menu** — 3x2 icon grid (or list) | arrows, 1–9 opens item n, Select / Exit |
| **Messaging** — Create message, Inbox, Drafts, Outbox, Sent items | multitap editor; Send files to Outbox (no SMS stack in this build) |
| **Contacts** — Names, Add new, Memory status, Delete all | keypad letters jump in the list; Options: Call, Send message, Edit, Delete |
| **Log** — Missed, Received, Dialled, Clear | Dialled is fed by the dialer |
| **Settings** — Profiles, Display (menu view, wallpaper, idle text), Date and time (format, time zone), My shortcuts, Phone (info, memory, key map, factory reset), Exit to KaiOS | |
| **Organiser** — Calculator, Stopwatch, Notes | calculator: `*` cycles + − × ÷, `#` decimal, centre = equals |
| **Applications** — Collection (installed suites: Open, Details, Update, Application settings, Delete), Install application, Running applications (Foreground, End), Certificates | this is the old "Java MIDlets" app manager |

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

Two small things live outside this directory:

- `midp/src/highlevelui/fb_application/reference/native/fbapp_export.c`
  (patch 0002): the End/power key is delivered to the AMS isolate as a key
  event instead of a "destroy the foreground MIDlet" request, so End on the
  idle screen does not exit to KaiOS. MIDlets still get closed by End.
- `os/device/j2me.sh`: exports `TZ` (from `appdb/s100_tz.txt`, KaiOS's
  `persist.sys.timezone`, or IST) because the static glibc binary has no
  zoneinfo and would otherwise show UTC.

## Limits of the font

The linux_fb putpixel backend has a single 9x14 bitmap font for every
`Font` size and style. `Theme.bold()` double-strikes text, and the idle
clock and stopwatch use `Theme.bigDigit()` (5x7 glyphs scaled up). Anything
fancier needs a real font renderer in `gxjport_text.c`.

## Working on it

```bash
# fast type check (5 s) against the last MIDP build, before the 10 min rebuild
wsl -d Ubuntu-24.04 -- bash os/scripts/check_port.sh

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
