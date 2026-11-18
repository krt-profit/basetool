#!/usr/bin/env python3
"""Fail when e2e.yml's job gates stop mirroring its concurrency selector.

``concurrency.group`` holds the gate once, selects ``'suite'`` for admitted runs and falls back to
``github.run_id`` otherwise; every gated job (``e2e``, ``build-stack``, ``build-stack-main``) must
carry that gate verbatim as one ``&&`` conjunct of its ``if``, and ``pull_request.types`` must
contain ``labeled`` (ADR-0169, ADR-0317).

Exit codes:
  0  -> every gated job still mirrors the selector.
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

GATED_JOBS = ("e2e", "build-stack", "build-stack-main")
SUITE_KEY = "'suite'"

UNIQUE_KEY = "github.run_id"

REQUIRED_PR_TYPE = "labeled"

SELECTOR = re.compile(r"\$\{\{(?P<gate>(?:(?!\$\{\{).)*?)&&\s*'suite'", re.DOTALL)


def _normalise(expression: str) -> str:
    """Collapse an expression's whitespace so YAML folding cannot fake a difference."""
    return re.sub(r"\s+", " ", expression).strip()


def conjuncts(expression: str) -> list[str]:
    """Split an expression at its top-level ``&&``, outside parentheses and string literals."""
    parts: list[str] = []
    depth = 0
    quoted = False
    start = 0
    i = 0
    while i < len(expression):
        char = expression[i]
        if char == "'":
            quoted = not quoted
        elif not quoted and char == "(":
            depth += 1
        elif not quoted and char == ")":
            depth -= 1
        elif not quoted and depth == 0 and expression.startswith("&&", i):
            parts.append(expression[start:i])
            start = i + 2
            i += 1
        i += 1
    parts.append(expression[start:])
    return [_normalise(part) for part in parts]


def check(document: str) -> list[str]:
    """Return one message per way the workflow's copies of the gate disagree.

    Takes the workflow source text; an empty list means they agree.
    """
    problems: list[str] = []
    workflow = yaml.safe_load(document) or {}

    concurrency = workflow.get("concurrency") or {}
    group = concurrency.get("group")
    if not group:
        return ["concurrency.group is missing -- every run would share one group."]

    group_n = _normalise(group)
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

    match = SELECTOR.search(group)
    gate = _normalise(match.group("gate")) if match else ""
    if not gate:
        problems.append(
            "concurrency.group holds no `${{ <gate> && 'suite' || ... }}` selector, so there is "
            "no gate the jobs could mirror."
        )

    jobs = workflow.get("jobs") or {}
    for name in GATED_JOBS:
        condition = (jobs.get(name) or {}).get("if")
        if not condition:
            problems.append(f"jobs.{name}.if is missing -- the job would run on every event.")
            continue
        if gate and gate not in conjuncts(condition):
            problems.append(
                f"jobs.{name}.if no longer carries the selector's gate as one `&&` conjunct, so\n"
                f"  a run that will skip can cancel the run that would have executed.\n"
                f"  gate : {gate}\n"
                f"  if   : {_normalise(condition)}"
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
  build-stack:
    if: >-
      (github.event_name != 'pull_request' ||
      (github.event.action == 'labeled' && github.event.label.name == 'e2e')) &&
      !(github.event_name != 'pull_request' && github.ref == 'refs/heads/main')
  build-stack-main:
    if: >-
      (github.event_name != 'pull_request' ||
      (github.event.action == 'labeled' && github.event.label.name == 'e2e')) &&
      (github.event_name != 'pull_request' && github.ref == 'refs/heads/main')
  e2e:
    if: >-
      !cancelled() &&
      (github.event_name != 'pull_request' ||
      (github.event.action == 'labeled' && github.event.label.name == 'e2e')) &&
      (needs.build-stack.result == 'success' || needs.build-stack-main.result == 'success')
"""

_GOOD_E2E_GATE = (
    "      !cancelled() &&\n"
    "      (github.event_name != 'pull_request' ||\n"
    "      (github.event.action == 'labeled' && github.event.label.name == 'e2e')) &&"
)

_GOOD_GROUP = (
    "e2e-${{ github.ref }}-${{ (github.event_name != 'pull_request' ||\n"
    "    (github.event.action == 'labeled' && github.event.label.name == 'e2e'))\n"
    "    && 'suite' || format('noop-{0}', github.run_id) }}"
)


def selftest() -> int:
    """Check that the detector tells a mirrored workflow from each drifted variant; return the exit code."""
    cases: list[tuple[str, str, bool]] = [
        ("mirrored gates", _GOOD, False),
        (
            "e2e gate widened, group left behind",
            _GOOD.replace(
                _GOOD_E2E_GATE,
                "      !cancelled() &&\n"
                "      (github.event_name != 'pull_request' || github.event.action == 'labeled') &&",
            ),
            True,
        ),
        (
            "build job gate OR-ed open",
            _GOOD.replace(
                "      (github.event.action == 'labeled' && github.event.label.name == 'e2e')) &&\n"
                "      !(github.event_name",
                "      (github.event.action == 'labeled' && github.event.label.name == 'e2e')) ||\n"
                "      !(github.event_name",
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
        (
            "cache-writer gate deleted",
            _GOOD.replace("  build-stack-main:\n    if:", "  build-stack-main:\n    x:"),
            True,
        ),
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
        print(f"{relative}: the concurrency selector and the job gates disagree.\n")
        for problem in problems:
            print(f"- {problem}")
        print(
            "\nAll must state the same decision; see ADR-0169, ADR-0317 and REQ-OPS-027. If the\n"
            "gate changed on purpose, copy it into the concurrency.group selector and into every\n"
            "gated job's if verbatim."
        )
        return 1

    print("e2e.yml: every gated job mirrors the concurrency selector.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
