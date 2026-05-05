"""Export scan results to JSON, CSV and a branded interactive HTML report.

Every export function accepts an optional ``out`` path so the caller (UI /
CLI / Android intent) can prompt the user for a save location.  When ``out``
is ``None`` we fall back to the per-platform default ``export_dir()``.
"""

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


def _resolve_out(out: Optional[Path], scan_id: Optional[int], ext: str) -> Path:
    if out is None:
        return export_dir() / _suggested_name(scan_id, ext)
    p = Path(out)
    if p.is_dir():
        return p / _suggested_name(scan_id, ext)
    p.parent.mkdir(parents=True, exist_ok=True)
    return p


def export_json(db: Database, scan_id: Optional[int] = None, out: Optional[Path] = None) -> Path:
    out_path = _resolve_out(out, scan_id, "json")
    rows = _rows(db, scan_id)
    payload = {
        "app": APP_NAME,
        "version": APP_VERSION,
        "scan_id": scan_id,
        "exported_at": time.time(),
        "host_count": len(rows),
        "hosts": [{f: row[f] for f in _FIELDS if f in row.keys()} for row in rows],
    }
    out_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    log.info("JSON export -> %s (%d hosts)", out_path, len(rows))
    return out_path


def export_csv(db: Database, scan_id: Optional[int] = None, out: Optional[Path] = None) -> Path:
    out_path = _resolve_out(out, scan_id, "csv")
    rows = _rows(db, scan_id)
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(_FIELDS))
        writer.writeheader()
        for row in rows:
            writer.writerow({f: row[f] for f in _FIELDS if f in row.keys()})
    log.info("CSV export -> %s (%d hosts)", out_path, len(rows))
    return out_path


def export_html(db: Database, scan_id: Optional[int] = None, out: Optional[Path] = None) -> Path:
    out_path = _resolve_out(out, scan_id, "html")
    rows = _rows(db, scan_id)
    stats = db.stats_by_provider(scan_id=scan_id)

    rows_html = "\n".join(_row_to_html(r) for r in rows)
    stats_html = "\n".join(
        f'<li><span class="dot" style="background:{html.escape(s["color"] or "#7aa9ff")}"></span>'
        f"<strong>{html.escape(s['name'])}</strong> &mdash; {s['hits']}</li>"
        for s in stats
    )

    # Quick summary numbers
    rtts = [r["rtt_ms"] for r in rows if r["rtt_ms"] is not None]
    avg_rtt = (sum(rtts) / len(rtts)) if rtts else 0.0
    fast_pct = (len([r for r in rtts if r < 100]) / len(rtts) * 100) if rtts else 0.0

    out_path.write_text(
        _HTML_TEMPLATE.format(
            app=html.escape(APP_NAME),
            version=html.escape(APP_VERSION),
            scan_label=f"#{scan_id}" if scan_id else "all scans",
            generated=time.strftime("%Y-%m-%d %H:%M:%S"),
            host_count=len(rows),
            avg_rtt=f"{avg_rtt:.0f}",
            fast_pct=f"{fast_pct:.0f}",
            stats=stats_html or "<li>no data</li>",
            rows=rows_html or "<tr><td colspan='7' style='text-align:center'>no hosts</td></tr>",
        ),
        encoding="utf-8",
    )
    log.info("HTML export -> %s (%d hosts)", out_path, len(rows))
    return out_path


def export_all(db: Database, scan_id: Optional[int] = None, out_dir: Optional[Path] = None) -> List[Path]:
    base = Path(out_dir) if out_dir else None
    return [
        export_json(db, scan_id, out=base),
        export_csv(db, scan_id, out=base),
        export_html(db, scan_id, out=base),
    ]


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _suggested_name(scan_id: Optional[int], ext: str) -> str:
    stamp = time.strftime("%Y%m%d-%H%M%S")
    label = f"scan{scan_id}-" if scan_id else "all-"
    return f"x-ravscan-{label}{stamp}.{ext}"


