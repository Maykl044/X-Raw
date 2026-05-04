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


def _install_excepthook() -> None:
    def hook(exc_type, exc, tb):
        text = "".join(traceback.format_exception(exc_type, exc, tb))
        sys.stderr.write(text)
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
    from x_ravscan.ui.splash import Splash

    splash = Splash()
    splash.update_status("loading providers…", 0.15)

    from x_ravscan.core import providers_manager
    from x_ravscan.core.database import Database
    from x_ravscan.utils.logger import init_logging

    init_logging()
    db = Database()
    splash.update_status("seeding bundled CIDR ranges…", 0.45)
    providers_manager.bootstrap(db)
    splash.update_status("warming up UI…", 0.85)

    from x_ravscan.core.scanner import configure_event_loop
    configure_event_loop()

    splash.update_status("ready", 1.0)
    splash.finish()
    splash = None  # release reference before creating second Tk root

    from x_ravscan.ui.app import XRavScanApp

    app = XRavScanApp(db)
    app.mainloop()
    return 0


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
        sys.stderr.write(text)
        _show_crash(text)
        return 1


if __name__ == "__main__":
    sys.exit(main())
