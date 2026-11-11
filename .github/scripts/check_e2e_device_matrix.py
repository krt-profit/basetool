#!/usr/bin/env python3
"""Keep the E2E device matrix and the sweep's own device classes in agreement.

Compares ``DEVICE_CLASSES`` in ``TouchClassLayoutE2eTest`` (REQ-UI-009) with the full ``device``
matrix axis of ``.github/workflows/e2e.yml``, the one the nightly and manual runs use; a class missing
from it is never measured. The pull-request axis must be a subset of the full one (ADR-0312).

Usage: ``check_e2e_device_matrix.py [--selftest]``. Exit 1 on disagreement.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github" / "workflows" / "e2e.yml"
SWEEP = (
    REPO
    / "frontend"
    / "src"
    / "e2e"
    / "java"
    / "de"
    / "greluc"
    / "krt"
    / "profit"
    / "basetool"
    / "frontend"
    / "e2e"
    / "TouchClassLayoutE2eTest.java"
)

MATRIX = re.compile(
    r"^\s*device:\s*\$\{\{\s*fromJSON\(\s*github\.event_name == 'pull_request'\s*&&\s*"
    r"'(?P<pr>\[[^\]]*\])'\s*\|\|\s*'(?P<full>\[[^\]]*\])'\s*\)\s*\}\}\s*$",
    re.MULTILINE,
)
DEVICE_DECLARATION = re.compile(r"\bDEVICE_CLASSES\s*=\s*List\.of\((?P<entries>[^;]*)\)\s*;")
VIEWPORT = r"new\s+Viewport\(\s*([1-9][0-9]*)\s*,\s*([1-9][0-9]*)\s*\)"
VIEWPORT_LIST = re.compile(rf"\s*{VIEWPORT}(?:\s*,\s*{VIEWPORT})*\s*")


def workflow_axes(text: str) -> tuple[list[str], list[str]]:
    """Return the workflow's pull-request and full ``device`` axes, each in declaration order."""
    match = MATRIX.search(text)
    if not match:
        raise SystemExit(
            "FAIL: no `device:` matrix axis of the form "
            "`${{ fromJSON(github.event_name == 'pull_request' && '[...]' || '[...]') }}` "
            "in .github/workflows/e2e.yml — was the fan-out removed?"
        )
    return json.loads(match.group("pr")), json.loads(match.group("full"))


def workflow_devices(text: str) -> list[str]:
    """Return the workflow's full ``device`` matrix axis, in declaration order."""
    return workflow_axes(text)[1]


def subset_problems(in_pr: list[str], in_full: list[str]) -> list[str]:
    """Return every pull-request device the full axis does not hold; empty means it is a subset."""
    return [
        f"e2e.yml runs {device} on pull requests but not in the nightly matrix, so the "
        f"pull-request axis is no longer a subset of the full one"
        for device in in_pr
        if device not in in_full
    ] + ([] if in_pr else ["e2e.yml's pull-request device axis is empty"])


def sweep_devices(text: str) -> list[str]:
    """Return ``DEVICE_CLASSES`` as WxH strings in declaration order, read from that declaration only."""
    declaration = DEVICE_DECLARATION.search(text)
    if not declaration:
        raise SystemExit("FAIL: no DEVICE_CLASSES List.of declaration in TouchClassLayoutE2eTest.java")
    entries = declaration.group("entries")
    if not VIEWPORT_LIST.fullmatch(entries):
        raise SystemExit("FAIL: DEVICE_CLASSES must contain only literal Viewport(width, height) entries")
    return [f"{width}x{height}" for width, height in re.findall(VIEWPORT, entries)]


def compare(in_workflow: list[str], in_sweep: list[str]) -> list[str]:
    """Return every disagreement as a readable line; empty means the two lists agree."""
    problems = []
    for device in in_sweep:
        if device not in in_workflow:
            problems.append(
                f"device class {device} is measured by TouchClassLayoutE2eTest but no runner in "
                f"e2e.yml passes -Pe2e.device={device}, so it is NEVER measured in CI"
            )
    for device in in_workflow:
        if device not in in_sweep:
            problems.append(
                f"e2e.yml runs a job for {device} but DEVICE_CLASSES has no such class, so that "
                f"runner measures nothing (the sweep reports it, after paying for the whole suite)"
            )
    if in_workflow and in_sweep and in_workflow != in_sweep and not problems:
        problems.append(
            f"the two lists hold the same devices in a different ORDER "
            f"({in_workflow} vs {in_sweep}) — harmless today, but they are meant to be read as "
            f"copies of each other"
        )
    return problems


def selftest() -> int:
    """Check both drift directions, the order mismatch and the agreeing case; return the exit code."""
    cases = [
        (["a", "b"], ["a", "b"], 0),
        (["a"], ["a", "b"], 1),
        (["a", "b"], ["a"], 1),
        (["b", "a"], ["a", "b"], 1),
        (["a"], ["b"], 2),
    ]
    for in_workflow, in_sweep, expected in cases:
        found = compare(in_workflow, in_sweep)
        if len(found) != expected:
            print(f"SELFTEST FAIL: {in_workflow} vs {in_sweep}: expected {expected}, got {found}")
            return 1
    subset_cases = [
        (["a"], ["a", "b"], 0),
        (["a", "c"], ["a", "b"], 1),
        ([], ["a", "b"], 1),
    ]
    for in_pr, in_full, expected in subset_cases:
        found = subset_problems(in_pr, in_full)
        if len(found) != expected:
            print(f"SELFTEST FAIL: subset {in_pr} of {in_full}: expected {expected}, got {found}")
            return 1
    in_pr, in_full = workflow_axes(WORKFLOW.read_text(encoding="utf-8"))
    if not in_pr or not in_full:
        print("SELFTEST FAIL: a workflow device axis parsed to an empty list")
        return 1
    declaration = "DEVICE_CLASSES = List.of(new Viewport(375, 812),\nnew Viewport(1180, 820));"
    sample = "OTHER = new Viewport(100, 100);\n" + declaration + "\nOTHER = new Viewport(200, 200);"
    if sweep_devices(sample) != ["375x812", "1180x820"]:
        print("SELFTEST FAIL: typed viewport declaration parsed incorrectly")
        return 1
    for invalid in (
        "DEVICE_CLASSES = List.of();",
        "DEVICE_CLASSES = List.of(new Viewport(375, 812), desktop());",
        "DEVICE_CLASSES = List.of(new Viewport(375, 812) new Viewport(1180, 820),);",
        "OTHER = List.of(new Viewport(375, 812));",
    ):
        try:
            sweep_devices(invalid)
        except SystemExit:
            continue
        print(f"SELFTEST FAIL: invalid declaration passed: {invalid}")
        return 1
    if compare(in_full, sweep_devices(SWEEP.read_text(encoding="utf-8"))):
        print("SELFTEST FAIL: the checked-in workflow and typed viewport declarations differ")
        return 1
    print("selftest: ok")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()

    in_pr, in_workflow = workflow_axes(WORKFLOW.read_text(encoding="utf-8"))
    in_sweep = sweep_devices(SWEEP.read_text(encoding="utf-8"))
    problems = compare(in_workflow, in_sweep) + subset_problems(in_pr, in_workflow)

    if problems:
        print("FAIL: the E2E device matrix and DEVICE_CLASSES disagree:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(
        f"ok: {len(in_sweep)} device classes, one runner each: {', '.join(in_sweep)}; "
        f"pull requests run {', '.join(in_pr)}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
