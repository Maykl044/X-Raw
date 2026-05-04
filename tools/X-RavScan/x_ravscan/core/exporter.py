"""Export scan results to JSON, CSV and a branded interactive HTML report."""

from __future__ import annotations

import csv
import html
import json
import sqlite3
import time
from pathlib import Path
from typing import Iterable, List, Optional, Sequence

from x_ravscan.core.config import APP_NAME, APP_VERSION, export_dir
from x_ravscan.core.database import Database
from x_ravscan.utils.logger import get_logger


log = get_logger("export")


_FIELDS: Sequence[str] = (
    "id",
    "scan_id",
    "provider_slug",
    "provider_name",
    "ip",
    "port",
    "tls_subject",
    "tls_issuer",
    "tls_san",
    "tls_expires",
    "rtt_ms",
    "seen_at",
)


def _rows(db: Database, scan_id: Optional[int]) -> List[sqlite3.Row]:
    return db.list_hosts(scan_id=scan_id)


def export_json(db: Database, scan_id: Optional[int] = None, out: Optional[Path] = None) -> Path:
    out = out or (export_dir() / _suggested_name(scan_id, "json"))
    rows = _rows(db, scan_id)
    payload = {
        "app": APP_NAME,
        "version": APP_VERSION,
        "scan_id": scan_id,
        "exported_at": time.time(),
        "host_count": len(rows),
        "hosts": [{f: row[f] for f in _FIELDS if f in row.keys()} for row in rows],
    }
    out.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    log.info("JSON export -> %s (%d hosts)", out, len(rows))
    return out


def export_csv(db: Database, scan_id: Optional[int] = None, out: Optional[Path] = None) -> Path:
    out = out or (export_dir() / _suggested_name(scan_id, "csv"))
    rows = _rows(db, scan_id)
    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(_FIELDS))
        writer.writeheader()
        for row in rows:
            writer.writerow({f: row[f] for f in _FIELDS if f in row.keys()})
    log.info("CSV export -> %s (%d hosts)", out, len(rows))
    return out


def export_html(db: Database, scan_id: Optional[int] = None, out: Optional[Path] = None) -> Path:
    out = out or (export_dir() / _suggested_name(scan_id, "html"))
    rows = _rows(db, scan_id)
    stats = db.stats_by_provider(scan_id=scan_id)

    rows_html = "\n".join(_row_to_html(r) for r in rows)
    stats_html = "\n".join(
        f'<li><span class="dot" style="background:{html.escape(s["color"] or "#00ff9c")}"></span>'
        f"<strong>{html.escape(s['name'])}</strong> — {s['hits']}</li>"
        for s in stats
    )

    out.write_text(
        _HTML_TEMPLATE.format(
            app=html.escape(APP_NAME),
            version=html.escape(APP_VERSION),
            scan_label=f"#{scan_id}" if scan_id else "all scans",
            generated=time.strftime("%Y-%m-%d %H:%M:%S"),
            host_count=len(rows),
            stats=stats_html or "<li>no data</li>",
            rows=rows_html or "<tr><td colspan='6' style='text-align:center'>no hosts</td></tr>",
        ),
        encoding="utf-8",
    )
    log.info("HTML export -> %s (%d hosts)", out, len(rows))
    return out


def export_all(db: Database, scan_id: Optional[int] = None) -> List[Path]:
    return [
        export_json(db, scan_id),
        export_csv(db, scan_id),
        export_html(db, scan_id),
    ]


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _suggested_name(scan_id: Optional[int], ext: str) -> str:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    label = f"scan{scan_id}-" if scan_id else "all-"
    return f"x-ravscan-{label}{stamp}.{ext}"


