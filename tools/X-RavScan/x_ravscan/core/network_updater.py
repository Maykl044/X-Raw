"""Smart Discovery — find new BGP-announced subnets per provider.

Sources used (in increasing depth):

1. **BGPView API** — `/asn/{asn}/prefixes` for direct ASN prefixes.
2. **BGPView peers** — `/asn/{asn}/peers` to walk the AS-graph and pull
   prefixes from neighbouring ASNs (Deep Discovery only).
3. **Hackertarget reverse-IP API** — `https://api.hackertarget.com/aslookup/?q=AS{asn}`
   (free, JSON-ish text).  Used as a cross-check / fallback.
4. **RIPEstat** — `https://stat.ripe.net/data/announced-prefixes/data.json`
   (no auth). Cross-check + IPv6 announcements.

Deep Discovery (when ``deep=True``) fans out to peers up to a configured
depth and merges the unique prefixes back into the local catalogue.
"""

from __future__ import annotations

import ipaddress
import time
from dataclasses import dataclass, field
from typing import Callable, Iterable, List, Optional, Set, Tuple

import requests

from x_ravscan.core.config import DEFAULT_USER_AGENT
from x_ravscan.core.database import Database, Provider
from x_ravscan.utils.logger import get_logger


log = get_logger("netupdate")


BGPVIEW_BASE = "https://api.bgpview.io"
RIPESTAT_BASE = "https://stat.ripe.net/data"
HACKERTARGET_BASE = "https://api.hackertarget.com"

BGPVIEW_TIMEOUT = 20
PER_REQUEST_DELAY = 0.4

# Deep Discovery limits — protect against AS-graph explosion.
DEEP_MAX_PEERS_PER_ASN = 12       # how many peers to enqueue per source ASN
DEEP_MAX_TOTAL_ASNS = 80          # absolute cap on ASNs visited per provider


ProgressCallback = Callable[[str], None]


@dataclass
class DiscoveredPrefix:
    cidr: str
    asn: int
    description: Optional[str]
    source: str = "bgpview"        # bgpview | bgpview-peer | ripestat | hackertarget


@dataclass
class DiscoveryReport:
    """Per-run summary returned by :func:`smart_discovery`."""

    fresh: List[DiscoveredPrefix] = field(default_factory=list)
    visited_asns: Set[int] = field(default_factory=set)
    sources_used: Set[str] = field(default_factory=set)
    started_at: float = field(default_factory=time.time)
    finished_at: float = 0.0

    @property
    def duration(self) -> float:
        return max(0.0, self.finished_at - self.started_at)


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def _http_json(url: str) -> Optional[dict]:
    headers = {"User-Agent": DEFAULT_USER_AGENT, "Accept": "application/json"}
    try:
        resp = requests.get(url, headers=headers, timeout=BGPVIEW_TIMEOUT)
        if resp.status_code == 429:
            log.warning("Rate-limited at %s", url)
            time.sleep(2.0)
            return None
        resp.raise_for_status()
        return resp.json()
    except requests.RequestException as e:
        log.warning("HTTP failed %s: %s", url, e)
        return None
    except ValueError as e:
        log.warning("HTTP non-json %s: %s", url, e)
        return None


def _http_text(url: str) -> Optional[str]:
    headers = {"User-Agent": DEFAULT_USER_AGENT}
    try:
        resp = requests.get(url, headers=headers, timeout=BGPVIEW_TIMEOUT)
        resp.raise_for_status()
        return resp.text
    except requests.RequestException as e:
        log.warning("HTTP failed %s: %s", url, e)
        return None


# ---------------------------------------------------------------------------
# BGPView API
# ---------------------------------------------------------------------------

def fetch_asn_prefixes(asn: int, ipv6: bool = False) -> List[DiscoveredPrefix]:
    """Return all prefixes announced by ``asn`` (BGPView)."""
    data = _http_json(f"{BGPVIEW_BASE}/asn/{asn}/prefixes")
    if not data or data.get("status") != "ok":
        return []
    payload = data.get("data") or {}
    out: List[DiscoveredPrefix] = []
    for entry in payload.get("ipv4_prefixes") or []:
        cidr = entry.get("prefix")
        if cidr:
            out.append(
                DiscoveredPrefix(
                    cidr=cidr,
                    asn=asn,
                    description=entry.get("description") or entry.get("name"),
                    source="bgpview",
                )
            )
    if ipv6:
        for entry in payload.get("ipv6_prefixes") or []:
            cidr = entry.get("prefix")
            if cidr:
                out.append(
                    DiscoveredPrefix(
                        cidr=cidr,
                        asn=asn,
                        description=entry.get("description") or entry.get("name"),
                        source="bgpview",
                    )
                )
    time.sleep(PER_REQUEST_DELAY)
    return out


