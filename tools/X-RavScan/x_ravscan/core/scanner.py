"""Asynchronous TLS:443 scanner.

Two engines are exposed:
    * ``asyncio``  — pure-Python, single-process, semaphore-throttled. On
      Windows the caller can swap in a ProactorEventLoop for fastest IO.
    * ``masscan``  — optional wrapper around an external ``masscan``/``zmap``
      binary for extreme rates (parses NDJSON output back into ``HostRecord``).

The scanner is a generator-style iterator: every IP that responds is reported
through an asyncio queue so the UI can update PPS counters in real-time
without the scanner having to know about Tk.
"""

from __future__ import annotations

import asyncio
import ipaddress
import json
import os
import shutil
import socket
import ssl
import subprocess
import sys
import time
from dataclasses import dataclass, field
from typing import AsyncIterator, Callable, List, Optional, Sequence, Tuple

from x_ravscan.core.config import (
    DEFAULT_CONCURRENCY,
    DEFAULT_PORT,
    DEFAULT_TCP_TIMEOUT,
    DEFAULT_TLS_TIMEOUT,
)
from x_ravscan.core.database import Database, HostRecord, Provider
from x_ravscan.utils.logger import get_logger


log = get_logger("scanner")


# ---------------------------------------------------------------------------
# Result model
# ---------------------------------------------------------------------------


@dataclass
class ScanProgress:
    total: int = 0
    completed: int = 0
    alive: int = 0
    started_at: float = field(default_factory=time.time)

    def pps(self) -> float:
        elapsed = max(time.time() - self.started_at, 1e-6)
        return self.completed / elapsed


@dataclass
class ScanResult:
    ip: str
    port: int
    provider: Optional[Provider]
    rtt_ms: float
    tls_subject: Optional[str] = None
    tls_issuer: Optional[str] = None
    tls_san: Optional[str] = None
    tls_expires: Optional[str] = None


# ---------------------------------------------------------------------------
# IP iteration helpers
# ---------------------------------------------------------------------------


def expand_targets(
    targets: Sequence[Tuple[Provider, Sequence[str]]],
    *,
    sample_per_cidr: Optional[int] = None,
) -> List[Tuple[Provider, str]]:
    """Convert (provider, [cidr,...]) into a flat (provider, ip) list.

    ``sample_per_cidr`` lets the UI cap huge /8 networks to a manageable
    sample size. ``None`` expands fully (use with caution!).
    """
    out: List[Tuple[Provider, str]] = []
    for prov, cidrs in targets:
        for cidr in cidrs:
            try:
                net = ipaddress.ip_network(cidr.strip(), strict=False)
            except ValueError:
                continue
            if isinstance(net, ipaddress.IPv6Network):
                # Skip IPv6 by default — too vast and most CDNs publish v4 too.
                continue
            hosts = net.hosts() if net.num_addresses > 2 else iter([net.network_address])
            count = 0
            for ip in hosts:
                out.append((prov, str(ip)))
                count += 1
                if sample_per_cidr and count >= sample_per_cidr:
                    break
    return out


# ---------------------------------------------------------------------------
# Asyncio engine
# ---------------------------------------------------------------------------


def _build_ssl_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE  # we want the cert, not validation.
    ctx.set_ciphers("DEFAULT@SECLEVEL=1")
    return ctx


# DER-form parser using cryptography (lazy import — keeps scanner.py importable
# in environments where cryptography is not yet installed).


def _parse_der(der: bytes) -> dict:
    try:
        from cryptography import x509
        from cryptography.x509.oid import ExtensionOID
    except Exception:  # pragma: no cover
        return {}
    try:
        cert = x509.load_der_x509_certificate(der)
    except Exception:
        return {}
    info: dict = {}

    def _format_name(name) -> Optional[str]:
        try:
            return ", ".join(f"{a.oid._name}={a.value}" for a in name)
        except Exception:
            try:
                return name.rfc4514_string()
            except Exception:
                return None

    info["subject"] = _format_name(cert.subject)
    info["issuer"] = _format_name(cert.issuer)
    try:
        # cryptography>=42 prefers ``not_valid_after_utc``; old releases only
        # have the naïve ``not_valid_after`` attribute. Use whichever exists.
        when = getattr(cert, "not_valid_after_utc", None) or cert.not_valid_after
        info["expires"] = when.strftime("%b %d %H:%M:%S %Y GMT")
    except Exception:
        info["expires"] = None
    try:
        san_ext = cert.extensions.get_extension_for_oid(ExtensionOID.SUBJECT_ALTERNATIVE_NAME)
        sans = []
        for value in san_ext.value:
            try:
                sans.append(f"{type(value).__name__}:{value.value}")
            except Exception:
                sans.append(str(value))
        info["san"] = ", ".join(sans)
    except Exception:
        info["san"] = None
    return info


