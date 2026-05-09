# X-RavScan

Modern desktop reconnaissance tool that probes well-known CDN / cloud IP
ranges (Cloudflare, Amazon, Microsoft Azure, Google Cloud, Fastly, Akamai,
G-Core, Alibaba, ArvanCloud, Imperva, StackPath, Sucuri, Verizon, Volterra,
DDoS-Guard, EdgeCenter, NGENIX, IBM, LeaseWeb, Lumen / Level 3 etc.) and
records every host that answers TLS:443 with full certificate metadata into
SQLite.

It is a successor of the original `cf_scan_443` script, fully redesigned as
a modular cyberpunk-styled desktop application:

```
tools/X-RavScan/
├── main.py                       # entry point, auto-installs deps, splash
├── pyproject.toml
├── requirements.txt
├── README.md
├── packaging/
│   ├── x_ravscan.spec            # PyInstaller
│   ├── build_pyinstaller.ps1
│   └── build_nuitka.ps1
└── x_ravscan/
    ├── core/
    │   ├── config.py             # paths & theme constants
    │   ├── database.py           # SQLite layer (providers/cidr/scans/hosts)
    │   ├── providers_manager.py  # CDN list + Cloud Sync (AWS/Azure/GCP/CF/Fastly)
    │   ├── network_updater.py    # BGPView Smart Discovery + auto_sync()
    │   ├── scanner.py            # asyncio engine + masscan/zmap fallback
    │   └── exporter.py           # JSON / CSV / interactive HTML report
    ├── ui/
    │   ├── app.py                # CustomTkinter main window
    │   ├── dashboard.py          # PPS chart + pie chart + log console
    │   ├── splash.py             # branded splash
    │   └── theme.py              # cyberpunk palette + matplotlib styling
    ├── utils/
    │   ├── deps.py               # auto-install missing pip deps on launch
    │   └── logger.py             # rotating file log + UI bridge
    └── data/
        ├── seed_providers.json   # provider catalogue (slug, ASNs, sources)
        └── ranges/*.txt          # offline seed CIDR lists
```

## Features

| Module                | What it does                                                                       |
|-----------------------|------------------------------------------------------------------------------------|
| `providers_manager`   | Pulls live IPs from official feeds (Cloudflare `/ips-v4`, AWS `ip-ranges.json`, Azure ServiceTags, GCP `cloud.json`, Fastly `public-ip-list`). Falls back to bundled seed lists when offline. |
| `network_updater`     | Smart Discovery via the public **BGPView** API: walks every provider's ASNs, lists announced IPv4 prefixes, queues new ones into the `discoveries` table. `auto_sync()` accepts them automatically. |
| `scanner`             | `asyncio` TLS:443 prober with a tunable semaphore, capturing certificate `Issuer`, `Subject`, `SAN` and `notAfter`. Optional `masscan` / `zmap` engine for extreme scan rates. ProactorEventLoop is selected automatically on Windows. |
| `database`            | Versioned SQLite schema (`providers`, `cidr_ranges`, `discoveries`, `scans`, `hosts`). |
| `ui`                  | CustomTkinter dashboard (`#0d1117` background + toxic-green accent), live PPS chart, provider pie chart, color-coded log console, provider checkbox list, results table. |
| `exporter`            | JSON, CSV and a self-contained HTML report (filterable table + brand header). |
| `main.py`             | Single entry point. Auto-installs missing deps, shows a branded splash, then opens the GUI. CLI flags: `--cli`, `--sync`, `--discover`, `--auto-sync`, `--export json|csv|html|all`. |

## Install (from source)

```bash
cd tools/X-RavScan
python -m venv .venv
source .venv/bin/activate            # on Windows: .venv\Scripts\activate
pip install -r requirements.txt
python main.py
```

If you skip the `pip install` step, `main.py` will offer to install the
required pip packages itself on first run.

## CLI quick reference

