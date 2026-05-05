"""Main Kivy application class for the mobile build.

Layout:
    +------------------------------------------+
    |  X-RavScan       v1.0.0                  |  <-- top app bar
    +------------------------------------------+
    |                                          |
    |             current screen               |
    |                                          |
    +------------------------------------------+
    |  Dash | Prov | Disco | Res | Set         |  <-- bottom tab bar
    +------------------------------------------+

We use a plain ``ScreenManager`` + custom bottom tab bar instead of KivyMD's
bottom navigation so the app works on bare Kivy (smaller deps, fewer
buildozer recipes required).
"""

from __future__ import annotations

import asyncio
import threading
from typing import Optional

from kivy.app import App
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.graphics import Color, Rectangle
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.uix.screenmanager import NoTransition, Screen, ScreenManager

from x_ravscan.core import providers_manager
from x_ravscan.core.config import APP_NAME, APP_VERSION
from x_ravscan.core.database import Database
from x_ravscan.core.scanner import ScanProgress, ScanResult, run_scan
from x_ravscan.i18n import t
from x_ravscan.ui_mobile.screens.dashboard import DashboardScreen
from x_ravscan.ui_mobile.screens.discovery import DiscoveryScreen
from x_ravscan.ui_mobile.screens.providers import ProvidersScreen
from x_ravscan.ui_mobile.screens.results import ResultsScreen
from x_ravscan.ui_mobile.screens.settings import SettingsScreen
from x_ravscan.ui_mobile.theme import rgba
from x_ravscan.ui_mobile.widgets import NeonButton


TAB_DEFS = [
    ("dashboard", "tab.dashboard", "accent"),
    ("providers", "tab.providers", "accent_alt"),
    ("discovery", "tab.discovery", "accent_pink"),
    ("results", "tab.results", "accent"),
    ("settings", "tab.settings", "accent_alt"),
]


class _TopBar(BoxLayout):
    def __init__(self, **kwargs) -> None:
        kwargs.setdefault("orientation", "horizontal")
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", "52dp")
        kwargs.setdefault("padding", (16, 6, 16, 6))
        super().__init__(**kwargs)
        with self.canvas.before:
            Color(*rgba("glass", 0.95))
            self._bg = Rectangle(pos=self.pos, size=self.size)
            Color(*rgba("border"))
            self._line = Rectangle(pos=(0, 0), size=(0, 1))
        self.bind(pos=self._refresh, size=self._refresh)
        self._title = Label(
            text=APP_NAME,
            color=rgba("accent"),
            font_size="18sp",
            bold=True,
            halign="left",
            valign="middle",
        )
        self._title.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        self.add_widget(self._title)
        self._version = Label(
            text=f"v{APP_VERSION}",
            color=rgba("text_dim"),
            font_size="11sp",
            halign="right",
            valign="middle",
            size_hint_x=None,
            width="80dp",
        )
        self._version.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        self.add_widget(self._version)

    def _refresh(self, *_args) -> None:
        self._bg.pos = self.pos
        self._bg.size = self.size
        x, y = self.pos
        self._line.pos = (x, y)
        self._line.size = (self.width, 1)


class _BottomBar(BoxLayout):
    def __init__(self, on_pick, **kwargs) -> None:
        kwargs.setdefault("orientation", "horizontal")
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", "60dp")
        kwargs.setdefault("padding", (8, 8, 8, 8))
        kwargs.setdefault("spacing", 6)
        super().__init__(**kwargs)
        self._on_pick = on_pick
        with self.canvas.before:
            Color(*rgba("glass", 0.95))
            self._bg = Rectangle(pos=self.pos, size=self.size)
        self.bind(pos=self._refresh, size=self._refresh)

        self._buttons = {}
        for slug, key, accent in TAB_DEFS:
            btn = NeonButton(
                text=t(key),
                accent=accent,
                on_press=lambda s=slug: self._on_pick(s),
                height="44dp",
            )
            btn.size_hint_x = 1
            self._buttons[slug] = btn
            self.add_widget(btn)

    def _refresh(self, *_args) -> None:
        self._bg.pos = self.pos
        self._bg.size = self.size

    def relabel(self) -> None:
        for slug, key, _ in TAB_DEFS:
            self._buttons[slug].update_text(t(key))


