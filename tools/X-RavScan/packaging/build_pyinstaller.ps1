# PyInstaller build helper (PowerShell, Windows 10/11)
# ---------------------------------------------------------------
# 1) creates a virtualenv ./packaging/.venv
# 2) installs runtime + build dependencies
# 3) runs PyInstaller with packaging/x_ravscan.spec
#
# Usage:
#     cd tools/X-RavScan
#     powershell -ExecutionPolicy Bypass -File packaging/build_pyinstaller.ps1

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
python -m pip install pyinstaller

Write-Host "[X-RavScan] running pyinstaller..."
python -m PyInstaller --noconfirm --clean packaging/x_ravscan.spec

Write-Host "[X-RavScan] done. Output: dist/X-RavScan/X-RavScan.exe"