def _row_to_html(row: sqlite3.Row) -> str:
    rtt = row["rtt_ms"]
    rtt_html = "—"
    rtt_class = "rtt-na"
    if rtt is not None:
        rtt_value = float(rtt)
        if rtt_value < 80:
            rtt_class = "rtt-fast"
        elif rtt_value < 200:
            rtt_class = "rtt-mid"
        else:
            rtt_class = "rtt-slow"
        rtt_html = f"{rtt_value:.0f} ms"
    return (
        "<tr>"
        f"<td class='ip'>{html.escape(row['ip'] or '')}</td>"
        f"<td>{int(row['port'] or 0)}</td>"
        f"<td class='{rtt_class}'>{rtt_html}</td>"
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
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>{app} {version} — Report ({scan_label})</title>
<style>
  :root {{
    --bg-1:#070a18; --bg-2:#0a1230; --bg-3:#11183c;
    --panel:rgba(255,255,255,0.05);
    --panel-border:rgba(255,255,255,0.1);
    --text:#eaecf4; --dim:#9aa3c0;
    --accent:#7aa9ff; --accent-2:#a98bff;
    --good:#46d58e; --warn:#f6c453; --bad:#ff8888;
  }}
  *{{box-sizing:border-box}}
  html,body{{margin:0;height:100%}}
  body{{
    font-family:-apple-system,'SF Pro Display','SF Pro Text','Inter',
                'Segoe UI',Roboto,sans-serif;
    color:var(--text);
    background:radial-gradient(1200px 800px at 10% -10%,rgba(122,169,255,.12),transparent 60%),
               radial-gradient(900px 600px at 100% 0%,rgba(169,139,255,.10),transparent 55%),
               radial-gradient(900px 700px at 50% 110%,rgba(70,213,142,.07),transparent 60%),
               linear-gradient(180deg,var(--bg-1),var(--bg-2) 40%,var(--bg-3));
    min-height:100vh;
    line-height:1.55;
    -webkit-font-smoothing:antialiased;
    -moz-osx-font-smoothing:grayscale;
    letter-spacing:.005em;
  }}
  header{{
    padding:28px 36px;
    display:flex;align-items:center;gap:18px;
    border-bottom:1px solid var(--panel-border);
    background:rgba(10,15,40,.4);
    backdrop-filter:blur(18px);
    -webkit-backdrop-filter:blur(18px);
  }}
  header .logo{{
    font-weight:700;letter-spacing:.6px;font-size:22px;
    background:linear-gradient(135deg,var(--accent),var(--accent-2));
    -webkit-background-clip:text;background-clip:text;color:transparent;
  }}
  header .meta{{color:var(--dim);font-size:13px}}
  main{{padding:28px 36px;max-width:1400px;margin:0 auto}}
  .summary{{
    display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));
    gap:16px;margin-bottom:24px;
  }}
  .tile{{
    background:var(--panel);
    border:1px solid var(--panel-border);
    border-radius:20px;
    padding:18px 20px;
    backdrop-filter:blur(20px);
    -webkit-backdrop-filter:blur(20px);
    box-shadow:0 8px 24px rgba(0,0,0,.18), inset 0 1px 0 rgba(255,255,255,.04);
  }}
  .tile h3{{
    margin:0 0 8px 0;font-size:11px;letter-spacing:1.5px;
    text-transform:uppercase;color:var(--dim);font-weight:600;
  }}
  .tile .v{{font-size:26px;font-weight:600;letter-spacing:-.5px}}
  .grid{{display:grid;grid-template-columns:1fr 2.2fr;gap:20px}}
  @media(max-width:980px){{ .grid{{grid-template-columns:1fr}} }}
  .card{{
    background:var(--panel);
    border:1px solid var(--panel-border);
    border-radius:24px;
    padding:22px 24px;
    backdrop-filter:blur(20px);
    -webkit-backdrop-filter:blur(20px);
    box-shadow:0 8px 24px rgba(0,0,0,.20), inset 0 1px 0 rgba(255,255,255,.04);
  }}
  .card h2{{
    margin:0 0 14px 0;font-size:12px;letter-spacing:1.5px;
    text-transform:uppercase;color:var(--dim);font-weight:600;
  }}
  ul.legend{{list-style:none;padding:0;margin:0}}
  ul.legend li{{display:flex;align-items:center;gap:10px;margin:6px 0;font-size:14px}}
  ul.legend .dot{{width:9px;height:9px;border-radius:50%;display:inline-block;
                  box-shadow:0 0 8px currentColor}}
  table{{width:100%;border-collapse:collapse;font-size:13px}}
  th,td{{padding:10px 12px;border-bottom:1px solid rgba(255,255,255,.06);
        text-align:left;vertical-align:top}}
  th{{color:var(--dim);font-weight:600;letter-spacing:1px;
      text-transform:uppercase;font-size:10.5px}}
  tbody tr:hover{{background:rgba(255,255,255,.03)}}
  td.ip{{font-family:'SF Mono','JetBrains Mono',Consolas,monospace;color:#cfd8ff}}
  .rtt-fast{{color:var(--good);font-weight:600}}
  .rtt-mid{{color:var(--warn);font-weight:600}}
  .rtt-slow{{color:var(--bad);font-weight:600}}
  .rtt-na{{color:var(--dim)}}
  .filter{{margin-bottom:14px}}
  .filter input{{
    width:100%;padding:10px 14px;border-radius:14px;
    background:rgba(255,255,255,.04);
    border:1px solid var(--panel-border);
    color:var(--text);font-family:inherit;font-size:14px;
    transition:border-color .2s ease,background .2s ease;
  }}
  .filter input:focus{{
    outline:none;border-color:rgba(122,169,255,.6);
    background:rgba(255,255,255,.06);
  }}
  footer{{
    padding:18px 36px;color:var(--dim);font-size:12px;text-align:center;
    border-top:1px solid var(--panel-border);
  }}
</style>
</head>
<body>
<header>
  <div class="logo">{app}</div>
  <div class="meta">v{version} · {scan_label} · {host_count} hosts · avg ping {avg_rtt} ms · {generated}</div>
</header>
<main>
  <div class="summary">
    <div class="tile"><h3>Hosts</h3><div class="v">{host_count}</div></div>
    <div class="tile"><h3>Avg ping</h3><div class="v">{avg_rtt} <span style="font-size:14px;color:var(--dim)">ms</span></div></div>
    <div class="tile"><h3>Fast (&lt;100ms)</h3><div class="v">{fast_pct}<span style="font-size:14px;color:var(--dim)">%</span></div></div>
    <div class="tile"><h3>Generated</h3><div class="v" style="font-size:14px">{generated}</div></div>
  </div>
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
          <tr><th>IP</th><th>Port</th><th>Ping</th><th>Provider</th>
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
