"""X-RavScan — single entrypoint.

Run from source:

    python main.py            # GUI
    python main.py --cli      # headless CLI scan
    python main.py --sync     # cloud-sync providers and exit

When dependencies are missing we try ``pip install -r requirements.txt`` once
before importing heavy modules.
"""

from __future__ import annotations

import argparse
import asyncio
import datetime as _dt
import os
import sys
import traceback
from pathlib import Path


# When frozen (PyInstaller / Nuitka) ``__file__`` lives inside the bundle, but
# we still want our project root on ``sys.path`` so the ``x_ravscan`` package
# is discoverable. ``sys._MEIPASS`` is the temporary unpack dir set by
# PyInstaller's bootloader; otherwise we fall back to the real source dir.
HERE = Path(getattr(sys, "_MEIPASS", "") or __file__).resolve()
if HERE.is_file():
    HERE = HERE.parent
for cand in (HERE, HERE.parent):
    if (cand / "x_ravscan").is_dir() and str(cand) not in sys.path:
        sys.path.insert(0, str(cand))


# ---------------------------------------------------------------------------
# Crash handler — writes a full traceback to user_data_dir/logs/crash.txt and
# (on Windows) shows a MessageBox so silent ``--windowed`` failures are
# diagnosable.
# ---------------------------------------------------------------------------


def _crash_log_path() -> Path:
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData/Roaming")) / "X-RavScan"
    elif sys.platform == "darwin":
        base = Path.home() / "Library/Application Support/X-RavScan"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "X-RavScan"
    base = base / "logs"
    base.mkdir(parents=True, exist_ok=True)
    return base / "crash.txt"


def _show_crash(message: str) -> None:
    try:
        target = _crash_log_path()
        with target.open("a", encoding="utf-8") as fh:
            fh.write("=" * 60 + "\n")
            fh.write(f"X-RavScan crash @ {_dt.datetime.now().isoformat()}\n")
            fh.write(f"Python {sys.version}\n")
            fh.write(f"Frozen: {getattr(sys, 'frozen', False)}  exe: {sys.executable}\n")
            fh.write(message + "\n")
    except Exception:
        target = None  # noqa: F841

    if sys.platform == "win32":
        try:
            import ctypes

            preview = (message[-1500:] if len(message) > 1500 else message)
            extra = f"\n\nFull log: {target}" if target is not None else ""
            ctypes.windll.user32.MessageBoxW(
                None,
                preview + extra,
                "X-RavScan crashed",
                0x10,  # MB_ICONERROR
            )
        except Exception:
            pass


def _safe_stderr_write(text: str) -> None:
    """Write to stderr if available — PyInstaller --windowed sets it to None."""
    try:
        if sys.stderr is not None:
            sys.stderr.write(text)
    except Exception:  # noqa: BLE001
        pass


def _install_excepthook() -> None:
    def hook(exc_type, exc, tb):
        text = "".join(traceback.format_exception(exc_type, exc, tb))
        _safe_stderr_write(text)
        _show_crash(text)

    sys.excepthook = hook


def _bootstrap_deps() -> None:
    from x_ravscan.utils.deps import ensure_deps  # noqa: WPS433

    if not ensure_deps():
        print(
            "[X-RavScan] missing dependencies — install them manually:\n"
            f"    {sys.executable} -m pip install -r {HERE / 'requirements.txt'}",
            file=sys.stderr,
        )
        sys.exit(2)


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="x-ravscan", description="CDN/cloud TLS:443 reconnaissance")
    p.add_argument("--cli", action="store_true", help="run a headless scan and exit")
    p.add_argument("--sync", action="store_true", help="refresh provider IP feeds and exit")
    p.add_argument("--discover", action="store_true", help="run BGPView smart-discovery and exit")
    p.add_argument("--auto-sync", action="store_true", help="discover + auto-accept new prefixes")
    p.add_argument("--engine", default="asyncio", choices=["asyncio", "masscan", "zmap"])
    p.add_argument("--concurrency", type=int, default=512)
    p.add_argument("--sample", type=int, default=128, help="max IPs probed per CIDR")
    p.add_argument("--export", choices=["json", "csv", "html", "all"], help="after a scan, export results")
    return p.parse_args()


def _run_cli(args: argparse.Namespace) -> int:
    from x_ravscan.core import exporter, providers_manager, scanner
    from x_ravscan.core.database import Database
    from x_ravscan.core.network_updater import auto_sync, smart_discovery
    from x_ravscan.utils.logger import init_logging

    init_logging()
    db = Database()
    providers_manager.bootstrap(db)

    if args.sync:
        results = providers_manager.cloud_sync(db)
        ok = sum(1 for r in results if r.ok)
        print(f"cloud sync: {ok}/{len(results)} providers updated")
        return 0
    if args.auto_sync:
        n = auto_sync(db, accept_new=True)
        print(f"auto-sync: {n} new prefixes accepted")
        return 0
    if args.discover:
        new = smart_discovery(db)
        print(f"smart discovery: {len(new)} new prefixes queued")
        return 0

    scanner.configure_event_loop()
    scan_id = asyncio.run(
        scanner.run_scan(
            db,
            engine=args.engine,
            concurrency=args.concurrency,
            sample_per_cidr=args.sample,
        )
    )
    print(f"scan finished, id={scan_id}")
    if args.export:
        if args.export == "all":
            paths = exporter.export_all(db, scan_id=scan_id)
        elif args.export == "json":
            paths = [exporter.export_json(db, scan_id=scan_id)]
        elif args.export == "csv":
            paths = [exporter.export_csv(db, scan_id=scan_id)]
        else:
            paths = [exporter.export_html(db, scan_id=scan_id)]
        for p in paths:
            print(f"export -> {p}")
    return 0


