"""Main application window for X-RavScan — glassmorphism dark UI + i18n."""

from __future__ import annotations

import asyncio
import platform
import sys
import threading
import time
import tkinter as tk
import tkinter.filedialog as filedialog
import tkinter.messagebox as messagebox
from typing import Dict, List, Optional, Tuple

import customtkinter as ctk

from x_ravscan import __version__
from x_ravscan.core.config import (
    APP_NAME,
    DEFAULT_CONCURRENCY,
    DEFAULT_PORT,
    THEME,
    db_path,
    export_dir,
    log_path,
    user_data_dir,
)
from x_ravscan.core.database import Database, Provider
from x_ravscan.core import exporter, network_updater, providers_manager, scanner
from x_ravscan.i18n import (
    LANGUAGES,
    current_language,
    language_name,
    set_language,
    t,
)
from x_ravscan.ui.dashboard import LogConsole, PPSChart, ProviderPie
from x_ravscan.ui.theme import (
    FrostedTile,
    GlassCard,
    GradientBackdrop,
    SectionHeader,
    StatTile,
    font,
)
from x_ravscan.utils.logger import add_ui_handler, get_logger


log = get_logger("ui")


ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("dark-blue")


# ---------------------------------------------------------------------------
# i18n bootstrap helpers used by main.py before we open the window
# ---------------------------------------------------------------------------


def load_persisted_language(db: Database) -> str:
    """Read the saved language from app_settings (or fall back to default)."""
    code = db.get_setting("ui.language") or ""
    return set_language(code)


# ---------------------------------------------------------------------------
# Main window
# ---------------------------------------------------------------------------


