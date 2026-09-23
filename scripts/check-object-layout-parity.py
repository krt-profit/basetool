#!/usr/bin/env python3
"""Assert that the image's startup cache and every runtime JVM agree on the object layout.

REQ-OPS-030 / ADR-0180 set ``-XX:+UseCompactObjectHeaders`` in two places that cannot see each
other: the AOT-cache training run in ``docker/app/Dockerfile`` (the image build, where
``JAVA_TOOL_OPTIONS`` does not exist) and every service's ``JAVA_TOOL_OPTIONS`` in the compose files
and the generated ``quadlet/env.d`` templates. A cache created with one layout is refused by a JVM
started with the other, and the refusal is not a failure: the JVM prints a warning and starts
without the cache (verified 2026-09-23). Until then the invariant rested on two comments naming each
other.

Three guards now cover it, one per moment it can break:
  - this check, before merge: the Dockerfile's ``layout=`` and every ``JAVA_TOOL_OPTIONS`` line
    that names the flag carry the SAME sign, and each application service's line names it at all;
  - the image build: a start under ``-XX:AOTMode=on`` with that ``layout=`` must accept the cache;
  - the runtime: the Loki rule ``JvmStartupCacheRejected`` fires on the JVM's refusal, which is what
    the documented rollback ``IRI_EXTRA_JAVA_OPTS=-XX:-UseCompactObjectHeaders`` produces on purpose.

Usage:
    check-object-layout-parity.py            # check the tree
    check-object-layout-parity.py --selftest
Exit: 0 = consistent; 1 = a mismatch, a missing flag, or an unreadable Dockerfile.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DOCKERFILE = REPO / "docker" / "app" / "Dockerfile"
FLAG = re.compile(r"-XX:([+-])UseCompactObjectHeaders")
LAYOUT = re.compile(r'layout="(-XX:[+-]UseCompactObjectHeaders)"')
TOOL_OPTIONS = re.compile(r"^\s*JAVA_TOOL_OPTIONS\s*[:=]\s*(.*)$", re.MULTILINE)


def image_layout(dockerfile: str) -> str | None:
    """Return the sign (``+``/``-``) the training run's ``layout=`` assignment sets.

    :param dockerfile: the text of ``docker/app/Dockerfile``.
    :return: ``"+"`` or ``"-"``, or ``None`` when no single ``layout=`` assignment is found.
    """
    found = LAYOUT.findall(dockerfile)
    if len(set(found)) != 1:
        return None
    return FLAG.search(found[0]).group(1)


def runtime_lines(files: dict[str, str]) -> list[tuple[str, str]]:
    """Collect every ``JAVA_TOOL_OPTIONS`` assignment, as ``(file, value)``.

    :param files: file name mapped to its text.
    :return: the assignments in file order.
    """
    return [(name, m.group(1)) for name, text in files.items() for m in TOOL_OPTIONS.finditer(text)]


def mismatches(sign: str | None, lines: list[tuple[str, str]], expected_count: int) -> list[str]:
    """Compare the image's layout with each runtime assignment.

    :param sign: the image's sign from :func:`image_layout`.
    :param lines: the runtime assignments from :func:`runtime_lines`.
    :param expected_count: how many assignments must exist at least (one per JVM service and file).
    :return: human-readable problems; empty when consistent.
    """
    if sign is None:
        return ["docker/app/Dockerfile: no single layout=\"-XX:(+|-)UseCompactObjectHeaders\" in the training run"]
    problems = []
    if len(lines) < expected_count:
        problems.append(f"only {len(lines)} JAVA_TOOL_OPTIONS assignment(s) found, expected at least {expected_count}")
    for name, value in lines:
        flags = FLAG.findall(value)
        if not flags:
            problems.append(f"{name}: JAVA_TOOL_OPTIONS does not set UseCompactObjectHeaders: {value.strip()[:90]}")
        elif flags[-1] != sign:
            problems.append(f"{name}: JAVA_TOOL_OPTIONS sets {flags[-1]}UseCompactObjectHeaders, the image cache is trained with {sign}")
    return problems


def selftest() -> int:
    """Prove the check discriminates.

    :return: 0 when every case holds, 1 otherwise.
    """
    failures = 0

    def expect(name: str, cond: bool) -> None:
        nonlocal failures
        print(f"  [{'ok ' if cond else 'FAIL'}] {name}")
        failures += not cond

    df = 'RUN set -eu; \\\n    layout="-XX:+UseCompactObjectHeaders"; \\\n'
    expect("the Dockerfile's layout is read", image_layout(df) == "+")
    expect("a missing layout is reported", image_layout("RUN true") is None)
    ok = runtime_lines({"compose": "    JAVA_TOOL_OPTIONS: -XX:+UseG1GC -XX:+UseCompactObjectHeaders ${X:-}\n"})
    expect("a matching runtime line passes", mismatches("+", ok, 1) == [])
    off = runtime_lines({"compose": "    JAVA_TOOL_OPTIONS: -XX:+UseG1GC -XX:-UseCompactObjectHeaders\n"})
    expect("a flipped runtime line is reported", len(mismatches("+", off, 1)) == 1)
    none = runtime_lines({"env.d": "JAVA_TOOL_OPTIONS=-XX:+UseG1GC -XX:MaxRAMPercentage=50.0\n"})
    expect("a runtime line without the flag is reported", len(mismatches("+", none, 1)) == 1)
    last = runtime_lines({"compose": "  JAVA_TOOL_OPTIONS: -XX:-UseCompactObjectHeaders -XX:+UseCompactObjectHeaders\n"})
    expect("the last setting wins, as in the JVM", mismatches("+", last, 1) == [])
    expect("too few assignments are reported", len(mismatches("+", ok, 3)) == 1)
    # Anti-vacuity: the real tree must yield the image's layout and six assignments (three services,
    # compose and env.d), or this check is looking at nothing.
    real = collect()
    expect("the real Dockerfile yields a layout", image_layout(DOCKERFILE.read_text(encoding="utf-8")) is not None)
    expect("the real tree yields at least six JAVA_TOOL_OPTIONS lines", len(runtime_lines(real)) >= 6)
    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def collect() -> dict[str, str]:
    """Read every file that can carry a runtime ``JAVA_TOOL_OPTIONS``.

    :return: repository-relative name mapped to text.
    """
    paths = sorted(REPO.glob("docker-compose*.yml")) + sorted((REPO / "quadlet" / "env.d").glob("*.env.tmpl"))
    return {str(p.relative_to(REPO)).replace("\\", "/"): p.read_text(encoding="utf-8") for p in paths}


def main() -> int:
    """Run the check or the self-test.

    :return: the process exit code.
    """
    if "--selftest" in sys.argv:
        return selftest()
    sign = image_layout(DOCKERFILE.read_text(encoding="utf-8"))
    lines = runtime_lines(collect())
    problems = mismatches(sign, lines, 6)
    for problem in problems:
        print(f"FAIL {problem}")
    if problems:
        print("REQ-OPS-030: the image's AOT cache and the runtime must use the same object layout; a"
              " mismatch makes every JVM start without the cache (ADR-0180, ADR-0209).")
        return 1
    print(f"OK: the image cache and {len(lines)} JAVA_TOOL_OPTIONS assignment(s) all use {sign}UseCompactObjectHeaders.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
