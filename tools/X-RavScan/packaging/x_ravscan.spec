# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for X-RavScan.

Build:
    pyinstaller --noconfirm packaging/x_ravscan.spec

Output:
    dist/X-RavScan/X-RavScan.exe   (one-folder, fast startup)
    dist/X-RavScan-onefile/...     (one-file, slower start, simpler delivery)
"""

import sys
from pathlib import Path

block_cipher = None
PROJECT_ROOT = Path(SPECPATH).resolve().parent
sys.path.insert(0, str(PROJECT_ROOT))

# Bundle data files: provider seed JSON, IP-range .txt, optional binaries.
datas = [
    (str(PROJECT_ROOT / "x_ravscan" / "data" / "seed_providers.json"),
     "x_ravscan/data"),
    (str(PROJECT_ROOT / "x_ravscan" / "data" / "ranges"),
     "x_ravscan/data/ranges"),
    (str(PROJECT_ROOT / "x_ravscan" / "data" / "resellers.txt"),
     "x_ravscan/data"),
]

# Optional binaries (subfinder etc.) — bundled if present.
bin_dir = PROJECT_ROOT / "x_ravscan" / "bin"
binaries = []
if bin_dir.exists():
    for f in bin_dir.iterdir():
        if f.is_file():
            binaries.append((str(f), "x_ravscan/bin"))

hiddenimports = [
    "customtkinter",
    "matplotlib.backends.backend_tkagg",
    "PIL.ImageTk",
    "tkinter",
]

a = Analysis(
    [str(PROJECT_ROOT / "main.py")],
    pathex=[str(PROJECT_ROOT)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    excludes=["tests"],
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="X-RavScan",
    icon=str(PROJECT_ROOT / "x_ravscan" / "assets" / "x_ravscan.ico") if (PROJECT_ROOT / "x_ravscan" / "assets" / "x_ravscan.ico").exists() else None,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name="X-RavScan",
)