class _RootBackground(BoxLayout):
    """Plain dark base behind everything (`bg` colour from theme)."""

    def __init__(self, **kwargs) -> None:
        kwargs.setdefault("orientation", "vertical")
        super().__init__(**kwargs)
        with self.canvas.before:
            Color(*rgba("bg"))
            self._bg = Rectangle(pos=self.pos, size=self.size)
        self.bind(pos=self._refresh, size=self._refresh)

    def _refresh(self, *_args) -> None:
        self._bg.pos = self.pos
        self._bg.size = self.size


class XRavScanMobileApp(App):
    title = APP_NAME

    def __init__(self, db: Database, **kwargs) -> None:
        super().__init__(**kwargs)
        self._db = db
        self._scan_thread: Optional[threading.Thread] = None
        self._scan_cancel: Optional[threading.Event] = None

    def build(self):
        # Start in mobile-friendly portrait; adjust on desktop runs.
        Window.clearcolor = rgba("bg")
        if not self._is_android():
            Window.size = (420, 820)

        root = _RootBackground()
        root.add_widget(_TopBar())

        sm = ScreenManager(transition=NoTransition())
        self._screens = {}

        # --- Dashboard ---
        dash_screen = Screen(name="dashboard")
        self._dashboard = DashboardScreen(
            on_start=self._start_scan,
            on_stop=self._stop_scan,
            on_sync=self._cloud_sync,
            on_discover=self._smart_discover,
        )
        dash_screen.add_widget(self._dashboard)
        sm.add_widget(dash_screen)
        self._screens["dashboard"] = self._dashboard

        # --- Providers ---
        prov_screen = Screen(name="providers")
        self._providers = ProvidersScreen(self._db, on_log=self._log)
        prov_screen.add_widget(self._providers)
        sm.add_widget(prov_screen)
        self._screens["providers"] = self._providers

        # --- Discovery ---
        disc_screen = Screen(name="discovery")
        self._discovery = DiscoveryScreen(self._db, on_log=self._log)
        disc_screen.add_widget(self._discovery)
        sm.add_widget(disc_screen)
        self._screens["discovery"] = self._discovery

        # --- Results ---
        res_screen = Screen(name="results")
        self._results = ResultsScreen(self._db, on_log=self._log)
        res_screen.add_widget(self._results)
        sm.add_widget(res_screen)
        self._screens["results"] = self._results

        # --- Settings ---
        set_screen = Screen(name="settings")
        self._settings = SettingsScreen(
            self._db,
            on_log=self._log,
            on_relabel=self._relabel_all,
        )
        set_screen.add_widget(self._settings)
        sm.add_widget(set_screen)
        self._screens["settings"] = self._settings

        self._sm = sm
        root.add_widget(sm)

        self._bottom = _BottomBar(self._switch_to)
        root.add_widget(self._bottom)

        sm.current = "dashboard"
        Clock.schedule_once(self._on_first_frame, 0.1)
        return root

    # ------------------------------------------------------------------
    @staticmethod
    def _is_android() -> bool:
        try:
            import android  # noqa: F401

            return True
        except Exception:
            return False

    def _on_first_frame(self, _dt) -> None:
        try:
            self._log("INFO", t("log.app.ready", count=len(self._db.list_providers())))
        except Exception:  # noqa: BLE001 - DB may still be empty during bootstrap
            self._log("INFO", "X-RavScan UI ready — seeding providers in background…")

    def _refresh_after_bootstrap(self) -> None:
        """Called from mobile_main once provider bootstrap completes."""
        try:
            self._log("INFO", t("log.app.ready", count=len(self._db.list_providers())))
        except Exception:  # noqa: BLE001
            pass
        for name, scr in self._screens.items():
            if hasattr(scr, "refresh"):
                try:
                    scr.refresh()
                except Exception:  # noqa: BLE001
                    pass

    def _switch_to(self, name: str) -> None:
        if name in self._screens:
            self._sm.current = name
            scr = self._screens[name]
            if hasattr(scr, "refresh"):
                try:
                    scr.refresh()
                except Exception:  # noqa: BLE001
                    pass

    def _log(self, level: str, message: str) -> None:
        # Always route through the dashboard's log feed, regardless of which
        # screen is currently visible.
        self._dashboard.log(level, message)

    def _relabel_all(self) -> None:
        self._bottom.relabel()
        for name, scr in self._screens.items():
            if hasattr(scr, "relabel"):
                try:
                    scr.relabel()
                except Exception:  # noqa: BLE001
                    pass

    # ---- Scan controls ----
    def _start_scan(self) -> None:
        if self._scan_thread and self._scan_thread.is_alive():
            self._log("WARNING", t("log.scan.already_running"))
            return
        self._scan_cancel = threading.Event()
        self._scan_thread = threading.Thread(
            target=self._scan_worker, daemon=True
        )
        self._scan_thread.start()

    def _stop_scan(self) -> None:
        if self._scan_cancel:
            self._scan_cancel.set()
            self._log("WARNING", t("log.scan.stop_requested"))

    def _scan_worker(self) -> None:
        cancel = self._scan_cancel
        try:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)
            cancel_event = asyncio.Event()

            def progress(p: ScanProgress, _res) -> None:
                Clock.schedule_once(
                    lambda _dt, p=p: self._dashboard.set_stats(
                        targets=p.total,
                        done=p.completed,
                        alive=p.alive,
                        pps=p.pps(),
                    ),
                    0,
                )

            async def watch_cancel() -> None:
                while not cancel_event.is_set():
                    if cancel and cancel.is_set():
                        cancel_event.set()
                        return
                    await asyncio.sleep(0.5)

            async def main() -> int:
                watcher = asyncio.create_task(watch_cancel())
                try:
                    return await run_scan(
                        self._db,
                        engine="asyncio",
                        concurrency=256,  # phone-friendly
                        sample_per_cidr=64,
                        progress_cb=progress,
                        cancel_event=cancel_event,
                    )
                finally:
                    watcher.cancel()

            try:
                loop.run_until_complete(main())
            finally:
                loop.close()
            Clock.schedule_once(
                lambda _dt: self._log("SUCCESS", t("log.scan.done")), 0
            )
            Clock.schedule_once(lambda _dt: self._results.refresh(), 0)
        except Exception as exc:  # noqa: BLE001
            Clock.schedule_once(
                lambda _dt, e=exc: self._log("ERROR", f"scan: {e}"), 0
            )

    # ---- Cloud sync ----
    def _cloud_sync(self) -> None:
        self._log("INFO", t("log.sync.start"))
        threading.Thread(target=self._cloud_sync_worker, daemon=True).start()

    def _cloud_sync_worker(self) -> None:
        try:
            results = providers_manager.cloud_sync(self._db)
        except Exception as exc:  # noqa: BLE001
            Clock.schedule_once(
                lambda _dt, e=exc: self._log("ERROR", f"cloud sync: {e}"), 0
            )
            return
        added_total = sum(
            getattr(r, "added", 0) for r in results
            if not getattr(r, "error", None)
        )
        ok = sum(1 for r in results if not getattr(r, "error", None))
        failed = sum(1 for r in results if getattr(r, "error", None))
        Clock.schedule_once(
            lambda _dt: self._log(
                "SUCCESS",
                t(
                    "log.sync.done",
                    count=added_total,
                    ok=ok,
                    failed=failed,
                ),
            ),
            0,
        )
        Clock.schedule_once(lambda _dt: self._providers.refresh(), 0)

    # ---- Smart discovery (also exposed on the Discovery tab) ----
    def _smart_discover(self) -> None:
        self._switch_to("discovery")
        self._discovery._run_discovery()  # noqa: SLF001 — explicit cross-screen call
