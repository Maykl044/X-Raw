"""Provider catalogue management.

Responsibilities:
- bootstrap providers from ``data/seed_providers.json`` and bundled
  ``data/ranges/*.txt`` on first run;
- pull fresh CIDR ranges from official feeds (Cloudflare, AWS, Azure, GCP,
  Fastly) and persist them to SQLite;
- expose a simple iterator of (provider, [cidrs]) for the scanner.

All network calls are best-effort: if a remote source is unreachable we keep
the previously stored ranges so the app stays usable offline.
"""

from __future__ import annotations

import ipaddress
import json
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import requests

from x_ravscan.core.config import DEFAULT_USER_AGENT, app_root, ranges_dir
from x_ravscan.core.database import Database, Provider
from x_ravscan.utils.logger import get_logger


log = get_logger("providers")


SEED_FILE = "seed_providers.json"
HTTP_TIMEOUT = 15

# Microsoft publishes a weekly JSON behind a download.microsoft.com URL.
# The DOWNLOAD_DETAILS page exposes the latest URL via a meta tag — we scrape
# it on demand and cache the resolved URL inside the provider notes.
AZURE_DOWNLOAD_PAGE = "https://www.microsoft.com/en-us/download/details.aspx?id=56519"
AZURE_URL_RE = re.compile(
    r"https://download\.microsoft\.com/download/[^\s\"'<>]+ServiceTags_Public_\d+\.json",
    re.IGNORECASE,
)


@dataclass
class SyncResult:
    slug: str
    ok: bool
    count: int
    message: str


# ---------------------------------------------------------------------------
# Bootstrap from seed JSON + bundled .txt files
# ---------------------------------------------------------------------------


def _seed_path() -> Path:
    return app_root() / "data" / SEED_FILE


def load_seed() -> List[Dict]:
    path = _seed_path()
    if not path.exists():
        log.warning("seed_providers.json not found at %s", path)
        return []
    with path.open(encoding="utf-8") as f:
        return json.load(f).get("providers", [])


def _read_seed_ranges(filename: Optional[str]) -> List[str]:
    if not filename:
        return []
    p = ranges_dir() / filename
    if not p.exists():
        return []
    with p.open(encoding="utf-8", errors="ignore") as f:
        return [line.strip() for line in f if line.strip() and not line.startswith("#")]


def bootstrap(db: Database, force_seed_ranges: bool = False) -> None:
    """Insert any missing provider rows + seed CIDRs from bundled .txt files."""
    seeds = load_seed()
    existing = {p.slug for p in db.list_providers()}
    for entry in seeds:
        slug = entry["slug"]
        prov = Provider(
            id=None,
            slug=slug,
            name=entry["name"],
            enabled=True,
            asns=entry.get("asns", []),
            sources=[s.get("url") or s.get("service") or "" for s in entry.get("sources", [])],
            color=entry.get("color", "#00ff9c"),
        )
        pid = db.upsert_provider(prov)
        if slug in existing and not force_seed_ranges:
            continue
        cidrs = _read_seed_ranges(entry.get("seed_file"))
        cidrs = [c for c in cidrs if _looks_like_cidr(c)]
        if cidrs:
            db.replace_ranges(pid, cidrs, source="seed")
            log.info("seeded %s with %d CIDRs", slug, len(cidrs))


def _looks_like_cidr(value: str) -> bool:
    try:
        ipaddress.ip_network(value, strict=False)
        return True
    except ValueError:
        return False


# ---------------------------------------------------------------------------
# Fresh sync from official feeds
# ---------------------------------------------------------------------------


def _http_get(url: str, *, json_response: bool = False) -> object:
    headers = {"User-Agent": DEFAULT_USER_AGENT, "Accept": "application/json,text/plain,*/*"}
    resp = requests.get(url, headers=headers, timeout=HTTP_TIMEOUT)
    resp.raise_for_status()
    if json_response:
        return resp.json()
    return resp.text


# --- Cloudflare ------------------------------------------------------------

def fetch_cloudflare() -> List[str]:
    text_v4 = _http_get("https://www.cloudflare.com/ips-v4/")
    text_v6 = _http_get("https://www.cloudflare.com/ips-v6/")
    cidrs = [line.strip() for line in (str(text_v4) + "\n" + str(text_v6)).splitlines()]
    return [c for c in cidrs if _looks_like_cidr(c)]


# --- AWS / CloudFront ------------------------------------------------------

def fetch_aws(service: str = "AMAZON", ipv6: bool = False) -> List[str]:
    data = _http_get("https://ip-ranges.amazonaws.com/ip-ranges.json", json_response=True)
    if not isinstance(data, dict):
        return []
    out: List[str] = []
    for entry in data.get("prefixes", []):
        if entry.get("service", "").upper() == service.upper():
            cidr = entry.get("ip_prefix")
            if cidr:
                out.append(cidr)
    if ipv6:
        for entry in data.get("ipv6_prefixes", []):
            if entry.get("service", "").upper() == service.upper():
                cidr = entry.get("ipv6_prefix")
                if cidr:
                    out.append(cidr)
    return out


# --- Azure (Service Tags) --------------------------------------------------

