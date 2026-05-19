"""Discovery screen — Smart Discovery via BGPView."""

from __future__ import annotations

import threading
from typing import Callable, List

from kivy.clock import Clock
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView

from x_ravscan.core.database import Database
from x_ravscan.i18n import t
from x_ravscan.ui_mobile.theme import rgba
from x_ravscan.ui_mobile.widgets import GlassCard, NeonButton, SectionHeader


class _DiscoveryRow(GlassCard):
    def __init__(self, slug: str, name: str, cidr: str, asn: int, **kwargs) -> None:
        kwargs.setdefault("orientation", "horizontal")
        kwargs.setdefault("padding", (12, 8, 12, 8))
        kwargs.setdefault("spacing", 10)
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", "72dp")
        super().__init__(accent="accent_pink", nested=True, **kwargs)

        col = BoxLayout(orientation="vertical", size_hint_x=1, spacing=2)
        title = Label(
            text=name,
            color=rgba("text"),
            font_size="13sp",
            bold=True,
            halign="left",
            valign="middle",
        )
        title.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        col.add_widget(title)
        cidr_lbl = Label(
            text=f"{cidr}  ·  AS{asn}",
            color=rgba("accent_pink"),
            font_size="11sp",
            halign="left",
            valign="middle",
        )
        cidr_lbl.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        col.add_widget(cidr_lbl)
        self.add_widget(col)


