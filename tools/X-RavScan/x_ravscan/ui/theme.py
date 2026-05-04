"""Glassmorphism theme — colour stack, matplotlib styling, GlassCard widget."""

from __future__ import annotations

from typing import Optional

import customtkinter as ctk

from x_ravscan.core.config import THEME


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
        "grid.alpha": 0.4,
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


class GlassCard(ctk.CTkFrame):
    """A 'frosted-glass' card: rounded corners, hairline border, layered bg.

    ``accent`` colours the 1-px border for a subtle neon outline. Pass
    ``accent=None`` for a plain glass card.
    """

    def __init__(
        self,
        master,
        *,
        accent: Optional[str] = None,
        nested: bool = False,
        corner_radius: int = 14,
        **kwargs,
    ) -> None:
        super().__init__(
            master,
            fg_color=THEME["glass_alt"] if nested else THEME["glass"],
            corner_radius=corner_radius,
            border_width=1,
            border_color=accent or THEME["border"],
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
            font=ctk.CTkFont(size=11, weight="bold"),
        ).pack(anchor="w")
        ctk.CTkLabel(
            self,
            text=description,
            text_color=THEME["text_dim"],
            font=ctk.CTkFont(size=11),
            wraplength=560,
            justify="left",
        ).pack(anchor="w", pady=(2, 0))
