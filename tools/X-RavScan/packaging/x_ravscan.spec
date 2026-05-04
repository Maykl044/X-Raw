# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for X-RavScan.

Build:
    pyinstaller --noconfirm packaging/x_ravscan.spec

Output:
    dist/X-RavScan/X-RavScan.exe   (one-folder, fast startup)
"""

import os
import sys
from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

# When PyInstaller evaluates a spec it sets ``SPECPATH`` to the spec directory.
# ``__file__`` is unset for spec files, so we rely on SPECPATH/argv.
PROJECT_ROOT = Path(SPECPATH).resolve().parent
sys.path.insert(0, str(PROJECT_ROOT))

# Allow toggling console mode via environment variable (used by CI for the
# debug-build matrix entry).  Defaults to a windowed app.
CONSOLE_MODE = os.environ.get("X_RAVSCAN_CONSOLE", "0") == "1"

# ---------------------------------------------------------------------------
# Data files
# ---------------------------------------------------------------------------

datas = [
    (str(PROJECT_ROOT / "x_ravscan" / "data" / "seed_providers.json"),
     "x_ravscan/data"),
    (str(PROJECT_ROOT / "x_ravscan" / "data" / "ranges"),
     "x_ravscan/data/ranges"),
    (str(PROJECT_ROOT / "x_ravscan" / "data" / "resellers.txt"),
     "x_ravscan/data"),
    # i18n locale JSONs (en / ru / tk)
    (str(PROJECT_ROOT / "x_ravscan" / "i18n" / "locales"),
     "x_ravscan/i18n/locales"),
]

# CustomTkinter / matplotlib / PIL ship JSON theme files / fonts that are not
# auto-detected by the dependency graph. ``collect_data_files`` walks the
# package and returns every non-py resource.
datas += collect_data_files("customtkinter")
datas += collect_data_files("matplotlib")
datas += collect_data_files("PIL")

# ---------------------------------------------------------------------------
# Binaries (optional subfinder etc.)
# ---------------------------------------------------------------------------

binaries = []
bin_dir = PROJECT_ROOT / "x_ravscan" / "bin"
if bin_dir.exists():
    for f in bin_dir.iterdir():
        if f.is_file():
            binaries.append((str(f), "x_ravscan/bin"))

# ---------------------------------------------------------------------------
# Hidden imports
# ---------------------------------------------------------------------------

hiddenimports = [
    "customtkinter",
    "matplotlib.backends.backend_tkagg",
    "PIL.ImageTk",
    "tkinter",
    "_tkinter",
]
hiddenimports += collect_submodules("customtkinter")

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------

block_cipher = None

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

icon_path = PROJECT_ROOT / "x_ravscan" / "assets" / "x_ravscan.ico"

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="X-RavScan",
    icon=str(icon_path) if icon_path.exists() else None,
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    # UPX is fragile on Windows for newer PyInstaller (often produces broken
    # binaries flagged by AV). Disable.
    upx=False,
    console=CONSOLE_MODE,
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
    upx=False,
    upx_exclude=[],
    name="X-RavScan",
)