class DiscoveryScreen(BoxLayout):
    def __init__(
        self,
        db: Database,
        *,
        on_log: Callable[[str, str], None],
        **kwargs,
    ) -> None:
        kwargs.setdefault("orientation", "vertical")
        kwargs.setdefault("padding", (12, 12, 12, 12))
        kwargs.setdefault("spacing", 10)
        super().__init__(**kwargs)
        self._db = db
        self._on_log = on_log

        header = GlassCard(size_hint_y=None, height="170dp")
        header.add_widget(
            SectionHeader(
                t("discovery.header"),
                t("discovery.header.desc"),
                accent="accent_pink",
            )
        )
        action_row = GridLayout(cols=2, spacing=8, size_hint_y=None, height="92dp")
        self._btn_run = NeonButton(
            text=t("discovery.run"),
            accent="accent",
            on_press=self._run_discovery,
            height="44dp",
        )
        self._btn_auto = NeonButton(
            text=t("discovery.auto_sync"),
            accent="accent_alt",
            on_press=self._auto_sync,
            height="44dp",
        )
        self._btn_smart = NeonButton(
            text=t("discovery.smart_append", default="Smart Append"),
            accent="accent",
            on_press=self._smart_append,
            height="44dp",
        )
        self._btn_optimize = NeonButton(
            text=t("discovery.optimize", default="Clean & Optimize"),
            accent="accent_pink",
            on_press=self._clean_optimize,
            height="44dp",
        )
        action_row.add_widget(self._btn_run)
        action_row.add_widget(self._btn_auto)
        action_row.add_widget(self._btn_smart)
        action_row.add_widget(self._btn_optimize)
        header.add_widget(action_row)
        self.add_widget(header)

        self._status = Label(
            text=t("discovery.empty"),
            color=rgba("text_dim"),
            font_size="11sp",
            size_hint_y=None,
            height="20dp",
            halign="left",
            valign="middle",
        )
        self._status.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        self.add_widget(self._status)

        scroll = ScrollView(do_scroll_x=False)
        self._list = BoxLayout(
            orientation="vertical",
            spacing=8,
            size_hint_y=None,
            padding=(0, 0, 0, 12),
        )
        self._list.bind(minimum_height=self._list.setter("height"))
        scroll.add_widget(self._list)
        self.add_widget(scroll)

        # On open, render whatever discoveries are already pending.
        self.refresh()

    # --- handlers ---
    def _run_discovery(self) -> None:
        self._on_log("INFO", t("log.discovery.start"))
        self._status.text = t("discovery.running")
        threading.Thread(target=self._discovery_worker, daemon=True).start()

    def _discovery_worker(self) -> None:
        from x_ravscan.core import network_updater

        try:
            fresh = network_updater.smart_discovery(self._db)
        except Exception as exc:  # noqa: BLE001
            Clock.schedule_once(
                lambda _dt, e=exc: self._on_log("ERROR", f"discovery: {e}"), 0
            )
            Clock.schedule_once(
                lambda _dt: setattr(self._status, "text", t("discovery.failed")), 0
            )
            return
        Clock.schedule_once(lambda _dt: self._after_run(len(fresh)), 0)

    def _after_run(self, count: int) -> None:
        self.refresh()
        if count == 0:
            self._on_log("INFO", t("log.discovery.no_new"))
        else:
            self._on_log("SUCCESS", t("log.discovery.done", count=count))

    def _auto_sync(self) -> None:
        self._on_log("INFO", t("log.discovery.auto_sync"))
        threading.Thread(target=self._auto_sync_worker, daemon=True).start()

    def _auto_sync_worker(self) -> None:
        from x_ravscan.core import network_updater

        try:
            added = network_updater.auto_sync(self._db)
        except Exception as exc:  # noqa: BLE001
            Clock.schedule_once(
                lambda _dt, e=exc: self._on_log("ERROR", f"auto-sync: {e}"), 0
            )
            return
        Clock.schedule_once(
            lambda _dt: self._after_auto_sync(added),
            0,
        )

    def _after_auto_sync(self, added: int) -> None:
        self.refresh()
        self._on_log("SUCCESS", t("log.discovery.auto_done", count=added))

    # ---- Smart Append (ASN-based, multi-threaded) -----------------------
    def _smart_append(self) -> None:
        self._on_log("INFO", "Smart Append (ASN) started")
        threading.Thread(target=self._smart_append_worker, daemon=True).start()

    def _smart_append_worker(self) -> None:
        def _ui_log(level: str, msg: str) -> None:
            Clock.schedule_once(lambda _dt, lv=level, m=msg: self._on_log(lv, m), 0)

        from x_ravscan.core import data_manager

        try:
            report = data_manager.sync_all_via_asn(self._db, log_fn=_ui_log)
        except Exception as exc:  # noqa: BLE001
            Clock.schedule_once(
                lambda _dt, e=exc: self._on_log("ERROR", f"smart append: {e}"), 0
            )
            return
        Clock.schedule_once(
            lambda _dt, r=report: self._on_log(
                "SUCCESS",
                f"Smart Append: +{r.total_added} new across "
                f"{r.providers_touched} providers in {r.duration:.1f}s",
            ),
            0,
        )
        Clock.schedule_once(lambda _dt: self.refresh(), 0)

    # ---- Clean & Optimize (Route Summarisation) -------------------------
    def _clean_optimize(self) -> None:
        self._on_log("INFO", "Clean & Optimize started")
        threading.Thread(target=self._clean_optimize_worker, daemon=True).start()

    def _clean_optimize_worker(self) -> None:
        def _ui_log(level: str, msg: str) -> None:
            Clock.schedule_once(lambda _dt, lv=level, m=msg: self._on_log(lv, m), 0)

        from x_ravscan.core import data_manager

        try:
            removed = data_manager.clean_and_optimize(self._db, log_fn=_ui_log)
        except Exception as exc:  # noqa: BLE001
            Clock.schedule_once(
                lambda _dt, e=exc: self._on_log("ERROR", f"optimize: {e}"), 0
            )
            return
        Clock.schedule_once(
            lambda _dt, r=removed: self._on_log(
                "SUCCESS",
                f"Clean & Optimize: {sum(r.values())} prefixes merged across "
                f"{len(r)} providers",
            ),
            0,
        )
        Clock.schedule_once(lambda _dt: self.refresh(), 0)

    def refresh(self, *_args) -> None:
        self._list.clear_widgets()
        try:
            pending = self._db.list_pending_discoveries()
        except Exception:  # noqa: BLE001
            pending = []
        if not pending:
            self._status.text = t("discovery.empty")
            return
        self._status.text = t("discovery.found", count=len(pending))
        for row in pending:
            self._list.add_widget(
                _DiscoveryRow(
                    slug=row["provider_slug"],
                    name=row["provider_name"],
                    cidr=row["cidr"],
                    asn=int(row["asn"]) if row["asn"] is not None else 0,
                )
            )

    def relabel(self) -> None:
        self._btn_run.update_text(t("discovery.run"))
        self._btn_auto.update_text(t("discovery.auto_sync"))
        try:
            self._btn_smart.update_text(t("discovery.smart_append", default="Smart Append"))
            self._btn_optimize.update_text(t("discovery.optimize", default="Clean & Optimize"))
        except Exception:  # noqa: BLE001
            pass
        self.refresh()
