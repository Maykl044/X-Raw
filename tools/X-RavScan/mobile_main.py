"""Mobile entry point — launches the Kivy UI of X-RavScan.

This is the *Android* (and desktop-debug) entry point. The Windows desktop
build still uses ``main.py`` with CustomTkinter unchanged. Both share the
same ``x_ravscan.core`` package.

Buildozer reads ``packaging/android/buildozer.spec``, which renames
``mobile_main.py`` → ``main.py`` in CI before invoking buildozer.

Mobile-specific behaviour:
    * Heavy initialisation (DB, provider seed, asyncio policy) is moved
      *inside* the Kivy app build / on_start so any failure happens AFTER
      the Kivy window is up — otherwise Android shows a logo, the
      Python init crashes, and the OS just kills the process. By the
      time the failure happens the UI exists and we can render the
      traceback on screen.
    * On Android we route the writable directory to ``ANDROID_PRIVATE``
      (the app's private files dir, always writable) so SQLite + logs
      work.
    * The crash handler writes a traceback to ``mobile_crash.txt`` in
      the same writable dir AND prints to logcat (``adb logcat | grep
      python``) for in-the-field debugging.
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
    try:
        base.mkdir(parents=True, exist_ok=True)
    except Exception:  # noqa: BLE001
        pass
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


def _write_crash(text: str) -> None:
    """Best-effort: write to crash log, stderr, and Android logcat."""
    try:
        log = _crash_log_path()
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open("a", encoding="utf-8") as fh:
            fh.write("\n=== uncaught exception ===\n")
            fh.write(text)
    except Exception:  # noqa: BLE001
        pass
    try:
        if sys.__stderr__ is not None:
            sys.__stderr__.write(text)
    except Exception:  # noqa: BLE001
        pass
    # On Android, force the message into logcat via the Kivy logger
    # (`adb logcat | grep python` will surface it). On desktop this is a
    # no-op-ish: the same line just goes to the console.
    try:
        from kivy.logger import Logger  # type: ignore

        for line in text.splitlines():
            Logger.error("XRavScan: %s", line)
    except Exception:  # noqa: BLE001
        pass


def _install_excepthook() -> None:
    def _hook(exc_type, exc, tb) -> None:
        text = "".join(traceback.format_exception(exc_type, exc, tb))
        _write_crash(text)

    sys.excepthook = _hook


def _show_fallback_error(text: str) -> None:
    """Render a plain Kivy app whose only widget is the error traceback.

    This is the **last resort** — used when ``main()`` cannot bring up the
    real ``XRavScanMobileApp``.  It guarantees the user sees a window
    instead of just the splash → close pattern, which makes diagnosing
    the crash on a real phone actually possible.
    """
    try:
        from kivy.app import App
        from kivy.uix.boxlayout import BoxLayout
        from kivy.uix.label import Label
        from kivy.uix.scrollview import ScrollView

        class _FallbackApp(App):
            title = "X-RavScan — boot error"

            def build(self):
                root = BoxLayout(orientation="vertical", padding=12, spacing=8)
                head = Label(
                    text="X-RavScan failed to start.\nSee details below.",
                    color=(1.0, 0.45, 0.5, 1.0),
                    bold=True,
                    halign="left",
                    valign="middle",
                    size_hint_y=None,
                    height="60dp",
                    font_size="15sp",
                )
                head.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
                root.add_widget(head)
                lbl = Label(
                    text=text,
                    color=(0.95, 0.95, 0.97, 1.0),
                    halign="left",
                    valign="top",
                    font_name="RobotoMono-Regular",
                    font_size="11sp",
                    size_hint_y=None,
                )
                lbl.bind(
                    width=lambda lb, w: setattr(lb, "text_size", (w - 12, None)),
                    texture_size=lambda lb, sz: setattr(lb, "height", sz[1]),
                )
                sv = ScrollView()
                sv.add_widget(lbl)
                root.add_widget(sv)
                return root

        _FallbackApp().run()
    except Exception:  # noqa: BLE001
        # If even Kivy isn't importable we have nothing left to do.
        pass


def main() -> int:
    _install_excepthook()

    try:
        # Lazy imports so the crash handler is registered first AND so any
        # ImportError bubbles up here rather than crashing before main() is
        # even entered.
        from x_ravscan.utils.logger import init_logging, get_logger
        from x_ravscan.ui_mobile.app import XRavScanMobileApp

        init_logging()
        log = get_logger("mobile")
        log.info("X-RavScan mobile boot — base dir = %s", _BASE_DIR)

        # The App owns DB creation + provider bootstrap so any error there
        # happens AFTER Kivy has a window up — see XRavScanMobileApp.
        app = XRavScanMobileApp()
        app.run()
        return 0
    except Exception:  # noqa: BLE001
        text = traceback.format_exc()
        _write_crash(text)
        _show_fallback_error(text)
        return 1


if __name__ == "__main__":
    sys.exit(main())
