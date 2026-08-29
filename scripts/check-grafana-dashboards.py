#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
"""Validate the provisioned Grafana dashboards.

WHY THIS GATE EXISTS
--------------------
The 13 dashboards under ``monitoring/grafana/dashboards/`` are provisioned as code with
``allowUiUpdates: false``, and until 2026-08-29 (#1708) **nothing validated them at all** -- not a
Gradle test, not a CI job, not a lint script. That matters more here than for most config, because
every way these files can be wrong is a *silent* failure:

* Invalid JSON -- Grafana logs a provisioning error and serves the stack without that dashboard.
  Nobody watches Grafana's own startup log, so the dashboard is simply gone.
* A duplicate ``uid`` -- provisioning is last-writer-wins, so one of the two dashboards silently
  replaces the other and its URL now shows someone else's panels.
* A datasource ``uid`` that is not provisioned -- every panel on it renders "Datasource not found",
  which reads as "no data" to anyone who is not looking closely.
* A duplicate panel ``id`` inside one dashboard -- panel links, shared URLs and the "view panel"
  deep links resolve to whichever Grafana finds first.

None of these is true today; this gate is what keeps it that way. It is the dashboard half of the
lesson #1707 drew for the alert rules: a monitoring surface that nothing checks drifts silently, and
its silence is indistinguishable from health.

The check is deliberately structural. It does NOT verify that a panel's metric has series -- that
needs production and is a judgement call (an absent counter is good news, an absent gauge is a
defect), which is why it stays a periodic review rather than a gate.

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
    """Read the datasource uids out of the provisioning file.

    Parsed with a regex rather than a YAML library on purpose: this script must run on a bare
    ``python3`` in CI with no pip install step, and the file's ``uid:`` lines are a flat, stable
    shape. A uid that the regex misses shows up as a false failure naming the exact uid, which is a
    far cheaper wrong answer than adding a dependency to a lint gate.
    """
    if not path.is_file():
        return set()
    return set(re.findall(r"^\s*uid:\s*(\S+)\s*$", path.read_text(encoding="utf-8"), re.M))


def walk_panels(panels, path="panels"):
    """Yield every panel, descending into collapsed rows.

    A row's children live in its own ``panels`` array and are the panels most likely to rot,
    precisely because a collapsed row is the one nobody opens.
    """
    for i, panel in enumerate(panels or []):
        where = "%s[%d]" % (path, i)
        if panel.get("type") == "row":
            yield from walk_panels(panel.get("panels"), where + ".panels")
        else:
            yield where, panel


def datasource_uids_in(node):
    """Collect every datasource uid referenced anywhere below ``node``.

    Grafana accepts a datasource as an object, as a bare uid string, or omitted (inheriting the
    default), and it appears on panels, on targets and inside annotations and templating variables.
    Walking the whole subtree is simpler than enumerating those places and cannot miss a new one.
    """
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
        print("ERROR: no dashboard directory at %s" % args.dashboards, file=sys.stderr)
        return 2

    known_uids = provisioned_datasource_uids(args.datasources)
    # A template variable renders as ${name}; those are resolved at view time, not provisioning.
    variable_ref = re.compile(r"^\$\{?[A-Za-z_][A-Za-z0-9_]*\}?$")

    problems: list[str] = []
    seen_uids: dict[str, str] = {}
    files = sorted(args.dashboards.glob("*.json"))
    if not files:
        print("ERROR: %s contains no dashboards" % args.dashboards, file=sys.stderr)
        return 2

    total_panels = 0
    for path in files:
        name = path.name
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            problems.append("%s: not valid UTF-8 JSON -- Grafana will skip it silently (%s)" % (name, exc))
            continue

        uid, title = doc.get("uid"), doc.get("title")
        if not uid:
            problems.append("%s: no uid -- its URL is then unstable across re-provisions" % name)
        elif uid in seen_uids:
            problems.append(
                "%s: uid %r is already used by %s -- provisioning is last-writer-wins, so one "
                "dashboard silently replaces the other" % (name, uid, seen_uids[uid])
            )
        else:
            seen_uids[uid] = name
        if not title:
            problems.append("%s: no title" % name)

        panel_ids: dict[int, str] = {}
        for where, panel in walk_panels(doc.get("panels")):
            total_panels += 1
            pid = panel.get("id")
            if pid is None:
                problems.append("%s: %s has no id" % (name, where))
            elif pid in panel_ids:
                problems.append(
                    "%s: panel id %s used twice (%s and %s) -- panel deep links resolve to "
                    "whichever comes first" % (name, pid, panel_ids[pid], where)
                )
            else:
                panel_ids[pid] = where
            if not (panel.get("title") or "").strip():
                problems.append("%s: %s (id %s) has no title" % (name, where, pid))

        if known_uids:
            for ds_uid in sorted(set(datasource_uids_in(doc))):
                if ds_uid in known_uids or variable_ref.match(ds_uid):
                    continue
                problems.append(
                    "%s: references datasource uid %r, which %s does not provision -- those panels "
                    "render 'Datasource not found', which looks like 'no data'"
                    % (name, ds_uid, args.datasources)
                )

    if problems:
        print("Grafana dashboard check FAILED (%d problem(s)):\n" % len(problems), file=sys.stderr)
        for p in problems:
            print("  - %s" % p, file=sys.stderr)
        return 1

    print(
        "Grafana dashboards OK: %d dashboards, %d panels, %d provisioned datasource uid(s)."
        % (len(files), total_panels, len(known_uids))
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
