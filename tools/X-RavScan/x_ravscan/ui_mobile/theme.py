"""Glassmorphism dark theme for the mobile UI.

Kivy doesn't have a real backdrop-filter, but we approximate the look with:
- A dark base background (#070a12) with a subtle radial gradient.
- Translucent ``MDCard`` containers (#0f1524 with low elevation).
- Hairline neon borders drawn via canvas ``Line`` instructions.
- Layered colours so nested cards (#141b2c, #1a2238) stay visually distinct.

These constants mirror :mod:`x_ravscan.core.config.THEME` so the desktop and
mobile builds share branding.
"""

from __future__ import annotations

from kivy.utils import get_color_from_hex


# ---------------------------------------------------------------------------
# Palette
# ---------------------------------------------------------------------------

PALETTE = {
    "bg":          "#070a12",
    "glass":       "#0f1524",
    "glass_alt":   "#141b2c",
    "glass_hi":    "#1a2238",
    "border":      "#23304a",
    "border_hi":   "#3b507c",
    "text":        "#e6edf3",
    "text_dim":    "#7f8a9d",
    "accent":      "#00ff9c",
    "accent_alt":  "#1f8cff",
    "accent_pink": "#ff4dd2",
    "accent_warn": "#ffb84d",
    "accent_red":  "#ff5874",
    "success":     "#00ff9c",
    "warning":     "#ffb84d",
    "error":       "#ff5874",
}


def rgba(name: str, alpha: float = 1.0) -> tuple:
    """Return the colour ``name`` from :data:`PALETTE` as Kivy RGBA."""
    if name.startswith("#"):
        r, g, b, _ = get_color_from_hex(name)
    else:
        r, g, b, _ = get_color_from_hex(PALETTE[name])
    return (r, g, b, alpha)