async def _probe_one(
    ip: str,
    port: int,
    ssl_ctx: ssl.SSLContext,
    tcp_timeout: float,
    tls_timeout: float,
) -> Optional[ScanResult]:
    started = time.perf_counter()
    try:
        reader, writer = await asyncio.wait_for(
            asyncio.open_connection(host=ip, port=port, ssl=ssl_ctx),
            timeout=max(tcp_timeout, tls_timeout),
        )
    except (
        asyncio.TimeoutError,
        ConnectionRefusedError,
        ConnectionResetError,
        OSError,
        ssl.SSLError,
    ):
        return None
    try:
        rtt_ms = (time.perf_counter() - started) * 1000.0
        cert_info: dict = {}
        try:
            sslobj = writer.get_extra_info("ssl_object")
            if sslobj is not None:
                der = sslobj.getpeercert(binary_form=True)
                if der:
                    cert_info = _parse_der(der)
        except Exception:  # pragma: no cover
            cert_info = {}
        return ScanResult(
            ip=ip,
            port=port,
            provider=None,
            rtt_ms=rtt_ms,
            tls_subject=cert_info.get("subject"),
            tls_issuer=cert_info.get("issuer"),
            tls_san=cert_info.get("san"),
            tls_expires=cert_info.get("expires"),
        )
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:  # pragma: no cover
            pass


async def asyncio_scan(
    flat_targets: Sequence[Tuple[Provider, str]],
    *,
    port: int = DEFAULT_PORT,
    concurrency: int = DEFAULT_CONCURRENCY,
    tcp_timeout: float = DEFAULT_TCP_TIMEOUT,
    tls_timeout: float = DEFAULT_TLS_TIMEOUT,
    progress_cb: Optional[Callable[[ScanProgress, Optional[ScanResult]], None]] = None,
    cancel_event: Optional[asyncio.Event] = None,
) -> AsyncIterator[ScanResult]:
    """Scan ``[(provider, ip), ...]`` asynchronously.

    Yields :class:`ScanResult` for each *alive* host. Use ``progress_cb`` for
    a per-IP UI tick (it's invoked even for dead hosts so PPS stays accurate).
    """
    ssl_ctx = _build_ssl_context()
    sem = asyncio.Semaphore(concurrency)
    progress = ScanProgress(total=len(flat_targets))
    queue: asyncio.Queue[Optional[ScanResult]] = asyncio.Queue()

    async def runner(prov: Provider, ip: str) -> None:
        if cancel_event and cancel_event.is_set():
            return
        async with sem:
            if cancel_event and cancel_event.is_set():
                return
            res = await _probe_one(ip, port, ssl_ctx, tcp_timeout, tls_timeout)
            progress.completed += 1
            if res is not None:
                res.provider = prov
                progress.alive += 1
                await queue.put(res)
            if progress_cb:
                try:
                    progress_cb(progress, res)
                except Exception:  # pragma: no cover
                    log.exception("progress_cb failed")

    async def producer() -> None:
        tasks = [asyncio.create_task(runner(p, ip)) for p, ip in flat_targets]
        try:
            await asyncio.gather(*tasks, return_exceptions=True)
        finally:
            await queue.put(None)

    producer_task = asyncio.create_task(producer())
    try:
        while True:
            item = await queue.get()
            if item is None:
                break
            yield item
    finally:
        producer_task.cancel()
        try:
            await producer_task
        except (asyncio.CancelledError, Exception):  # pragma: no cover
            pass


# ---------------------------------------------------------------------------
# Optional masscan/zmap engine
# ---------------------------------------------------------------------------


def has_external_engine(name: str) -> bool:
    return shutil.which(name) is not None


