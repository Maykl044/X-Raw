"""Centralised logging with optional UI hook.

The UI subscribes via :func:`add_ui_handler` to get colour-coded log lines.
All other modules just use ``logging.getLogger(__name__)``.
"""

from __future__ import annotations

import logging
import logging.handlers
from typing import Callable, Optional

from x_ravscan.core.config import APP_NAME, log_path


_initialised = False


def _build_formatter() -> logging.Formatter:
    return logging.Formatter(
        fmt="%(asctime)s [%(levelname)-7s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )


def init_logging(level: int = logging.INFO) -> logging.Logger:
    """Idempotent root-logger setup."""
    global _initialised
    root = logging.getLogger(APP_NAME)
    if _initialised:
        return root

    root.setLevel(level)
    root.propagate = False

    fmt = _build_formatter()

    stream = logging.StreamHandler()
    stream.setFormatter(fmt)
    root.addHandler(stream)

    try:
        file_handler = logging.handlers.RotatingFileHandler(
            log_path(), maxBytes=2 * 1024 * 1024, backupCount=5, encoding="utf-8"
        )
        file_handler.setFormatter(fmt)
        root.addHandler(file_handler)
    except OSError:
        # Logging to file is best-effort; never fail startup over this.
        pass

    # Forward stdlib loggers (asyncio, urllib3...) at WARNING+ into our pipeline.
    logging.getLogger().setLevel(logging.WARNING)
    _initialised = True
    return root


def get_logger(name: str) -> logging.Logger:
    init_logging()
    return logging.getLogger(f"{APP_NAME}.{name}")


# --- UI bridge -------------------------------------------------------------


class UICallbackHandler(logging.Handler):
    """Forwards log records to a UI callback ``(level, message)``."""

    def __init__(self, callback: Callable[[str, str], None]):
        super().__init__()
        self.callback = callback
        self.setFormatter(_build_formatter())

    def emit(self, record: logging.LogRecord) -> None:
        try:
            msg = self.format(record)
            self.callback(record.levelname, msg)
        except Exception:  # pragma: no cover - never break UI
            self.handleError(record)


_ui_handler: Optional[UICallbackHandler] = None


def add_ui_handler(callback: Callable[[str, str], None]) -> None:
    """Install or replace the UI log forwarder."""
    global _ui_handler
    init_logging()
    root = logging.getLogger(APP_NAME)
    if _ui_handler is not None:
        root.removeHandler(_ui_handler)
    _ui_handler = UICallbackHandler(callback)
    root.addHandler(_ui_handler)
