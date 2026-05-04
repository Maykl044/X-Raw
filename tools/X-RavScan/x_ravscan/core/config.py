"""Runtime configuration & paths.

All paths are computed lazily so the module is import-safe in any
environment, including frozen builds (PyInstaller / Nuitka one-file).
"""

from __future__ import annotations

import os
import sys
from pathlib import Path


APP_NAME = "X-RavScan"
APP_VERSION = "1.0.0"


def _is_frozen() -> bool:
    return getattr(sys, "frozen", False)


def app_root() -> Path:
    """Directory containing bundled resources (data/, assets/)."""
    if _is_frozen():
        # PyInstaller one-file extracts to _MEIPASS; Nuitka --onefile sets
        # __compiled__ but keeps cwd next to the binary.
        meipass = getattr(sys, "_MEIPASS", None)
        if meipass:
            return Path(meipass) / "x_ravscan"
        return Path(sys.executable).parent / "x_ravscan"
    return Path(__file__).resolve().parent.parent


def user_data_dir() -> Path:
    """Per-user writable directory for DB, logs, cached ranges, exports."""
    if sys.platform.startswith("win"):
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    p = base / APP_NAME
    p.mkdir(parents=True, exist_ok=True)
    return p


def db_path() -> Path:
    return user_data_dir() / "x_ravscan.sqlite3"


def log_path() -> Path:
    logs = user_data_dir() / "logs"
    logs.mkdir(parents=True, exist_ok=True)
    return logs / "x_ravscan.log"


def ranges_dir() -> Path:
    """Bundled seed ranges shipped with the app."""
    return app_root() / "data" / "ranges"


def cache_dir() -> Path:
    """Writable cache for fresh range downloads."""
    p = user_data_dir() / "ranges_cache"
    p.mkdir(parents=True, exist_ok=True)
    return p


def export_dir() -> Path:
    p = user_data_dir() / "exports"
    p.mkdir(parents=True, exist_ok=True)
    return p


# --- Scan defaults ---------------------------------------------------------

DEFAULT_TCP_TIMEOUT = 4.0
DEFAULT_TLS_TIMEOUT = 6.0
DEFAULT_CONCURRENCY = 512
DEFAULT_PORT = 443
DEFAULT_USER_AGENT = f"{APP_NAME}/{APP_VERSION} (+https://github.com/Maykl044/X-Raw)"


# --- Theme (Modern Dark Cyberpunk) -----------------------------------------

THEME = {
    "bg": "#0d1117",
    "panel": "#11161d",
    "panel_alt": "#161b22",
    "border": "#1f2630",
    "text": "#e6edf3",
    "text_dim": "#8b949e",
    "accent": "#00ff9c",       # toxic green
    "accent_alt": "#1f8cff",   # electric blue
    "success": "#3fb950",
    "warning": "#d29922",
    "danger": "#f85149",
    "info": "#58a6ff",
}
