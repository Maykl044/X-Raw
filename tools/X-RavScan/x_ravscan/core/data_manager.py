"""Data manager — Smart Append + dedup + multi-threaded sync + Route Summarisation.

This module sits *on top* of :mod:`providers_manager` and :mod:`network_updater`.
Its job is to manage the project-wide CIDR catalogue with the following
guarantees:

1. **Global coverage** — iterate every provider in the catalogue, never one at
   a time.

2. **Smart Append, never replace** — when new CIDRs arrive (from cloud-sync,
   ASN walk, manual import...) we only *add* them if they bring genuinely
   new address space.  Existing ranges are kept intact.

   Drop rules (in this order):

   * exact duplicate → skip
   * the new prefix is a subset of an already-known one → skip
   * the new prefix is a *superset* of one or more known prefixes → keep,
     and (optionally) prune the now-redundant smaller ones during the next
     ``Clean & Optimize`` pass
   * otherwise → keep

3. **ASN map** — ``data/asn_map.json`` lives next to the seed JSON and maps
   ``ProviderName -> [ASN, ASN, ...]``.  ``sync_all_via_asn()`` walks every
   ASN in the map (in parallel) via BGPView and feeds discovered prefixes
   back through the Smart-Append filter.

4. **iOS-style log line per provider** — each per-provider sync emits a
   single, terse line:

       Cloudflare: 5 added, 120 skipped (already in base)
       Azure: up to date — no new networks

5. **Clean & Optimize** — runs ``ipaddress.collapse_addresses()`` per
   provider and replaces the bag of ranges with a minimal canonical set.
   This shrinks the work the scanner has to do and removes the ``subset``
   leftovers from ad-hoc imports.

6. **Multi-threaded** — every per-provider sync runs on a worker thread so
   the whole catalogue is refreshed in seconds rather than minutes.

The module is UI-agnostic: every public entrypoint accepts an optional
``log`` callable ``(level: str, message: str) -> None`` so the desktop /
mobile UI can pipe lines straight into its activity feed.
"""

from __future__ import annotations

import ipaddress
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Tuple

import requests

from x_ravscan.core.config import DEFAULT_USER_AGENT, app_root
from x_ravscan.core.database import Database, Provider
from x_ravscan.core import providers_manager
from x_ravscan.utils.logger import get_logger


log = get_logger("data_manager")


# ---------------------------------------------------------------------------
# Public types
# ---------------------------------------------------------------------------


LogFn = Callable[[str, str], None]


def _noop(level: str, msg: str) -> None:  # pragma: no cover
    pass


