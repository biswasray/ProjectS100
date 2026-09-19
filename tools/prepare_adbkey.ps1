#requires -Version 5.1
<#
.SYNOPSIS
  Generate firmware\adbkey.pub from this PC's ADB key (creating one if needed).

.DESCRIPTION
  make_root_boot.py bakes an ADB public key into the rooted boot as /adb_keys so
  adb authorises this host with no on-device prompt. That key is your credential
  and is NOT committed to the repo; this script (re)produces it locally.

  It copies %USERPROFILE%\.android\adbkey.pub into firmware\adbkey.pub, running
  `adb keygen` first if you don't have a key yet.
#>
[CmdletBinding()]
param()

$Tools = $PSScriptRoot
$Root  = Split-Path -Parent $Tools
$fwDir = Join-Path $Root "firmware"
$dst   = Join-Path $fwDir "adbkey.pub"
$src   = Join-Path $env:USERPROFILE ".android\adbkey.pub"

function Find-Adb {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $p = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $p) { return $p }
    return $null
}

New-Item -ItemType Directory -Force -Path $fwDir | Out-Null

if (-not (Test-Path $src)) {
    Write-Host "[*] No ADB key at $src - generating one ..." -ForegroundColor Cyan
    $adb = Find-Adb
    if (-not $adb) {
        Write-Host "[x] adb not found. Install Android platform-tools, then re-run." -ForegroundColor Red
        exit 1
    }
    New-Item -ItemType Directory -Force -Path (Split-Path $src) | Out-Null
    & $adb keygen (Join-Path $env:USERPROFILE ".android\adbkey") | Out-Null
}

if (-not (Test-Path $src)) {
    Write-Host "[x] Still no $src after keygen." -ForegroundColor Red
    exit 1
}

Copy-Item -Force $src $dst
Write-Host "[+] Wrote $dst" -ForegroundColor Green
Write-Host "    (gitignored - it is your credential and stays local.)"