def masscan_scan(
    cidrs: Sequence[Tuple[Provider, Sequence[str]]],
    *,
    rate: int = 5000,
    port: int = DEFAULT_PORT,
    binary: str = "masscan",
) -> List[ScanResult]:
    """Run masscan against the given CIDRs. Requires admin privileges."""
    if not has_external_engine(binary):
        raise RuntimeError(f"{binary} binary not found in PATH")
    targets: List[str] = []
    provider_lookup: dict[str, Provider] = {}
    for prov, blocks in cidrs:
        for c in blocks:
            targets.append(c)
            try:
                net = ipaddress.ip_network(c, strict=False)
                provider_lookup[str(net.network_address)] = prov
            except ValueError:
                continue
    if not targets:
        return []
    cmd = [binary, "-p", str(port), "--rate", str(rate), "-oJ", "-"] + targets
    log.info("running %s", " ".join(cmd))
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        log.warning("masscan exited %s: %s", proc.returncode, proc.stderr.strip())
    out: List[ScanResult] = []
    for line in proc.stdout.splitlines():
        line = line.strip().rstrip(",")
        if not line or line in ("[", "]"):
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        ip = row.get("ip")
        if not ip:
            continue
        out.append(ScanResult(ip=ip, port=port, provider=None, rtt_ms=-1.0))
    return out


# ---------------------------------------------------------------------------
# High-level entrypoint
# ---------------------------------------------------------------------------


def configure_event_loop() -> None:
    """Switch to ProactorEventLoop on Windows for max IO concurrency."""
    if sys.platform.startswith("win"):
        try:
            asyncio.set_event_loop_policy(asyncio.WindowsProactorEventLoopPolicy())
            log.info("Windows ProactorEventLoopPolicy active")
        except AttributeError:  # pragma: no cover - non-Windows fallback
            pass
    else:
        # Higher default file-descriptor limit helps when concurrency is very
        # large; failures are non-fatal (e.g. in containers).
        try:
            import resource

            soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
            target = min(max(soft, 8192), hard)
            resource.setrlimit(resource.RLIMIT_NOFILE, (target, hard))
        except Exception:
            pass


async def run_scan(
    db: Database,
    *,
    engine: str = "asyncio",
    concurrency: int = DEFAULT_CONCURRENCY,
    sample_per_cidr: Optional[int] = 256,
    progress_cb: Optional[Callable[[ScanProgress, Optional[ScanResult]], None]] = None,
    cancel_event: Optional[asyncio.Event] = None,
) -> int:
    """End-to-end scan: pick enabled providers, scan, persist alive hosts.

    Returns the scan_id row in the SQLite table.
    """
    targets = []
    from x_ravscan.core.providers_manager import iter_targets

    for prov, cidrs in iter_targets(db, only_enabled=True):
        targets.append((prov, cidrs))

    if not targets:
        log.warning("no providers enabled — nothing to scan")
        return -1

    flat = expand_targets(targets, sample_per_cidr=sample_per_cidr)
    scan_id = db.start_scan(engine, [p.slug for p, _ in targets], len(flat))
    log.info("scan #%d started: engine=%s, hosts=%d", scan_id, engine, len(flat))

    alive = 0
    if engine == "asyncio":
        async for res in asyncio_scan(
            flat,
            concurrency=concurrency,
            progress_cb=progress_cb,
            cancel_event=cancel_event,
        ):
            assert res.provider is not None and res.provider.id is not None
            db.add_host(
                HostRecord(
                    ip=res.ip,
                    port=res.port,
                    provider_id=res.provider.id,
                    scan_id=scan_id,
                    tls_subject=res.tls_subject,
                    tls_issuer=res.tls_issuer,
                    tls_san=res.tls_san,
                    tls_expires=res.tls_expires,
                    rtt_ms=res.rtt_ms,
                )
            )
            alive += 1
    elif engine in ("masscan", "zmap"):
        results = masscan_scan(targets, port=DEFAULT_PORT, binary=engine)
        # Map result IPs back to providers.
        prov_for_cidr: List[Tuple[ipaddress._BaseNetwork, Provider]] = []
        for prov, cidrs in targets:
            for c in cidrs:
                try:
                    prov_for_cidr.append((ipaddress.ip_network(c, strict=False), prov))
                except ValueError:
                    continue
        for res in results:
            try:
                ip = ipaddress.ip_address(res.ip)
            except ValueError:
                continue
            prov = next((p for net, p in prov_for_cidr if ip in net), None)
            if prov is None or prov.id is None:
                continue
            db.add_host(
                HostRecord(
                    ip=res.ip,
                    port=res.port,
                    provider_id=prov.id,
                    scan_id=scan_id,
                )
            )
            alive += 1
    else:
        raise ValueError(f"unknown engine {engine!r}")

    db.finish_scan(scan_id, alive)
    log.info("scan #%d finished: alive=%d / total=%d", scan_id, alive, len(flat))
    return scan_id