@dataclass
class ProviderSyncReport:
    """Result of a Smart-Append sync for a single provider."""

    slug: str
    name: str
    added: int = 0
    skipped: int = 0
    superseded: int = 0  # smaller prefixes shadowed by a new larger one
    errors: List[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors

    def ios_line(self) -> str:
        if self.added:
            return (
                f"{self.name}: {self.added} added, {self.skipped} skipped"
                f"{f', {self.superseded} superseded' if self.superseded else ''}"
            )
        if self.errors:
            return f"{self.name}: error — {self.errors[0]}"
        return f"{self.name}: up to date — no new networks"


@dataclass
class GlobalSyncReport:
    started_at: float
    finished_at: float = 0.0
    per_provider: List[ProviderSyncReport] = field(default_factory=list)

    @property
    def duration(self) -> float:
        return max(0.0, self.finished_at - self.started_at)

    @property
    def total_added(self) -> int:
        return sum(p.added for p in self.per_provider)

    @property
    def total_skipped(self) -> int:
        return sum(p.skipped for p in self.per_provider)

    @property
    def providers_touched(self) -> int:
        return sum(1 for p in self.per_provider if p.added)


# ---------------------------------------------------------------------------
# Smart-Append core
# ---------------------------------------------------------------------------


def _parse_network(cidr: str) -> Optional[ipaddress._BaseNetwork]:
    try:
        return ipaddress.ip_network(cidr.strip(), strict=False)
    except (ValueError, TypeError):
        return None


def smart_append(
    existing: Sequence[str],
    incoming: Iterable[str],
) -> Tuple[List[str], List[str], int, List[str]]:
    """Merge ``incoming`` into ``existing`` using the Smart-Append rules.

    Returns:
        merged, added, skipped, superseded
    where ``merged`` is the deduplicated list, ``added`` is the list of
    truly new entries that survived the subset / superset checks,
    ``skipped`` is the number of incoming entries that were already
    covered, and ``superseded`` lists previously-known small prefixes
    whose space is now covered by a freshly-added larger one.
    """
    by_family: Dict[int, List[ipaddress._BaseNetwork]] = {4: [], 6: []}
    raw_existing: List[str] = []

    for c in existing:
        net = _parse_network(c)
        if net is None:
            continue
        by_family[net.version].append(net)
        raw_existing.append(str(net))

    seen_keys = {str(net) for nets in by_family.values() for net in nets}
    added: List[str] = []
    skipped = 0
    superseded: List[str] = []

    for raw in incoming:
        net = _parse_network(raw)
        if net is None:
            continue
        key = str(net)
        if key in seen_keys:
            skipped += 1
            continue

        family = by_family[net.version]
        # subset of any existing supernet?
        is_subset = False
        for known in family:
            if net.version != known.version:
                continue
            if net.subnet_of(known):
                is_subset = True
                break
        if is_subset:
            skipped += 1
            continue

        # supernet of any existing? mark them as superseded but keep them
        # for now — Clean & Optimize will collapse them properly later.
        for known in list(family):
            if net.version != known.version:
                continue
            if known.subnet_of(net) and known != net:
                superseded.append(str(known))

        family.append(net)
        seen_keys.add(key)
        added.append(key)

    merged = sorted(seen_keys, key=lambda c: (
        _parse_network(c).version,
        int(_parse_network(c).network_address),
        _parse_network(c).prefixlen,
    ))
    return merged, added, skipped, superseded


def collapse_provider(existing: Sequence[str]) -> Tuple[List[str], int]:
    """Run Route Summarisation on ``existing`` — returns (collapsed, removed)."""
    nets_v4: List[ipaddress.IPv4Network] = []
    nets_v6: List[ipaddress.IPv6Network] = []
    for c in existing:
        n = _parse_network(c)
        if isinstance(n, ipaddress.IPv4Network):
            nets_v4.append(n)
        elif isinstance(n, ipaddress.IPv6Network):
            nets_v6.append(n)
    collapsed = list(ipaddress.collapse_addresses(nets_v4)) + list(
        ipaddress.collapse_addresses(nets_v6)
    )
    out = sorted(
        {str(c) for c in collapsed},
        key=lambda c: (
            _parse_network(c).version,
            int(_parse_network(c).network_address),
            _parse_network(c).prefixlen,
        ),
    )
    removed = max(0, len(set(existing)) - len(out))
    return out, removed


# ---------------------------------------------------------------------------
# Smart-Append into the database
# ---------------------------------------------------------------------------


def smart_append_for_provider(
    db: Database,
    provider_id: int,
    incoming: Iterable[str],
    *,
    source: str = "smart_append",
) -> Tuple[int, int, List[str]]:
    """Apply the Smart-Append rules and persist truly-new CIDRs to SQLite.

    Returns ``(added, skipped, superseded)``.
    """
    existing = db.list_ranges(provider_id)
    _, added_keys, skipped, superseded = smart_append(existing, incoming)
    truly_new = sorted(set(added_keys))
    if truly_new:
        # We can't use replace_ranges() (it nukes provider's CIDRs); instead
        # we INSERT OR IGNORE just the truly new prefixes — those that
        # smart_append already validated as not being a subset / duplicate
        # of any existing range. We piggy-back on Database._cursor().
        with db._cursor() as cur:  # noqa: SLF001 — same package
            now = time.time()
            cur.executemany(
                """
                INSERT OR IGNORE INTO cidr_ranges(provider_id, cidr, source, discovered, added_at)
                VALUES (?, ?, ?, 1, ?)
                """,
                [(provider_id, c, source, now) for c in truly_new],
            )
    return len(added_keys), skipped, superseded


# ---------------------------------------------------------------------------
# ASN map
# ---------------------------------------------------------------------------


ASN_MAP_FILE = "asn_map.json"


def _asn_map_path() -> Path:
    return app_root() / "data" / ASN_MAP_FILE


def load_asn_map() -> Dict[str, List[int]]:
    """Return ``{ProviderName: [ASN, ...]}`` from the bundled JSON."""
    path = _asn_map_path()
    if not path.exists():
        # Fall back to seed_providers.json if asn_map wasn't bundled.
        seeds = providers_manager.load_seed()
        return {s["name"]: list(s.get("asns", [])) for s in seeds}
    with path.open(encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, dict):
        return {k: list(v or []) for k, v in data.items()}
    return {}


def fetch_bgp_prefixes(asn: int, *, timeout: int = 12) -> List[str]:
    """Pull v4+v6 prefixes announced by ``asn`` from BGPView."""
    try:
        r = requests.get(
            f"https://api.bgpview.io/asn/{asn}/prefixes",
            timeout=timeout,
            headers={"User-Agent": DEFAULT_USER_AGENT},
        )
        r.raise_for_status()
        body = r.json()
    except (requests.RequestException, ValueError) as exc:
        log.warning("BGPView asn=%s failed: %s", asn, exc)
        return []
    out: List[str] = []
    for fam in ("ipv4_prefixes", "ipv6_prefixes"):
        for entry in body.get("data", {}).get(fam, []) or []:
            cidr = entry.get("prefix")
            if cidr:
                out.append(cidr)
    return out


# ---------------------------------------------------------------------------
# Multi-threaded global sync
# ---------------------------------------------------------------------------


def sync_all_via_asn(
    db: Database,
    *,
    log_fn: Optional[LogFn] = None,
    workers: int = 8,
    request_delay: float = 0.0,
) -> GlobalSyncReport:
    """Walk the ASN map across every provider in parallel.

    For each ``(provider, [asns...])`` we call BGPView in background threads,
    Smart-Append the prefixes into SQLite, and emit a one-line iOS-style
    summary for the UI log.
    """
    log_fn = log_fn or _noop
    asn_map = load_asn_map()
    providers = {p.name: p for p in db.list_providers()}

    started = time.time()
    report = GlobalSyncReport(started_at=started)
    lock = threading.Lock()

    def _sync_one(name: str, asns: List[int]) -> ProviderSyncReport:
        prov = providers.get(name)
        if prov is None or prov.id is None:
            return ProviderSyncReport(slug=name.lower(), name=name, errors=["no provider"])
        rep = ProviderSyncReport(slug=prov.slug, name=prov.name)
        all_prefixes: List[str] = []
        for asn in asns:
            try:
                if request_delay:
                    time.sleep(request_delay)
                cidrs = fetch_bgp_prefixes(asn)
                all_prefixes.extend(cidrs)
            except Exception as exc:  # noqa: BLE001
                rep.errors.append(f"asn={asn}: {exc}")
        if not all_prefixes and not rep.errors:
            # API responded fine, just no prefixes — provider is up to date.
            return rep
        try:
            added, skipped, superseded = smart_append_for_provider(
                db, prov.id, all_prefixes, source="asn_smart_append"
            )
            rep.added = added
            rep.skipped = skipped
            rep.superseded = len(superseded)
        except Exception as exc:  # noqa: BLE001
            rep.errors.append(str(exc))
        return rep

    workers = max(1, min(workers, len(asn_map) or 1))
    log_fn("INFO", f"Smart Append sync starting — {len(asn_map)} providers, {workers} threads")

    with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="data-sync") as pool:
        futures = {pool.submit(_sync_one, name, asns): name for name, asns in asn_map.items()}
        for fut in as_completed(futures):
            try:
                rep = fut.result()
            except Exception as exc:  # noqa: BLE001
                name = futures[fut]
                rep = ProviderSyncReport(slug=name.lower(), name=name, errors=[str(exc)])
            with lock:
                report.per_provider.append(rep)
            log_fn("INFO", rep.ios_line())

    report.finished_at = time.time()
    log_fn(
        "INFO",
        f"Smart Append done — {report.total_added} new, "
        f"{report.total_skipped} skipped across {len(report.per_provider)} "
        f"providers in {report.duration:.1f}s",
    )
    return report


