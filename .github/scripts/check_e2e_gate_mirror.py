#!/usr/bin/env python3
"""Fail when e2e.yml's concurrency selector stops mirroring its job gate.

``concurrency.group`` must embed ``jobs.e2e.if`` verbatim, select ``'suite'`` for
admitted runs and fall back to ``github.run_id`` otherwise, and ``pull_request.types``
must contain ``labeled`` (ADR-0169).

Exit codes:
  0  -> the selector still mirrors the gate.
  1  -> they have drifted; each problem is printed with what must change.

Usage:
    check_e2e_gate_mirror.py [--selftest]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github" / "workflows" / "e2e.yml"

JOB = "e2e"
SUITE_KEY = "'suite'"

UNIQUE_KEY = "github.run_id"

REQUIRED_PR_TYPE = "labeled"


def _normalise(expression: str) -> str:
    """Collapse an expression's whitespace so YAML folding cannot fake a difference."""
    return re.sub(r"\s+", " ", expression).strip()


def check(document: str) -> list[str]:
    """Return one message per way the workflow's two copies of the gate disagree.

    Takes the workflow source text; an empty list means they agree.
    """
    problems: list[str] = []
    workflow = yaml.safe_load(document) or {}

    concurrency = workflow.get("concurrency") or {}
    group = concurrency.get("group")
    job = (workflow.get("jobs") or {}).get(JOB) or {}
    gate = job.get("if")

    if not group:
        return ["concurrency.group is missing -- every run would share one group."]
    if not gate:
        return [f"jobs.{JOB}.if is missing -- the heavy suite would run on every event."]

    group_n, gate_n = _normalise(group), _normalise(gate)

    if gate_n not in group_n:
        problems.append(
            f"concurrency.group no longer contains jobs.{JOB}.if verbatim, so a run that will\n"
            f"  skip can cancel the run that would have executed.\n"
            f"  gate  : {gate_n}\n"
            f"  group : {group_n}"
        )
    if SUITE_KEY not in group_n:
        problems.append(
            f"concurrency.group no longer selects {SUITE_KEY} for the runs the gate admits; "
            "they must share one group for cancel-in-progress to supersede a stale suite."
        )
    if UNIQUE_KEY not in group_n:
        problems.append(
            f"concurrency.group no longer falls back to {UNIQUE_KEY}, so the runs that skip "
            "share a group again and cancel each other -- which reads as `fail` on the PR."
        )

    triggers = workflow.get(True) or workflow.get("on") or {}
    types = ((triggers or {}).get("pull_request") or {}).get("types") or []
    if REQUIRED_PR_TYPE not in types:
        problems.append(
            f"pull_request.types no longer contains `{REQUIRED_PR_TYPE}`, so the gate's label "
            "clause is unreachable and a label applied after open never starts the suite."
        )

    return problems


_GOOD = """
on:
  pull_request:
    types: [opened, synchronize, reopened, labeled]
concurrency:
  group: >-
    e2e-${{ github.ref }}-${{ (github.event_name != 'pull_request' ||
    (github.event.action == 'labeled' && github.event.label.name == 'e2e'))
    && 'suite' || format('noop-{0}', github.run_id) }}
  cancel-in-progress: true
jobs:
  e2e:
    if: >-
      github.event_name != 'pull_request' ||
      (github.event.action == 'labeled' && github.event.label.name == 'e2e')
"""

_GOOD_GATE = (
    "      github.event_name != 'pull_request' ||\n"
    "      (github.event.action == 'labeled' && github.event.label.name == 'e2e')"
)

_GOOD_GROUP = (
    "e2e-${{ github.ref }}-${{ (github.event_name != 'pull_request' ||\n"
    "    (github.event.action == 'labeled' && github.event.label.name == 'e2e'))\n"
    "    && 'suite' || format('noop-{0}', github.run_id) }}"
)


def selftest() -> int:
    """Check that the detector tells a mirrored selector from each drifted variant; return the exit code."""
    cases: list[tuple[str, str, bool]] = [
        ("mirrored selector", _GOOD, False),
        (
            "gate widened, group left behind",
            _GOOD.replace(
                _GOOD_GATE,
                "      github.event_name != 'pull_request' || github.event.action == 'labeled'",
            ),
            True,
        ),
        (
            "group loosened back to one shared key",
            _GOOD.replace(_GOOD_GROUP, "e2e-${{ github.ref }}"),
            True,
        ),
        (
            "skipped runs share one group again",
            _GOOD.replace("format('noop-{0}', github.run_id)", "'noop'"),
            True,
        ),
        (
            "`labeled` dropped from the trigger",
            _GOOD.replace("[opened, synchronize, reopened, labeled]", "[opened, synchronize]"),
            True,
        ),
        ("gate deleted", _GOOD.replace("    if: >-", "    unrelated-key: >-"), True),
    ]

    failures = 0
    for name, document, expect_problems in cases:
        problems = check(document)
        ok = bool(problems) == expect_problems
        print(f"  {'ok  ' if ok else 'FAIL'}  {name}")
        if not ok:
            failures += 1
            print(f"        expected problems={expect_problems}, got {problems}")

    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main() -> int:
    """Run the self-test or the real check, reporting each disagreement found."""
    if "--selftest" in sys.argv:
        return selftest()

    problems = check(WORKFLOW.read_text(encoding="utf-8"))
    if problems:
        relative = WORKFLOW.relative_to(REPO).as_posix()
        print(f"{relative}: the concurrency selector and the job gate disagree.\n")
        for problem in problems:
            print(f"- {problem}")
        print(
            "\nBoth must state the same decision; see ADR-0169 and REQ-OPS-027. If the gate\n"
            "changed on purpose, copy it into the concurrency.group selector verbatim."
        )
        return 1

    print("e2e.yml: the concurrency selector mirrors the job gate.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
