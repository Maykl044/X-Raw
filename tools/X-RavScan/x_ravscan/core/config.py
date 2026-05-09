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


# --- Theme (Deep Midnight Glass — Apple HIG aesthetic) ---------------------
#
# Backdrop: a deep blue gradient (#070a18 → #0a1230 → #11183c) painted onto
# a Tk Canvas behind every tab, with three soft blurred radial blobs to
# imitate the iOS / iPadOS abstract background.
#
# Glass cards: rgba(255,255,255,0.05) fill + 1 px rgba(255,255,255,0.10)
# border + 20 px corner radius.  Tk has no real backdrop-filter so we
# approximate by layering near-white tones over the gradient.
#
# Accent palette is intentionally muted — soft sky-blue + violet —
# matching the iOS 26 aesthetic the user requested.

THEME = {
    # base gradient (Canvas backdrop)
    "bg":            "#070a18",
    "bg_grad_top":   "#070a18",
    "bg_grad_mid":   "#0a1230",
    "bg_grad_bot":   "#11183c",

    # legacy aliases (still referenced by older widgets)
    "panel":         "#10162e",
    "panel_alt":     "#161d3a",

    # frosted-glass surfaces (approximate alpha ≈ 0.05 over gradient)
    "glass":         "#161c33",
    "glass_alt":     "#1c2440",
    "glass_hi":      "#252e54",   # hover / highlighted glass
    "glass_strong":  "#2b3563",   # active / pressed

    # 1 px hairline edges (≈ rgba(255,255,255,0.10/0.20))
    "border":        "#2a3357",
    "border_hi":     "#41507f",

    # typography
    "text":          "#eaecf4",
    "text_dim":      "#9aa3c0",
    "text_muted":    "#6b7595",

    # accents — soft Apple-like palette
    "accent":        "#7aa9ff",   # primary sky-blue
    "accent_alt":    "#a98bff",   # secondary lavender / violet
    "accent_pink":   "#ff7ad9",   # decorative
    "accent_mint":   "#46d58e",   # success / "fast"

    # semantic
    "success":       "#46d58e",
    "warning":       "#f6c453",
    "danger":        "#ff8888",
    "info":          "#7aa9ff",

    # corner radii (Apple HIG ≈ 16–24)
    "radius":        20,
    "radius_pill":   28,
    "radius_chip":   12,
}
