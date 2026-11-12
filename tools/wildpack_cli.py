#!/usr/bin/env python3
"""Entry point for the ZooApp / Adrienne Zoo historical pack CLI."""

from __future__ import annotations

import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from wildpack_pipeline.cli import main  # noqa: E402


if __name__ == "__main__":
    raise SystemExit(main())
