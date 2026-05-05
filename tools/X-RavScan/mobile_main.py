"""Mobile entry point — launches the Kivy / KivyMD UI of X-RavScan.

This is the *Android* (and desktop-debug) entry point. The Windows desktop
build still uses ``main.py`` with CustomTkinter unchanged. Both share the
same ``x_ravscan.core`` package.

Buildozer reads ``packaging/android/buildozer.spec``, which sets
``main.py = mobile_main.py``.

Mobile-specific behaviour:
    * Heavy initialisation (DB, provider seed, asyncio policy) is moved to a
      background thread that runs *after* the Kivy window is up — otherwise
      Android shows a black screen for 10-15s and the user thinks it crashed.
    * On Android we route the writable directory to ``ANDROID_PRIVATE``
      (the app's private files dir, always writable) so SQLite + logs work.
    * The crash handler writes a traceback to ``mobile_crash.txt`` in the
      same writable dir, so we can grab it via Files / adb if anything blows.
"""

from __future__ import annotations

import os
import sys
import traceback
from pathlib import Path


# ---------------------------------------------------------------------------
# Android-aware writable directory.  python-for-android sets ANDROID_PRIVATE
# to ``/data/data/<pkg>/files`` (always writable from inside the app).  We
# point XDG_DATA_HOME at it BEFORE any module-level import that might pick
# up the path.
# ---------------------------------------------------------------------------


def _setup_android_dirs() -> Path:
    """Return a writable base dir and configure env vars accordingly."""
    if "ANDROID_PRIVATE" in os.environ:
        base = Path(os.environ["ANDROID_PRIVATE"])
    elif "ANDROID_ARGUMENT" in os.environ:
        base = Path(os.environ["ANDROID_ARGUMENT"])
    else:
        # Desktop-debug — let config.user_data_dir() pick its normal path.
        return Path.home()
    base.mkdir(parents=True, exist_ok=True)
    # Force config.user_data_dir() to use this base.
    os.environ["XDG_DATA_HOME"] = str(base)
    os.environ.setdefault("HOME", str(base))
    return base


_BASE_DIR = _setup_android_dirs()


# ---------------------------------------------------------------------------
# Crash handler — *must* be installed before anything else so a stray import
# error is captured.
# ---------------------------------------------------------------------------


def _crash_log_path() -> Path:
    return _BASE_DIR / "X-RavScan" / "logs" / "mobile_crash.txt"


def _install_excepthook() -> None:
    def _hook(exc_type, exc, tb) -> None:
        try:
            log = _crash_log_path()
            log.parent.mkdir(parents=True, exist_ok=True)
            with log.open("a", encoding="utf-8") as fh:
                fh.write("\n=== uncaught exception ===\n")
                traceback.print_exception(exc_type, exc, tb, file=fh)
        except Exception:  # noqa: BLE001
            pass
        try:
            traceback.print_exception(exc_type, exc, tb, file=sys.__stderr__)
        except Exception:  # noqa: BLE001
            pass

    sys.excepthook = _hook


def main() -> int:
    _install_excepthook()

    # Lazy imports so the crash handler is registered first.
    from kivy.clock import Clock

    from x_ravscan.core.database import Database
    from x_ravscan.core.scanner import configure_event_loop
    from x_ravscan.utils.logger import init_logging, get_logger
    from x_ravscan.ui_mobile.app import XRavScanMobileApp

    init_logging()
    log = get_logger("mobile")
    log.info("X-RavScan mobile boot — base dir = %s", _BASE_DIR)

    # The DB is cheap to construct (just opens the SQLite file) so we keep
    # this on the main thread.  Provider seeding is deferred.
    db = Database()
    configure_event_loop()

    # Restore the user's saved language before any UI text is rendered.
    from x_ravscan.i18n import set_language

    saved = db.get_setting("ui.language")
    if saved:
        set_language(saved)

    app = XRavScanMobileApp(db)

    # Seed providers in a background thread *after* the first Kivy frame.
    # This means the user always sees the splash + UI within ~1s rather
    # than staring at a black screen for 10s while we ingest 37k CIDRs.
    def _deferred_bootstrap(_dt) -> None:
        import threading

        def _worker():
            try:
                from x_ravscan.core import providers_manager

                log.info("starting deferred provider bootstrap")
                providers_manager.bootstrap(db)
                log.info("provider bootstrap done — %d providers", len(db.list_providers()))
                # Refresh visible screens once seeding is done.
                try:
                    Clock.schedule_once(lambda _dt2: app._refresh_after_bootstrap(), 0)
                except Exception:  # noqa: BLE001
                    pass
            except Exception:
                log.exception("deferred bootstrap failed")

        threading.Thread(target=_worker, name="bootstrap", daemon=True).start()

    Clock.schedule_once(_deferred_bootstrap, 0.5)

    app.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
