"""Glassmorphism theme — colour stack, matplotlib styling, glass widgets.

The Tk runtime cannot do real CSS-style ``backdrop-filter: blur()``, so the
"frosted glass" effect is approximated visually:

* a :class:`GradientBackdrop` Canvas paints the deep-midnight gradient and
  three soft radial blobs once, behind every page;
* glass cards are filled with a near-white tint over that gradient and
  outlined with a single hairline (rgba 0.10 ≈ ``#3b507c`` over our base);
* corner radii are ≥ 16 px to match Apple HIG.
"""

from __future__ import annotations

import math
import tkinter as tk
from typing import Optional, Tuple

import customtkinter as ctk

from x_ravscan.core.config import THEME


# ---------------------------------------------------------------------------
# Typography helpers
# ---------------------------------------------------------------------------

# Apple's SF Pro is not bundled on Linux/Windows, so we fall back to Inter and
# the platform's default UI font.  CustomTkinter / Tk pick the first family
# that exists on the system from this list.
_FONT_STACK = (
    "SF Pro Display",
    "SF Pro Text",
    "Inter",
    "Inter Display",
    "Segoe UI Variable",
    "Segoe UI",
    "Helvetica Neue",
    "Helvetica",
    "Arial",
)


def font(size: int, *, weight: str = "normal") -> ctk.CTkFont:
    return ctk.CTkFont(family=_FONT_STACK[0], size=size, weight=weight)


def font_mono(size: int) -> ctk.CTkFont:
    return ctk.CTkFont(family="JetBrains Mono", size=size)


# ---------------------------------------------------------------------------
# Matplotlib styling
# ---------------------------------------------------------------------------

def apply_matplotlib_dark() -> None:
    """Configure matplotlib to match the glassmorphism palette."""
    import matplotlib as mpl

    mpl.rcParams.update({
        "figure.facecolor": THEME["glass"],
        "axes.facecolor": THEME["glass"],
        "axes.edgecolor": THEME["border"],
        "axes.labelcolor": THEME["text_dim"],
        "xtick.color": THEME["text_dim"],
        "ytick.color": THEME["text_dim"],
        "axes.titlecolor": THEME["accent"],
        "axes.titleweight": "bold",
        "axes.titlesize": 11,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "grid.color": THEME["border"],
        "grid.linestyle": "--",
        "grid.alpha": 0.35,
        "savefig.facecolor": THEME["glass"],
        "savefig.edgecolor": THEME["glass"],
        "font.family": "DejaVu Sans",
    })


LEVEL_COLORS = {
    "DEBUG": THEME["text_dim"],
    "INFO": THEME["info"],
    "WARNING": THEME["warning"],
    "ERROR": THEME["danger"],
    "CRITICAL": THEME["danger"],
    "SUCCESS": THEME["success"],
}


# ---------------------------------------------------------------------------
# Gradient + blob backdrop
# ---------------------------------------------------------------------------

def _hex_to_rgb(value: str) -> Tuple[int, int, int]:
    value = value.lstrip("#")
    return int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16)


def _rgb_to_hex(rgb: Tuple[int, int, int]) -> str:
    return "#{:02x}{:02x}{:02x}".format(*rgb)


def _lerp(a: Tuple[int, int, int], b: Tuple[int, int, int], t: float) -> Tuple[int, int, int]:
    return (
        max(0, min(255, int(a[0] + (b[0] - a[0]) * t))),
        max(0, min(255, int(a[1] + (b[1] - a[1]) * t))),
        max(0, min(255, int(a[2] + (b[2] - a[2]) * t))),
    )


