#!/usr/bin/env python3
"""Remember which month the last bank update covered, so the next run needs no argument.

The marker lives in the shared git directory, so every worktree sees the same one.

Usage:
    python .claude/skills/bank-update/scripts/bank_update_state.py --show
    python .claude/skills/bank-update/scripts/bank_update_state.py --set 2026-08
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import date
from pathlib import Path

FILENAME = ".bank-update-state.json"


def marker_path() -> Path:
    """Return the marker's location, preferring the shared git directory."""
    try:
        common = subprocess.run(
            ["git", "rev-parse", "--git-common-dir"],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        if common:
            return (Path(common).resolve() / FILENAME)
    except Exception:  # noqa: BLE001 - outside a repo the fallback below is correct
        pass
    return Path.cwd() / FILENAME


def parse_month(text: str) -> tuple[int, int]:
    """Parse a ``YYYY-MM`` string into (year, month), rejecting anything else."""
    try:
        year, month = (int(part) for part in text.split("-", 1))
        date(year, month, 1)
    except Exception:  # noqa: BLE001
        sys.exit(f"Month must be YYYY-MM, got {text!r}")
    return year, month


def main() -> None:
    """Show or advance the marker; it only ever moves forward."""
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--show", action="store_true", help="print the last covered month")
    group.add_argument("--set", dest="value", metavar="YYYY-MM",
                       help="record this month as covered")
    parser.add_argument("--force", action="store_true",
                        help="allow moving the marker backwards")
    args = parser.parse_args()

    path = marker_path()
    stored = None
    if path.exists():
        try:
            stored = json.loads(path.read_text(encoding="utf-8")).get("last_month")
        except Exception:  # noqa: BLE001 - a corrupt marker is treated as absent
            stored = None

    if args.show:
        if stored:
            year, month = parse_month(stored)
            nxt = (year + 1, 1) if month == 12 else (year, month + 1)
            print(f"last_month: {stored}")
            print(f"next_month: {nxt[0]:04d}-{nxt[1]:02d}")
        else:
            print("last_month: (none yet)")
        print(f"marker: {path}")
        return

    new = parse_month(args.value)
    if stored and not args.force and new <= parse_month(stored):
        sys.exit(
            f"Marker already at {stored}; refusing to move it back to {args.value}. "
            "Use --force only after a deliberate correction."
        )

    path.write_text(
        json.dumps({"last_month": f"{new[0]:04d}-{new[1]:02d}"}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Marker set to {new[0]:04d}-{new[1]:02d} ({path})")


if __name__ == "__main__":
    main()
