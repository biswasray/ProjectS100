#requires -Version 5.1
<#
.SYNOPSIS
  One-command environment setup for the ProjectS100 JioPhone LF-2403N EDL/root toolkit.

.DESCRIPTION
  Installs dependencies and tools, clones bkerler `edl` if missing, and applies the
  ProjectS100 edl source patches. Idempotent and safe to re-run. See HOW_TO_START.md.

  Manual steps this script CANNOT do: assign the WinUSB driver in Zadig, and put
  the phone into EDL (both are GUI/physical). It prints instructions for those.

.PARAMETER WithDevTools
  Also set up WSL2 Ubuntu + the ARM cross-compiler (only needed to rebuild s60su).

.PARAMETER SkipDrivers
  Skip the Zadig download / WinUSB reminder.

.PARAMETER Python
  Explicit path to a Python 3.10+ interpreter to use.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\setup.ps1
#>
[CmdletBinding()]
param(
    [switch]$WithDevTools,
    [switch]$SkipDrivers,
    [string]$Python
)

$ErrorActionPreference = 'Continue'
$Tools = $PSScriptRoot
$Root  = Split-Path -Parent $Tools

function Info($m){ Write-Host "[*] $m" -ForegroundColor Cyan }
function Ok($m)  { Write-Host "[+] $m" -ForegroundColor Green }
function Warn($m){ Write-Host "[!] $m" -ForegroundColor Yellow }
function Fail($m){ Write-Host "[x] $m" -ForegroundColor Red }
function Have($name){ [bool](Get-Command $name -ErrorAction SilentlyContinue) }

$notes = New-Object System.Collections.Generic.List[string]

function Winget-Install($id){
    if(-not (Have winget)){ Warn "winget not available - install '$id' manually."; return $false }
    Info "winget install $id ..."
    winget install --id $id -e --accept-package-agreements --accept-source-agreements --silent | Out-Null
    if($LASTEXITCODE -eq 0){ return $true }
    Warn "winget failed for $id (exit $LASTEXITCODE)."; return $false
}

Write-Host ""
Info "ProjectS100 setup  (root: $Root)"
Write-Host ""

# --------------------------------------------------------------- 1. Python --
function Find-Python([string]$Pref){
    $cands = @()
    if($Pref){ $cands += $Pref }
    $cands += "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe"
    if(Have py){
        $p = (& py -3.12 -c "import sys;print(sys.executable)" 2>$null)
        if($p){ $cands += $p }
    }
    if(Have python){ $cands += (Get-Command python).Source }
    foreach($c in $cands){
        if($c -and (Test-Path $c)){
            & $c -c "import sys; sys.exit(0 if sys.version_info[:2] >= (3,10) else 1)" 2>$null
            if($LASTEXITCODE -eq 0){ return (Resolve-Path $c).Path }
        }
    }
    return $null
}

$PY = Find-Python $Python
if(-not $PY){
    Info "Python 3.10+ not found; installing Python 3.12 ..."
    Winget-Install "Python.Python.3.12" | Out-Null
    $PY = Find-Python $Python
}
if(-not $PY){ Fail "Python not available. Install Python 3.12 and re-run."; exit 1 }
$pyver = & $PY -c "import sys;print('%d.%d.%d'%sys.version_info[:3])"
Ok "Python: $PY ($pyver)"

# ------------------------------------------------------------------ 2. Git --
if(Have git){ Ok "Git present" } else {
    Info "Git not found; installing ..."
    if(-not (Winget-Install "Git.Git")){ $notes.Add("Install Git manually if you need to clone edl.") }
}

# ------------------------------------------------------------------ 3. edl --
$edlDir = Join-Path $Tools "edl"
if(Test-Path (Join-Path $edlDir "edl.py")){
    Ok "edl present ($edlDir)"
    if(Have git){ git -C $edlDir submodule update --init --recursive 2>$null | Out-Null }
} else {
    if(Have git){
        Info "Cloning bkerler/edl ..."
        git clone --recurse-submodules https://github.com/bkerler/edl.git $edlDir
        if(-not (Test-Path (Join-Path $edlDir "edl.py"))){ Fail "edl clone failed."; exit 1 }
        Ok "edl cloned"
    } else {
        Fail "edl missing and git unavailable - install Git or copy tools/edl in place."; exit 1
    }
}

# --------------------------------------------------- 4. Apply edl patches --
Info "Applying ProjectS100 edl patches ..."
& $PY (Join-Path $Tools "apply_edl_patch.py") $edlDir
if($LASTEXITCODE -ne 0){ Warn "edl patch step reported a problem (see above)." }