def fetch_asn_peers(asn: int, max_peers: int = DEEP_MAX_PEERS_PER_ASN) -> List[Tuple[int, Optional[str]]]:
    """Return neighbouring ASNs (BGPView).  Limited to ``max_peers``."""
    data = _http_json(f"{BGPVIEW_BASE}/asn/{asn}/peers")
    time.sleep(PER_REQUEST_DELAY)
    if not data or data.get("status") != "ok":
        return []
    seen: List[Tuple[int, Optional[str]]] = []
    for kind in ("ipv4_peers", "ipv6_peers"):
        for entry in (data.get("data") or {}).get(kind) or []:
            try:
                pasn = int(entry.get("asn") or 0)
            except (TypeError, ValueError):
                continue
            if pasn <= 0:
                continue
            seen.append((pasn, entry.get("description") or entry.get("name")))
            if len(seen) >= max_peers:
                return seen
    return seen


# ---------------------------------------------------------------------------
# RIPEstat
# ---------------------------------------------------------------------------

def fetch_ripestat_prefixes(asn: int) -> List[DiscoveredPrefix]:
    """Cross-check BGPView results via RIPEstat (open data, no auth)."""
    data = _http_json(f"{RIPESTAT_BASE}/announced-prefixes/data.json?resource=AS{asn}")
    time.sleep(PER_REQUEST_DELAY)
    if not data or data.get("status") != "ok":
        return []
    out: List[DiscoveredPrefix] = []
    for entry in (data.get("data") or {}).get("prefixes") or []:
        cidr = entry.get("prefix")
        if cidr:
            out.append(
                DiscoveredPrefix(
                    cidr=cidr,
                    asn=asn,
                    description=None,
                    source="ripestat",
                )
            )
    return out


# ---------------------------------------------------------------------------
# Hackertarget (free fallback, plain-text response)
# ---------------------------------------------------------------------------

def fetch_hackertarget_prefixes(asn: int) -> List[DiscoveredPrefix]:
    text = _http_text(f"{HACKERTARGET_BASE}/aslookup/?q=AS{asn}")
    time.sleep(PER_REQUEST_DELAY)
    if not text:
        return []
    out: List[DiscoveredPrefix] = []
    for raw_line in text.splitlines():
        line = raw_line.strip().strip('"')
        if not line or line.startswith("error"):
            continue
        # Format: "AS, prefix, description" or just "prefix"
        parts = [p.strip() for p in line.split(",")]
        candidate = next((p for p in parts if "/" in p), None)
        if not candidate or not _is_valid(candidate):
            continue
        desc = ", ".join(p for p in parts if p and p != candidate and not p.startswith("AS"))
        out.append(
            DiscoveredPrefix(
                cidr=candidate,
                asn=asn,
                description=desc or None,
                source="hackertarget",
            )
        )
    return out


# ---------------------------------------------------------------------------
# Smart Discovery (single-level, original behaviour)
# ---------------------------------------------------------------------------

def smart_discovery(
    db: Database,
    providers: Optional[Iterable[Provider]] = None,
    *,
    progress: Optional[ProgressCallback] = None,
) -> List[DiscoveredPrefix]:
    """Walk all enabled providers (or a custom subset) and queue any prefix
    that we don't already have stored (BGPView only)."""
    if providers is None:
        providers = db.list_providers(only_enabled=True)
    fresh: List[DiscoveredPrefix] = []
    for prov in providers:
        if not prov.asns:
            continue
        assert prov.id is not None
        known = {_normalise(c) for c in db.list_ranges(prov.id) if _is_valid(c)}
        for asn in prov.asns:
            if progress:
                progress(f"[{prov.slug}] BGPView AS{asn}")
            for prefix in fetch_asn_prefixes(asn, ipv6=False):
                key = _normalise(prefix.cidr)
                if not key or key in known:
                    continue
                if db.add_discovered_range(prov.id, prefix.cidr, prefix.asn, prefix.description):
                    fresh.append(prefix)
                    log.info(
                        "discovered %s for %s (AS%d, %s)",
                        prefix.cidr,
                        prov.slug,
                        prefix.asn,
                        prefix.description,
                    )
                    known.add(key)
    return fresh


# ---------------------------------------------------------------------------
# Deep Discovery (multi-source, with peer-walk)
# ---------------------------------------------------------------------------

