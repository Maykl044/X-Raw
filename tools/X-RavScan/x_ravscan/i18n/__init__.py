"""Lightweight i18n for X-RavScan.

Translations live in ``locales/<code>.json`` and are looked up by key with
graceful fallback to English. The active language is persisted in the
``app_settings`` SQLite table (key ``ui.language``).

Usage:

    from x_ravscan.i18n import t, set_language, current_language
    set_language("ru")
    title = t("app.title")
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

log = logging.getLogger("X-RavScan.i18n")


# Display order (code, native label) — used by the Settings dropdown.
LANGUAGES: List[Tuple[str, str]] = [
    ("en", "English"),
    ("ru", "Русский"),
    ("tk", "Türkmençe"),
]
SUPPORTED = [code for code, _ in LANGUAGES]
DEFAULT_LANGUAGE = "en"


def _locales_dir() -> Path:
    # ``__file__`` works under PyInstaller because ``collect_data_files`` /
    # the spec datas list both keep this folder next to the source tree.
    return Path(__file__).resolve().parent / "locales"


_cache: Dict[str, Dict[str, str]] = {}
_active: str = DEFAULT_LANGUAGE


def _load(code: str) -> Dict[str, str]:
    if code in _cache:
        return _cache[code]
    path = _locales_dir() / f"{code}.json"
    if not path.exists():
        log.warning("locale %s not found at %s", code, path)
        _cache[code] = {}
        return _cache[code]
    try:
        with path.open(encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, dict):
            data = {}
    except Exception:  # pragma: no cover
        log.exception("failed to load locale %s", code)
        data = {}
    _cache[code] = {k: str(v) for k, v in data.items()}
    return _cache[code]


def set_language(code: str) -> str:
    """Set the active language. Falls back to default if unknown."""
    global _active
    code = (code or "").lower()
    if code not in SUPPORTED:
        code = DEFAULT_LANGUAGE
    _active = code
    _load(code)
    _load(DEFAULT_LANGUAGE)
    return _active


def current_language() -> str:
    return _active


def language_name(code: str) -> str:
    for c, label in LANGUAGES:
        if c == code:
            return label
    return code


def t(key: str, **kwargs: object) -> str:
    """Translate ``key``. Unknown keys fall back to English then raw key."""
    primary = _load(_active)
    if key in primary:
        text = primary[key]
    else:
        text = _load(DEFAULT_LANGUAGE).get(key, key)
    if kwargs:
        try:
            return text.format(**kwargs)
        except (KeyError, IndexError):
            return text
    return text


def available_languages() -> Iterable[Tuple[str, str]]:
    return list(LANGUAGES)
