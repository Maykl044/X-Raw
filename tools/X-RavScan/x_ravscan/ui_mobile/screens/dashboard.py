"""Dashboard screen — live PPS, hits, log feed."""

from __future__ import annotations

import collections
import time
from typing import Deque, List

from kivy.clock import Clock
from kivy.graphics import Color, Line, Rectangle
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget

from x_ravscan.i18n import t
from x_ravscan.ui_mobile.theme import PALETTE, rgba
from x_ravscan.ui_mobile.widgets import GlassCard, NeonButton, SectionHeader, StatTile


class _PPSChart(Widget):
    """Rolling sparkline drawn directly with Kivy canvas."""

    def __init__(self, history: int = 60, **kwargs) -> None:
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", "120dp")
        super().__init__(**kwargs)
        self._history = history
        self._values: Deque[float] = collections.deque([0.0] * history, maxlen=history)
        self.bind(pos=self._redraw, size=self._redraw)

    def push(self, value: float) -> None:
        self._values.append(max(value, 0.0))
        self._redraw()

    def _redraw(self, *_args) -> None:
        self.canvas.clear()
        if self.width <= 4 or self.height <= 4:
            return
        with self.canvas:
            Color(*rgba("glass_alt", 0.6))
            Rectangle(pos=self.pos, size=self.size)
            # grid
            Color(*rgba("border", 0.4))
            for i in range(1, 4):
                y = self.y + self.height * i / 4.0
                Line(points=[self.x, y, self.right, y], width=0.8)
            ymax = max(self._values) or 10.0
            n = len(self._values)
            if n < 2:
                return
            step = self.width / max(n - 1, 1)
            points: List[float] = []
            for i, v in enumerate(self._values):
                x = self.x + step * i
                y = self.y + (v / ymax) * (self.height * 0.92) + (self.height * 0.04)
                points.extend([x, y])
            Color(*rgba("accent"))
            Line(points=points, width=1.6)


class _LogFeed(BoxLayout):
    """Auto-scroll list of recent activity events."""

    def __init__(self, max_lines: int = 200, **kwargs) -> None:
        kwargs.setdefault("orientation", "vertical")
        kwargs.setdefault("size_hint_y", None)
        super().__init__(**kwargs)
        self._max = max_lines
        self.bind(minimum_height=self.setter("height"))

    def append(self, level: str, message: str) -> None:
        color = {
            "INFO": rgba("text"),
            "SUCCESS": rgba("accent"),
            "WARNING": rgba("warning"),
            "ERROR": rgba("error"),
        }.get(level.upper(), rgba("text"))
        ts = time.strftime("%H:%M:%S")
        lbl = Label(
            text=f"[{ts}] {message}",
            color=color,
            font_size="11sp",
            halign="left",
            valign="top",
            size_hint_y=None,
            text_size=(self.width - 12, None),
        )
        lbl.bind(
            texture_size=lambda lb, sz: setattr(lb, "height", sz[1] + 2),
            width=lambda lb, w: setattr(lb, "text_size", (w - 12, None)),
        )
        self.add_widget(lbl)
        # Trim oldest beyond max
        while len(self.children) > self._max:
            self.remove_widget(self.children[-1])


class DashboardScreen(BoxLayout):
    """Mobile dashboard — stats grid, PPS sparkline, log feed, primary action."""

    def __init__(self, *, on_start, on_stop, on_sync, on_discover, **kwargs) -> None:
        kwargs.setdefault("orientation", "vertical")
        kwargs.setdefault("padding", (12, 12, 12, 12))
        kwargs.setdefault("spacing", 10)
        super().__init__(**kwargs)
        self._on_start = on_start
        self._on_stop = on_stop
        self._on_sync = on_sync
        self._on_discover = on_discover

        # --- Stats strip (2x2 grid) ---
        stats = GridLayout(
            cols=2,
            spacing=10,
            size_hint_y=None,
            height="200dp",
        )
        self.tile_targets = StatTile(label=t("stat.targets"), value="0", accent="accent")
        self.tile_done = StatTile(label=t("stat.completed"), value="0", accent="accent_alt")
        self.tile_alive = StatTile(label=t("stat.alive"), value="0", accent="accent")
        self.tile_pps = StatTile(label=t("stat.pps"), value="0", accent="accent_pink")
        for w in (self.tile_targets, self.tile_done, self.tile_alive, self.tile_pps):
            stats.add_widget(w)
        self.add_widget(stats)

        # --- PPS chart ---
        chart_card = GlassCard(size_hint_y=None, height="170dp")
        chart_card.add_widget(SectionHeader(t("dashboard.pps_chart"), "", accent="accent"))
        self._chart = _PPSChart()
        chart_card.add_widget(self._chart)
        self.add_widget(chart_card)

        # --- Action bar ---
        actions = GridLayout(
            cols=2,
            spacing=10,
            size_hint_y=None,
            height="100dp",
        )
        self._btn_start = NeonButton(text=t("sidebar.start"), accent="accent", on_press=self._handle_start, height="44dp")
        self._btn_stop = NeonButton(text=t("sidebar.stop"), accent="accent_red", on_press=self._handle_stop, height="44dp")
        self._btn_sync = NeonButton(text=t("sidebar.cloud_sync"), accent="accent_alt", on_press=self._handle_sync, height="44dp")
        self._btn_discover = NeonButton(text=t("sidebar.smart_discovery"), accent="accent_pink", on_press=self._handle_discover, height="44dp")
        for w in (self._btn_start, self._btn_stop, self._btn_sync, self._btn_discover):
            actions.add_widget(w)
        self.add_widget(actions)

        # --- Log feed ---
        log_card = GlassCard()
        log_card.add_widget(SectionHeader(t("dashboard.console"), "", accent="accent"))
        scroll = ScrollView(size_hint=(1, 1), do_scroll_x=False)
        self._feed = _LogFeed()
        scroll.add_widget(self._feed)
        log_card.add_widget(scroll)
        self.add_widget(log_card)

    # --- handlers ---
    def _handle_start(self) -> None:
        self.log("INFO", t("log.scan_started"))
        if self._on_start:
            self._on_start()

    def _handle_stop(self) -> None:
        if self._on_stop:
            self._on_stop()

    def _handle_sync(self) -> None:
        if self._on_sync:
            self._on_sync()

    def _handle_discover(self) -> None:
        if self._on_discover:
            self._on_discover()

    # --- public mutators ---
    def set_stats(self, *, targets=None, done=None, alive=None, pps=None) -> None:
        if targets is not None:
            self.tile_targets.set_value(targets)
        if done is not None:
            self.tile_done.set_value(done)
        if alive is not None:
            self.tile_alive.set_value(alive)
        if pps is not None:
            self.tile_pps.set_value(f"{pps:.1f}")
            self._chart.push(pps)

    def log(self, level: str, message: str) -> None:
        self._feed.append(level, message)

    def relabel(self) -> None:
        """Re-fetch all translatable strings (call after language change)."""
        self.tile_targets.update_label(t("stat.targets"))
        self.tile_done.update_label(t("stat.completed"))
        self.tile_alive.update_label(t("stat.alive"))
        self.tile_pps.update_label(t("stat.pps"))
        self._btn_start.update_text(t("sidebar.start"))
        self._btn_stop.update_text(t("sidebar.stop"))
        self._btn_sync.update_text(t("sidebar.cloud_sync"))
        self._btn_discover.update_text(t("sidebar.smart_discovery"))
