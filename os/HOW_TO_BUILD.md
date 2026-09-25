# Build and deploy the current os/port shell changes

## Context
### Example

 The uncommitted changes (Camera, MediaPlayer, Screen, SettingsMenu, Shell, Sys .java under
`os/port/ams/appmanager_ui/`) are S100 shell Java only: no phoneME patch, no pcsl/cldc change.
 That means only the `midp` + `package` stages need to run, then a deploy to the phone.

## Steps (from Git Bash in the repo root)

 1. Fast type check (~5 s, catches Java 1.3 / CLDC API misuse):
    `MSYS_NO_PATHCONV=1 wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/Biswasray/Documents/Projects/ProjectS100/os/scripts/check_port.sh`
 2. Rebuild MIDP + shell and package (~10 min; the midp stage is never skipped, and it wipes stale
    classes itself):
    `MSYS_NO_PATHCONV=1 wsl -d Ubuntu-24.04 -- bash /mnt/c/Users/Biswasray/Documents/Projects/ProjectS100/os/scripts/build.sh midp package`
    Look for "ROMizing failed" in the output; that means the build failed even if make says it succeeded.
 3. Stop the running OS so `runMidlet` isn't busy: `bash os/scripts/ams_stop.sh`
    (runs `stop s100`), then wait about 15 s for adb.
 4. Push and restart: `bash os/scripts/deploy.sh --run`
 5. Optional check: `bash os/scripts/screenshot.sh` to grab the framebuffer as a PNG.

## Verification

 - check_port prints [+] ok: N classes
 - build ends with no "ROMizing failed", and `os/out/j2me/bin/runMidlet` has a new timestamp
 - the phone comes back to the S100 home screen, and the changed screens (camera, mediaplayer,settings) behave as expected