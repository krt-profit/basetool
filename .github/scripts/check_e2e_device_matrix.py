#!/usr/bin/env python3
"""Keep the E2E device matrix and the sweep's own device classes in agreement.

``TouchClassLayoutE2eTest`` declares the five device classes REQ-UI-009 is measured at, and
``.github/workflows/e2e.yml`` fans them across runners as ``browser x device``. The two lists are
verbatim copies of each other with nothing comparing them, and the drift is **one-directional**:

* a device in the workflow that the test does not know fails loudly — the sweep reports
  ``e2e.device=... matched none of N classes`` and the job goes red;
* a device class added to the test and not to the workflow is **silent**. No job passes
  ``-Pe2e.device`` for it, so it is simply never measured, while the test's own coverage assertion
  is satisfied by the one class its runner did get.

That second case is what this check exists for. It is the same failure shape as the seventeen
routes missing from ``PAGES`` while its Javadoc claimed completeness: a list that reads as
authoritative and is short.

Run it by hand with ``python3 .github/scripts/check_e2e_device_matrix.py``, and its self-test with
``--selftest``.
"""

from __future__ import annotations

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

# `device: ['375x812', '810x1080', ...]` — the matrix axis, single-quoted WxH entries.
MATRIX = re.compile(r"^\s*device:\s*\[(?P<items>[^\]]*)\]", re.MULTILINE)
# `new int[] {375, 812},` — one DEVICE_CLASSES entry.
CLASSES = re.compile(r"new int\[\]\s*\{\s*(?P<w>\d+)\s*,\s*(?P<h>\d+)\s*\}")


def workflow_devices(text: str) -> list[str]:
    """The matrix axis, in declaration order."""
    match = MATRIX.search(text)
    if not match:
        raise SystemExit(
            "FAIL: no `device:` matrix axis in .github/workflows/e2e.yml — was the fan-out removed?"
        )
    return re.findall(r"'([^']+)'", match.group("items"))


def sweep_devices(text: str) -> list[str]:
    """DEVICE_CLASSES, as WxH strings in declaration order.

    Anchored to the declaration so an ``int[]`` elsewhere in the file cannot contribute.
    """
    start = text.find("DEVICE_CLASSES =")
    if start == -1:
        raise SystemExit("FAIL: no DEVICE_CLASSES declaration in TouchClassLayoutE2eTest.java")
    end = text.find(";", start)
    return [f"{w}x{h}" for w, h in CLASSES.findall(text[start:end])]


def compare(in_workflow: list[str], in_sweep: list[str]) -> list[str]:
    """Every problem found, as operator-readable lines. Empty means the two agree."""
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
    """Both drift directions, and the agreeing case."""
    cases = [
        (["a", "b"], ["a", "b"], 0),
        (["a"], ["a", "b"], 1),  # the silent direction
        (["a", "b"], ["a"], 1),  # the loud one
        (["b", "a"], ["a", "b"], 1),  # order
        (["a"], ["b"], 2),
    ]
    for in_workflow, in_sweep, expected in cases:
        found = compare(in_workflow, in_sweep)
        if len(found) != expected:
            print(f"SELFTEST FAIL: {in_workflow} vs {in_sweep}: expected {expected}, got {found}")
            return 1
    # And the real files must parse to something non-empty, or the regexes have rotted.
    if not workflow_devices(WORKFLOW.read_text(encoding="utf-8")):
        print("SELFTEST FAIL: the workflow matrix parsed to an empty list")
        return 1
    if not sweep_devices(SWEEP.read_text(encoding="utf-8")):
        print("SELFTEST FAIL: DEVICE_CLASSES parsed to an empty list")
        return 1
    print("selftest: ok")
    return 0


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()

    in_workflow = workflow_devices(WORKFLOW.read_text(encoding="utf-8"))
    in_sweep = sweep_devices(SWEEP.read_text(encoding="utf-8"))
    problems = compare(in_workflow, in_sweep)

    if problems:
        print("FAIL: the E2E device matrix and DEVICE_CLASSES disagree:")
        for problem in problems:
            print(f"  - {problem}")
        return 1

    print(f"ok: {len(in_sweep)} device classes, one runner each: {', '.join(in_sweep)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
