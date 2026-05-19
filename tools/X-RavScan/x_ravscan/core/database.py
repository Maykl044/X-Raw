"""SQLite persistence layer.

Schema:
    providers      — registered CDN/cloud providers (AWS, Cloudflare, ...)
    cidr_ranges    — known IP/CIDR blocks per provider
    discoveries    — newly discovered ranges from Smart Discovery
    scans          — scan job metadata (start/end, total/alive)
    hosts          — alive hosts found during scans (with TLS info)

The module is intentionally self-contained: no global state, every public
function takes (or returns) a :class:`Database` instance, so unit tests can
operate on a temporary file or ``:memory:`` database.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator, List, Optional, Sequence

from x_ravscan.core.config import db_path
from x_ravscan.utils.logger import get_logger


log = get_logger("db")


SCHEMA = """
CREATE TABLE IF NOT EXISTS providers (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    slug         TEXT UNIQUE NOT NULL,
    name         TEXT NOT NULL,
    enabled      INTEGER NOT NULL DEFAULT 1,
    asns         TEXT NOT NULL DEFAULT '[]',   -- JSON array of ints
    sources      TEXT NOT NULL DEFAULT '[]',   -- JSON array of URL/strings
    color        TEXT NOT NULL DEFAULT '#00ff9c',
    last_synced  REAL,
    notes        TEXT
);

CREATE TABLE IF NOT EXISTS cidr_ranges (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id  INTEGER NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    cidr         TEXT NOT NULL,
    source       TEXT,
    discovered   INTEGER NOT NULL DEFAULT 0,   -- 1 = found by Smart Discovery
    added_at     REAL NOT NULL,
    UNIQUE(provider_id, cidr)
);

CREATE INDEX IF NOT EXISTS idx_cidr_provider ON cidr_ranges(provider_id);

CREATE TABLE IF NOT EXISTS discoveries (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id  INTEGER NOT NULL REFERENCES providers(id) ON DELETE CASCADE,
    cidr         TEXT NOT NULL,
    asn          INTEGER,
    description  TEXT,
    accepted     INTEGER NOT NULL DEFAULT 0,
    found_at     REAL NOT NULL,
    UNIQUE(provider_id, cidr)
);

CREATE TABLE IF NOT EXISTS scans (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at   REAL NOT NULL,
    finished_at  REAL,
    engine       TEXT NOT NULL,
    providers    TEXT NOT NULL,                -- JSON array of slugs
    total_hosts  INTEGER NOT NULL DEFAULT 0,
    alive_hosts  INTEGER NOT NULL DEFAULT 0,
    notes        TEXT
);

CREATE TABLE IF NOT EXISTS hosts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    scan_id      INTEGER REFERENCES scans(id) ON DELETE SET NULL,
    provider_id  INTEGER REFERENCES providers(id) ON DELETE SET NULL,
    ip           TEXT NOT NULL,
    port         INTEGER NOT NULL DEFAULT 443,
    alive        INTEGER NOT NULL DEFAULT 1,
    tls_subject  TEXT,
    tls_issuer   TEXT,
    tls_san      TEXT,
    tls_expires  TEXT,
    rtt_ms       REAL,
    seen_at      REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_hosts_scan ON hosts(scan_id);
CREATE INDEX IF NOT EXISTS idx_hosts_ip ON hosts(ip);
CREATE INDEX IF NOT EXISTS idx_hosts_provider ON hosts(provider_id);

CREATE TABLE IF NOT EXISTS app_settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


@dataclass
class Provider:
    id: Optional[int]
    slug: str
    name: str
    enabled: bool = True
    asns: List[int] = field(default_factory=list)
    sources: List[str] = field(default_factory=list)
    color: str = "#00ff9c"
    last_synced: Optional[float] = None
    notes: Optional[str] = None


@dataclass
class HostRecord:
    ip: str
    port: int
    provider_id: Optional[int]
    scan_id: Optional[int]
    tls_subject: Optional[str] = None
    tls_issuer: Optional[str] = None
    tls_san: Optional[str] = None
    tls_expires: Optional[str] = None
    rtt_ms: Optional[float] = None