class XRavScanApp(ctk.CTk):
    """Top-level CTk window with glassmorphism dark theme."""

    def __init__(self, db: Database) -> None:
        super().__init__()
        self.db = db
        self.title(f"{APP_NAME} v{__version__}")
        self.geometry("1340x860")
        self.minsize(1180, 740)
        self.configure(fg_color=THEME["bg"])
        # System fonts: prefer SF Pro / Inter, fall back to Segoe UI.
        try:
            self.option_add("*Font", ("SF Pro Display", 10))
        except tk.TclError:
            pass

        self._cancel_event: Optional[asyncio.Event] = None
        self._scan_thread: Optional[threading.Thread] = None
        self._scan_loop: Optional[asyncio.AbstractEventLoop] = None
        self._provider_vars: Dict[str, tk.BooleanVar] = {}
        self._language_var = tk.StringVar(value=current_language())

        # Build the real layout with a fallback safety net: if anything in
        # the layout / refresh chain throws (e.g. a DPI race in CTk, a
        # missing locale file, a corrupt SQLite row), surface the
        # traceback in a still-visible CTk window instead of letting the
        # process die silently. This is what "logo → close" used to look
        # like with PyInstaller's ``--windowed`` mode.
        try:
            self._build_layout()
            self._wire_logging()
            self._refresh_providers_panel()
            self._refresh_results_panel()
        except Exception:  # noqa: BLE001
            import traceback as _tb

            text = _tb.format_exc()
            log.exception("XRavScanApp layout failed")
            try:
                self._show_layout_error(text)
            except Exception:  # noqa: BLE001
                # If even the fallback widget cannot render we re-raise so
                # the global excepthook in main.py can pop the MessageBox.
                raise
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ------------------------------------------------------------------
    # Fallback error UI
    # ------------------------------------------------------------------
    def _show_layout_error(self, text: str) -> None:
        for child in list(self.winfo_children()):
            try:
                child.destroy()
            except Exception:  # noqa: BLE001
                pass
        wrap = ctk.CTkFrame(self, fg_color=THEME["bg"])
        wrap.pack(fill="both", expand=True, padx=16, pady=16)
        ctk.CTkLabel(
            wrap,
            text="X-RavScan failed to build its main window",
            text_color="#ff7c8e",
            font=ctk.CTkFont(size=18, weight="bold"),
        ).pack(anchor="w", pady=(0, 8))
        ctk.CTkLabel(
            wrap,
            text=(
                "The error below has been written to "
                f"{log_path().parent / 'crash.txt'} — please send a "
                "screenshot or that file to support."
            ),
            text_color=THEME["text_dim"],
            justify="left",
            wraplength=900,
        ).pack(anchor="w", pady=(0, 12))
        box = ctk.CTkTextbox(
            wrap,
            fg_color=THEME["glass"],
            text_color=THEME["text"],
            border_width=1,
            border_color=THEME["border"],
            corner_radius=12,
            font=ctk.CTkFont(family="Consolas", size=11),
        )
        box.pack(fill="both", expand=True)
        box.insert("1.0", text)
        box.configure(state="disabled")

    # ------------------------------------------------------------------
    # Layout
    # ------------------------------------------------------------------
    def _build_layout(self) -> None:
        self.grid_columnconfigure(0, weight=0, minsize=270)
        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(0, weight=1)

        # Gradient backdrop spans the whole window (behind sidebar + body).
        self._backdrop = GradientBackdrop(self)
        self._backdrop.place(x=0, y=0, relwidth=1, relheight=1)

        self._build_sidebar()

        body = ctk.CTkFrame(self, fg_color="transparent", corner_radius=0)
        body.grid(row=0, column=1, sticky="nsew")
        body.grid_columnconfigure(0, weight=1)
        body.grid_rowconfigure(0, weight=1)

        self._tabs = ctk.CTkTabview(
            body,
            fg_color=THEME["glass"],
            border_color=THEME["border"],
            border_width=1,
            corner_radius=THEME["radius"],
            segmented_button_fg_color=THEME["glass_alt"],
            segmented_button_unselected_color=THEME["glass_alt"],
            segmented_button_unselected_hover_color=THEME["glass_hi"],
            segmented_button_selected_color=THEME["accent"],
            segmented_button_selected_hover_color="#9bbeff",
            text_color=THEME["text"],
            text_color_disabled=THEME["text_muted"],
        )
        self._tabs.grid(row=0, column=0, padx=18, pady=18, sticky="nsew")

        self._tab_keys = [
            ("Dashboard", "tab.dashboard"),
            ("Providers", "tab.providers"),
            ("Discovery", "tab.discovery"),
            ("Results", "tab.results"),
            ("Settings", "tab.settings"),
        ]
        for tab, _ in self._tab_keys:
            self._tabs.add(t(f"tab.{tab.lower()}"))
        # CTkTabview indexes tabs by displayed name — track the localized
        # names so we can look them up later.
        self._tab_names = {key: t(f"tab.{name.lower()}") for name, key in self._tab_keys}

        self._build_dashboard_tab(self._tabs.tab(self._tab_names["tab.dashboard"]))
        self._build_providers_tab(self._tabs.tab(self._tab_names["tab.providers"]))
        self._build_discovery_tab(self._tabs.tab(self._tab_names["tab.discovery"]))
        self._build_results_tab(self._tabs.tab(self._tab_names["tab.results"]))
        self._build_settings_tab(self._tabs.tab(self._tab_names["tab.settings"]))

    def _build_sidebar(self) -> None:
        side = ctk.CTkFrame(
            self,
            fg_color=THEME["glass"],
            border_color=THEME["border"],
            border_width=1,
            corner_radius=THEME["radius"],
        )
        side.grid(row=0, column=0, sticky="nsw", padx=(18, 0), pady=18)
        side.grid_rowconfigure(99, weight=1)
        side.grid_columnconfigure(0, weight=1)

        # Brand block
        brand = ctk.CTkFrame(side, fg_color="transparent")
        brand.grid(row=0, column=0, padx=18, pady=(20, 4), sticky="ew")
        ctk.CTkLabel(
            brand,
            text=APP_NAME,
            text_color=THEME["accent"],
            font=ctk.CTkFont("Consolas", 24, weight="bold"),
        ).pack(anchor="w")
        ctk.CTkLabel(
            brand,
            text=t("app.subtitle"),
            text_color=THEME["text_dim"],
            font=ctk.CTkFont(size=10),
            wraplength=210,
            justify="left",
        ).pack(anchor="w")
        ctk.CTkLabel(
            brand,
            text=f"v{__version__}",
            text_color=THEME["text_muted"],
            font=ctk.CTkFont(size=10),
        ).pack(anchor="w", pady=(4, 0))

        # Engine / concurrency / sample inside a glass card
        card = GlassCard(side, nested=True)
        card.grid(row=1, column=0, padx=14, pady=12, sticky="ew")
        card.grid_columnconfigure(0, weight=1)

        ctk.CTkLabel(card, text=t("sidebar.engine"), text_color=THEME["text_dim"], font=ctk.CTkFont(size=10, weight="bold")).grid(row=0, column=0, padx=14, pady=(12, 2), sticky="w")
        self._engine = ctk.CTkOptionMenu(
            card,
            values=["asyncio", "masscan", "zmap"],
            fg_color=THEME["glass_hi"],
            button_color=THEME["glass_hi"],
            button_hover_color=THEME["border_hi"],
            text_color=THEME["text"],
        )
        self._engine.set("asyncio")
        self._engine.grid(row=1, column=0, padx=14, pady=(0, 10), sticky="ew")

        ctk.CTkLabel(card, text=t("sidebar.concurrency"), text_color=THEME["text_dim"], font=ctk.CTkFont(size=10, weight="bold")).grid(row=2, column=0, padx=14, pady=(0, 2), sticky="w")
        self._conc = ctk.CTkSlider(card, from_=64, to=2048, number_of_steps=31, progress_color=THEME["accent"], button_color=THEME["accent"], button_hover_color="#5cffb6")
        self._conc.set(DEFAULT_CONCURRENCY)
        self._conc.grid(row=3, column=0, padx=14, pady=(0, 2), sticky="ew")
        self._conc_lbl = ctk.CTkLabel(card, text=str(DEFAULT_CONCURRENCY), text_color=THEME["accent"], font=ctk.CTkFont(size=11, weight="bold"))
        self._conc_lbl.grid(row=4, column=0, padx=14, pady=(0, 8), sticky="w")
        self._conc.configure(command=self._on_conc_change)

        ctk.CTkLabel(card, text=t("sidebar.sample"), text_color=THEME["text_dim"], font=ctk.CTkFont(size=10, weight="bold")).grid(row=5, column=0, padx=14, pady=(0, 2), sticky="w")
        self._sample = ctk.CTkSlider(card, from_=16, to=1024, number_of_steps=63, progress_color=THEME["accent_alt"], button_color=THEME["accent_alt"], button_hover_color="#67b7ff")
        self._sample.set(128)
        self._sample.grid(row=6, column=0, padx=14, pady=(0, 2), sticky="ew")
        self._sample_lbl = ctk.CTkLabel(card, text="128", text_color=THEME["accent_alt"], font=ctk.CTkFont(size=11, weight="bold"))
        self._sample_lbl.grid(row=7, column=0, padx=14, pady=(0, 12), sticky="w")
        self._sample.configure(command=self._on_sample_change)

        # CTAs
        self._scan_btn = ctk.CTkButton(
            side,
            text=t("sidebar.start"),
            font=ctk.CTkFont(weight="bold"),
            fg_color=THEME["accent"],
            text_color=THEME["bg"],
            hover_color="#5cffb6",
            corner_radius=12,
            command=self._on_start_scan,
        )
        self._scan_btn.grid(row=2, column=0, padx=14, pady=(0, 6), sticky="ew")

        self._stop_btn = ctk.CTkButton(
            side,
            text=t("sidebar.stop"),
            fg_color=THEME["danger"],
            text_color=THEME["bg"],
            hover_color="#ff7c7c",
            corner_radius=12,
            command=self._on_stop_scan,
            state="disabled",
        )
        self._stop_btn.grid(row=3, column=0, padx=14, pady=(0, 16), sticky="ew")

        ctk.CTkButton(
            side,
            text=t("sidebar.cloud_sync"),
            fg_color="transparent",
            border_color=THEME["accent_alt"],
            border_width=1,
            text_color=THEME["accent_alt"],
            hover_color=THEME["glass_hi"],
            corner_radius=12,
            command=self._on_cloud_sync,
        ).grid(row=4, column=0, padx=14, pady=(0, 6), sticky="ew")

        ctk.CTkButton(
            side,
            text=t("sidebar.discovery"),
            fg_color="transparent",
            border_color=THEME["accent"],
            border_width=1,
            text_color=THEME["accent"],
            hover_color=THEME["glass_hi"],
            corner_radius=12,
            command=self._on_smart_discovery,
        ).grid(row=5, column=0, padx=14, pady=(0, 18), sticky="ew")

    # ------------------------------------------------------------------
    # Dashboard
    # ------------------------------------------------------------------
    def _build_dashboard_tab(self, parent) -> None:
        parent.grid_rowconfigure(2, weight=1)
        parent.grid_columnconfigure(0, weight=1)
        parent.grid_columnconfigure(1, weight=0, minsize=420)

        # Stats row — four floating glass tiles
        stats_row = ctk.CTkFrame(parent, fg_color="transparent")
        stats_row.grid(row=0, column=0, columnspan=2, sticky="ew", padx=4, pady=(4, 14))
        for c in range(4):
            stats_row.grid_columnconfigure(c, weight=1, uniform="stats")
        self._stat_total = self._make_stat(stats_row, 0, t("stat.targets"), "0", THEME["accent"])
        self._stat_done = self._make_stat(stats_row, 1, t("stat.completed"), "0", THEME["accent_alt"])
        self._stat_alive = self._make_stat(stats_row, 2, t("stat.alive"), "0", THEME["accent_mint"])
        self._stat_pps = self._make_stat(stats_row, 3, t("stat.pps"), "0.0", THEME["accent_pink"])

        # Charts row
        self._pps_chart = PPSChart(parent)
        self._pps_chart.grid(row=1, column=0, sticky="nsew", padx=4, pady=4)
        self._pie = ProviderPie(parent)
        self._pie.grid(row=1, column=1, sticky="nsew", padx=4, pady=4)
        parent.grid_rowconfigure(1, weight=1)

        # Activity log under the charts
        self._console = LogConsole(parent)
        self._console.grid(row=2, column=0, columnspan=2, sticky="nsew", padx=4, pady=(14, 4))

    def _make_stat(self, parent, col: int, label: str, value: str, color: str) -> ctk.CTkLabel:
        tile = StatTile(parent, label, value, accent=color)
        tile.grid(row=0, column=col, padx=8, pady=4, sticky="nsew")
        # Backwards-compat: callers expect a CTkLabel they can ``.configure(text=...)`` on.
        return tile._value

    # ------------------------------------------------------------------
    # Providers
    # ------------------------------------------------------------------
    def _build_providers_tab(self, parent) -> None:
        parent.grid_rowconfigure(1, weight=1)
        parent.grid_columnconfigure(0, weight=1)

        toolbar = ctk.CTkFrame(parent, fg_color="transparent")
        toolbar.grid(row=0, column=0, sticky="ew", pady=(4, 8))
        for label_key, color, fn in (
            ("providers.toolbar.enable_all", THEME["accent"], lambda: self._toggle_all(True)),
            ("providers.toolbar.disable_all", THEME["danger"], lambda: self._toggle_all(False)),
            ("providers.toolbar.refresh", THEME["accent_alt"], self._refresh_providers_panel),
        ):
            ctk.CTkButton(
                toolbar,
                text=t(label_key),
                fg_color="transparent",
                border_color=color,
                border_width=1,
                text_color=color,
                hover_color=THEME["glass_hi"],
                corner_radius=10,
                command=fn,
            ).pack(side="left", padx=4)

        wrap = GlassCard(parent)
        wrap.grid(row=1, column=0, sticky="nsew", padx=4, pady=4)
        wrap.grid_rowconfigure(0, weight=1)
        wrap.grid_columnconfigure(0, weight=1)

        self._providers_box = ctk.CTkScrollableFrame(
            wrap, fg_color="transparent", corner_radius=0,
        )
        self._providers_box.grid(row=0, column=0, padx=8, pady=8, sticky="nsew")
        self._providers_box.grid_columnconfigure(0, weight=1)

    def _refresh_providers_panel(self) -> None:
        try:
            self._do_refresh_providers_panel()
        except Exception:  # noqa: BLE001
            log.exception("refresh providers panel failed")

    def _do_refresh_providers_panel(self) -> None:
        # Use ``pack`` for the outer rows. CTkScrollableFrame's internal
        # canvas/inner-frame combo can throw ``TclError: row out of bounds``
        # when grid is called repeatedly with sparse rows after children
        # were destroyed. Pack has no row state so it's bulletproof.
        for child in list(self._providers_box.winfo_children()):
            try:
                child.destroy()
            except Exception:  # noqa: BLE001
                pass
        self._provider_vars.clear()

        try:
            providers = self.db.list_providers()
        except Exception:  # noqa: BLE001
            log.exception("list_providers failed")
            providers = []

        for prov in providers:
            row = GlassCard(self._providers_box, nested=True, accent=prov.color, corner_radius=10)
            row.pack(fill="x", padx=4, pady=4)
            row.grid_columnconfigure(1, weight=1)

            var = tk.BooleanVar(value=prov.enabled)
            self._provider_vars[prov.slug] = var
            chk = ctk.CTkCheckBox(
                row,
                text="",
                variable=var,
                width=24,
                fg_color=prov.color,
                hover_color=prov.color,
                border_color=THEME["border_hi"],
                command=lambda slug=prov.slug, v=var: self._on_provider_toggle(slug, v.get()),
            )
            chk.grid(row=0, column=0, rowspan=2, padx=(12, 6), pady=10)

            ctk.CTkLabel(
                row,
                text=prov.name,
                text_color=THEME["text"],
                font=ctk.CTkFont(size=13, weight="bold"),
                anchor="w",
            ).grid(row=0, column=1, sticky="w", padx=4, pady=(8, 0))

            try:
                cidr_count = len(self.db.list_ranges(prov.id)) if prov.id is not None else 0
            except Exception:  # noqa: BLE001
                cidr_count = 0
            asns = ", ".join(map(str, prov.asns)) or "—"
            sub = " · ".join([
                t("providers.row.cidrs", count=cidr_count),
                t("providers.row.asns", asns=asns),
                t("providers.row.synced", when=providers_managed_label(prov)),
            ])
            ctk.CTkLabel(
                row,
                text=sub,
                text_color=THEME["text_dim"],
                anchor="w",
                font=ctk.CTkFont(size=11),
            ).grid(row=1, column=1, sticky="w", padx=4, pady=(0, 8))

            dot = tk.Canvas(row, width=14, height=14, highlightthickness=0, bg=THEME["glass_alt"])
            dot.create_oval(2, 2, 12, 12, fill=prov.color, outline=prov.color)
            dot.grid(row=0, column=2, rowspan=2, padx=14, pady=10)

    def _on_provider_toggle(self, slug: str, enabled: bool) -> None:
        self.db.set_provider_enabled(slug, enabled)
        log.info("provider %s -> %s", slug, "enabled" if enabled else "disabled")

    def _toggle_all(self, enabled: bool) -> None:
        for slug in list(self._provider_vars.keys()):
            self._provider_vars[slug].set(enabled)
            self.db.set_provider_enabled(slug, enabled)
        log.info("bulk toggle providers -> %s", enabled)

    # ------------------------------------------------------------------
    # Discovery
    # ------------------------------------------------------------------
    def _build_discovery_tab(self, parent) -> None:
        parent.grid_rowconfigure(1, weight=1)
        parent.grid_columnconfigure(0, weight=1)

        bar = ctk.CTkFrame(parent, fg_color="transparent")
        bar.grid(row=0, column=0, sticky="ew", pady=(4, 8))
        ctk.CTkButton(bar, text=t("discovery.run"), fg_color=THEME["accent"], text_color=THEME["bg"], hover_color="#5cffb6", corner_radius=10, command=self._on_smart_discovery).pack(side="left", padx=4)
        ctk.CTkButton(bar, text=t("discovery.deep", default="Deep Discovery"), fg_color="#a98bff", text_color=THEME["bg"], hover_color="#beadff", corner_radius=10, command=self._on_deep_discovery).pack(side="left", padx=4)
        ctk.CTkButton(bar, text=t("discovery.smart_append", default="Smart Append (ASN)"), fg_color="#46d58e", text_color=THEME["bg"], hover_color="#6ee0a8", corner_radius=10, command=self._on_smart_append).pack(side="left", padx=4)
        ctk.CTkButton(bar, text=t("discovery.optimize", default="Clean & Optimize"), fg_color=THEME["accent_pink"], text_color=THEME["bg"], hover_color="#ff9be0", corner_radius=10, command=self._on_clean_optimize).pack(side="left", padx=4)
        ctk.CTkButton(bar, text=t("discovery.auto_sync"), fg_color=THEME["accent_alt"], text_color=THEME["bg"], hover_color="#67b7ff", corner_radius=10, command=self._on_auto_sync).pack(side="left", padx=4)
        ctk.CTkButton(bar, text=t("discovery.refresh"), fg_color="transparent", border_color=THEME["border_hi"], border_width=1, text_color=THEME["text"], hover_color=THEME["glass_hi"], corner_radius=10, command=self._refresh_discoveries_panel).pack(side="left", padx=4)

        wrap = GlassCard(parent)
        wrap.grid(row=1, column=0, sticky="nsew", padx=4, pady=4)
        wrap.grid_rowconfigure(0, weight=1)
        wrap.grid_columnconfigure(0, weight=1)
        self._discovery_box = ctk.CTkScrollableFrame(wrap, fg_color="transparent", corner_radius=0)
        self._discovery_box.grid(row=0, column=0, padx=8, pady=8, sticky="nsew")
        self._discovery_box.grid_columnconfigure(0, weight=1)
        self._discovery_widgets: List = []
        # Defer initial refresh so the widget tree is fully realised before we
        # start placing children inside the scrollable frame (Windows DPI/grid
        # initialisation race).
        self.after(50, self._refresh_discoveries_panel)

    def _refresh_discoveries_panel(self) -> None:
        try:
            self._do_refresh_discoveries_panel()
        except Exception:  # noqa: BLE001
            log.exception("refresh discovery panel failed")

    def _do_refresh_discoveries_panel(self) -> None:
        # Destroy only widgets WE added. We use ``pack`` instead of ``grid``
        # for the outer rows because ``CTkScrollableFrame`` repeatedly
        # gridded with sparse rows triggers ``TclError: row out of bounds``
        # when its internal frame is rebuilt — pack has no row state.
        for w in list(getattr(self, "_discovery_widgets", []) or []):
            try:
                w.destroy()
            except Exception:  # noqa: BLE001
                pass
        self._discovery_widgets = []

        try:
            rows = self.db.list_pending_discoveries()
        except Exception:  # noqa: BLE001
            log.exception("list_pending_discoveries failed")
            rows = []

        if not rows:
            empty = ctk.CTkLabel(
                self._discovery_box,
                text=t("discovery.empty"),
                text_color=THEME["text_dim"],
                wraplength=720,
                justify="left",
            )
            empty.pack(anchor="w", padx=14, pady=14, fill="x")
            self._discovery_widgets.append(empty)
            return

        for r in rows:
            box = GlassCard(self._discovery_box, nested=True, corner_radius=10)
            box.pack(fill="x", padx=4, pady=4)
            # Inside the card we grid 3 fixed columns — that grid is brand
            # new (the GlassCard was just created) so it can never be
            # "row out of bounds".
            box.grid_columnconfigure(1, weight=1)
            ctk.CTkLabel(
                box,
                text=r["cidr"],
                text_color=THEME["accent"],
                font=ctk.CTkFont(weight="bold", family="Consolas"),
            ).grid(row=0, column=0, padx=14, pady=10, sticky="w")
            desc = r["description"] or ""
            ctk.CTkLabel(
                box,
                text=f"AS{r['asn']} · {r['provider_name']} · {desc}",
                text_color=THEME["text_dim"],
                anchor="w",
            ).grid(row=0, column=1, padx=4, pady=10, sticky="w")
            ctk.CTkButton(
                box,
                text=t("discovery.accept"),
                fg_color=THEME["accent"],
                text_color=THEME["bg"],
                width=90,
                corner_radius=8,
                hover_color="#5cffb6",
                command=lambda pid=r["provider_id"]: self._accept_discoveries(pid),
            ).grid(row=0, column=2, padx=10, pady=8)
            self._discovery_widgets.append(box)

    def _accept_discoveries(self, provider_id: int) -> None:
        n = self.db.accept_discoveries(provider_id)
        log.info("accepted %d discoveries for provider id=%s", n, provider_id)
        self._refresh_discoveries_panel()
        self._refresh_providers_panel()

    # ------------------------------------------------------------------
    # Results
    # ------------------------------------------------------------------
    def _build_results_tab(self, parent) -> None:
        parent.grid_rowconfigure(1, weight=1)
        parent.grid_columnconfigure(0, weight=1)

        bar = ctk.CTkFrame(parent, fg_color="transparent")
        bar.grid(row=0, column=0, sticky="ew", pady=(4, 8))
        ctk.CTkButton(bar, text=t("results.refresh"), fg_color="transparent", border_color=THEME["border_hi"], border_width=1, text_color=THEME["text"], hover_color=THEME["glass_hi"], corner_radius=10, command=self._refresh_results_panel).pack(side="left", padx=4)
        ctk.CTkButton(bar, text=t("results.export_json"), fg_color="transparent", border_color=THEME["accent"], border_width=1, text_color=THEME["accent"], hover_color=THEME["glass_hi"], corner_radius=10, command=lambda: self._export("json")).pack(side="left", padx=4)
        ctk.CTkButton(bar, text=t("results.export_csv"), fg_color="transparent", border_color=THEME["accent_alt"], border_width=1, text_color=THEME["accent_alt"], hover_color=THEME["glass_hi"], corner_radius=10, command=lambda: self._export("csv")).pack(side="left", padx=4)
        ctk.CTkButton(bar, text=t("results.export_html"), fg_color=THEME["accent"], text_color=THEME["bg"], hover_color="#5cffb6", corner_radius=10, command=lambda: self._export("html")).pack(side="left", padx=4)

        from tkinter import ttk
        style = ttk.Style()
        style.theme_use("default")
        style.configure(
            "Cyber.Treeview",
            background=THEME["glass"],
            foreground=THEME["text"],
            fieldbackground=THEME["glass"],
            rowheight=24,
            bordercolor=THEME["border"],
            borderwidth=0,
        )
        style.configure(
            "Cyber.Treeview.Heading",
            background=THEME["glass_alt"],
            foreground=THEME["accent"],
            relief="flat",
            font=("Segoe UI", 9, "bold"),
        )
        style.map("Cyber.Treeview", background=[("selected", THEME["glass_hi"])], foreground=[("selected", THEME["accent"])])

        wrap = GlassCard(parent)
        wrap.grid(row=1, column=0, sticky="nsew", padx=4, pady=4)
        wrap.grid_rowconfigure(0, weight=1)
        wrap.grid_columnconfigure(0, weight=1)

        cols = ("ip", "port", "rtt", "provider", "issuer", "subject", "expires")
        col_keys = {
            "ip": "results.col.ip",
            "port": "results.col.port",
            "rtt": "results.col.rtt",
            "provider": "results.col.provider",
            "issuer": "results.col.issuer",
            "subject": "results.col.subject",
            "expires": "results.col.expires",
        }
        self._tree = ttk.Treeview(wrap, columns=cols, show="headings", style="Cyber.Treeview")
        for c, w in zip(cols, (140, 60, 80, 130, 260, 260, 140)):
            self._tree.heading(c, text=t(col_keys[c]))
            self._tree.column(c, width=w, stretch=True)
        self._tree.grid(row=0, column=0, sticky="nsew", padx=8, pady=8)

        scroll = ttk.Scrollbar(wrap, orient="vertical", command=self._tree.yview)
        self._tree.configure(yscrollcommand=scroll.set)
        scroll.grid(row=0, column=1, sticky="ns")

    def _refresh_results_panel(self) -> None:
        try:
            self._do_refresh_results_panel()
        except Exception:  # noqa: BLE001
            log.exception("refresh results panel failed")

    def _do_refresh_results_panel(self) -> None:
        for r in self._tree.get_children():
            self._tree.delete(r)
        try:
            rows = self.db.list_hosts(scan_id=None)
        except Exception:  # noqa: BLE001
            log.exception("list_hosts failed")
            rows = []
        for row in rows:
            rtt = row["rtt_ms"]
            rtt_text = "" if rtt is None else f"{float(rtt):.0f}"
            self._tree.insert(
                "",
                "end",
                values=(
                    row["ip"],
                    row["port"],
                    rtt_text,
                    row["provider_name"] or "",
                    row["tls_issuer"] or "",
                    row["tls_subject"] or "",
                    row["tls_expires"] or "",
                ),
            )
        try:
            self._refresh_pie()
        except Exception:  # noqa: BLE001
            log.exception("refresh pie failed")

    def _refresh_pie(self) -> None:
        stats = self.db.stats_by_provider(scan_id=None)
        self._pie.update_data([(s["name"], int(s["hits"]), s["color"] or THEME["accent"]) for s in stats])

    def _export(self, fmt: str) -> None:
        from pathlib import Path as _Path
        import time as _time

        ext_map = {
            "json": ("JSON", "*.json"),
            "csv": ("CSV", "*.csv"),
            "html": ("HTML", "*.html"),
        }
        if fmt not in ext_map:
            return
        label, pattern = ext_map[fmt]
        stamp = _time.strftime("%Y%m%d-%H%M%S")
        suggested = f"x-ravscan-all-{stamp}.{fmt}"
        path = filedialog.asksaveasfilename(
            parent=self,
            title=t("results.save_as_title", default=f"Save {label} report as…"),
            defaultextension=f".{fmt}",
            initialfile=suggested,
            initialdir=str(export_dir()),
            filetypes=[(label, pattern), ("All files", "*.*")],
        )
        if not path:
            return
        try:
            out = _Path(path)
            if fmt == "json":
                p = exporter.export_json(self.db, out=out)
            elif fmt == "csv":
                p = exporter.export_csv(self.db, out=out)
            elif fmt == "html":
                p = exporter.export_html(self.db, out=out)
            else:
                return
            messagebox.showinfo(APP_NAME, t("results.export_saved", path=str(p)))
        except Exception as e:
            log.exception("export failed")
            messagebox.showerror(APP_NAME, t("results.export_failed", error=str(e)))

    # ------------------------------------------------------------------
    # Settings
    # ------------------------------------------------------------------
    def _build_settings_tab(self, parent) -> None:
        parent.grid_columnconfigure(0, weight=1)
        parent.grid_columnconfigure(1, weight=1)
        parent.grid_rowconfigure(99, weight=1)

        # Two-column grid of frosted cards
        # Left column: Appearance, Engine, External Engines
        # Right column: Language, Paths, About
        self._build_appearance_card(parent, row=0, col=0)
        self._build_language_card(parent, row=0, col=1)
        self._build_engine_card(parent, row=1, col=0)
        self._build_paths_card(parent, row=1, col=1)
        self._build_external_engines_card(parent, row=2, col=0, span=2)
        self._build_about_card(parent, row=3, col=0, span=2)

        # Save button at the bottom
        save_bar = ctk.CTkFrame(parent, fg_color="transparent")
        save_bar.grid(row=4, column=0, columnspan=2, sticky="ew", padx=8, pady=(8, 6))
        ctk.CTkButton(
            save_bar,
            text=t("settings.action.save"),
            fg_color=THEME["accent"],
            text_color=THEME["bg"],
            hover_color="#9bbeff",
            corner_radius=14,
            font=ctk.CTkFont(weight="bold"),
            command=self._on_save_settings,
        ).pack(side="right", padx=4)

    def _settings_card(self, parent, row: int, col: int, *, span: int = 1, accent: Optional[str] = None) -> ctk.CTkFrame:
        card = GlassCard(parent, accent=accent, corner_radius=14)
        card.grid(row=row, column=col, columnspan=span, sticky="nsew", padx=10, pady=10)
        card.grid_columnconfigure(0, weight=1)
        return card

    def _add_field(self, parent, row: int, label: str, value: str) -> None:
        ctk.CTkLabel(parent, text=label, text_color=THEME["text_dim"], font=ctk.CTkFont(size=11)).grid(row=row, column=0, sticky="w", padx=18, pady=(2, 1))
        ctk.CTkLabel(parent, text=value, text_color=THEME["text"], font=ctk.CTkFont(size=11, family="Consolas"), anchor="w").grid(row=row, column=1, sticky="ew", padx=18, pady=(2, 1))

    def _build_appearance_card(self, parent, *, row: int, col: int) -> None:
        card = self._settings_card(parent, row, col, accent=THEME["accent_pink"])
        SectionHeader(card, t("settings.section.appearance"), t("settings.section.appearance.desc"), accent=THEME["accent_pink"]).grid(row=0, column=0, columnspan=2, sticky="ew", padx=18, pady=(14, 10))
        card.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(card, text=t("settings.field.theme"), text_color=THEME["text_dim"], font=ctk.CTkFont(size=11)).grid(row=1, column=0, sticky="w", padx=18, pady=(0, 14))
        ctk.CTkLabel(card, text=t("settings.field.theme.value"), text_color=THEME["accent_pink"], font=ctk.CTkFont(size=11, weight="bold")).grid(row=1, column=1, sticky="w", padx=18, pady=(0, 14))

    def _build_language_card(self, parent, *, row: int, col: int) -> None:
        card = self._settings_card(parent, row, col, accent=THEME["accent_alt"])
        SectionHeader(card, t("settings.section.language"), t("settings.section.language.desc"), accent=THEME["accent_alt"]).grid(row=0, column=0, columnspan=2, sticky="ew", padx=18, pady=(14, 10))
        card.grid_columnconfigure(1, weight=1)
        ctk.CTkLabel(card, text=t("settings.field.language"), text_color=THEME["text_dim"], font=ctk.CTkFont(size=11)).grid(row=1, column=0, sticky="w", padx=18, pady=(0, 6))
        labels = [label for _, label in LANGUAGES]
        codes = [code for code, _ in LANGUAGES]
        self._language_var.set(language_name(current_language()))
        opt = ctk.CTkOptionMenu(
            card,
            values=labels,
            variable=self._language_var,
            fg_color=THEME["glass_hi"],
            button_color=THEME["glass_hi"],
            button_hover_color=THEME["border_hi"],
            text_color=THEME["text"],
            command=lambda label, codes=codes, labels=labels: self._on_language_pick(codes[labels.index(label)] if label in labels else "en"),
        )
        opt.grid(row=1, column=1, sticky="ew", padx=18, pady=(0, 6))
        ctk.CTkLabel(card, text=t("settings.action.restart_hint"), text_color=THEME["text_muted"], font=ctk.CTkFont(size=10), wraplength=440, justify="left").grid(row=2, column=0, columnspan=2, sticky="w", padx=18, pady=(0, 14))

    def _on_language_pick(self, code: str) -> None:
        set_language(code)
        self.db.set_setting("ui.language", code)
        log.info("ui language pre-selected: %s", code)

    def _build_engine_card(self, parent, *, row: int, col: int) -> None:
        card = self._settings_card(parent, row, col, accent=THEME["accent"])
        SectionHeader(card, t("settings.section.engine"), t("settings.section.engine.desc"), accent=THEME["accent"]).grid(row=0, column=0, columnspan=2, sticky="ew", padx=18, pady=(14, 10))
        card.grid_columnconfigure(1, weight=1)
        self._add_field(card, 1, t("settings.field.engine"), self._engine.get())
        self._add_field(card, 2, t("settings.field.concurrency"), str(int(self._conc.get())))
        self._add_field(card, 3, t("settings.field.sample"), str(int(self._sample.get())))
        ctk.CTkLabel(card, text="", height=10).grid(row=4, column=0)

    def _build_paths_card(self, parent, *, row: int, col: int) -> None:
        card = self._settings_card(parent, row, col, accent=THEME["info"])
        SectionHeader(card, t("settings.section.paths"), t("settings.section.paths.desc"), accent=THEME["info"]).grid(row=0, column=0, columnspan=2, sticky="ew", padx=18, pady=(14, 10))
        card.grid_columnconfigure(1, weight=1)
        self._add_field(card, 1, t("settings.field.data_dir"), str(user_data_dir()))
        self._add_field(card, 2, t("settings.field.database"), str(db_path()))
        self._add_field(card, 3, t("settings.field.logs"), str(log_path()))
        self._add_field(card, 4, t("settings.field.exports"), str(export_dir()))
        ctk.CTkLabel(card, text="", height=10).grid(row=5, column=0)

    def _build_about_card(self, parent, *, row: int, col: int, span: int) -> None:
        card = self._settings_card(parent, row, col, span=span, accent=THEME["accent_alt"])
        SectionHeader(card, t("settings.section.about"), t("settings.section.about.desc"), accent=THEME["accent_alt"]).grid(row=0, column=0, columnspan=2, sticky="ew", padx=18, pady=(14, 10))
        card.grid_columnconfigure(1, weight=1)
        self._add_field(card, 1, t("settings.field.app"), f"{APP_NAME} v{__version__}")
        self._add_field(card, 2, t("settings.field.version"), "frozen" if getattr(sys, "frozen", False) else "from source")
        self._add_field(card, 3, t("settings.field.python"), f"{platform.python_implementation()} {platform.python_version()} · {platform.system()} {platform.release()}")
        ctk.CTkLabel(card, text="", height=10).grid(row=4, column=0)

    def _build_external_engines_card(self, parent, *, row: int, col: int, span: int) -> None:
        import shutil

        card = self._settings_card(parent, row, col, span=span, accent=THEME["accent_pink"])
        SectionHeader(
            card,
            t("settings.section.external_engines", default="External engines"),
            t(
                "settings.section.external_engines.desc",
                default="masscan & zmap are external C scanners for extreme-rate raw-socket sweeps. They are NOT bundled — install separately and ensure the binary is on PATH.",
            ),
            accent=THEME["accent_pink"],
        ).grid(row=0, column=0, columnspan=2, sticky="ew", padx=18, pady=(14, 10))
        card.grid_columnconfigure(1, weight=1)

        masscan_bin = shutil.which("masscan")
        zmap_bin = shutil.which("zmap")
        masscan_status = (
            f"{t('settings.engine.found', default='found at')} {masscan_bin}"
            if masscan_bin
            else t("settings.engine.missing", default="not installed")
        )
        zmap_status = (
            f"{t('settings.engine.found', default='found at')} {zmap_bin}"
            if zmap_bin
            else t("settings.engine.missing", default="not installed")
        )
        masscan_color = THEME["success"] if masscan_bin else THEME["danger"]
        zmap_color = THEME["success"] if zmap_bin else THEME["danger"]

        ctk.CTkLabel(card, text="masscan", text_color=THEME["text"], font=ctk.CTkFont(size=12, weight="bold")).grid(row=1, column=0, sticky="w", padx=18, pady=(2, 0))
        ctk.CTkLabel(card, text=masscan_status, text_color=masscan_color, font=ctk.CTkFont(size=11)).grid(row=1, column=1, sticky="w", padx=18, pady=(2, 0))
        ctk.CTkLabel(
            card,
            text=t(
                "settings.engine.masscan.desc",
                default="C-based SYN scanner, ~10M pps. Requires raw sockets + admin/root. On Windows install Npcap. On Android NOT supported.",
            ),
            text_color=THEME["text_dim"],
            font=ctk.CTkFont(size=11),
            wraplength=900,
            justify="left",
        ).grid(row=2, column=0, columnspan=2, sticky="w", padx=18, pady=(0, 10))

        ctk.CTkLabel(card, text="zmap", text_color=THEME["text"], font=ctk.CTkFont(size=12, weight="bold")).grid(row=3, column=0, sticky="w", padx=18, pady=(2, 0))
        ctk.CTkLabel(card, text=zmap_status, text_color=zmap_color, font=ctk.CTkFont(size=11)).grid(row=3, column=1, sticky="w", padx=18, pady=(2, 0))
        ctk.CTkLabel(
            card,
            text=t(
                "settings.engine.zmap.desc",
                default="Stateless internet-wide scanner. Linux/BSD only. Used to sweep entire IPv4 in seconds — overkill for CDN diapasones; use the bundled asyncio engine.",
            ),
            text_color=THEME["text_dim"],
            font=ctk.CTkFont(size=11),
            wraplength=900,
            justify="left",
        ).grid(row=4, column=0, columnspan=2, sticky="w", padx=18, pady=(0, 10))

        ctk.CTkLabel(
            card,
            text=t(
                "settings.engine.install_hint",
                default="Linux: sudo apt install masscan zmap   |   macOS: brew install masscan zmap   |   Windows: download masscan from github.com/robertdavidgraham/masscan + install Npcap",
            ),
            text_color=THEME["text_muted"],
            font=ctk.CTkFont(size=10, family="JetBrains Mono"),
            wraplength=900,
            justify="left",
        ).grid(row=5, column=0, columnspan=2, sticky="w", padx=18, pady=(0, 14))

    def _on_save_settings(self) -> None:
        self.db.set_setting("ui.language", current_language())
        self.db.set_setting("scan.engine", self._engine.get())
        self.db.set_setting("scan.concurrency", str(int(self._conc.get())))
        self.db.set_setting("scan.sample", str(int(self._sample.get())))
        log.info("settings saved (lang=%s)", current_language())
        messagebox.showinfo(
            APP_NAME,
            f"{t('settings.action.saved')}\n{t('settings.action.restart_hint')}",
        )

    # ------------------------------------------------------------------
    # Logging hookup
    # ------------------------------------------------------------------
    def _wire_logging(self) -> None:
        def cb(level: str, message: str) -> None:
            try:
                self.after(0, lambda: self._console.append(level, message))
            except RuntimeError:
                pass
        add_ui_handler(cb)

    # ------------------------------------------------------------------
    # Sliders
    # ------------------------------------------------------------------
    def _on_conc_change(self, value) -> None:
        self._conc_lbl.configure(text=str(int(value)))

    def _on_sample_change(self, value) -> None:
        self._sample_lbl.configure(text=str(int(value)))

    # ------------------------------------------------------------------
    # Background actions
    # ------------------------------------------------------------------
    def _on_cloud_sync(self) -> None:
        threading.Thread(target=self._cloud_sync_worker, daemon=True).start()

    def _cloud_sync_worker(self) -> None:
        log.info("cloud sync started")
        results = providers_manager.cloud_sync(self.db)
        ok = sum(1 for r in results if r.ok)
        log.info("cloud sync finished: %d/%d providers updated", ok, len(results))
        for r in results:
            if r.ok:
                log.info("  ✓ %s — %d cidrs", r.slug, r.count)
            else:
                log.warning("  ✗ %s — %s", r.slug, r.message)
        self.after(0, self._refresh_providers_panel)

    def _on_smart_discovery(self) -> None:
        threading.Thread(target=self._smart_discovery_worker, daemon=True).start()

    def _smart_discovery_worker(self) -> None:
        log.info("smart discovery started")
        try:
            new = network_updater.smart_discovery(
                self.db, progress=lambda m: log.info("[discovery] %s", m)
            )
            log.info("smart discovery: %d new prefixes queued", len(new))
        except Exception:
            log.exception("smart discovery failed")
        self.after(0, self._refresh_discoveries_panel)

    def _on_deep_discovery(self) -> None:
        threading.Thread(target=self._deep_discovery_worker, daemon=True).start()

    def _deep_discovery_worker(self) -> None:
        log.info("deep discovery started — BGPView + RIPEstat + Hackertarget + peers")
        try:
            report = network_updater.deep_discovery(
                self.db, progress=lambda m: log.info("[discovery] %s", m)
            )
            log.info(
                "deep discovery: +%d prefixes from %d ASNs in %.1fs (sources=%s)",
                len(report.fresh),
                len(report.visited_asns),
                report.duration,
                ",".join(sorted(report.sources_used)),
            )
        except Exception:
            log.exception("deep discovery failed")
        self.after(0, self._refresh_discoveries_panel)

    def _on_smart_append(self) -> None:
        threading.Thread(target=self._smart_append_worker, daemon=True).start()

    def _smart_append_worker(self) -> None:
        from x_ravscan.core import data_manager

        log.info("Smart Append (ASN) started")

        def _ui_log(level: str, msg: str) -> None:
            getattr(log, level.lower(), log.info)(msg)

        try:
            report = data_manager.sync_all_via_asn(self.db, log_fn=_ui_log)
            log.info(
                "Smart Append done: +%d new across %d providers in %.1fs",
                report.total_added, report.providers_touched, report.duration,
            )
        except Exception:
            log.exception("smart append failed")
        self.after(0, self._refresh_providers_panel)
        self.after(0, self._refresh_discoveries_panel)

    def _on_clean_optimize(self) -> None:
        threading.Thread(target=self._clean_optimize_worker, daemon=True).start()

    def _clean_optimize_worker(self) -> None:
        from x_ravscan.core import data_manager

        log.info("Clean & Optimize started — collapsing redundant prefixes")

        def _ui_log(level: str, msg: str) -> None:
            getattr(log, level.lower(), log.info)(msg)

        try:
            removed = data_manager.clean_and_optimize(self.db, log_fn=_ui_log)
            log.info(
                "Clean & Optimize done — %d prefixes merged across %d providers",
                sum(removed.values()), len(removed),
            )
        except Exception:
            log.exception("clean & optimize failed")
        self.after(0, self._refresh_providers_panel)

    def _on_auto_sync(self) -> None:
        threading.Thread(target=self._auto_sync_worker, daemon=True).start()

    def _auto_sync_worker(self) -> None:
        log.info("auto-sync started")
        try:
            n = network_updater.auto_sync(self.db, accept_new=True)
            log.info("auto-sync accepted %d prefixes", n)
        except Exception:
            log.exception("auto-sync failed")
        self.after(0, self._refresh_providers_panel)
        self.after(0, self._refresh_discoveries_panel)

    # ------------------------------------------------------------------
    # Scan lifecycle
    # ------------------------------------------------------------------
    def _on_start_scan(self) -> None:
        if self._scan_thread and self._scan_thread.is_alive():
            return
        self._scan_btn.configure(state="disabled")
        self._stop_btn.configure(state="normal")
        engine = self._engine.get()
        concurrency = int(self._conc.get())
        sample = int(self._sample.get())
        log.info("starting scan: engine=%s, concurrency=%d, sample=%d", engine, concurrency, sample)
        self._scan_thread = threading.Thread(
            target=self._scan_worker,
            args=(engine, concurrency, sample),
            daemon=True,
        )
        self._scan_thread.start()

    def _on_stop_scan(self) -> None:
        if self._cancel_event and self._scan_loop:
            self._scan_loop.call_soon_threadsafe(self._cancel_event.set)
            log.warning("stop requested")

    def _scan_worker(self, engine: str, concurrency: int, sample: int) -> None:
        scanner.configure_event_loop()
        loop = asyncio.new_event_loop()
        self._scan_loop = loop
        cancel = asyncio.Event()
        self._cancel_event = cancel
        last_pps_push = [time.time()]
        last_completed = [0]

        def progress_cb(prog, _res):
            now = time.time()
            self.after(0, lambda: self._stat_total.configure(text=str(prog.total)))
            self.after(0, lambda: self._stat_done.configure(text=str(prog.completed)))
            self.after(0, lambda: self._stat_alive.configure(text=str(prog.alive)))
            if now - last_pps_push[0] >= 1.0:
                delta = prog.completed - last_completed[0]
                last_completed[0] = prog.completed
                last_pps_push[0] = now
                self.after(0, lambda v=float(delta): self._stat_pps.configure(text=f"{v:.1f}"))
                self.after(0, lambda v=float(delta): self._pps_chart.push(v))

        async def main() -> None:
            await scanner.run_scan(
                self.db,
                engine=engine,
                concurrency=concurrency,
                sample_per_cidr=sample,
                progress_cb=progress_cb,
                cancel_event=cancel,
            )

        try:
            asyncio.set_event_loop(loop)
            loop.run_until_complete(main())
        except Exception:
            log.exception("scan worker crashed")
        finally:
            try:
                loop.close()
            except Exception:
                pass
            self.after(0, self._on_scan_finished)

    def _on_scan_finished(self) -> None:
        self._scan_btn.configure(state="normal")
        self._stop_btn.configure(state="disabled")
        self._refresh_results_panel()

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------
    def _on_close(self) -> None:
        try:
            if self._cancel_event and self._scan_loop:
                self._scan_loop.call_soon_threadsafe(self._cancel_event.set)
        except Exception:
            pass
        try:
            self.db.close()
        except Exception:
            pass
        self.destroy()


def providers_managed_label(prov: Provider) -> str:
    return providers_manager.last_synced_label(prov)
