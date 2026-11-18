#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Validate the provisioned Grafana dashboards.

Structural checks only: valid UTF-8 JSON, a unique dashboard ``uid`` and a title, unique and
titled panels (collapsed rows included), no two panels on the same grid cells, and only provisioned
datasource uids. Whether a panel's metric has series is not checked.

Usage:
    python scripts/check-grafana-dashboards.py [--dashboards DIR] [--datasources FILE]

Exits non-zero and prints every problem it found; prints a one-line summary when clean.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

DEFAULT_DASHBOARDS = pathlib.Path("monitoring/grafana/dashboards")
DEFAULT_DATASOURCES = pathlib.Path("monitoring/grafana/provisioning/datasources/datasources.yaml")


def provisioned_datasource_uids(path: pathlib.Path) -> set[str]:
    """Read the datasource uids out of the provisioning file's ``uid:`` lines, without a YAML parser."""
    if not path.is_file():
        return set()
    return set(re.findall(r"^\s*uid:\s*(\S+)\s*$", path.read_text(encoding="utf-8"), re.MULTILINE))


def walk_panels(panels, path="panels"):
    """Yield ``(path, panel)`` for every panel, descending into collapsed rows."""
    for i, panel in enumerate(panels or []):
        where = f"{path}[{i}]"
        if panel.get("type") == "row":
            yield from walk_panels(panel.get("panels"), where + ".panels")
        else:
            yield where, panel


def overlapping(panels):
    """Yield ``(a, b)`` panel ids for every pair of panels of one grid whose ``gridPos`` intersect.

    The dashboard's own panel list is one grid, rows included; the panels of each collapsed row are
    another, checked on their own.
    """
    placed = []
    for panel in panels or []:
        pos = panel.get("gridPos") or {}
        if all(isinstance(pos.get(k), int) for k in ("x", "y", "w", "h")):
            placed.append(
                (panel.get("id"), pos["x"], pos["y"], pos["x"] + pos["w"], pos["y"] + pos["h"])
            )
        if panel.get("type") == "row" and panel.get("collapsed"):
            yield from overlapping(panel.get("panels"))
    for i, (a, ax0, ay0, ax1, ay1) in enumerate(placed):
        for b, bx0, by0, bx1, by1 in placed[i + 1 :]:
            if ax0 < bx1 and bx0 < ax1 and ay0 < by1 and by0 < ay1:
                yield a, b


def datasource_uids_in(node):
    """Yield every datasource uid referenced anywhere below ``node``, as an object or a bare string."""
    if isinstance(node, dict):
        ds = node.get("datasource")
        if isinstance(ds, dict) and isinstance(ds.get("uid"), str):
            yield ds["uid"]
        elif isinstance(ds, str):
            yield ds
        for value in node.values():
            yield from datasource_uids_in(value)
    elif isinstance(node, list):
        for value in node:
            yield from datasource_uids_in(value)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dashboards", type=pathlib.Path, default=DEFAULT_DASHBOARDS)
    parser.add_argument("--datasources", type=pathlib.Path, default=DEFAULT_DATASOURCES)
    args = parser.parse_args()

    if not args.dashboards.is_dir():
        print(f"ERROR: no dashboard directory at {args.dashboards}", file=sys.stderr)
        return 2

    known_uids = provisioned_datasource_uids(args.datasources)
    variable_ref = re.compile(r"^\$\{?[A-Za-z_][A-Za-z0-9_]*\}?$")

    problems: list[str] = []
    seen_uids: dict[str, str] = {}
    files = sorted(args.dashboards.glob("*.json"))
    if not files:
        print(f"ERROR: {args.dashboards} contains no dashboards", file=sys.stderr)
        return 2

    total_panels = 0
    for path in files:
        name = path.name
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            problems.append(
                f"{name}: not valid UTF-8 JSON -- Grafana will skip it silently ({exc})"
            )
            continue

        uid, title = doc.get("uid"), doc.get("title")
        if not uid:
            problems.append(f"{name}: no uid -- its URL is then unstable across re-provisions")
        elif uid in seen_uids:
            problems.append(
                f"{name}: uid {uid!r} is already used by {seen_uids[uid]} -- provisioning is "
                "last-writer-wins, so one dashboard silently replaces the other"
            )
        else:
            seen_uids[uid] = name
        if not title:
            problems.append(f"{name}: no title")

        panel_ids: dict[int, str] = {}
        for where, panel in walk_panels(doc.get("panels")):
            total_panels += 1
            pid = panel.get("id")
            if pid is None:
                problems.append(f"{name}: {where} has no id")
            elif pid in panel_ids:
                problems.append(
                    f"{name}: panel id {pid} used twice ({panel_ids[pid]} and {where}) -- panel "
                    "deep links resolve to whichever comes first"
                )
            else:
                panel_ids[pid] = where
            if not (panel.get("title") or "").strip():
                problems.append(f"{name}: {where} (id {pid}) has no title")

        for a, b in overlapping(doc.get("panels")):
            problems.append(
                f"{name}: panels {a} and {b} overlap on the grid -- Grafana draws one over the other"
            )

        if known_uids:
            for ds_uid in sorted(set(datasource_uids_in(doc))):
                if ds_uid in known_uids or variable_ref.match(ds_uid):
                    continue
                problems.append(
                    f"{name}: references datasource uid {ds_uid!r}, which {args.datasources} does "
                    "not provision -- those panels render 'Datasource not found', which looks "
                    "like 'no data'"
                )

    if problems:
        print(f"Grafana dashboard check FAILED ({len(problems)} problem(s)):\n", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        return 1

    print(
        f"Grafana dashboards OK: {len(files)} dashboards, {total_panels} panels, "
        f"{len(known_uids)} provisioned datasource uid(s)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