def _row_to_html(row: sqlite3.Row) -> str:
    return (
        "<tr>"
        f"<td>{html.escape(row['ip'] or '')}</td>"
        f"<td>{int(row['port'] or 0)}</td>"
        f"<td>{html.escape(row['provider_name'] or '')}</td>"
        f"<td>{html.escape(row['tls_issuer'] or '')}</td>"
        f"<td>{html.escape(row['tls_subject'] or '')}</td>"
        f"<td>{html.escape(row['tls_expires'] or '')}</td>"
        "</tr>"
    )


_HTML_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<title>{app} {version} — Report ({scan_label})</title>
<style>
  :root {{
    --bg:#0d1117; --panel:#161b22; --border:#1f2630;
    --text:#e6edf3; --dim:#8b949e; --accent:#00ff9c; --alt:#1f8cff;
  }}
  *{{box-sizing:border-box}}
  body{{margin:0;font-family:'Segoe UI',-apple-system,Roboto,sans-serif;
        background:var(--bg);color:var(--text)}}
  header{{padding:24px 32px;border-bottom:1px solid var(--border);
         background:linear-gradient(90deg,#0d1117,#11161d 60%,#0d1117);
         display:flex;align-items:center;gap:16px}}
  header .logo{{font-weight:800;letter-spacing:2px;color:var(--accent);
                font-size:24px;text-shadow:0 0 12px rgba(0,255,156,.4)}}
  header .meta{{color:var(--dim);font-size:13px}}
  main{{padding:24px 32px;max-width:1400px;margin:0 auto}}
  .grid{{display:grid;grid-template-columns:1fr 2fr;gap:24px}}
  .card{{background:var(--panel);border:1px solid var(--border);
        border-radius:10px;padding:20px}}
  .card h2{{margin:0 0 12px 0;color:var(--accent);font-size:14px;
            letter-spacing:1px;text-transform:uppercase}}
  ul.legend{{list-style:none;padding:0;margin:0}}
  ul.legend li{{display:flex;align-items:center;gap:10px;margin:4px 0;
                font-size:14px}}
  ul.legend .dot{{width:10px;height:10px;border-radius:50%;
                  display:inline-block;box-shadow:0 0 6px currentColor}}
  table{{width:100%;border-collapse:collapse;font-size:13px}}
  th,td{{padding:8px 10px;border-bottom:1px solid var(--border);
        text-align:left;vertical-align:top}}
  th{{color:var(--accent);font-weight:600;letter-spacing:.5px;
      text-transform:uppercase;font-size:11px}}
  tbody tr:hover{{background:rgba(0,255,156,.05)}}
  .filter{{margin-bottom:12px}}
  .filter input{{width:100%;padding:8px 12px;border-radius:6px;
                 background:#0d1117;border:1px solid var(--border);
                 color:var(--text);font-family:inherit}}
  footer{{padding:16px 32px;color:var(--dim);font-size:12px;
          border-top:1px solid var(--border);text-align:center}}
</style>
</head>
<body>
<header>
  <div class="logo">{app}</div>
  <div class="meta">v{version} · {scan_label} · {host_count} hosts · {generated}</div>
</header>
<main>
  <div class="grid">
    <section class="card">
      <h2>Hosts by provider</h2>
      <ul class="legend">{stats}</ul>
    </section>
    <section class="card">
      <h2>Alive hosts</h2>
      <div class="filter"><input id="q" placeholder="Filter by IP, issuer or subject..." /></div>
      <table id="hosts">
        <thead>
          <tr><th>IP</th><th>Port</th><th>Provider</th>
              <th>TLS Issuer</th><th>Subject</th><th>Expires</th></tr>
        </thead>
        <tbody>{rows}</tbody>
      </table>
    </section>
  </div>
</main>
<footer>generated by {app} v{version}</footer>
<script>
  const q=document.getElementById('q');
  const rows=Array.from(document.querySelectorAll('#hosts tbody tr'));
  q.addEventListener('input',()=>{{
    const v=q.value.toLowerCase();
    for(const r of rows){{
      r.style.display=r.textContent.toLowerCase().includes(v)?'':'none';
    }}
  }});
</script>
</body>
</html>
"""
