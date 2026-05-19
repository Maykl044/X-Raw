"""Branded splash screen shown while ``main.py`` boots heavy modules."""

from __future__ import annotations

import tkinter as tk
from typing import Optional

from x_ravscan.core.config import APP_NAME, APP_VERSION, THEME


_LOGO = r"""
        __  __  ____               _____
       \ \/ / |  _ \ __ ___   __  / ____|  ___ __ _ _ __
        \  /  | |_) / _` \ \ / / | (___   / __/ _` | '_ \
        /  \  |  _ < (_| |\ V /   \___ \  | (| (_| | | | |
       /_/\_\ |_| \_\__,_| \_/    ____) ||___\__,_|_| |_|
                                  |_____/
"""


class Splash(tk.Tk):
    """Borderless splash with neon logo.

    Implemented as the *root* Tk instance so we don't fight with the second
    ``ctk.CTk`` root that the main app creates.  We destroy ourselves in
    :meth:`finish` before the main window starts its mainloop.
    """

    def __init__(self, master: Optional[tk.Misc] = None) -> None:
        super().__init__()
        self.overrideredirect(True)
        self.configure(bg=THEME["bg"])
        w, h = 560, 320
        self.geometry(self._center(w, h))
        self.attributes("-topmost", True)

        canvas = tk.Canvas(
            self, width=w, height=h, bg=THEME["bg"], highlightthickness=0
        )
        canvas.pack(fill="both", expand=True)

        # outer neon border
        canvas.create_rectangle(
            4, 4, w - 4, h - 4, outline=THEME["accent"], width=2
        )
        canvas.create_text(
            w // 2,
            70,
            text=APP_NAME,
            fill=THEME["accent"],
            font=("Consolas", 30, "bold"),
        )
        canvas.create_text(
            w // 2,
            115,
            text="advanced CDN / cloud reconnaissance",
            fill=THEME["text_dim"],
            font=("Segoe UI", 11),
        )

        canvas.create_text(
            w // 2,
            195,
            text=_LOGO,
            fill=THEME["accent_alt"],
            font=("Consolas", 7),
        )

        self._status = canvas.create_text(
            w // 2,
            h - 50,
            text="loading…",
            fill=THEME["text"],
            font=("Segoe UI", 10),
        )
        self._bar_bg = canvas.create_rectangle(
            60,
            h - 30,
            w - 60,
            h - 18,
            outline=THEME["border"],
            fill=THEME["glass"],
        )
        self._bar = canvas.create_rectangle(
            60, h - 30, 60, h - 18, outline="", fill=THEME["accent"]
        )
        canvas.create_text(
            w - 30,
            h - 12,
            text=f"v{APP_VERSION}",
            fill=THEME["text_dim"],
            font=("Segoe UI", 8),
            anchor="e",
        )

        self._canvas = canvas
        self._w = w
        self._h = h
        self._progress = 0.0
        self.update()

    def _center(self, w: int, h: int) -> str:
        sw = self.winfo_screenwidth()
        sh = self.winfo_screenheight()
        x = max((sw - w) // 2, 0)
        y = max((sh - h) // 3, 0)
        return f"{w}x{h}+{x}+{y}"

    def update_status(self, text: str, progress: float) -> None:
        self._progress = max(0.0, min(progress, 1.0))
        try:
            self._canvas.itemconfigure(self._status, text=text)
            right = 60 + (self._w - 120) * self._progress
            self._canvas.coords(self._bar, 60, self._h - 30, right, self._h - 18)
            self.update()
        except tk.TclError:
            # splash already destroyed
            pass

    def finish(self) -> None:
        try:
            self.destroy()
        except tk.TclError:
            pass
