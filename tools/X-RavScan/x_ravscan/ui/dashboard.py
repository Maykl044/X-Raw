"""Dashboard widgets: PPS line chart, provider pie chart, log console."""

from __future__ import annotations

import collections
import time
from typing import Deque, List, Optional, Tuple

import customtkinter as ctk

from x_ravscan.core.config import THEME
from x_ravscan.ui.theme import LEVEL_COLORS, apply_matplotlib_dark


class _LazyMpl:
    """Imports matplotlib lazily — UI module shouldn't fail import without it."""

    def __init__(self) -> None:
        self._figure_cls = None
        self._canvas_cls = None

    def figure(self, **kwargs):
        if self._figure_cls is None:
            apply_matplotlib_dark()
            from matplotlib.figure import Figure
            from matplotlib.backends.backend_tkagg import FigureCanvasTkAgg

            self._figure_cls = Figure
            self._canvas_cls = FigureCanvasTkAgg
        return self._figure_cls(**kwargs), self._canvas_cls


_mpl = _LazyMpl()


# ---------------------------------------------------------------------------
# Live PPS chart
# ---------------------------------------------------------------------------


class PPSChart(ctk.CTkFrame):
    """Rolling window of packets-per-second."""

    def __init__(self, master, history: int = 60, **kwargs):
        super().__init__(master, fg_color=THEME["panel"], corner_radius=10, **kwargs)
        self._history = history
        self._values: Deque[float] = collections.deque([0.0] * history, maxlen=history)
        self._times: Deque[float] = collections.deque([time.time()] * history, maxlen=history)

        fig, canvas_cls = _mpl.figure(figsize=(5.5, 2.4), dpi=100)
        self._fig = fig
        self._ax = fig.add_subplot(111)
        self._ax.set_title("PPS (packets / sec)")
        self._ax.set_ylim(0, 100)
        self._ax.grid(True)
        self._line, = self._ax.plot(
            range(history), list(self._values), color=THEME["accent"], linewidth=2.0
        )
        self._fill = None
        self._fig.tight_layout()
        # NB: CTkFrame already uses ``_canvas`` for its own background canvas,
        # so we rename ours to ``_mpl_canvas``.
        self._mpl_canvas = canvas_cls(fig, master=self)
        self._mpl_canvas.get_tk_widget().pack(fill="both", expand=True, padx=8, pady=8)

    def push(self, value: float) -> None:
        self._values.append(max(value, 0.0))
        self._times.append(time.time())
        ymax = max(self._values) or 10.0
        self._ax.set_ylim(0, ymax * 1.25 + 1)
        self._line.set_ydata(list(self._values))
        if self._fill is not None:
            try:
                self._fill.remove()
            except Exception:  # pragma: no cover
                pass
        self._fill = self._ax.fill_between(
            range(len(self._values)),
            list(self._values),
            color=THEME["accent"],
            alpha=0.18,
        )
        self._mpl_canvas.draw_idle()


# ---------------------------------------------------------------------------
# Provider pie chart
# ---------------------------------------------------------------------------


class ProviderPie(ctk.CTkFrame):
    def __init__(self, master, **kwargs):
        super().__init__(master, fg_color=THEME["panel"], corner_radius=10, **kwargs)
        fig, canvas_cls = _mpl.figure(figsize=(3.6, 3.0), dpi=100)
        self._fig = fig
        self._ax = fig.add_subplot(111)
        self._ax.set_title("Hits by provider")
        self._fig.tight_layout()
        self._mpl_canvas = canvas_cls(fig, master=self)
        self._mpl_canvas.get_tk_widget().pack(fill="both", expand=True, padx=8, pady=8)
        self._draw_empty()

    def _draw_empty(self) -> None:
        self._ax.clear()
        self._ax.set_title("Hits by provider")
        self._ax.text(
            0.5,
            0.5,
            "no data yet",
            ha="center",
            va="center",
            color=THEME["text_dim"],
            transform=self._ax.transAxes,
        )
        self._ax.set_xticks([])
        self._ax.set_yticks([])
        self._mpl_canvas.draw_idle()

    def update_data(self, slices: List[Tuple[str, int, str]]) -> None:
        """``slices`` = [(label, count, color)]"""
        self._ax.clear()
        self._ax.set_title("Hits by provider")
        if not slices:
            self._draw_empty()
            return
        labels = [s[0] for s in slices]
        sizes = [s[1] for s in slices]
        colors = [s[2] for s in slices]
        wedges, _texts = self._ax.pie(
            sizes,
            colors=colors,
            wedgeprops={"linewidth": 1, "edgecolor": THEME["bg"]},
            startangle=90,
        )
        legend = self._ax.legend(
            wedges,
            [f"{l}  ({sz})" for l, sz in zip(labels, sizes)],
            loc="center left",
            bbox_to_anchor=(1.02, 0.5),
            fontsize=8,
            frameon=False,
            labelcolor=THEME["text"],
        )
        if legend is not None:
            for text in legend.get_texts():
                text.set_color(THEME["text"])
        self._fig.tight_layout()
        self._mpl_canvas.draw_idle()


# ---------------------------------------------------------------------------
# Log console
# ---------------------------------------------------------------------------


class LogConsole(ctk.CTkFrame):
    def __init__(self, master, max_lines: int = 1000, **kwargs):
        super().__init__(master, fg_color=THEME["panel"], corner_radius=10, **kwargs)
        self._max = max_lines
        header = ctk.CTkLabel(
            self,
            text="LIVE LOG",
            text_color=THEME["accent"],
            font=ctk.CTkFont(size=11, weight="bold"),
        )
        header.pack(anchor="w", padx=12, pady=(8, 2))

        # CTkTextbox doesn't support per-tag colours; drop down to tk.Text.
        import tkinter as tk

        self._text = tk.Text(
            self,
            bg=THEME["bg"],
            fg=THEME["text"],
            insertbackground=THEME["accent"],
            highlightthickness=0,
            relief="flat",
            wrap="none",
            font=("Consolas", 10),
            height=12,
        )
        self._text.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        for level, color in LEVEL_COLORS.items():
            self._text.tag_configure(level, foreground=color)
        self._text.configure(state="disabled")

    def append(self, level: str, message: str) -> None:
        try:
            self._text.configure(state="normal")
            self._text.insert("end", message + "\n", level.upper())
            # rotate
            line_count = int(self._text.index("end-1c").split(".")[0])
            if line_count > self._max:
                self._text.delete("1.0", f"{line_count - self._max}.0")
            self._text.see("end")
        finally:
            self._text.configure(state="disabled")
