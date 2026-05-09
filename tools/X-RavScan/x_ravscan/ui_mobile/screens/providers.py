"""Providers screen — toggle CDN/cloud providers, run cloud sync."""

from __future__ import annotations

from typing import Callable, Dict, List

from kivy.graphics import Color, Ellipse
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.checkbox import CheckBox
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.widget import Widget

from x_ravscan.core.database import Database, Provider
from x_ravscan.core import providers_manager
from x_ravscan.i18n import t
from x_ravscan.ui_mobile.theme import rgba
from x_ravscan.ui_mobile.widgets import GlassCard, NeonButton, SectionHeader


class _ColorChip(Widget):
    """Small filled circle showing a provider's brand colour."""

    def __init__(self, color_hex: str, **kwargs) -> None:
        kwargs.setdefault("size_hint", (None, None))
        kwargs.setdefault("size", ("18dp", "18dp"))
        super().__init__(**kwargs)
        self._color_hex = color_hex
        self.bind(pos=self._redraw, size=self._redraw)

    def _redraw(self, *_args) -> None:
        self.canvas.clear()
        with self.canvas:
            Color(*rgba(self._color_hex if self._color_hex.startswith("#") else "accent"))
            Ellipse(pos=self.pos, size=self.size)


class _ProviderRow(GlassCard):
    """A single provider item: chip + name + ASN/CIDR meta + checkbox."""

    def __init__(
        self,
        prov: Provider,
        on_toggle: Callable[[str, bool], None],
        cidr_count: int,
        **kwargs,
    ) -> None:
        kwargs.setdefault("orientation", "horizontal")
        kwargs.setdefault("padding", (12, 8, 12, 8))
        kwargs.setdefault("spacing", 10)
        kwargs.setdefault("size_hint_y", None)
        kwargs.setdefault("height", "84dp")
        super().__init__(accent=prov.color, nested=True, **kwargs)
        self._prov = prov
        self._on_toggle = on_toggle

        # left: brand colour chip
        chip_box = BoxLayout(orientation="vertical", size_hint_x=None, width="22dp")
        chip_box.add_widget(Widget(size_hint_y=0.3))
        chip_box.add_widget(_ColorChip(prov.color))
        chip_box.add_widget(Widget(size_hint_y=0.3))
        self.add_widget(chip_box)

        # middle: name + meta
        text_col = BoxLayout(orientation="vertical", size_hint_x=1, spacing=2)
        name_lbl = Label(
            text=prov.name,
            color=rgba("text"),
            font_size="14sp",
            bold=True,
            halign="left",
            valign="middle",
        )
        name_lbl.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        text_col.add_widget(name_lbl)

        asn_str = ", ".join(str(a) for a in prov.asns[:6])
        if len(prov.asns) > 6:
            asn_str += "…"
        last = providers_manager.last_synced_label(prov)
        meta = f"{cidr_count} CIDR · ASN: {asn_str or '—'} · {t('providers.row.synced')}: {last}"
        meta_lbl = Label(
            text=meta,
            color=rgba("text_dim"),
            font_size="10sp",
            halign="left",
            valign="middle",
        )
        meta_lbl.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        text_col.add_widget(meta_lbl)
        self.add_widget(text_col)

        # right: checkbox toggle
        cb = CheckBox(
            active=prov.enabled,
            size_hint=(None, None),
            size=("32dp", "32dp"),
            pos_hint={"center_y": 0.5},
            color=rgba(prov.color if prov.color.startswith("#") else "accent"),
        )
        cb.bind(active=lambda _w, value: self._on_toggle(prov.slug, value))
        cb_wrap = BoxLayout(orientation="vertical", size_hint_x=None, width="40dp")
        cb_wrap.add_widget(Widget(size_hint_y=0.3))
        cb_wrap.add_widget(cb)
        cb_wrap.add_widget(Widget(size_hint_y=0.3))
        self.add_widget(cb_wrap)


class ProvidersScreen(BoxLayout):
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

        # Header card with bulk-action buttons
        header = GlassCard(size_hint_y=None, height="120dp")
        header.add_widget(
            SectionHeader(
                t("providers.header"),
                t("providers.header.desc"),
                accent="accent",
            )
        )
        actions = GridLayout(cols=3, spacing=8, size_hint_y=None, height="44dp")
        self._btn_enable = NeonButton(
            text=t("providers.enable_all"),
            accent="accent",
            on_press=self._enable_all,
            height="44dp",
        )
        self._btn_disable = NeonButton(
            text=t("providers.disable_all"),
            accent="accent_red",
            on_press=self._disable_all,
            height="44dp",
        )
        self._btn_refresh = NeonButton(
            text=t("providers.refresh"),
            accent="accent_alt",
            on_press=self.refresh,
            height="44dp",
        )
        for w in (self._btn_enable, self._btn_disable, self._btn_refresh):
            actions.add_widget(w)
        header.add_widget(actions)
        self.add_widget(header)

        # Scrollable list of providers
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

        self.refresh()

    # --- handlers ---
    def _enable_all(self) -> None:
        provs = self._db.list_providers()
        providers_manager.set_enabled_bulk(self._db, {p.slug: True for p in provs})
        self.refresh()
        self._on_log("SUCCESS", t("log.providers.enable_all"))

    def _disable_all(self) -> None:
        provs = self._db.list_providers()
        providers_manager.set_enabled_bulk(self._db, {p.slug: False for p in provs})
        self.refresh()
        self._on_log("WARNING", t("log.providers.disable_all"))

    def _toggle(self, slug: str, value: bool) -> None:
        self._db.set_provider_enabled(slug, value)

    def refresh(self, *_args) -> None:
        self._list.clear_widgets()
        provs = sorted(self._db.list_providers(), key=lambda p: p.name.lower())
        for prov in provs:
            count = len(self._db.list_ranges(prov.id))
            row = _ProviderRow(prov, self._toggle, count)
            self._list.add_widget(row)

    def relabel(self) -> None:
        self._btn_enable.update_text(t("providers.enable_all"))
        self._btn_disable.update_text(t("providers.disable_all"))
        self._btn_refresh.update_text(t("providers.refresh"))
        self.refresh()
