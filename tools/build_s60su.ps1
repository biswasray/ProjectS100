#requires -Version 5.1
<#
.SYNOPSIS
  Build the setuid-root helper tools\s60su (ARM) from s60su.c via WSL.

.DESCRIPTION
  s60su is a tiny static ARM binary baked into the rooted boot ramdisk to give a
  uid=0 shell. The compiled binary is not committed; this rebuilds it. Requires
  WSL2 with an Ubuntu distro and the ARM cross-compiler:

      wsl --install -d Ubuntu-24.04
      (setup.ps1 -WithDevTools installs gcc-arm-linux-gnueabi for you)

.PARAMETER Distro
  WSL distro name (default: Ubuntu-24.04).
#>
[CmdletBinding()]
param([string]$Distro = "Ubuntu-24.04")

$Tools = $PSScriptRoot
if (-not (Get-Command wsl -ErrorAction SilentlyContinue)) {
    Write-Host "[x] wsl not found. Enable WSL2 + install a distro first (see HOW_TO_START.md #8)." -ForegroundColor Red
    exit 1
}

# Translate the Windows tools dir to a /mnt/<drive>/... path for WSL.
$wslTools = (& wsl -d $Distro -- wslpath -a "$Tools") 2>$null
if (-not $wslTools) {
    Write-Host "[x] Could not reach distro '$Distro'. Is it installed and started?" -ForegroundColor Red
    exit 1
}
$wslTools = $wslTools.Trim()

Write-Host "[*] Building s60su in $Distro ..." -ForegroundColor Cyan
$cmd = "cd '$wslTools' && arm-linux-gnueabi-gcc -static -O2 -o s60su s60su.c && arm-linux-gnueabi-strip s60su && ls -la s60su"
& wsl -d $Distro -u root -- bash -lc "$cmd"
if ($LASTEXITCODE -eq 0 -and (Test-Path (Join-Path $Tools "s60su"))) {
    Write-Host "[+] Built tools\s60su" -ForegroundColor Green
} else {
    Write-Host "[x] Build failed. Ensure gcc-arm-linux-gnueabi is installed:" -ForegroundColor Red
    Write-Host "    wsl -d $Distro -u root -- apt-get install -y gcc-arm-linux-gnueabi"
    exit 1
}
