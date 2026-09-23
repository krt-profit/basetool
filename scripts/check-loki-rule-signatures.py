#!/usr/bin/env python3
"""Assert that the Loki alerts keyed on a JVM's own wording still match that wording.

Two alerts in ``monitoring/loki/rules/fake/basetool-log-alerts.yml`` fire on lines that HotSpot
writes outside logback, into the ``<svc>-stdout`` streams: ``JvmNativeThreadExhaustion`` and
``JvmStartupCacheRejected``. Their line filters were written against lines reproduced on the pinned
Temurin image, and those lines are recorded verbatim below. ``scripts/check-loki-rules.sh`` proves
the rules PARSE; nothing proved they MATCH -- a filter edited into a regex that is valid LogQL and
matches nothing keeps deploying and can never fire again, which is the dead-alert shape REQ-OBS-014
exists for.

So each alert names lines it must fire on and lines it must stay silent on. The pipeline's line
filters (``|~``, ``|=``, ``!~``, ``!=``) are applied in order, as Loki applies them. Python's ``re``
stands in for RE2: for the alternations and literals used here the two agree, and the self-test
covers the one construct that could differ (a case-insensitive flag).

When a Temurin bump changes the wording, re-record the lines here from the new image (the procedure
is in monitoring/README.md, "After a Temurin bump") -- never edit the expectation to fit the filter.

Usage:
    check-loki-rule-signatures.py            # check the committed rules
    check-loki-rule-signatures.py --selftest
Exit: 0 = every recorded line is matched as expected; 1 = a mismatch or a missing alert.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[1]
RULES = REPO / "monitoring" / "loki" / "rules" / "fake" / "basetool-log-alerts.yml"

# alert -> (lines that must fire it, lines that must not). Verbatim, as the JVM printed them.
SIGNATURES: dict[str, tuple[list[str], list[str]]] = {
    "JvmStartupCacheRejected": (
        [
            # AOT cache, object layout mismatch -- Temurin 25.0.4+7, 2026-09-23.
            "[0.005s][warning][aot] Unable to use AOT cache.",
            "[0.005s][error  ][aot] Loading static archive failed.",
            # AppCDS, the same mismatch -- verified for ADR-0180 on 2026-09-15.
            "[warning][cds] Unable to use shared archive file.",
            "[error  ][cds] Loading dynamic archive failed.",
        ],
        [
            "Picked up JAVA_TOOL_OPTIONS: -XX:+UseContainerSupport -XX:+UseG1GC -XX:+UseCompactObjectHeaders",
            "[AOT] Cache accepted by a start under -XX:AOTMode=on -XX:+UseCompactObjectHeaders",
            "2026-09-23 10:00:00.000  INFO --- [main] d.g.k.p.b.backend.BackendApplication : Started BackendApplication in 7.1 seconds",
        ],
    ),
    "JvmNativeThreadExhaustion": (
        [
            # Temurin 25.0.4+7 under --pids-limit 60, re-verified 2026-09-22 (rule comment).
            '[0.053s][warning][os,thread] Failed to start thread "Unknown thread" - pthread_create failed (EAGAIN) for attributes: stacksize: 1024k, guardsize: 0k, detached.',
            'Exception in thread "main" java.lang.OutOfMemoryError: unable to create native thread: possibly out of memory or process/resource limits reached',
        ],
        [
            # Printed between the two above, never alone -- deliberately not matched.
            '[0.055s][warning][os,thread] Failed to start the native thread for java.lang.Thread "Thread-47"',
        ],
    ),
}

FILTER = re.compile(r'(\|~|\|=|!~|!=)\s*"((?:[^"\\]|\\.)*)"')


def logql_string(raw: str) -> str:
    """Decode a LogQL double-quoted string body (Go escape rules) into its value.

    :param raw: the characters between the quotes.
    :return: the decoded string.
    """
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), raw)


def line_filters(expr: str) -> list[tuple[str, str]]:
    """Extract the line-filter stages of a LogQL expression, in pipeline order.

    Only the part after the stream selector's closing brace and before the range is read.

    :param expr: the rule's ``expr``.
    :return: ``(operator, operand)`` pairs.
    """
    selector_end = expr.index("}")
    range_start = expr.find("[", selector_end)
    pipeline = expr[selector_end + 1 : range_start if range_start != -1 else len(expr)]
    return [(op, logql_string(value)) for op, value in FILTER.findall(pipeline)]


def passes(line: str, filters: list[tuple[str, str]]) -> bool:
    """Whether a log line survives every line filter.

    :param line: the log line.
    :param filters: the output of :func:`line_filters`.
    :return: ``True`` when Loki would keep the line.
    """
    for op, value in filters:
        if op == "|=" and value not in line:
            return False
        if op == "!=" and value in line:
            return False
        if op == "|~" and not re.search(value, line):
            return False
        if op == "!~" and re.search(value, line):
            return False
    return True


def load_exprs(path: Path) -> dict[str, str]:
    """Read every alert's expression from a Loki rule file.

    :param path: the rule file.
    :return: alert name mapped to its ``expr``.
    """
    doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return {
        rule["alert"]: rule["expr"]
        for group in doc.get("groups", [])
        for rule in group.get("rules", [])
        if "alert" in rule
    }


def check(exprs: dict[str, str], signatures: dict[str, tuple[list[str], list[str]]]) -> list[str]:
    """Compare each alert's filters against its recorded lines.

    :param exprs: alert name to expression.
    :param signatures: alert name to (must-fire, must-not-fire) lines.
    :return: human-readable failures; empty when everything matches.
    """
    failures = []
    for alert, (fire, silent) in signatures.items():
        if alert not in exprs:
            failures.append(f"{alert}: no such alert in the rule file")
            continue
        filters = line_filters(exprs[alert])
        if not filters:
            failures.append(f"{alert}: no line filter found in its expression")
            continue
        failures += [f"{alert}: does NOT fire on {line!r}" for line in fire if not passes(line, filters)]
        failures += [f"{alert}: fires on {line!r}, which it must ignore" for line in silent if passes(line, filters)]
    return failures


def selftest() -> int:
    """Prove the matcher discriminates, so the real check cannot pass vacuously.

    :return: 0 when every case holds, 1 otherwise.
    """
    cases = [
        ('sum(count_over_time({app="x"} |~ "a|b" [5m])) > 0', "xa", True),
        ('sum(count_over_time({app="x"} |~ "a|b" [5m])) > 0', "xc", False),
        ('sum(rate({app="x"} |~ "FATAL" != "starting up" [5m]))', "FATAL: starting up", False),
        ('sum(rate({app="x"} |~ "FATAL" != "starting up" [5m]))', "FATAL: auth failed", True),
        ('sum(rate({app="x"} |~ "(?i)\\\\bERROR\\\\b" [5m]))', "an error here", True),
        ('sum(rate({app="x"} |~ "(?i)\\\\bERROR\\\\b" [5m]))', "terrors", False),
    ]
    failures = 0
    for expr, line, expected in cases:
        got = passes(line, line_filters(expr))
        ok = got == expected
        failures += not ok
        print(f"  [{'ok ' if ok else 'FAIL'}] {line!r} -> {got}")
    # A filter that matches nothing must be reported, and a missing alert too.
    broken = {"JvmStartupCacheRejected": 'sum(count_over_time({app="x"} |~ "nothing like it" [5m]))'}
    if not check(broken, {k: v for k, v in SIGNATURES.items() if k == "JvmStartupCacheRejected"}):
        print("  [FAIL] a filter that matches none of the recorded lines was not reported")
        failures += 1
    if not check({}, SIGNATURES):
        print("  [FAIL] a missing alert was not reported")
        failures += 1
    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main() -> int:
    """Run the check or the self-test.

    :return: the process exit code.
    """
    if "--selftest" in sys.argv:
        return selftest()
    failures = check(load_exprs(RULES), SIGNATURES)
    for failure in failures:
        print(f"FAIL {failure}")
    if failures:
        return 1
    print(f"OK: {len(SIGNATURES)} alert(s) fire on their recorded JVM lines and stay silent on the others.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
