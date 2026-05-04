"""Mobile entry point — launches the Kivy / KivyMD UI of X-RavScan.

This is the *Android* (and desktop-debug) entry point. The Windows desktop
build still uses ``main.py`` with CustomTkinter unchanged. Both share the
same ``x_ravscan.core`` package.

Buildozer reads ``packaging/android/buildozer.spec``, which sets
``main.py = mobile_main.py``.
"""

from __future__ import annotations

import os
import sys
import traceback
from pathlib import Path


# ---------------------------------------------------------------------------
# Crash handler — write traceback to a known file so we can grep it on Android
# (``adb logcat`` is the canonical view, but a file is easier to share).
# ---------------------------------------------------------------------------


def _install_excepthook() -> None:
    def _hook(exc_type, exc, tb) -> None:
        try:
            log = _logs_dir() / "mobile_crash.txt"
            log.parent.mkdir(parents=True, exist_ok=True)
            with log.open("a", encoding="utf-8") as fh:
                fh.write("\n=== uncaught exception ===\n")
                traceback.print_exception(exc_type, exc, tb, file=fh)
        except Exception:  # noqa: BLE001
            pass
        traceback.print_exception(exc_type, exc, tb, file=sys.__stderr__)

    sys.excepthook = _hook


def _logs_dir() -> Path:
    """Pick a writable log dir on each platform.

    On Android we use the app's private storage (``getFilesDir``), which is
    exposed via the ``ANDROID_PRIVATE`` env var or via the runtime check.
    """
    if "ANDROID_PRIVATE" in os.environ:
        return Path(os.environ["ANDROID_PRIVATE"]) / "logs"
    try:
        from x_ravscan.core.config import log_path

        return log_path().parent
    except Exception:  # noqa: BLE001
        return Path.home() / ".X-RavScan" / "logs"


def main() -> int:
    _install_excepthook()

    # Lazy imports so the crash handler is registered first.
    from x_ravscan.core import providers_manager
    from x_ravscan.core.database import Database
    from x_ravscan.core.scanner import configure_event_loop
    from x_ravscan.utils.logger import init_logging
    from x_ravscan.ui_mobile.app import XRavScanMobileApp

    init_logging()
    db = Database()
    providers_manager.bootstrap(db)
    configure_event_loop()

    # Restore the user's saved language before any UI text is rendered.
    from x_ravscan.i18n import set_language

    saved = db.get_setting("ui.language")
    if saved:
        set_language(saved)

    XRavScanMobileApp(db).run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