def clean_and_optimize(
    db: Database,
    *,
    log_fn: Optional[LogFn] = None,
) -> Dict[str, int]:
    """Run Route Summarisation across every provider — returns ``{slug: removed}``."""
    log_fn = log_fn or _noop
    out: Dict[str, int] = {}
    started = time.time()
    log_fn("INFO", "Clean & Optimize started")

    for prov in db.list_providers():
        if prov.id is None:
            continue
        existing = db.list_ranges(prov.id)
        if not existing:
            continue
        collapsed, removed = collapse_provider(existing)
        if removed <= 0:
            log_fn("DEBUG", f"{prov.name}: already optimal ({len(existing)} prefixes)")
            continue
        # Replace the provider's stored prefixes with the collapsed set.
        with db._cursor() as cur:  # noqa: SLF001
            cur.execute(
                "DELETE FROM cidr_ranges WHERE provider_id = ?",
                (prov.id,),
            )
            now = time.time()
            cur.executemany(
                """
                INSERT OR IGNORE INTO cidr_ranges(provider_id, cidr, source, discovered, added_at)
                VALUES (?, ?, 'optimize', 0, ?)
                """,
                [(prov.id, c, now) for c in collapsed],
            )
        out[prov.slug] = removed
        log_fn(
            "INFO",
            f"{prov.name}: collapsed {len(existing)} → {len(collapsed)} prefixes ({removed} merged)",
        )

    log_fn(
        "INFO",
        f"Clean & Optimize done — {sum(out.values())} prefixes merged across "
        f"{len(out)} providers in {time.time() - started:.1f}s",
    )
    return out
