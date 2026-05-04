"""Main application window for X-RavScan."""

from __future__ import annotations

import asyncio
import threading
import time
import tkinter as tk
import tkinter.messagebox as messagebox
from pathlib import Path
from typing import Dict, List, Optional

import customtkinter as ctk

from x_ravscan import __version__
from x_ravscan.core.config import APP_NAME, THEME
from x_ravscan.core.database import Database, Provider
from x_ravscan.core import exporter, network_updater, providers_manager, scanner
from x_ravscan.ui.dashboard import LogConsole, PPSChart, ProviderPie
from x_ravscan.utils.logger import add_ui_handler, get_logger


log = get_logger("ui")


ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("dark-blue")


class XRavScanApp(ctk.CTk):
    """Top-level CTk window."""

    def __init__(self, db: Database) -> None:
        super().__init__()
        self.db = db
        self.title(f"{APP_NAME} v{__version__}")
        self.geometry("1280x820")
        self.minsize(1100, 700)
        self.configure(fg_color=THEME["bg"])
        try:
            self.option_add("*Font", ("Segoe UI", 10))
        except tk.TclError:
            pass

        self._cancel_event: Optional[asyncio.Event] = None
        self._scan_thread: Optional[threading.Thread] = None
        self._scan_loop: Optional[asyncio.AbstractEventLoop] = None
        self._provider_vars: Dict[str, tk.BooleanVar] = {}

        self._build_layout()
        self._wire_logging()
        self._refresh_providers_panel()
        self._refresh_results_panel()
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ------------------------------------------------------------------
    # Layout
    # ------------------------------------------------------------------
    def _build_layout(self) -> None:
        self.grid_columnconfigure(0, weight=0, minsize=240)
        self.grid_columnconfigure(1, weight=1)
        self.grid_rowconfigure(0, weight=1)

        self._build_sidebar()

        self._tabs = ctk.CTkTabview(self, fg_color=THEME["panel"], segmented_button_selected_color=THEME["accent"], segmented_button_selected_hover_color=THEME["accent"])
        self._tabs.grid(row=0, column=1, padx=12, pady=12, sticky="nsew")
        for tab in ("Dashboard", "Providers", "Discovery", "Results", "Settings"):
            self._tabs.add(tab)
        self._build_dashboard_tab(self._tabs.tab("Dashboard"))
        self._build_providers_tab(self._tabs.tab("Providers"))
        self._build_discovery_tab(self._tabs.tab("Discovery"))
        self._build_results_tab(self._tabs.tab("Results"))
        self._build_settings_tab(self._tabs.tab("Settings"))

    def _build_sidebar(self) -> None:
        side = ctk.CTkFrame(self, fg_color=THEME["panel"], corner_radius=0)
        side.grid(row=0, column=0, sticky="nsw")
        side.grid_rowconfigure(99, weight=1)

        ctk.CTkLabel(
            side,
            text=APP_NAME,
            text_color=THEME["accent"],
            font=ctk.CTkFont("Consolas", 22, weight="bold"),
        ).grid(row=0, column=0, padx=20, pady=(20, 0), sticky="w")
        ctk.CTkLabel(
            side,
            text=f"v{__version__}",
            text_color=THEME["text_dim"],
        ).grid(row=1, column=0, padx=22, pady=(0, 18), sticky="w")

        # Engine
        ctk.CTkLabel(side, text="Scan engine", text_color=THEME["text_dim"]).grid(row=2, column=0, padx=20, pady=(8, 2), sticky="w")
        self._engine = ctk.CTkOptionMenu(
            side,
            values=["asyncio", "masscan", "zmap"],
            fg_color=THEME["panel_alt"],
            button_color=THEME["panel_alt"],
            button_hover_color=THEME["border"],
            text_color=THEME["text"],
        )
        self._engine.set("asyncio")
        self._engine.grid(row=3, column=0, padx=20, pady=(0, 12), sticky="ew")

        # Concurrency
        ctk.CTkLabel(side, text="Concurrency", text_color=THEME["text_dim"]).grid(row=4, column=0, padx=20, pady=(0, 2), sticky="w")
        self._conc = ctk.CTkSlider(side, from_=64, to=2048, number_of_steps=31)
        self._conc.set(512)
        self._conc.grid(row=5, column=0, padx=20, pady=(0, 4), sticky="ew")
        self._conc_lbl = ctk.CTkLabel(side, text="512", text_color=THEME["text_dim"])
        self._conc_lbl.grid(row=6, column=0, padx=20, pady=(0, 12), sticky="w")
        self._conc.configure(command=self._on_conc_change)

        # Sample per CIDR
        ctk.CTkLabel(side, text="Sample per CIDR", text_color=THEME["text_dim"]).grid(row=7, column=0, padx=20, pady=(0, 2), sticky="w")
        self._sample = ctk.CTkSlider(side, from_=16, to=1024, number_of_steps=63)
        self._sample.set(128)
        self._sample.grid(row=8, column=0, padx=20, pady=(0, 4), sticky="ew")
        self._sample_lbl = ctk.CTkLabel(side, text="128", text_color=THEME["text_dim"])
        self._sample_lbl.grid(row=9, column=0, padx=20, pady=(0, 18), sticky="w")
        self._sample.configure(command=self._on_sample_change)

        self._scan_btn = ctk.CTkButton(
            side,
            text="▶  START SCAN",
            font=ctk.CTkFont(weight="bold"),
            fg_color=THEME["accent"],
            text_color=THEME["bg"],
            hover_color="#5cffb6",
            command=self._on_start_scan,
        )
        self._scan_btn.grid(row=10, column=0, padx=20, pady=(0, 6), sticky="ew")

        self._stop_btn = ctk.CTkButton(
            side,
            text="■  STOP",
            fg_color=THEME["danger"],
            text_color=THEME["bg"],
            hover_color="#ff7c7c",
            command=self._on_stop_scan,
            state="disabled",
        )
        self._stop_btn.grid(row=11, column=0, padx=20, pady=(0, 18), sticky="ew")

        ctk.CTkButton(
            side,
            text="↺  Cloud Sync",
            fg_color=THEME["panel_alt"],
            border_color=THEME["accent_alt"],
            border_width=1,
            text_color=THEME["accent_alt"],
            hover_color=THEME["panel_alt"],
            command=self._on_cloud_sync,
        ).grid(row=12, column=0, padx=20, pady=(0, 6), sticky="ew")

        ctk.CTkButton(
            side,
            text="🔍  Smart Discovery",
            fg_color=THEME["panel_alt"],
            border_color=THEME["accent"],
            border_width=1,
            text_color=THEME["accent"],
            hover_color=THEME["panel_alt"],
            command=self._on_smart_discovery,
        ).grid(row=13, column=0, padx=20, pady=(0, 18), sticky="ew")

        side.grid_columnconfigure(0, weight=1)

    def _build_dashboard_tab(self, parent) -> None:
        parent.grid_rowconfigure(1, weight=1)
        parent.grid_columnconfigure(0, weight=1)
        parent.grid_columnconfigure(1, weight=0, minsize=380)

        # Top stats strip
        stats = ctk.CTkFrame(parent, fg_color=THEME["panel"], corner_radius=10)
        stats.grid(row=0, column=0, columnspan=2, sticky="ew", padx=4, pady=(4, 12))
        for c in range(4):
            stats.grid_columnconfigure(c, weight=1, uniform="stats")
        self._stat_total = self._make_stat(stats, 0, "Targets", "0")
        self._stat_done = self._make_stat(stats, 1, "Completed", "0")
        self._stat_alive = self._make_stat(stats, 2, "Alive", "0")
        self._stat_pps = self._make_stat(stats, 3, "PPS", "0.0")

        self._pps_chart = PPSChart(parent)
        self._pps_chart.grid(row=1, column=0, sticky="nsew", padx=4, pady=4)

        self._pie = ProviderPie(parent)
        self._pie.grid(row=1, column=1, sticky="nsew", padx=4, pady=4)

        self._console = LogConsole(parent)
        self._console.grid(row=2, column=0, columnspan=2, sticky="nsew", padx=4, pady=(12, 4))
        parent.grid_rowconfigure(2, weight=1)

    def _make_stat(self, parent, col: int, label: str, value: str) -> ctk.CTkLabel:
        cell = ctk.CTkFrame(parent, fg_color="transparent")
        cell.grid(row=0, column=col, padx=12, pady=12, sticky="ew")
        ctk.CTkLabel(
            cell,
            text=label.upper(),
            text_color=THEME["text_dim"],
            font=ctk.CTkFont(size=10, weight="bold"),
        ).pack(anchor="w")
        v = ctk.CTkLabel(
            cell,
            text=value,
            text_color=THEME["accent"],
            font=ctk.CTkFont(size=22, weight="bold"),
        )
        v.pack(anchor="w")
        return v

    def _build_providers_tab(self, parent) -> None:
        parent.grid_rowconfigure(1, weight=1)
        parent.grid_columnconfigure(0, weight=1)

        toolbar = ctk.CTkFrame(parent, fg_color="transparent")
        toolbar.grid(row=0, column=0, sticky="ew", pady=(4, 8))
        ctk.CTkButton(toolbar, text="Enable all", fg_color=THEME["panel_alt"], text_color=THEME["text"], command=lambda: self._toggle_all(True)).pack(side="left", padx=4)
        ctk.CTkButton(toolbar, text="Disable all", fg_color=THEME["panel_alt"], text_color=THEME["text"], command=lambda: self._toggle_all(False)).pack(side="left", padx=4)
        ctk.CTkButton(toolbar, text="Refresh", fg_color=THEME["panel_alt"], text_color=THEME["text"], command=self._refresh_providers_panel).pack(side="left", padx=4)

        self._providers_box = ctk.CTkScrollableFrame(parent, fg_color=THEME["panel"], corner_radius=10)
        self._providers_box.grid(row=1, column=0, sticky="nsew", padx=4, pady=4)
        self._providers_box.grid_columnconfigure(0, weight=1)

    def _build_discovery_tab(self, parent) -> None:
        parent.grid_rowconfigure(1, weight=1)
        parent.grid_columnconfigure(0, weight=1)

        bar = ctk.CTkFrame(parent, fg_color="transparent")
        bar.grid(row=0, column=0, sticky="ew", pady=(4, 8))
        ctk.CTkButton(bar, text="Run Smart Discovery", fg_color=THEME["accent"], text_color=THEME["bg"], hover_color="#5cffb6", command=self._on_smart_discovery).pack(side="left", padx=4)
        ctk.CTkButton(bar, text="Auto-sync (accept all)", fg_color=THEME["accent_alt"], text_color=THEME["bg"], command=self._on_auto_sync).pack(side="left", padx=4)
        ctk.CTkButton(bar, text="Refresh", fg_color=THEME["panel_alt"], text_color=THEME["text"], command=self._refresh_discoveries_panel).pack(side="left", padx=4)

        self._discovery_box = ctk.CTkScrollableFrame(parent, fg_color=THEME["panel"], corner_radius=10)
        self._discovery_box.grid(row=1, column=0, sticky="nsew", padx=4, pady=4)
        self._discovery_box.grid_columnconfigure(0, weight=1)
        self._refresh_discoveries_panel()

    def _build_results_tab(self, parent) -> None:
        parent.grid_rowconfigure(1, weight=1)
        parent.grid_columnconfigure(0, weight=1)

        bar = ctk.CTkFrame(parent, fg_color="transparent")
        bar.grid(row=0, column=0, sticky="ew", pady=(4, 8))
        ctk.CTkButton(bar, text="Refresh", fg_color=THEME["panel_alt"], text_color=THEME["text"], command=self._refresh_results_panel).pack(side="left", padx=4)
        ctk.CTkButton(bar, text="Export JSON", fg_color=THEME["panel_alt"], text_color=THEME["accent"], border_color=THEME["accent"], border_width=1, hover_color=THEME["panel_alt"], command=lambda: self._export("json")).pack(side="left", padx=4)
        ctk.CTkButton(bar, text="Export CSV", fg_color=THEME["panel_alt"], text_color=THEME["accent_alt"], border_color=THEME["accent_alt"], border_width=1, hover_color=THEME["panel_alt"], command=lambda: self._export("csv")).pack(side="left", padx=4)
        ctk.CTkButton(bar, text="Export HTML report", fg_color=THEME["accent"], text_color=THEME["bg"], hover_color="#5cffb6", command=lambda: self._export("html")).pack(side="left", padx=4)

        # Use ttk.Treeview wrapped inside CTkFrame for tabular output.
        from tkinter import ttk

        style = ttk.Style()
        style.theme_use("default")
        style.configure(
            "Cyber.Treeview",
            background=THEME["bg"],
            foreground=THEME["text"],
            fieldbackground=THEME["bg"],
            rowheight=22,
            bordercolor=THEME["border"],
        )
        style.configure(
            "Cyber.Treeview.Heading",
            background=THEME["panel_alt"],
            foreground=THEME["accent"],
            relief="flat",
        )
        style.map("Cyber.Treeview", background=[("selected", THEME["panel_alt"])])

        wrap = ctk.CTkFrame(parent, fg_color=THEME["panel"], corner_radius=10)
        wrap.grid(row=1, column=0, sticky="nsew", padx=4, pady=4)
        wrap.grid_rowconfigure(0, weight=1)
        wrap.grid_columnconfigure(0, weight=1)

        cols = ("ip", "port", "provider", "issuer", "subject", "expires")
        self._tree = ttk.Treeview(wrap, columns=cols, show="headings", style="Cyber.Treeview")
        for c, w in zip(cols, (140, 60, 130, 280, 280, 160)):
            self._tree.heading(c, text=c.upper())
            self._tree.column(c, width=w, stretch=True)
        self._tree.grid(row=0, column=0, sticky="nsew", padx=4, pady=4)

        scroll = ttk.Scrollbar(wrap, orient="vertical", command=self._tree.yview)
        self._tree.configure(yscrollcommand=scroll.set)
        scroll.grid(row=0, column=1, sticky="ns")

    def _build_settings_tab(self, parent) -> None:
        for col in (0, 1):
            parent.grid_columnconfigure(col, weight=1)
        info = ctk.CTkFrame(parent, fg_color=THEME["panel"], corner_radius=10)
        info.grid(row=0, column=0, columnspan=2, sticky="nsew", padx=4, pady=4)
        from x_ravscan.core.config import db_path, log_path, export_dir, user_data_dir

        rows = [
            ("Application", f"{APP_NAME} v{__version__}"),
            ("Data dir", str(user_data_dir())),
            ("Database", str(db_path())),
            ("Logs", str(log_path())),
            ("Exports", str(export_dir())),
        ]
        for i, (k, v) in enumerate(rows):
            ctk.CTkLabel(info, text=k, text_color=THEME["text_dim"], font=ctk.CTkFont(weight="bold")).grid(row=i, column=0, sticky="w", padx=18, pady=4)
            ctk.CTkLabel(info, text=v, text_color=THEME["text"], anchor="w").grid(row=i, column=1, sticky="ew", padx=18, pady=4)
        info.grid_columnconfigure(1, weight=1)

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
    # Providers panel
    # ------------------------------------------------------------------
    def _refresh_providers_panel(self) -> None:
        for child in self._providers_box.winfo_children():
            child.destroy()
        self._provider_vars.clear()
        for i, prov in enumerate(self.db.list_providers()):
            row = ctk.CTkFrame(self._providers_box, fg_color=THEME["panel_alt"], corner_radius=8)
            row.grid(row=i, column=0, sticky="ew", padx=6, pady=4)
            row.grid_columnconfigure(1, weight=1)

            var = tk.BooleanVar(value=prov.enabled)
            self._provider_vars[prov.slug] = var
            chk = ctk.CTkCheckBox(
                row,
                text="",
                variable=var,
                width=24,
                fg_color=THEME["accent"],
                hover_color="#5cffb6",
                command=lambda slug=prov.slug, v=var: self._on_provider_toggle(slug, v.get()),
            )
            chk.grid(row=0, column=0, padx=(8, 4), pady=8)

            txt = ctk.CTkLabel(
                row,
                text=f"{prov.name}",
                text_color=THEME["text"],
                font=ctk.CTkFont(weight="bold"),
                anchor="w",
            )
            txt.grid(row=0, column=1, sticky="w", padx=4, pady=8)

            assert prov.id is not None
            cidr_count = len(self.db.list_ranges(prov.id))
            sub = ctk.CTkLabel(
                row,
                text=f"{cidr_count} CIDRs · ASNs: {', '.join(map(str, prov.asns)) or '—'} · synced: {providers_managed_label(prov)}",
                text_color=THEME["text_dim"],
                anchor="w",
                font=ctk.CTkFont(size=11),
            )
            sub.grid(row=1, column=1, sticky="w", padx=4, pady=(0, 8))

            dot = tk.Canvas(row, width=10, height=10, highlightthickness=0, bg=THEME["panel_alt"])
            dot.create_oval(1, 1, 9, 9, fill=prov.color, outline=prov.color)
            dot.grid(row=0, column=2, padx=12, pady=8)

    def _on_provider_toggle(self, slug: str, enabled: bool) -> None:
        self.db.set_provider_enabled(slug, enabled)
        log.info("provider %s -> %s", slug, "enabled" if enabled else "disabled")

    def _toggle_all(self, enabled: bool) -> None:
        for slug in list(self._provider_vars.keys()):
            self._provider_vars[slug].set(enabled)
            self.db.set_provider_enabled(slug, enabled)
        log.info("bulk toggle providers -> %s", enabled)

    # ------------------------------------------------------------------
    # Discovery panel
    # ------------------------------------------------------------------
    def _refresh_discoveries_panel(self) -> None:
        for c in self._discovery_box.winfo_children():
            c.destroy()
        rows = self.db.list_pending_discoveries()
        if not rows:
            ctk.CTkLabel(
                self._discovery_box,
                text="No pending discoveries — run Smart Discovery to query BGPView for new prefixes.",
                text_color=THEME["text_dim"],
            ).grid(row=0, column=0, padx=12, pady=12, sticky="w")
            return
        for i, r in enumerate(rows):
            box = ctk.CTkFrame(self._discovery_box, fg_color=THEME["panel_alt"], corner_radius=8)
            box.grid(row=i, column=0, sticky="ew", padx=6, pady=4)
            box.grid_columnconfigure(1, weight=1)
            ctk.CTkLabel(box, text=r["cidr"], text_color=THEME["accent"], font=ctk.CTkFont(weight="bold", family="Consolas")).grid(row=0, column=0, padx=10, pady=6, sticky="w")
            desc = r["description"] or ""
            ctk.CTkLabel(box, text=f"AS{r['asn']} · {r['provider_name']} · {desc}", text_color=THEME["text_dim"], anchor="w").grid(row=0, column=1, padx=4, pady=6, sticky="w")
            ctk.CTkButton(box, text="Accept", fg_color=THEME["accent"], text_color=THEME["bg"], width=80, command=lambda pid=r["provider_id"]: self._accept_discoveries(pid)).grid(row=0, column=2, padx=8, pady=6)

    def _accept_discoveries(self, provider_id: int) -> None:
        n = self.db.accept_discoveries(provider_id)
        log.info("accepted %d discoveries for provider id=%s", n, provider_id)
        self._refresh_discoveries_panel()
        self._refresh_providers_panel()

    # ------------------------------------------------------------------
    # Results panel
    # ------------------------------------------------------------------
    def _refresh_results_panel(self) -> None:
        for r in self._tree.get_children():
            self._tree.delete(r)
        for row in self.db.list_hosts(scan_id=None):
            self._tree.insert(
                "",
                "end",
                values=(
                    row["ip"],
                    row["port"],
                    row["provider_name"] or "",
                    row["tls_issuer"] or "",
                    row["tls_subject"] or "",
                    row["tls_expires"] or "",
                ),
            )
        self._refresh_pie()

    def _refresh_pie(self) -> None:
        stats = self.db.stats_by_provider(scan_id=None)
        self._pie.update_data([(s["name"], int(s["hits"]), s["color"] or THEME["accent"]) for s in stats])

    def _export(self, fmt: str) -> None:
        try:
            if fmt == "json":
                p = exporter.export_json(self.db)
            elif fmt == "csv":
                p = exporter.export_csv(self.db)
            elif fmt == "html":
                p = exporter.export_html(self.db)
            else:
                return
            messagebox.showinfo(APP_NAME, f"Saved: {p}")
        except Exception as e:
            log.exception("export failed")
            messagebox.showerror(APP_NAME, f"Export failed: {e}")

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
            new = network_updater.smart_discovery(self.db)
            log.info("smart discovery: %d new prefixes queued", len(new))
        except Exception:
            log.exception("smart discovery failed")
        self.after(0, self._refresh_discoveries_panel)

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
