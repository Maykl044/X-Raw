"""Smart Discovery — find new BGP-announced subnets per provider.

We use the public BGPView API (https://bgpview.docs.apiary.io/):
    GET /asn/{asn}/prefixes  -> {data: {ipv4_prefixes: [...], ipv6_prefixes: [...]}}

For each provider we walk through its declared ASNs, collect the union of
announced prefixes, and diff it against the local SQLite catalogue. New
prefixes are stored in the ``discoveries`` table so the UI / ``auto_sync``
can decide whether to merge them into the live ranges.
"""

from __future__ import annotations

import ipaddress
import time
from dataclasses import dataclass
from typing import Iterable, List, Optional

import requests

from x_ravscan.core.config import DEFAULT_USER_AGENT
from x_ravscan.core.database import Database, Provider
from x_ravscan.utils.logger import get_logger


log = get_logger("netupdate")


BGPVIEW_BASE = "https://api.bgpview.io"
BGPVIEW_TIMEOUT = 20
# BGPView is rate-limited; back off a bit between calls so a 30-ASN provider
# doesn't trip the limiter.
PER_REQUEST_DELAY = 0.4


@dataclass
class DiscoveredPrefix:
    cidr: str
    asn: int
    description: Optional[str]


def _http_get(url: str) -> Optional[dict]:
    headers = {"User-Agent": DEFAULT_USER_AGENT, "Accept": "application/json"}
    try:
        resp = requests.get(url, headers=headers, timeout=BGPVIEW_TIMEOUT)
        if resp.status_code == 429:
            log.warning("BGPView rate-limited at %s", url)
            time.sleep(2.0)
            return None
        resp.raise_for_status()
        return resp.json()
    except requests.RequestException as e:
        log.warning("BGPView request failed %s: %s", url, e)
        return None


def fetch_asn_prefixes(asn: int, ipv6: bool = False) -> List[DiscoveredPrefix]:
    data = _http_get(f"{BGPVIEW_BASE}/asn/{asn}/prefixes")
    if not data or data.get("status") != "ok":
        return []
    payload = data.get("data") or {}
    prefixes: List[DiscoveredPrefix] = []
    for entry in payload.get("ipv4_prefixes") or []:
        cidr = entry.get("prefix")
        if cidr:
            prefixes.append(
                DiscoveredPrefix(
                    cidr=cidr,
                    asn=asn,
                    description=entry.get("description") or entry.get("name"),
                )
            )
    if ipv6:
        for entry in payload.get("ipv6_prefixes") or []:
            cidr = entry.get("prefix")
            if cidr:
                prefixes.append(
                    DiscoveredPrefix(
                        cidr=cidr,
                        asn=asn,
                        description=entry.get("description") or entry.get("name"),
                    )
                )
    time.sleep(PER_REQUEST_DELAY)
    return prefixes


def smart_discovery(db: Database, providers: Optional[Iterable[Provider]] = None) -> List[DiscoveredPrefix]:
    """Walk all enabled providers (or a custom subset) and queue any prefix
    that we don't already have stored."""
    if providers is None:
        providers = db.list_providers(only_enabled=True)
    fresh: List[DiscoveredPrefix] = []
    for prov in providers:
        if not prov.asns:
            continue
        assert prov.id is not None
        known = {_normalise(c) for c in db.list_ranges(prov.id) if _is_valid(c)}
        for asn in prov.asns:
            for prefix in fetch_asn_prefixes(asn, ipv6=False):
                key = _normalise(prefix.cidr)
                if not key or key in known:
                    continue
                added = db.add_discovered_range(
                    prov.id, prefix.cidr, prefix.asn, prefix.description
                )
                if added:
                    fresh.append(prefix)
                    log.info(
                        "discovered %s for %s (AS%d, %s)",
                        prefix.cidr,
                        prov.slug,
                        prefix.asn,
                        prefix.description,
                    )
    return fresh


def auto_sync(db: Database, accept_new: bool = True) -> int:
    """Run :func:`smart_discovery` and, if ``accept_new`` is True, automatically
    promote everything found into the live CIDR table. Returns the number of
    newly accepted prefixes (across all providers)."""
    fresh = smart_discovery(db)
    if not fresh or not accept_new:
        return 0
    accepted = 0
    seen_pids = {p.id for p in db.list_providers() if p.id is not None}
    for pid in seen_pids:
        accepted += db.accept_discoveries(pid)
    log.info("auto_sync accepted %d new prefixes (saw %d candidates)", accepted, len(fresh))
    return accepted


def _is_valid(cidr: str) -> bool:
    try:
        ipaddress.ip_network(cidr, strict=False)
        return True
    except ValueError:
        return False


def _normalise(cidr: str) -> Optional[str]:
    try:
        return str(ipaddress.ip_network(cidr, strict=False))
    except ValueError:
        return None
