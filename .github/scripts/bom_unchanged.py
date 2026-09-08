#!/usr/bin/env python3
"""Exit 0 when two CycloneDX JSON SBOMs are equal ignoring volatile fields.

The cyclonedx-gradle plugin rotates ``serialNumber`` and ``metadata.timestamp``
on every run (``includeBomSerialNumber = true`` in every shipped module's build
script -- backend, frontend, ingest and keycloak-spi, see REQ-OPS-025),
so a regeneration that did *not* actually change the dependency graph still
yields a textual diff. This helper strips exactly those two volatile fields and
compares the rest, letting the release workflow tell a real component change
(worth committing) from pure churn (discard it). That is the deliberate handling
of the serial-number flap called out in issue #416.

Everything else -- the component list, their versions, licenses, the plugin
version under ``metadata.tools``, the project component -- is compared as-is, so
a genuine dependency change (e.g. asciidoctorj disappearing after #414, or a
version bump) is still detected and surfaced as "changed".

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
    """Load a CycloneDX JSON SBOM with the per-run volatile fields stripped.

    Removes the top-level ``serialNumber`` and ``metadata.timestamp`` so two BOMs
    built from the same dependency graph compare equal despite the plugin
    rotating those on every run.

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
        sys.exit(1)  # no readable committed baseline -> treat as changed
    try:
        new = normalised(new_path)
    except (OSError, ValueError):
        sys.exit(1)
    sys.exit(0 if old == new else 1)


if __name__ == "__main__":
    main()