```bash
python main.py --sync                 # refresh all provider feeds
python main.py --discover             # query BGPView, queue new prefixes
python main.py --auto-sync            # discover + auto-accept everything
python main.py --cli --engine asyncio --concurrency 1024 --sample 256 \
               --export all           # headless scan + dump JSON/CSV/HTML
```

## Data locations (per OS)

| Item              | Windows                               | Linux                                | macOS                                          |
|-------------------|---------------------------------------|--------------------------------------|------------------------------------------------|
| SQLite database   | `%APPDATA%\X-RavScan\x_ravscan.sqlite3` | `$XDG_DATA_HOME/X-RavScan/...`        | `~/Library/Application Support/X-RavScan/...`  |
| Logs              | `%APPDATA%\X-RavScan\logs\`           | `$XDG_DATA_HOME/X-RavScan/logs/`      | `~/Library/Application Support/X-RavScan/logs/`|
| Exports           | `%APPDATA%\X-RavScan\exports\`        | `$XDG_DATA_HOME/X-RavScan/exports/`   | `~/Library/Application Support/X-RavScan/exports/` |

## Building the .exe

### Option A — PyInstaller (one-folder, fast startup, ~70 MB)

```powershell
cd tools/X-RavScan
powershell -ExecutionPolicy Bypass -File packaging/build_pyinstaller.ps1
# output: dist/X-RavScan/X-RavScan.exe
```

### Option B — Nuitka (single `.exe`, slower startup, ~100 MB, fastest at runtime)

```powershell
cd tools/X-RavScan
powershell -ExecutionPolicy Bypass -File packaging/build_nuitka.ps1
# output: dist/nuitka/X-RavScan.exe
```

Both helpers automatically:

1. create a `packaging/.venv` virtualenv,
2. `pip install -r requirements.txt`,
3. install the chosen builder (`pyinstaller` or `nuitka`),
4. bundle `x_ravscan/data/seed_providers.json`, `x_ravscan/data/ranges/*.txt`
   and (if present) `x_ravscan/bin/subfinder*` into the .exe.

> CI also produces these artifacts on every push: see the **x-ravscan-build**
> workflow in *Actions* → download `X-RavScan-windows-zip` (one-folder build
> archived to ZIP) or `X-RavScan-windows-onefile` (single `.exe`).

## Smart Discovery & auto-sync logic

```
foreach provider in providers (enabled):
    foreach asn in provider.asns:
        prefixes = bgpview.io/asn/{asn}/prefixes
        for p in prefixes:
            if p not in cidr_ranges and p not in discoveries:
                INSERT INTO discoveries(provider_id, p, asn, description)
auto_sync():
    smart_discovery()
    foreach provider:
        accept_discoveries(provider)   # promotes pending -> live ranges
```

The UI exposes both flows (`Smart Discovery` button + `Discovery` tab with
`Accept` per-row, plus a one-click `Auto-sync (accept all)`).

## Cloud Sync sources

| Provider            | Endpoint                                              |
|---------------------|-------------------------------------------------------|
| Cloudflare          | `https://www.cloudflare.com/ips-v4/`                  |
| AWS                 | `https://ip-ranges.amazonaws.com/ip-ranges.json`      |
| Amazon CloudFront   | same JSON, `service == "CLOUDFRONT"`                  |
| Microsoft Azure     | weekly ServiceTags JSON (auto-resolved)               |
| Azure Front Door    | ServiceTags `AzureFrontDoor.Frontend`                 |
| Google Cloud        | `https://www.gstatic.com/ipranges/cloud.json`         |
| Fastly              | `https://api.fastly.com/public-ip-list`               |

When a feed is unreachable the app keeps the previously stored ranges, so
the scanner stays usable offline.

## Legal / safety

This tool is intended for authorised security research, defensive
infrastructure auditing and bug-bounty engagements where you have explicit
permission to scan the targets. Active probing of third-party networks may
violate local law and the providers' terms of service.

## License

MIT, see [`../../LICENSE`](../../LICENSE).
