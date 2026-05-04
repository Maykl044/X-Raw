"""Centralised theme constants & matplotlib styling helpers."""

from __future__ import annotations

from x_ravscan.core.config import THEME


def apply_matplotlib_dark() -> None:
    """Configure matplotlib to match the cyberpunk palette."""
    import matplotlib as mpl

    mpl.rcParams.update({
        "figure.facecolor": THEME["panel"],
        "axes.facecolor": THEME["panel"],
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
        "savefig.facecolor": THEME["panel"],
        "savefig.edgecolor": THEME["panel"],
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