def _run_gui() -> int:
    # Boot splash first so users see something while heavy imports happen.
    # We isolate the splash inside its own scope so its Tk root is fully
    # destroyed before we instantiate the main ``ctk.CTk`` root.
    splash = None
    try:
        from x_ravscan.ui.splash import Splash

        splash = Splash()
        splash.update_status("loading providers…", 0.15)
    except Exception:  # noqa: BLE001
        # Splash is purely cosmetic — never let it block the app from starting.
        splash = None

    from x_ravscan.core import providers_manager
    from x_ravscan.core.database import Database
    from x_ravscan.utils.logger import init_logging

    init_logging()
    db = Database()
    if splash is not None:
        try:
            splash.update_status("seeding bundled CIDR ranges…", 0.45)
        except Exception:  # noqa: BLE001
            splash = None
    try:
        providers_manager.bootstrap(db)
    except Exception:  # noqa: BLE001
        # A bad seed file shouldn't block the UI from rendering. The user
        # can always re-sync from the cloud after the window is up.
        traceback.print_exc()
    if splash is not None:
        try:
            splash.update_status("warming up UI…", 0.85)
        except Exception:  # noqa: BLE001
            splash = None

    try:
        from x_ravscan.core.scanner import configure_event_loop
        configure_event_loop()
    except Exception:  # noqa: BLE001
        traceback.print_exc()

    try:
        # Restore the user's saved language before any UI strings are rendered.
        from x_ravscan.ui.app import load_persisted_language
        load_persisted_language(db)
    except Exception:  # noqa: BLE001
        traceback.print_exc()

    if splash is not None:
        try:
            splash.update_status("ready", 1.0)
            splash.finish()
        except Exception:  # noqa: BLE001
            pass
        splash = None  # release reference before creating second Tk root

    # Construct the real CTk window. Wrapped so that even if ``__init__``
    # crashes outright (rare — it has its own internal try/except now), we
    # spin up a *fallback* CTk window with the traceback so the user sees
    # an error instead of a silent splash → close.
    try:
        from x_ravscan.ui.app import XRavScanApp

        app = XRavScanApp(db)
        app.mainloop()
        return 0
    except Exception:
        text = traceback.format_exc()
        _safe_stderr_write(text)
        _show_crash(text)
        try:
            _show_fallback_ctk(text)
        except Exception:  # noqa: BLE001
            pass
        return 1


def _show_fallback_ctk(text: str) -> None:
    """Open a minimal CTk window showing the traceback.

    Used as a last resort when ``XRavScanApp.__init__`` raises before its
    own internal fallback can render. Keeps the user from seeing a silent
    "splash → close" pattern with no diagnostic.
    """
    try:
        import customtkinter as ctk
    except Exception:  # noqa: BLE001
        return
    ctk.set_appearance_mode("dark")
    root = ctk.CTk()
    root.title("X-RavScan — startup failure")
    root.geometry("980x620")
    head = ctk.CTkLabel(
        root,
        text="X-RavScan failed to start",
        text_color="#ff7c8e",
        font=ctk.CTkFont(size=18, weight="bold"),
    )
    head.pack(anchor="w", padx=18, pady=(16, 4))
    sub = ctk.CTkLabel(
        root,
        text=(
            "Full traceback below. A copy was also written to "
            f"{_crash_log_path()}."
        ),
        text_color="#a0a4b8",
        justify="left",
        wraplength=920,
    )
    sub.pack(anchor="w", padx=18, pady=(0, 12))
    box = ctk.CTkTextbox(
        root,
        fg_color="#11183c",
        text_color="#eef0fa",
        border_width=1,
        border_color="#2a3360",
        corner_radius=12,
        font=ctk.CTkFont(family="Consolas", size=11),
    )
    box.pack(fill="both", expand=True, padx=18, pady=(0, 18))
    box.insert("1.0", text)
    box.configure(state="disabled")
    root.mainloop()


def main() -> int:
    _install_excepthook()
    try:
        _bootstrap_deps()
        args = _parse_args()
        if args.cli or args.sync or args.discover or args.auto_sync:
            return _run_cli(args)
        return _run_gui()
    except SystemExit:
        raise
    except Exception:
        text = traceback.format_exc()
        _safe_stderr_write(text)
        _show_crash(text)
        return 1


if __name__ == "__main__":
    sys.exit(main())