class GradientBackdrop(tk.Canvas):
    """A Tk Canvas that paints a deep-midnight vertical gradient with three
    soft blurred blobs of accent colour on top.

    The canvas re-renders on ``<Configure>`` so the gradient stays sharp at
    any window size.  Use it as a parent for the actual UI by gridding child
    widgets *on top of* it (Tk's stacking order: later children are above).
    """

    def __init__(self, master, **kwargs):
        super().__init__(
            master,
            highlightthickness=0,
            bd=0,
            background=THEME["bg"],
            **kwargs,
        )
        self.bind("<Configure>", lambda _e: self._render())

    def _render(self) -> None:
        self.delete("backdrop")
        w = self.winfo_width()
        h = self.winfo_height()
        if w <= 1 or h <= 1:
            return

        # 1. Vertical gradient — top → mid → bottom.
        top = _hex_to_rgb(THEME["bg_grad_top"])
        mid = _hex_to_rgb(THEME["bg_grad_mid"])
        bot = _hex_to_rgb(THEME["bg_grad_bot"])
        steps = max(80, h // 4)
        for i in range(steps):
            t = i / max(1, steps - 1)
            if t < 0.5:
                colour = _rgb_to_hex(_lerp(top, mid, t * 2))
            else:
                colour = _rgb_to_hex(_lerp(mid, bot, (t - 0.5) * 2))
            y0 = int(h * t)
            y1 = int(h * (i + 1) / steps) + 1
            self.create_rectangle(
                0, y0, w, y1, outline="", fill=colour, tags="backdrop",
            )

        # 2. Three abstract blurred blobs — fake a CSS radial-gradient by
        #    drawing many concentric ovals with linearly fading alpha.  We
        #    can't do real alpha in plain Tk, so we blend each ring towards
        #    the gradient colour at that y-coordinate.
        blobs = (
            (0.18 * w, 0.10 * h, 0.55 * min(w, h), THEME["accent"]),
            (0.92 * w, 0.18 * h, 0.45 * min(w, h), THEME["accent_alt"]),
            (0.55 * w, 1.05 * h, 0.65 * min(w, h), THEME["accent_mint"]),
        )
        for cx, cy, radius, colour in blobs:
            self._draw_blob(cx, cy, radius, colour, w, h, top, mid, bot)

    def _draw_blob(
        self,
        cx: float,
        cy: float,
        radius: float,
        colour: str,
        canvas_w: int,
        canvas_h: int,
        top: Tuple[int, int, int],
        mid: Tuple[int, int, int],
        bot: Tuple[int, int, int],
    ) -> None:
        """Draw a soft glow by stacking concentric ovals."""
        target = _hex_to_rgb(colour)
        rings = 22
        for i in range(rings):
            t = i / rings
            r = radius * (1 - t * 0.7)
            # Strength: ~0.10 at centre fading to 0 at edge.
            strength = 0.10 * (1 - t) ** 1.6
            # Approximate the local gradient colour at cy.
            cy_norm = max(0.0, min(1.0, cy / max(1, canvas_h)))
            if cy_norm < 0.5:
                base = _lerp(top, mid, cy_norm * 2)
            else:
                base = _lerp(mid, bot, (cy_norm - 0.5) * 2)
            blended = _rgb_to_hex(_lerp(base, target, strength))
            self.create_oval(
                cx - r, cy - r, cx + r, cy + r,
                outline="",
                fill=blended,
                tags="backdrop",
            )

        self.tag_lower("backdrop")


# ---------------------------------------------------------------------------
# Glass widgets
# ---------------------------------------------------------------------------

class GlassCard(ctk.CTkFrame):
    """A 'frosted-glass' card: 20 px corner radius, hairline border, near-white
    fill that reads as semi-transparent over the gradient backdrop.

    ``accent`` colours the 1-px border for a subtle neon outline. Pass
    ``accent=None`` for a plain glass card.
    """

    def __init__(
        self,
        master,
        *,
        accent: Optional[str] = None,
        nested: bool = False,
        corner_radius: int = 20,
        elevated: bool = False,
        **kwargs,
    ) -> None:
        fg = THEME["glass_alt"] if nested else THEME["glass"]
        if elevated:
            # Slightly lighter for "floating" stat tiles.
            fg = THEME["glass_hi"]
        super().__init__(
            master,
            fg_color=fg,
            corner_radius=corner_radius,
            border_width=1,
            border_color=accent or THEME["border"],
            **kwargs,
        )


class FrostedTile(ctk.CTkFrame):
    """A 'floating glass tile' for dashboard stat widgets — bigger corner
    radius, soft hairline, no accent border by default.
    """

    def __init__(self, master, **kwargs) -> None:
        super().__init__(
            master,
            fg_color=THEME["glass_hi"],
            corner_radius=22,
            border_width=1,
            border_color=THEME["border"],
            **kwargs,
        )


class SectionHeader(ctk.CTkFrame):
    """Small label + dim description, used at the top of settings cards."""

    def __init__(
        self,
        master,
        title: str,
        description: str,
        *,
        accent: Optional[str] = None,
        **kwargs,
    ) -> None:
        super().__init__(master, fg_color="transparent", **kwargs)
        title_color = accent or THEME["accent"]
        ctk.CTkLabel(
            self,
            text=title.upper(),
            text_color=title_color,
            font=font(11, weight="bold"),
        ).pack(anchor="w")
        ctk.CTkLabel(
            self,
            text=description,
            text_color=THEME["text_dim"],
            font=font(11),
            wraplength=560,
            justify="left",
        ).pack(anchor="w", pady=(2, 0))


class StatTile(ctk.CTkFrame):
    """A floating glass dashboard tile: tiny upper-case caption + big value."""

    def __init__(
        self,
        master,
        label: str,
        value: str,
        *,
        accent: Optional[str] = None,
        **kwargs,
    ) -> None:
        super().__init__(
            master,
            fg_color=THEME["glass_hi"],
            corner_radius=22,
            border_width=1,
            border_color=accent or THEME["border"],
            **kwargs,
        )
        accent_color = accent or THEME["accent"]
        ctk.CTkLabel(
            self,
            text=label.upper(),
            text_color=THEME["text_dim"],
            font=font(10, weight="bold"),
        ).pack(anchor="w", padx=18, pady=(14, 0))
        self._value = ctk.CTkLabel(
            self,
            text=value,
            text_color=THEME["text"],
            font=font(26, weight="bold"),
        )
        self._value.pack(anchor="w", padx=18, pady=(2, 14))
        self._accent_color = accent_color

    def set_value(self, value: str) -> None:
        self._value.configure(text=value)


__all__ = [
    "apply_matplotlib_dark",
    "LEVEL_COLORS",
    "GlassCard",
    "FrostedTile",
    "GradientBackdrop",
    "SectionHeader",
    "StatTile",
    "font",
    "font_mono",
]
