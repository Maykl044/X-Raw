"""Reusable glassmorphism widgets for the mobile UI.

We don't depend on KivyMD ``MDCard`` directly so the same widgets can be
used in plain Kivy (useful for the desktop debug runner that doesn't
require KivyMD's Material theme manager).
"""

from __future__ import annotations

from typing import Optional

from kivy.uix.boxlayout import BoxLayout
from kivy.uix.label import Label
from kivy.graphics import Color, Line, RoundedRectangle
from kivy.properties import ListProperty, NumericProperty, StringProperty

from x_ravscan.ui_mobile.theme import PALETTE, rgba


class GlassCard(BoxLayout):
    """A frosted-glass card: rounded background + 1-px neon border.

    Colour priorities:
        * ``accent`` overrides the border (used for clustered colour-coding).
        * ``nested=True`` darkens the fill so cards-within-cards stay legible.
    """

    radius = NumericProperty(18)
    accent_color = ListProperty([0, 0, 0, 0])
    fill_color = ListProperty([0, 0, 0, 0])

    def __init__(
        self,
        *,
        accent: Optional[str] = None,
        nested: bool = False,
        radius: int = 18,
        **kwargs,
    ) -> None:
        kwargs.setdefault("orientation", "vertical")
        kwargs.setdefault("padding", (16, 12, 16, 12))
        kwargs.setdefault("spacing", 6)
        super().__init__(**kwargs)
        self.radius = radius
        self.fill_color = list(rgba("glass_alt" if nested else "glass", 0.92))
        self.accent_color = list(rgba(accent if accent else "border"))
        with self.canvas.before:
            self._fill = Color(*self.fill_color)
            self._bg = RoundedRectangle(pos=self.pos, size=self.size, radius=[radius])
            self._stroke = Color(*self.accent_color)
            self._line = Line(rounded_rectangle=(0, 0, 0, 0, radius), width=1.1)
        self.bind(pos=self._refresh, size=self._refresh)
        self.bind(accent_color=self._refresh, fill_color=self._refresh)

    def _refresh(self, *_args) -> None:
        self._bg.pos = self.pos
        self._bg.size = self.size
        self._fill.rgba = self.fill_color
        self._stroke.rgba = self.accent_color
        x, y = self.pos
        w, h = self.size
        self._line.rounded_rectangle = (x, y, w, h, self.radius)


class SectionHeader(BoxLayout):
    """Card header: small accent title + dim description."""

    title = StringProperty("")
    description = StringProperty("")

    def __init__(
        self,
        title: str,
        description: str = "",
        *,
        accent: Optional[str] = None,
        **kwargs,
    ) -> None:
        kwargs.setdefault("orientation", "vertical")
        kwargs.setdefault("size_hint_y", None)
        super().__init__(**kwargs)
        self.title = title
        self.description = description
        title_color = rgba(accent or "accent")
        title_lbl = Label(
            text=title.upper(),
            color=title_color,
            font_size="13sp",
            bold=True,
            halign="left",
            valign="middle",
            size_hint_y=None,
            height="20dp",
        )
        title_lbl.bind(size=lambda lbl, *_: setattr(lbl, "text_size", lbl.size))
        self.add_widget(title_lbl)
        if description:
            desc = Label(
                text=description,
                color=rgba("text_dim"),
                font_size="11sp",
                halign="left",
                valign="top",
                size_hint_y=None,
                height="32dp",
            )
            desc.bind(size=lambda lbl, *_: setattr(lbl, "text_size", lbl.size))
            self.add_widget(desc)
            self.height = "56dp"
        else:
            self.height = "24dp"


class StatTile(GlassCard):
    """Compact metric tile used in the dashboard summary strip."""

    def __init__(self, *, label: str, value: str = "0", accent: str = "accent", **kwargs):
        kwargs.setdefault("padding", (14, 10, 14, 10))
        kwargs.setdefault("size_hint", (1, None))
        kwargs.setdefault("height", "92dp")
        super().__init__(accent=accent, **kwargs)
        self._label_lbl = Label(
            text=label.upper(),
            color=rgba("text_dim"),
            font_size="10sp",
            bold=True,
            halign="left",
            valign="top",
            size_hint_y=None,
            height="16dp",
        )
        self._label_lbl.bind(
            size=lambda lbl, *_: setattr(lbl, "text_size", lbl.size)
        )
        self._value_lbl = Label(
            text=value,
            color=rgba(accent),
            font_size="28sp",
            bold=True,
            halign="left",
            valign="middle",
        )
        self._value_lbl.bind(
            size=lambda lbl, *_: setattr(lbl, "text_size", lbl.size)
        )
        self.add_widget(self._label_lbl)
        self.add_widget(self._value_lbl)

    def update_label(self, label: str) -> None:
        self._label_lbl.text = label.upper()

    def set_value(self, value) -> None:
        self._value_lbl.text = str(value)


class NeonButton(BoxLayout):
    """Touch-friendly button with a coloured border and label.

    KivyMD MDRaisedButton works too, but we use a custom widget for the
    glassmorphism aesthetic and to avoid pulling Material ripple effects on
    every press (which would clash with the cyber palette).
    """

    text = StringProperty("")
    accent = StringProperty("accent")

    def __init__(
        self,
        *,
        text: str = "",
        accent: str = "accent",
        on_press=None,
        height: str = "44dp",
        **kwargs,
    ) -> None:
        kwargs.setdefault("orientation", "horizontal")
        kwargs.setdefault("size_hint_y", None)
        super().__init__(**kwargs)
        self.text = text
        self.accent = accent
        self.height = height
        self._on_press = on_press
        with self.canvas.before:
            self._fill_color = Color(*rgba("glass_alt", 0.95))
            self._fill_rect = RoundedRectangle(pos=self.pos, size=self.size, radius=[12])
            self._stroke_color = Color(*rgba(accent))
            self._stroke_line = Line(rounded_rectangle=(0, 0, 0, 0, 12), width=1.4)
        self.bind(pos=self._refresh, size=self._refresh)
        self._lbl = Label(
            text=text,
            color=rgba(accent),
            font_size="13sp",
            bold=True,
            halign="center",
            valign="middle",
        )
        self._lbl.bind(size=lambda lbl, *_: setattr(lbl, "text_size", lbl.size))
        self.add_widget(self._lbl)

    def _refresh(self, *_args) -> None:
        self._fill_rect.pos = self.pos
        self._fill_rect.size = self.size
        x, y = self.pos
        w, h = self.size
        self._stroke_line.rounded_rectangle = (x, y, w, h, 12)

    def update_text(self, text: str) -> None:
        self.text = text
        self._lbl.text = text

    def on_touch_down(self, touch):
        if self.collide_point(*touch.pos):
            self._fill_color.rgba = rgba("glass_hi", 0.95)
            return True
        return super().on_touch_down(touch)

    def on_touch_up(self, touch):
        was_pressed = self.collide_point(*touch.pos) and self._fill_color.rgba[1] != rgba("glass_alt", 0.95)[1]
        self._fill_color.rgba = rgba("glass_alt", 0.95)
        if was_pressed and self._on_press:
            try:
                self._on_press()
            except Exception:  # noqa: BLE001 — UI handler shouldn't crash app
                import traceback

                traceback.print_exc()
            return True
        return super().on_touch_up(touch)
