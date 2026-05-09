"""Settings screen — language picker, runtime info, paths."""

from __future__ import annotations

import platform
import sys
from typing import Callable

from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.spinner import Spinner

from x_ravscan.core.config import (
    APP_NAME,
    APP_VERSION,
    db_path,
    export_dir,
    log_path,
    user_data_dir,
)
from x_ravscan.core.database import Database
from x_ravscan.i18n import LANGUAGES, current_language, set_language, t
from x_ravscan.ui_mobile.theme import rgba
from x_ravscan.ui_mobile.widgets import GlassCard, NeonButton, SectionHeader


def _kv_row(parent, key: str, value: str, *, accent: str = "text") -> None:
    row = BoxLayout(
        orientation="horizontal",
        size_hint_y=None,
        height="28dp",
        spacing=8,
    )
    k = Label(
        text=key,
        color=rgba("text_dim"),
        font_size="11sp",
        size_hint_x=0.5,
        halign="left",
        valign="middle",
    )
    k.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
    v = Label(
        text=value,
        color=rgba(accent),
        font_size="11sp",
        size_hint_x=0.5,
        halign="right",
        valign="middle",
    )
    v.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
    row.add_widget(k)
    row.add_widget(v)
    parent.add_widget(row)


class SettingsScreen(BoxLayout):
    def __init__(
        self,
        db: Database,
        *,
        on_log: Callable[[str, str], None],
        on_relabel: Callable[[], None],
        **kwargs,
    ) -> None:
        kwargs.setdefault("orientation", "vertical")
        kwargs.setdefault("padding", (12, 12, 12, 12))
        kwargs.setdefault("spacing", 10)
        super().__init__(**kwargs)
        self._db = db
        self._on_log = on_log
        self._on_relabel = on_relabel

        scroll = ScrollView(do_scroll_x=False)
        body = BoxLayout(
            orientation="vertical",
            spacing=12,
            size_hint_y=None,
            padding=(0, 0, 0, 12),
        )
        body.bind(minimum_height=body.setter("height"))
        scroll.add_widget(body)
        self.add_widget(scroll)

        # ---- Appearance card ----
        self._appearance_card = GlassCard(size_hint_y=None, height="120dp")
        self._appearance_card.add_widget(
            SectionHeader(
                t("settings.section.appearance"),
                t("settings.section.appearance.desc"),
                accent="accent_pink",
            )
        )
        theme_row = BoxLayout(orientation="horizontal", size_hint_y=None, height="32dp")
        self._theme_key = Label(
            text=t("settings.field.theme"),
            color=rgba("text_dim"),
            font_size="11sp",
            halign="left",
            valign="middle",
            size_hint_x=0.5,
        )
        self._theme_key.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        self._theme_val = Label(
            text=t("settings.field.theme.value"),
            color=rgba("accent_pink"),
            font_size="12sp",
            bold=True,
            halign="right",
            valign="middle",
            size_hint_x=0.5,
        )
        self._theme_val.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        theme_row.add_widget(self._theme_key)
        theme_row.add_widget(self._theme_val)
        self._appearance_card.add_widget(theme_row)
        body.add_widget(self._appearance_card)

        # ---- Language card ----
        self._lang_card = GlassCard(size_hint_y=None, height="180dp")
        self._lang_card.add_widget(
            SectionHeader(
                t("settings.section.language"),
                t("settings.section.language.desc"),
                accent="accent_alt",
            )
        )
        spinner_row = BoxLayout(
            orientation="horizontal", size_hint_y=None, height="44dp", spacing=10
        )
        self._lang_lbl = Label(
            text=t("settings.field.language"),
            color=rgba("text_dim"),
            font_size="11sp",
            size_hint_x=0.4,
            halign="left",
            valign="middle",
        )
        self._lang_lbl.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        spinner_row.add_widget(self._lang_lbl)

        codes = [code for code, _ in LANGUAGES]
        labels = [name for _, name in LANGUAGES]
        active = current_language()
        active_label = labels[codes.index(active)] if active in codes else labels[0]
        self._spinner = Spinner(
            text=active_label,
            values=labels,
            size_hint_x=0.6,
            background_color=rgba("glass_alt", 1.0),
            color=rgba("accent_alt"),
            background_normal="",
        )
        self._codes = codes
        self._labels = labels
        self._spinner.bind(text=self._on_lang_pick)
        spinner_row.add_widget(self._spinner)
        self._lang_card.add_widget(spinner_row)

        self._lang_hint = Label(
            text=t("settings.section.language.hint"),
            color=rgba("text_dim"),
            font_size="10sp",
            halign="left",
            valign="top",
            size_hint_y=None,
            height="36dp",
        )
        self._lang_hint.bind(size=lambda lb, *_: setattr(lb, "text_size", lb.size))
        self._lang_card.add_widget(self._lang_hint)
        body.add_widget(self._lang_card)

        # ---- Paths card ----
        self._paths_card = GlassCard(size_hint_y=None, height="200dp")
        self._paths_card.add_widget(
            SectionHeader(
                t("settings.section.paths"),
                t("settings.section.paths.desc"),
                accent="accent_alt",
            )
        )
        _kv_row(self._paths_card, t("settings.field.data_dir"), str(user_data_dir()))
        _kv_row(self._paths_card, t("settings.field.db"), str(db_path()))
        _kv_row(self._paths_card, t("settings.field.log_dir"), str(log_path().parent))
        _kv_row(self._paths_card, t("settings.field.export_dir"), str(export_dir()))
        body.add_widget(self._paths_card)

        # ---- About card ----
        self._about_card = GlassCard(size_hint_y=None, height="170dp")
        self._about_card.add_widget(
            SectionHeader(
                t("settings.section.about"),
                t("settings.section.about.desc"),
                accent="accent",
            )
        )
        _kv_row(
            self._about_card,
            t("settings.field.app"),
            f"{APP_NAME} v{APP_VERSION}",
            accent="accent",
        )
        _kv_row(
            self._about_card,
            t("settings.field.runtime"),
            f"Python {sys.version.split()[0]} · {platform.system()} {platform.release()}",
        )
        _kv_row(
            self._about_card,
            t("settings.field.frozen"),
            "yes" if getattr(sys, "frozen", False) else "no",
        )
        body.add_widget(self._about_card)

    def _on_lang_pick(self, _spinner, label: str) -> None:
        if label not in self._labels:
            return
        code = self._codes[self._labels.index(label)]
        if code == current_language():
            return
        set_language(code)
        try:
            self._db.set_setting("ui.language", code)
        except Exception as exc:  # noqa: BLE001
            self._on_log("ERROR", f"save language: {exc}")
            return
        self._on_log(
            "SUCCESS",
            t("log.settings.language", lang=label),
        )
        self._refresh_labels()
        # Notify the rest of the UI to relabel itself live.
        self._on_relabel()

    def _refresh_labels(self) -> None:
        self._appearance_card.children[-1].title = t("settings.section.appearance")
        # SectionHeader is a normal BoxLayout — easier to rebuild on language change.
        self._theme_key.text = t("settings.field.theme")
        self._theme_val.text = t("settings.field.theme.value")
        self._lang_lbl.text = t("settings.field.language")
        self._lang_hint.text = t("settings.section.language.hint")

    def relabel(self) -> None:
        self._refresh_labels()