class Database:
    """Thread-safe SQLite wrapper.

    SQLite connections are not safe to share across threads by default; we
    lock at the Python level for simplicity and only run quick statements.
    """

    def __init__(self, path: Optional[Path] = None) -> None:
        self.path = Path(path) if path else db_path()
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(
            self.path, detect_types=sqlite3.PARSE_DECLTYPES, check_same_thread=False
        )
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL;")
        self._conn.execute("PRAGMA foreign_keys=ON;")
        self._conn.executescript(SCHEMA)
        self._conn.commit()
        log.info("SQLite ready at %s", self.path)

    # ------------------------------------------------------------------
    # Low-level
    # ------------------------------------------------------------------
    @contextmanager
    def _cursor(self) -> Iterator[sqlite3.Cursor]:
        with self._lock:
            cur = self._conn.cursor()
            try:
                yield cur
                self._conn.commit()
            except Exception:
                self._conn.rollback()
                raise
            finally:
                cur.close()

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    # ------------------------------------------------------------------
    # App settings (key/value)
    # ------------------------------------------------------------------
    def get_setting(self, key: str, default: Optional[str] = None) -> Optional[str]:
        with self._cursor() as cur:
            row = cur.execute(
                "SELECT value FROM app_settings WHERE key=?", (key,)
            ).fetchone()
            return row["value"] if row else default

    def set_setting(self, key: str, value: str) -> None:
        with self._cursor() as cur:
            cur.execute(
                "INSERT INTO app_settings(key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, value),
            )

    # ------------------------------------------------------------------
    # Providers
    # ------------------------------------------------------------------
    def upsert_provider(self, p: Provider) -> int:
        with self._cursor() as cur:
            cur.execute(
                """
                INSERT INTO providers(slug, name, enabled, asns, sources, color, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(slug) DO UPDATE SET
                    name=excluded.name,
                    asns=excluded.asns,
                    sources=excluded.sources,
                    color=excluded.color,
                    notes=excluded.notes
                """,
                (
                    p.slug,
                    p.name,
                    int(p.enabled),
                    json.dumps(p.asns),
                    json.dumps(p.sources),
                    p.color,
                    p.notes,
                ),
            )
            cur.execute("SELECT id FROM providers WHERE slug = ?", (p.slug,))
            row = cur.fetchone()
            return int(row["id"])

    def set_provider_enabled(self, slug: str, enabled: bool) -> None:
        with self._cursor() as cur:
            cur.execute(
                "UPDATE providers SET enabled = ? WHERE slug = ?",
                (int(enabled), slug),
            )

    def mark_provider_synced(self, slug: str, ts: Optional[float] = None) -> None:
        with self._cursor() as cur:
            cur.execute(
                "UPDATE providers SET last_synced = ? WHERE slug = ?",
                (ts if ts is not None else time.time(), slug),
            )

    def list_providers(self, only_enabled: bool = False) -> List[Provider]:
        with self._cursor() as cur:
            if only_enabled:
                cur.execute(
                    "SELECT * FROM providers WHERE enabled = 1 ORDER BY name"
                )
            else:
                cur.execute("SELECT * FROM providers ORDER BY name")
            return [_row_to_provider(r) for r in cur.fetchall()]

    def get_provider(self, slug: str) -> Optional[Provider]:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM providers WHERE slug = ?", (slug,))
            row = cur.fetchone()
        return _row_to_provider(row) if row else None

    # ------------------------------------------------------------------
    # CIDR ranges
    # ------------------------------------------------------------------
    def replace_ranges(self, provider_id: int, cidrs: Iterable[str], source: str = "sync") -> int:
        """Replace all ranges for a provider; returns inserted count."""
        cidrs = sorted(set(c.strip() for c in cidrs if c.strip()))
        with self._cursor() as cur:
            cur.execute(
                "DELETE FROM cidr_ranges WHERE provider_id = ? AND discovered = 0",
                (provider_id,),
            )
            now = time.time()
            cur.executemany(
                """
                INSERT OR IGNORE INTO cidr_ranges(provider_id, cidr, source, discovered, added_at)
                VALUES (?, ?, ?, 0, ?)
                """,
                [(provider_id, c, source, now) for c in cidrs],
            )
        return len(cidrs)

    def add_discovered_range(self, provider_id: int, cidr: str, asn: Optional[int], description: Optional[str]) -> bool:
        """Returns True if newly inserted, False if already known."""
        with self._cursor() as cur:
            cur.execute(
                "SELECT 1 FROM cidr_ranges WHERE provider_id = ? AND cidr = ?",
                (provider_id, cidr),
            )
            if cur.fetchone():
                return False
            cur.execute(
                "SELECT 1 FROM discoveries WHERE provider_id = ? AND cidr = ?",
                (provider_id, cidr),
            )
            if cur.fetchone():
                return False
            now = time.time()
            cur.execute(
                """
                INSERT INTO discoveries(provider_id, cidr, asn, description, accepted, found_at)
                VALUES (?, ?, ?, ?, 0, ?)
                """,
                (provider_id, cidr, asn, description, now),
            )
        return True

    def accept_discoveries(self, provider_id: int) -> int:
        """Move pending discoveries into cidr_ranges for the given provider."""
        with self._cursor() as cur:
            cur.execute(
                "SELECT id, cidr FROM discoveries WHERE provider_id = ? AND accepted = 0",
                (provider_id,),
            )
            rows = cur.fetchall()
            if not rows:
                return 0
            now = time.time()
            cur.executemany(
                """
                INSERT OR IGNORE INTO cidr_ranges(provider_id, cidr, source, discovered, added_at)
                VALUES (?, ?, 'discovery', 1, ?)
                """,
                [(provider_id, r["cidr"], now) for r in rows],
            )
            cur.executemany(
                "UPDATE discoveries SET accepted = 1 WHERE id = ?",
                [(r["id"],) for r in rows],
            )
            return len(rows)

    def list_ranges(self, provider_id: int) -> List[str]:
        with self._cursor() as cur:
            cur.execute(
                "SELECT cidr FROM cidr_ranges WHERE provider_id = ? ORDER BY cidr",
                (provider_id,),
            )
            return [r["cidr"] for r in cur.fetchall()]

    def list_pending_discoveries(self) -> List[sqlite3.Row]:
        with self._cursor() as cur:
            cur.execute(
                """
                SELECT d.*, p.slug AS provider_slug, p.name AS provider_name
                FROM discoveries d
                JOIN providers p ON p.id = d.provider_id
                WHERE d.accepted = 0
                ORDER BY d.found_at DESC
                """
            )
            return list(cur.fetchall())

    # ------------------------------------------------------------------
    # Scans + hosts
    # ------------------------------------------------------------------
    def start_scan(self, engine: str, provider_slugs: Sequence[str], total: int) -> int:
        with self._cursor() as cur:
            cur.execute(
                """
                INSERT INTO scans(started_at, engine, providers, total_hosts)
                VALUES (?, ?, ?, ?)
                """,
                (time.time(), engine, json.dumps(list(provider_slugs)), total),
            )
            return int(cur.lastrowid)

    def finish_scan(self, scan_id: int, alive: int) -> None:
        with self._cursor() as cur:
            cur.execute(
                "UPDATE scans SET finished_at = ?, alive_hosts = ? WHERE id = ?",
                (time.time(), alive, scan_id),
            )

    def add_host(self, h: HostRecord) -> None:
        with self._cursor() as cur:
            cur.execute(
                """
                INSERT INTO hosts(scan_id, provider_id, ip, port, alive,
                                  tls_subject, tls_issuer, tls_san, tls_expires,
                                  rtt_ms, seen_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)
                """,
                (
                    h.scan_id,
                    h.provider_id,
                    h.ip,
                    h.port,
                    h.tls_subject,
                    h.tls_issuer,
                    h.tls_san,
                    h.tls_expires,
                    h.rtt_ms,
                    time.time(),
                ),
            )

    def list_hosts(self, scan_id: Optional[int] = None) -> List[sqlite3.Row]:
        with self._cursor() as cur:
            if scan_id is None:
                cur.execute(
                    """
                    SELECT h.*, p.slug AS provider_slug, p.name AS provider_name
                    FROM hosts h LEFT JOIN providers p ON p.id = h.provider_id
                    ORDER BY h.seen_at DESC LIMIT 5000
                    """
                )
            else:
                cur.execute(
                    """
                    SELECT h.*, p.slug AS provider_slug, p.name AS provider_name
                    FROM hosts h LEFT JOIN providers p ON p.id = h.provider_id
                    WHERE h.scan_id = ? ORDER BY h.seen_at DESC
                    """,
                    (scan_id,),
                )
            return list(cur.fetchall())

    def stats_by_provider(self, scan_id: Optional[int] = None) -> List[sqlite3.Row]:
        with self._cursor() as cur:
            if scan_id is None:
                cur.execute(
                    """
                    SELECT p.name AS name, p.color AS color, COUNT(h.id) AS hits
                    FROM hosts h JOIN providers p ON p.id = h.provider_id
                    GROUP BY p.id ORDER BY hits DESC
                    """
                )
            else:
                cur.execute(
                    """
                    SELECT p.name AS name, p.color AS color, COUNT(h.id) AS hits
                    FROM hosts h JOIN providers p ON p.id = h.provider_id
                    WHERE h.scan_id = ?
                    GROUP BY p.id ORDER BY hits DESC
                    """,
                    (scan_id,),
                )
            return list(cur.fetchall())


def _row_to_provider(row: sqlite3.Row) -> Provider:
    return Provider(
        id=int(row["id"]),
        slug=row["slug"],
        name=row["name"],
        enabled=bool(row["enabled"]),
        asns=json.loads(row["asns"] or "[]"),
        sources=json.loads(row["sources"] or "[]"),
        color=row["color"] or "#00ff9c",
        last_synced=row["last_synced"],
        notes=row["notes"],
    )
