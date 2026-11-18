#!/usr/bin/env python3
"""Exit 0 when two CycloneDX JSON SBOMs are equal ignoring volatile fields.

The volatile fields are ``serialNumber`` and ``metadata.timestamp``; everything else
is compared as-is (REQ-OPS-025).

Exit codes:
  0  -> semantically identical (only serialNumber / timestamp differ) -> churn.
  1  -> a real difference, or OLD is missing / empty / unparseable -> commit it.

Usage:
    bom_unchanged.py OLD.json NEW.json
"""

from __future__ import annotations

import json
import sys


def normalised(path: str) -> dict:
    """Load a CycloneDX JSON SBOM without ``serialNumber`` and ``metadata.timestamp``.

    :param path: filesystem path to a CycloneDX JSON document.
    :return: the parsed BOM mapping without its volatile fields.
    :raises OSError: if the file cannot be read.
    :raises ValueError: if the file is not valid JSON.
    """
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    data.pop("serialNumber", None)
    meta = data.get("metadata")
    if isinstance(meta, dict):
        meta.pop("timestamp", None)
    return data


def main() -> None:
    """Compare two SBOMs and exit 0 (churn) or 1 (real change / missing baseline)."""
    if len(sys.argv) != 3:
        sys.exit("usage: bom_unchanged.py OLD.json NEW.json")
    old_path, new_path = sys.argv[1], sys.argv[2]
    try:
        old = normalised(old_path)
    except (OSError, ValueError):
        sys.exit(1)
    try:
        new = normalised(new_path)
    except (OSError, ValueError):
        sys.exit(1)
    sys.exit(0 if old == new else 1)


if __name__ == "__main__":
    main()
