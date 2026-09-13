#!/usr/bin/env python3
"""Fail when e2e.yml's concurrency selector stops mirroring its job gate.

``e2e.yml`` has to state one decision -- "does this event run the Playwright
suite?" -- in two places, because GitHub evaluates them at different moments and
offers no way to share the expression:

* ``jobs.e2e.if`` decides whether the job runs, and is evaluated *after* the run
  exists;
* ``concurrency.group`` is evaluated when the run is *created*, so it decides
  which runs may cancel each other before any gate has been consulted.

The group therefore has to predict the gate. It does that by embedding the gate
expression verbatim: runs the gate will admit share one ``-suite`` group, where
``cancel-in-progress`` supersedes a stale suite, and every other run is parked in
a group of its own so it can neither cancel nor be cancelled.

That holds only while the two copies agree, and a selector merely *looser* than
the gate silently restores the original bug -- a run that will skip re-enters
``-suite`` and cancels the run that would have executed. #1537 and #1871 both
reached a green board with zero end-to-end coverage that way, each time behind
cancelled runs that ``gh pr checks`` renders as ``fail``. None of that is visible
in a diff, which is why it is asserted here rather than trusted to a comment.

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

# The job whose gate the group must predict, and the literal the group selects
# for the runs that gate admits.
JOB = "e2e"
SUITE_KEY = "'suite'"

# Every run the gate will NOT admit must land in a group nothing else shares.
# `github.run_id` is unique per run and is the only context value available here
# that is; without it the skipped runs share one group again and start cancelling
# each other, which is the half of the bug that paints the PR red.
UNIQUE_KEY = "github.run_id"

# The gate's `labeled` clause is only reachable while `labeled` is a trigger.
# Dropping it is a real option -- a label applied after open would then wait for
# the next push -- but it is a decision to take deliberately (ADR-0169), not a
# line to lose, so the coupling is asserted rather than assumed.
REQUIRED_PR_TYPE = "labeled"


def _normalise(expression: str) -> str:
    """Collapse an expression's whitespace so YAML folding cannot fake a difference.

    ``if:`` and ``group:`` are both folded block scalars broken at different
    widths, so the same expression arrives with different runs of spaces. Only
    the token sequence is meaningful to GitHub.
    """
    return re.sub(r"\s+", " ", expression).strip()


def check(document: str) -> list[str]:
    """Return one message per way the workflow's two copies of the gate disagree.

    Takes the workflow source rather than a path so the self-test can feed it
    documents that are deliberately broken. An empty list means they agree.
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

    # PyYAML resolves the bare `on:` key to the boolean True (YAML 1.1), so the
    # trigger block answers to either spelling depending on how it was written.
    triggers = workflow.get(True) or workflow.get("on") or {}
    types = ((triggers or {}).get("pull_request") or {}).get("types") or []
    if REQUIRED_PR_TYPE not in types:
        problems.append(
            f"pull_request.types no longer contains `{REQUIRED_PR_TYPE}`, so the gate's label "
            "clause is unreachable and a label applied after open never starts the suite."
        )

    return problems


# A minimal workflow shaped like the real one: the self-test mutates this to
# produce each way the two copies can drift apart.
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
    """Prove the detector still tells a mirrored selector from a drifted one.

    Runs before the real check in CI so the gate can never pass vacuously: a
    checker that quietly stopped resolving the workflow would otherwise report
    "no problems" forever, which is the exact failure mode it exists to catch.
    """
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
