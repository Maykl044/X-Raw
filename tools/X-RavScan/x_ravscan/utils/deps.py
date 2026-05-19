"""Bootstrap missing third-party dependencies on first run.

The application ships with a ``requirements.txt`` next to ``main.py``. When
launched from source we attempt a best-effort install of any missing modules
so non-developers can simply double-click ``main.py``. When frozen
(PyInstaller / Nuitka) all deps are bundled and this is a no-op.
"""

from __future__ import annotations

import importlib
import subprocess
import sys
from pathlib import Path
from typing import Iterable, List


# (import_name, pip_spec). We only require the *imports*, but if a user's
# environment has the import missing we'll request the (matching) pip name.
REQUIRED = [
    ("customtkinter", "customtkinter>=5.2.0"),
    ("matplotlib", "matplotlib>=3.7"),
    ("requests", "requests>=2.31"),
    ("cryptography", "cryptography>=41.0"),
]


def is_frozen() -> bool:
    return getattr(sys, "frozen", False)


def _missing(packages: Iterable) -> List[str]:
    out: List[str] = []
    for import_name, pip_spec in packages:
        try:
            importlib.import_module(import_name)
        except ImportError:
            out.append(pip_spec)
    return out


def _pip_install(specs: List[str]) -> bool:
    cmd = [sys.executable, "-m", "pip", "install", "--upgrade", *specs]
    print(f"[X-RavScan] installing: {' '.join(specs)}")
    try:
        subprocess.run(cmd, check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError) as e:
        print(f"[X-RavScan] pip install failed: {e}", file=sys.stderr)
        return False


def ensure_deps(required: Iterable = REQUIRED, *, allow_install: bool = True) -> bool:
    """Returns True if every required module is importable after the call."""
    if is_frozen():
        return True
    missing = _missing(required)
    if not missing:
        return True
    if not allow_install:
        return False
    if not _pip_install(missing):
        return False
    return not _missing(required)


def find_requirements() -> Path:
    """Locate ``requirements.txt`` next to ``main.py`` (parent of x_ravscan)."""
    here = Path(__file__).resolve().parent
    for parent in (here.parent, here.parent.parent):
        cand = parent / "requirements.txt"
        if cand.exists():
            return cand
    return here.parent.parent / "requirements.txt"
