# Nuitka build helper (Windows 10/11, MSVC or MinGW64 toolchain).
# Produces a single self-contained X-RavScan.exe (~80-120 MB).
#
# Usage:
#     cd tools/X-RavScan
#     powershell -ExecutionPolicy Bypass -File packaging/build_nuitka.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path "packaging/.venv")) {
    Write-Host "[X-RavScan] creating venv..."
    python -m venv packaging/.venv
}
. "packaging/.venv/Scripts/Activate.ps1"

Write-Host "[X-RavScan] installing dependencies..."
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python -m pip install nuitka ordered-set zstandard

$icon = "x_ravscan/assets/x_ravscan.ico"
$iconArg = ""
if (Test-Path $icon) {
    $iconArg = "--windows-icon-from-ico=$icon"
}

Write-Host "[X-RavScan] running nuitka..."
python -m nuitka `
    --standalone --onefile `
    --enable-plugin=tk-inter `
    --include-data-dir="x_ravscan/data=x_ravscan/data" `
    --include-package=customtkinter `
    --include-package=matplotlib `
    --windows-disable-console `
    --output-dir=dist/nuitka `
    --output-filename=X-RavScan.exe `
    $iconArg `
    main.py

Write-Host "[X-RavScan] done. Output: dist/nuitka/X-RavScan.exe"