# ----------------------------------------------------------- 5. pip deps ---
Info "Installing Python dependencies ..."
& $PY -m pip install --quiet --upgrade pip | Out-Null
$reqs = Join-Path $edlDir "requirements.txt"
if(Test-Path $reqs){ & $PY -m pip install --quiet -r $reqs }
& $PY -m pip install --quiet libusb-package gdown
# quick backend sanity
$backend = & $PY -c "import libusb_package, usb.backend.libusb1 as l; print(bool(l.get_backend(find_library=libusb_package.find_library)))" 2>$null
if($backend -match "True"){ Ok "Python deps OK (WinUSB/libusb backend available)" }
else { Warn "libusb-package backend check did not return True - verify 'pip show libusb-package'." }

# ---------------------------------------------------- 6. Zadig / WinUSB ----
if(-not $SkipDrivers){
    $zadig = Join-Path $Tools "zadig.exe"
    if(-not (Test-Path $zadig)){
        Info "Downloading Zadig ..."
        try{ Invoke-WebRequest -UseBasicParsing -Uri "https://github.com/pbatard/libwdi/releases/download/v1.5.1/zadig-2.9.exe" -OutFile $zadig }
        catch { Warn "Zadig download failed - get it from https://zadig.akeo.ie" }
    }
    if(Test-Path $zadig){ Ok "Zadig ready ($zadig)" }
    $notes.Add("DRIVER (manual): put phone in EDL, run tools\zadig.exe -> Options: List All Devices -> select 'QHSUSB__BULK' (05C6:9008) -> WinUSB -> Install/Replace Driver.")
}

# ------------------------------------------------------- 7. UsbDk guard ----
$usbdk = Get-Service -Name "UsbDk" -ErrorAction SilentlyContinue
if(-not $usbdk){
    $usbdk = Get-ChildItem "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall","HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall" -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue } | Where-Object { $_.DisplayName -match 'UsbDk' } | Select-Object -First 1
}
if($usbdk){
    Warn "UsbDk is installed - it BREAKS this toolchain (conflicts with usbipd and destabilises USB)."
    $notes.Add("REMOVE UsbDk: winget uninstall daynix.UsbDk  (or Apps -> UsbDk -> Uninstall), then reboot.")
} else { Ok "UsbDk not present (good)" }

# --------------------------------------------------------------- 8. adb ----
$haveAdb = $false
if(Have adb){ Ok "adb present"; $haveAdb = $true }
elseif(Test-Path "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"){ Ok "adb present (Android SDK)"; $haveAdb = $true }
else { $notes.Add("adb not found - get platform-tools: https://developer.android.com/tools/releases/platform-tools (needed after rooting, and for prepare_adbkey).") }

# ---------------------------------------------- 8b. ADB key (credential) ---
# Generate firmware\adbkey.pub locally (gitignored, never committed).
if($haveAdb){
    Info "Preparing ADB key (firmware\adbkey.pub) ..."
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Tools "prepare_adbkey.ps1")
} else {
    $notes.Add("Run tools\prepare_adbkey.ps1 (after installing adb) to generate firmware\adbkey.pub.")
}

# ---------------------------------------------- 8c. firmware artifacts -----
if(-not (Test-Path (Join-Path $Root "firmware\prog_emmc_firehose_8909_ddr.mbn"))){
    $notes.Add("Firmware not present - download the LF-2403N firmware zip (see README.md), then: python tools\fetch_firmware.py -Zip <zip>.")
}

# ------------------------------------------------- 9. -WithDevTools (opt) --
if($WithDevTools){
    Info "Setting up WSL2 Ubuntu + ARM cross-compiler (optional dev tools) ..."
    if(Have wsl){
        wsl --install -d Ubuntu-24.04 --no-launch 2>$null | Out-Null
        wsl -d Ubuntu-24.04 -u root -- bash -lc "export DEBIAN_FRONTEND=noninteractive; apt-get update -qq && apt-get install -y -qq gcc-arm-linux-gnueabi" 2>$null
        if($LASTEXITCODE -eq 0){
            Ok "WSL Ubuntu + gcc-arm-linux-gnueabi ready"
            Info "Building tools\s60su ..."
            & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Tools "build_s60su.ps1")
        }
        else { Warn "WSL/cross-compiler setup incomplete - see HOW_TO_START.md #8." }
    } else { Warn "wsl not available - enable WSL2 first (see HOW_TO_START.md #8)." }
} else {
    if(-not (Test-Path (Join-Path $Tools "s60su"))){
        $notes.Add("s60su binary not built - run setup.ps1 -WithDevTools, or tools\build_s60su.ps1 (needs WSL + ARM gcc).")
    }
}

# ------------------------------------------------------------- summary -----
Write-Host ""
Ok "Setup complete."
if($notes.Count -gt 0){
    Write-Host ""
    Info "Action items:"
    foreach($n in $notes){ Write-Host "    - $n" -ForegroundColor Yellow }
}
Write-Host ""
Info "Next: put phone in EDL (power off, hold * and #, plug USB, screen black), then:"
Write-Host "    cd `"$Tools`""
Write-Host "    set LDR=$Root\firmware\prog_emmc_firehose_8909_ddr.mbn"
Write-Host "    .\edl.cmd printgpt --loader=%LDR%"
Write-Host ""