def _resolve_azure_url() -> Optional[str]:
    try:
        page = _http_get(AZURE_DOWNLOAD_PAGE)
        match = AZURE_URL_RE.search(str(page))
        if match:
            return match.group(0)
    except requests.RequestException as e:
        log.warning("azure landing page unreachable: %s", e)
    return None


def fetch_azure(service: Optional[str] = None) -> List[str]:
    """Fetch Azure ServiceTags JSON. ``service`` filters to a single tag, e.g.
    ``AzureFrontDoor.Frontend``. ``None`` returns the union of all values."""
    url = _resolve_azure_url()
    if not url:
        return []
    data = _http_get(url, json_response=True)
    if not isinstance(data, dict):
        return []
    out: List[str] = []
    for tag in data.get("values", []):
        name = tag.get("name", "")
        if service and name.lower() != service.lower():
            continue
        props = tag.get("properties") or {}
        for cidr in props.get("addressPrefixes", []) or []:
            if _looks_like_cidr(cidr):
                out.append(cidr)
    return out


# --- Google Cloud ----------------------------------------------------------

def fetch_gcp(url: str = "https://www.gstatic.com/ipranges/cloud.json") -> List[str]:
    data = _http_get(url, json_response=True)
    if not isinstance(data, dict):
        return []
    out: List[str] = []
    for prefix in data.get("prefixes", []) or []:
        cidr = prefix.get("ipv4Prefix") or prefix.get("ipv6Prefix")
        if cidr and _looks_like_cidr(cidr):
            out.append(cidr)
    return out


# --- Fastly ----------------------------------------------------------------

def fetch_fastly() -> List[str]:
    data = _http_get("https://api.fastly.com/public-ip-list", json_response=True)
    if not isinstance(data, dict):
        return []
    return [c for c in (data.get("addresses") or []) + (data.get("ipv6_addresses") or []) if _looks_like_cidr(c)]


# ---------------------------------------------------------------------------
# Orchestrator
# ---------------------------------------------------------------------------

_SYNCERS = {
    "cloudflare": fetch_cloudflare,
    "aws": lambda: fetch_aws("AMAZON"),
    "cloudfront": lambda: fetch_aws("CLOUDFRONT"),
    "azure": lambda: fetch_azure(None),
    "azure_frontdoor": lambda: fetch_azure("AzureFrontDoor.Frontend"),
    "gcp": fetch_gcp,
    "fastly": fetch_fastly,
}


def cloud_sync(db: Database, slugs: Optional[Iterable[str]] = None) -> List[SyncResult]:
    """Refresh CIDR ranges from official feeds for given (or all) providers."""
    targets = list(slugs) if slugs else list(_SYNCERS.keys())
    results: List[SyncResult] = []
    for slug in targets:
        fetcher = _SYNCERS.get(slug)
        prov = db.get_provider(slug)
        if not fetcher or not prov:
            results.append(SyncResult(slug=slug, ok=False, count=0, message="no fetcher / provider unknown"))
            continue
        try:
            cidrs = fetcher()
            if not cidrs:
                results.append(SyncResult(slug=slug, ok=False, count=0, message="empty response"))
                continue
            assert prov.id is not None
            inserted = db.replace_ranges(prov.id, cidrs, source="cloud_sync")
            db.mark_provider_synced(slug)
            log.info("cloud_sync %s -> %d cidrs", slug, inserted)
            results.append(SyncResult(slug=slug, ok=True, count=inserted, message="ok"))
        except requests.RequestException as e:
            log.warning("cloud_sync %s failed (network): %s", slug, e)
            results.append(SyncResult(slug=slug, ok=False, count=0, message=f"network: {e}"))
        except Exception as e:  # pragma: no cover
            log.exception("cloud_sync %s failed", slug)
            results.append(SyncResult(slug=slug, ok=False, count=0, message=f"{type(e).__name__}: {e}"))
    return results


def iter_targets(db: Database, only_enabled: bool = True) -> List[Tuple[Provider, List[str]]]:
    """Return [(provider, cidrs)] for the scanner."""
    out: List[Tuple[Provider, List[str]]] = []
    for prov in db.list_providers(only_enabled=only_enabled):
        assert prov.id is not None
        cidrs = db.list_ranges(prov.id)
        if cidrs:
            out.append((prov, cidrs))
    return out


def total_address_count(cidrs: Iterable[str]) -> int:
    """Sum host counts across CIDRs (skips invalid)."""
    n = 0
    for c in cidrs:
        try:
            n += ipaddress.ip_network(c, strict=False).num_addresses
        except ValueError:
            continue
    return n


# ---------------------------------------------------------------------------
# Used by UI: enable/disable toggling
# ---------------------------------------------------------------------------


def set_enabled_bulk(db: Database, mapping: Dict[str, bool]) -> None:
    for slug, enabled in mapping.items():
        db.set_provider_enabled(slug, enabled)


def last_synced_label(prov: Provider) -> str:
    if not prov.last_synced:
        return "never"
    delta = time.time() - prov.last_synced
    if delta < 60:
        return "just now"
    if delta < 3600:
        return f"{int(delta // 60)}m ago"
    if delta < 86400:
        return f"{int(delta // 3600)}h ago"
    return f"{int(delta // 86400)}d ago"
