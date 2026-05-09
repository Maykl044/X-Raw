"""Results screen — alive hosts table-card list with export shortcuts."""

from __future__ import annotations

import threading
from typing import Callable, List

from kivy.clock import Clock
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView

from x_ravscan.core.database import Database
from x_ravscan.core import exporter
from x_ravscan.i18n import t
from x_ravscan.ui_mobile.theme import rgba
from x_ravscan.ui_mobile.widgets import GlassCard, NeonButton, SectionHeader


class _HostRow(GlassCard):
    def __init__(self, ip: str, provider: str, issuer: str, **kwargs) -> None:
        kwargs.setdefault("orientation", "vertical")
        kwargs.setdefault("padding", (12, 8, 12, 8))
        kwargs.setdefault("spacing", 2)
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", "70dp")
        super().__init__(accent="accent", nested=True, **kwargs)
        ip_lbl = Label(
            text=ip,
            color=rgba("accent"),
            font_size="13sp",
            bold=True,
            halign="left",
            valign="middle",
        )
        ip_lbl.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        self.add_widget(ip_lbl)
        meta = f"{provider}  ·  {issuer or '—'}"
        meta_lbl = Label(
            text=meta,
            color=rgba("text_dim"),
            font_size="10sp",
            halign="left",
            valign="middle",
        )
        meta_lbl.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        self.add_widget(meta_lbl)


class ResultsScreen(BoxLayout):
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

        header = GlassCard(size_hint_y=None, height="120dp")
        header.add_widget(
            SectionHeader(
                t("results.header"),
                t("results.header.desc"),
                accent="accent",
            )
        )
        action_row = GridLayout(cols=4, spacing=6, size_hint_y=None, height="44dp")
        self._btn_refresh = NeonButton(
            text=t("results.refresh"),
            accent="accent_alt",
            on_press=self.refresh,
            height="44dp",
        )
        self._btn_json = NeonButton(
            text="JSON",
            accent="accent",
            on_press=lambda: self._export("json"),
            height="44dp",
        )
        self._btn_csv = NeonButton(
            text="CSV",
            accent="accent",
            on_press=lambda: self._export("csv"),
            height="44dp",
        )
        self._btn_html = NeonButton(
            text="HTML",
            accent="accent_pink",
            on_press=lambda: self._export("html"),
            height="44dp",
        )
        for w in (self._btn_refresh, self._btn_json, self._btn_csv, self._btn_html):
            action_row.add_widget(w)
        header.add_widget(action_row)
        self.add_widget(header)

        self._status = Label(
            text=t("results.empty"),
            color=rgba("text_dim"),
            font_size="11sp",
            halign="left",
            valign="middle",
            size_hint_y=None,
            height="20dp",
        )
        self._status.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        self.add_widget(self._status)

        scroll = ScrollView(do_scroll_x=False)
        self._list = BoxLayout(
            orientation="vertical",
            spacing=6,
            size_hint_y=None,
            padding=(0, 0, 0, 12),
        )
        self._list.bind(minimum_height=self._list.setter("height"))
        scroll.add_widget(self._list)
        self.add_widget(scroll)

        self.refresh()

    def refresh(self, *_args) -> None:
        self._list.clear_widgets()
        try:
            hosts = self._db.list_hosts()
        except Exception:  # noqa: BLE001
            hosts = []
        if not hosts:
            self._status.text = t("results.empty")
            return
        self._status.text = t("results.found", count=len(hosts))
        for row in hosts[:300]:  # cap rendering — phones don't like 5000 widgets
            self._list.add_widget(
                _HostRow(
                    ip=str(row["ip"]),
                    provider=str(row["provider_name"] or row["provider_slug"] or "?"),
                    issuer=str(row["issuer"] or ""),
                )
            )

    def _export(self, fmt: str) -> None:
        self._on_log("INFO", t("log.export.start", fmt=fmt.upper()))
        threading.Thread(target=self._export_worker, args=(fmt,), daemon=True).start()

    def _export_worker(self, fmt: str) -> None:
        fn = {
            "json": exporter.export_json,
            "csv": exporter.export_csv,
            "html": exporter.export_html,
        }.get(fmt)
        if fn is None:
            Clock.schedule_once(
                lambda _dt: self._on_log("ERROR", f"unknown export fmt: {fmt}"), 0
            )
            return
        try:
            path = fn(self._db)
        except Exception as exc:  # noqa: BLE001
            Clock.schedule_once(
                lambda _dt, e=exc: self._on_log("ERROR", f"export {fmt}: {e}"), 0
            )
            return
        Clock.schedule_once(
            lambda _dt: self._on_log(
                "SUCCESS", t("log.export.done", fmt=fmt.upper(), path=str(path))
            ),
            0,
        )

    def relabel(self) -> None:
        self._btn_refresh.update_text(t("results.refresh"))
        self.refresh()
