#!/usr/bin/env python3
"""Fail when the edge probe expects a refusal on a path the vhost allow-list admits.

The nightly probe and the allow-list runbook describe the same surface from two
sides, and nothing compared them. Five times an admission was written into
``docs/API_VHOST_ROLLOUT_RUNBOOK.md`` while the probe kept its old refusal row,
and every time production was the first to say so -- as a red cron run that reads
like edge drift when it is really a stale table:

* ``POST /api/v1/bank/deposits`` (phase O)
* ``POST /api/v1/hangar/import/fleetview`` (phase X)
* ``POST …/participants/{id}/check-in/slim`` (2026-09-08)
* ``GET /api/v1/refinery-orders/all`` (phase Y, 2026-09-09)
* ``GET /api/v1/materials/{id}`` (REQ-SEC-052 made it authenticated, 2026-09-09)

The rule this asserts is the one all five broke, and it needs no knowledge of
what a path *should* answer:

    a probe row expecting 404 must name a path the allow-list does NOT admit.

404 is the vhost's own refusal. Once a path is admitted the request reaches the
backend, which answers 401/403/405 -- never the edge's 404. So an admitted path
with a 404 row is a contradiction between two files in this repository, provable
here, with no access to production.

The reverse direction is deliberately NOT asserted: a row expecting 401 on an
unadmitted path would be wrong too, but distinguishing "unadmitted" from "this
checker failed to model an nginx regex" is the difference between a guard and a
false blocker, and a false blocker on a nightly gate is worse than the drift.

Exit codes:
  0  -> every 404 row names a path the allow-list refuses.
  1  -> at least one 404 row names an admitted path; each is printed.

Usage:
    check_probe_against_allowlist.py
"""

from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PROBE = REPO / ".github" / "workflows" / "edge-deny-probe.yml"
RUNBOOK = REPO / "docs" / "API_VHOST_ROLLOUT_RUNBOOK.md"

# The probe's stand-in for "an id that cannot exist".
NIL = "00000000-0000-4000-8000-00000000cafe"

# `probe VERB PATH CODE`, where PATH may be quoted and may carry $nil.
PROBE_LINE = re.compile(
    r"""^\s*probe\s+(?P<verb>[A-Z]+)\s+(?P<path>"[^"]+"|'[^']+'|\S+)\s+(?P<code>\d{3})\s*$""",
    re.MULTILINE,
)

# The two shapes the allow-list uses to admit a path.
LITERAL_RULE = re.compile(r'if\s*\(\s*\$uri\s*=\s*"([^"]+)"\s*\)\s*\{\s*set\s+\$krt_api_allowed\s+1;')
REGEX_RULE = re.compile(r'if\s*\(\s*\$uri\s*~\s*"([^"]+)"\s*\)\s*\{\s*set\s+\$krt_api_allowed\s+1;')


def probe_rows() -> list[tuple[str, str, int]]:
    """Read every ``probe`` assertion out of the workflow.

    Parses the workflow text rather than running the shell, so the check needs no
    network, no container and no production access -- the whole point being that
    this contradiction is provable from the repository alone.

    :return: one ``(verb, path, expected_code)`` per assertion, ``$nil`` expanded.
    :raises OSError: if the workflow cannot be read.
    """
    text = PROBE.read_text(encoding="utf-8")
    rows: list[tuple[str, str, int]] = []
    for m in PROBE_LINE.finditer(text):
        path = m.group("path").strip("\"'").replace("$nil", NIL)
        rows.append((m.group("verb"), path, int(m.group("code"))))
    return rows


def admitting_rules() -> tuple[list[str], list[re.Pattern[str]]]:
    """Read the allow-list's admitting rules out of the runbook's config block.

    Only rules that set ``$krt_api_allowed 1`` count: the block also carries
    read-only-family and rate-limit rules that admit nothing.

    :return: the literal paths, and the compiled regexes, that admit a request.
    :raises OSError: if the runbook cannot be read.
    :raises re.error: if a rule's regex is not valid Python ``re`` syntax, which
        means this checker can no longer model the block and must be fixed rather
        than trusted.
    """
    text = RUNBOOK.read_text(encoding="utf-8")
    literals = LITERAL_RULE.findall(text)
    regexes = [re.compile(r) for r in REGEX_RULE.findall(text)]
    return literals, regexes


def main() -> None:
    """Report every 404 row whose path the allow-list admits.

    :raises SystemExit: always -- 0 when the two files agree, 1 with the list.
    """
    literals, regexes = admitting_rules()
    if not literals and not regexes:
        print(
            "error: no `set $krt_api_allowed 1` rule found in "
            f"{RUNBOOK.relative_to(REPO)} — this checker can no longer read the block, "
            "which makes it vacuous rather than green"
        )
        raise SystemExit(1)

    rows = probe_rows()
    if not rows:
        print(f"error: no `probe` assertion found in {PROBE.relative_to(REPO)}")
        raise SystemExit(1)

    problems: list[str] = []
    for verb, path, code in rows:
        if code != 404:
            continue
        if path in literals:
            problems.append(f"{verb} {path}: expected 404, but the allow-list admits it literally")
            continue
        for rx in regexes:
            if rx.search(path):
                problems.append(
                    f"{verb} {path}: expected 404, but the allow-list admits it via /{rx.pattern}/"
                )
                break

    if problems:
        print("The probe expects the edge to refuse paths the allow-list admits:\n")
        for problem in problems:
            print(f"  - {problem}")
        print(
            "\n404 is the vhost's own refusal. An admitted path reaches the backend and answers "
            "401/403/405 instead, so each row above is a contradiction between the probe and the "
            "runbook. Admitting a path DELETES its refusal row; it does not only add an admitted "
            "one."
        )
        raise SystemExit(1)

    refusals = sum(1 for _, _, c in rows if c == 404)
    print(
        f"probe/allow-list agree: {len(rows)} assertions, {refusals} of them refusals, "
        f"checked against {len(literals)} literal and {len(regexes)} regex rules"
    )


if __name__ == "__main__":
    main()
