"""Console-script entrypoint mirroring ``main.py``."""

from __future__ import annotations

import sys
from pathlib import Path


def main() -> int:
    here = Path(__file__).resolve().parent.parent
    if str(here) not in sys.path:
        sys.path.insert(0, str(here))
    from main import main as _main  # noqa: WPS433

    return _main()


if __name__ == "__main__":
    sys.exit(main())