def deep_discovery(
    db: Database,
    providers: Optional[Iterable[Provider]] = None,
    *,
    use_peers: bool = True,
    use_ripestat: bool = True,
    use_hackertarget: bool = True,
    max_total_asns: int = DEEP_MAX_TOTAL_ASNS,
    progress: Optional[ProgressCallback] = None,
) -> DiscoveryReport:
    """Aggressive multi-source discovery.

    For every provider:
      * pulls direct ASN prefixes from BGPView,
      * cross-checks against RIPEstat (catches IPv6 + edge cases),
      * fetches peer ASNs via BGPView, and pulls *their* prefixes too —
        this catches subsidiary ASNs (e.g. DDoS-Guard ru/us, AWS regional),
      * uses Hackertarget aslookup as a fallback if BGPView/RIPEstat are
        rate-limited.

    Returns a :class:`DiscoveryReport` summarising what we did.  All new
    prefixes that are not already in the live catalogue are queued in the
    ``discoveries`` table — accept them via :func:`auto_sync` or the UI.
    """
    if providers is None:
        providers = list(db.list_providers(only_enabled=True))
    else:
        providers = list(providers)

    report = DiscoveryReport()
    if progress:
        progress(f"deep discovery: {len(providers)} providers")

    for prov in providers:
        if not prov.asns:
            continue
        assert prov.id is not None
        known = {_normalise(c) for c in db.list_ranges(prov.id) if _is_valid(c)}
        visited: Set[int] = set()
        queue: List[Tuple[int, Optional[str]]] = [(asn, None) for asn in prov.asns]

        while queue and len(visited) < max_total_asns:
            asn, hint = queue.pop(0)
            if asn in visited:
                continue
            visited.add(asn)
            report.visited_asns.add(asn)

            # 1. BGPView direct prefixes
            if progress:
                progress(f"[{prov.slug}] BGPView AS{asn}")
            for prefix in fetch_asn_prefixes(asn, ipv6=False):
                _store_if_fresh(db, prov.id, prefix, known, report)
            report.sources_used.add("bgpview")

            # 2. RIPEstat (cross-check + IPv6)
            if use_ripestat:
                if progress:
                    progress(f"[{prov.slug}] RIPEstat AS{asn}")
                for prefix in fetch_ripestat_prefixes(asn):
                    _store_if_fresh(db, prov.id, prefix, known, report)
                report.sources_used.add("ripestat")

            # 3. Hackertarget fallback
            if use_hackertarget:
                if progress:
                    progress(f"[{prov.slug}] Hackertarget AS{asn}")
                for prefix in fetch_hackertarget_prefixes(asn):
                    _store_if_fresh(db, prov.id, prefix, known, report)
                report.sources_used.add("hackertarget")

            # 4. Peer-walk (one level deep)
            if use_peers and hint is None:
                # Only walk peers from the *seed* ASNs, not from peers-of-peers
                # (otherwise the AS graph explodes).
                peers = fetch_asn_peers(asn)
                if peers and progress:
                    progress(f"[{prov.slug}] peers of AS{asn}: {len(peers)}")
                for peer_asn, peer_desc in peers:
                    if peer_asn not in visited and len(visited) + len(queue) < max_total_asns:
                        queue.append((peer_asn, peer_desc))
                report.sources_used.add("bgpview-peers")

    report.finished_at = time.time()
    if progress:
        progress(
            f"done: +{len(report.fresh)} prefixes, "
            f"{len(report.visited_asns)} ASNs, "
            f"{report.duration:.1f}s"
        )
    log.info(
        "deep_discovery finished: +%d prefixes from %d ASNs in %.1fs (sources=%s)",
        len(report.fresh),
        len(report.visited_asns),
        report.duration,
        ",".join(sorted(report.sources_used)),
    )
    return report


def _store_if_fresh(
    db: Database,
    provider_id: int,
    prefix: DiscoveredPrefix,
    known: Set[Optional[str]],
    report: DiscoveryReport,
) -> None:
    key = _normalise(prefix.cidr)
    if not key or key in known:
        return
    if db.add_discovered_range(provider_id, prefix.cidr, prefix.asn, prefix.description):
        report.fresh.append(prefix)
        known.add(key)


# ---------------------------------------------------------------------------
# Auto-sync (promote pending discoveries → live ranges)
# ---------------------------------------------------------------------------

def auto_sync(db: Database, accept_new: bool = True, *, deep: bool = False) -> int:
    """Run discovery and (optionally) promote everything found into the live
    CIDR table.  When ``deep=True`` we use :func:`deep_discovery` instead of
    the single-source :func:`smart_discovery`.  Returns the number of newly
    accepted prefixes (across all providers)."""
    if deep:
        report = deep_discovery(db)
        fresh = report.fresh
    else:
        fresh = smart_discovery(db)
    if not fresh or not accept_new:
        return 0
    accepted = 0
    seen_pids = {p.id for p in db.list_providers() if p.id is not None}
    for pid in seen_pids:
        accepted += db.accept_discoveries(pid)
    log.info("auto_sync accepted %d new prefixes (saw %d candidates)", accepted, len(fresh))
    return accepted


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

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
