#!/usr/bin/env python3
"""Fail a pull request that touches the frozen exchange path without the ``e2e`` label.

The exchange relay seam is frozen (D-05, REQ-XCH-036): a change to the backend's exchange
controllers, services or DTOs, to the ingest gateway, to the published contract fixtures or to the
shared seam definition must run the full E2E suite, which ``e2e.yml`` gates on the ``e2e`` label.

Usage:
    check_exchange_e2e_label.py --changed <file listing one path per line> --labels <JSON array>
    check_exchange_e2e_label.py --selftest

Exit codes:
  0  -> no exchange path changed, or the label is present.
  1  -> an exchange path changed and the label is missing; the paths are printed.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

LABEL = "e2e"

BACKEND = "backend/src/main/java/de/greluc/krt/profit/basetool/backend/"

EXCHANGE_PATHS = (
    BACKEND + "exchange/",
    BACKEND + "platform/api/ActingMemberHeader.java",
    BACKEND + "platform/api/ActingMemberFilterProvider.java",
    "ingest/src/main/",
    "docs/exchange/examples/",
    "test-support/src/main/java/de/greluc/krt/profit/basetool/testsupport/exchange/",
)


def exchange_changes(changed: list[str]) -> list[str]:
    """Return the changed paths that lie on the exchange path, in the given order."""
    return [path for path in changed if path.startswith(EXCHANGE_PATHS)]


def check(changed: list[str], labels: list[str]) -> list[str]:
    """Return the exchange paths that demand the label when it is missing; empty means pass."""
    if LABEL in labels:
        return []
    return exchange_changes(changed)


def selftest() -> int:
    """Check the decision against each case that must pass or fail; return the exit code."""
    controller = BACKEND + "exchange/web/ExchangeStockController.java"
    cases: list[tuple[str, list[str], list[str], bool]] = [
        ("unrelated change without the label", ["frontend/src/main/x.java"], [], False),
        ("exchange controller without the label", [controller], ["enhancement"], True),
        ("exchange controller with the label", [controller], ["e2e", "BE"], False),
        ("ingest code without the label", ["ingest/src/main/java/a/B.java"], [], True),
        ("contract fixture without the label", ["docs/exchange/examples/v1/x/valid/a.json"], [], True),
        ("acting-member filter without the label", [BACKEND + "exchange/internal/ActingMemberFilter.java"], [], True),
        ("relay header contract without the label", [BACKEND + "platform/api/ActingMemberHeader.java"], [], True),
        ("another platform type without the label", [BACKEND + "platform/api/ClientAttribution.java"], [], False),
        ("exchange docs prose without the label", ["docs/exchange/quickstart.md"], [], False),
        ("ingest test without the label", ["ingest/src/test/java/a/BTest.java"], [], False),
        ("a label that only contains e2e", [controller], ["e2e-smoke"], True),
    ]
    failures = 0
    for name, changed, labels, expect_failure in cases:
        problems = check(changed, labels)
        ok = bool(problems) == expect_failure
        print(f"  {'ok  ' if ok else 'FAIL'}  {name}")
        if not ok:
            failures += 1
    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main() -> int:
    """Run the self-test or check the pull request's changed files against its labels."""
    if "--selftest" in sys.argv:
        return selftest()
    try:
        changed_file = sys.argv[sys.argv.index("--changed") + 1]
        labels_json = sys.argv[sys.argv.index("--labels") + 1]
    except (ValueError, IndexError):
        print(__doc__)
        return 2
    changed = [
        line.strip()
        for line in Path(changed_file).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    labels = json.loads(labels_json)
    if not isinstance(labels, list) or not all(isinstance(label, str) for label in labels):
        print("FAIL: --labels must be a JSON array of label names.")
        return 2
    problems = check(changed, labels)
    if problems:
        print(
            f"This pull request changes the frozen exchange path but carries no `{LABEL}` label,"
            " so the E2E suite does not run on it (D-05, REQ-XCH-036). Add the label. Paths:"
        )
        for path in problems:
            print(f"  - {path}")
        return 1
    touched = len(exchange_changes(changed))
    print(
        f"exchange-label: {touched} exchange path(s) changed"
        + (f"; the `{LABEL}` label is present." if touched else ".")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
