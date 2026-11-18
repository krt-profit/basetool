#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Fail when the briefing seed calls an API operation or sends a body field the backend lacks.

``scripts/seed-briefing.py`` builds the testing system's data through ``/api/v1`` (REQ-OPS-050), so
an endpoint or a request field the backend removes breaks it only at the next briefing. This check
reads the seed's calls statically and matches them against the committed OpenAPI document.

* **Rule A.** Every ``api.get/post/put/patch/delete(account, path, ...)`` and
  ``api.call(account, METHOD, path, ...)`` whose path is a literal or an f-string starting with
  ``/`` names an operation of ``openapi.json`` with that method; f-string parts match any path
  segment, a query string is ignored. A path built at run time is skipped and counted.
* **Rule B.** Every literal key of a dict body is a property of the operation's request schema.
* **Rule C.** At least ``MIN_CHECKED_CALLS`` calls are checked, so a parser that stops finding the
  calls cannot pass.

Usage::

    python3 scripts/check-seed-api-contract.py            # check the repository
    python3 scripts/check-seed-api-contract.py --selftest # prove every rule can fail
"""

from __future__ import annotations

import ast
import json
import pathlib
import re
import sys
from collections.abc import Iterator

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEED = ROOT / "scripts" / "seed-briefing.py"
OPENAPI = ROOT / "backend" / "src" / "main" / "resources" / "api" / "openapi.json"
API_PREFIX = "/api/v1"
METHODS = {"get": "GET", "post": "POST", "put": "PUT", "patch": "PATCH", "delete": "DELETE"}
# The seed holds about a hundred literal calls; far fewer means the call shape changed.
MIN_CHECKED_CALLS = 60
PLACEHOLDER = "{}"


def _path_text(node: ast.expr) -> str | None:
    """The literal path with each f-string part as ``{}``, or ``None`` for a run-time path."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        text = node.value
    elif isinstance(node, ast.JoinedStr):
        text = "".join(
            str(part.value) if isinstance(part, ast.Constant) else PLACEHOLDER
            for part in node.values
        )
    else:
        return None
    return text.split("?", 1)[0] if text.startswith("/") else None


def _body_keys(node: ast.expr | None) -> list[str]:
    """The literal top-level keys of a dict body; ``**`` entries and other bodies give none."""
    if not isinstance(node, ast.Dict):
        return []
    return [k.value for k in node.keys if isinstance(k, ast.Constant) and isinstance(k.value, str)]


def _is_api(node: ast.expr) -> bool:
    """Whether `node` is ``api`` or ``<anything>.api``."""
    return (isinstance(node, ast.Name) and node.id == "api") or (
        isinstance(node, ast.Attribute) and node.attr == "api"
    )


def seed_calls(source: str) -> Iterator[tuple[int, str, str | None, list[str]]]:
    """Yield ``(line, method, path or None, body keys)`` for every API call of the seed source."""
    for node in ast.walk(ast.parse(source)):
        if not (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)):
            continue
        if not _is_api(node.func.value):
            continue
        args = node.args
        if node.func.attr in METHODS and len(args) >= 2:
            body = args[2] if len(args) > 2 else None
            yield node.lineno, METHODS[node.func.attr], _path_text(args[1]), _body_keys(body)
        elif (
            node.func.attr == "call"
            and len(args) >= 3
            and isinstance(args[1], ast.Constant)
            and isinstance(args[1].value, str)
        ):
            body = args[3] if len(args) > 3 else None
            yield node.lineno, args[1].value, _path_text(args[2]), _body_keys(body)


def _schema_properties(schema: dict, spec: dict) -> set[str]:
    """The property names of a request schema, following ``$ref`` and ``allOf``."""
    while "$ref" in schema:
        schema = spec["components"]["schemas"][schema["$ref"].rsplit("/", 1)[-1]]
    names = set(schema.get("properties", {}))
    for part in schema.get("allOf", []):
        names |= _schema_properties(part, spec)
    return names


def _route(path: str) -> re.Pattern[str]:
    """A matcher for an OpenAPI path whose ``{param}`` segments accept any single segment."""
    parts = re.split(r"(\{[^/]+\})", path)
    return re.compile(
        "^" + "".join("[^/]+" if p.startswith("{") else re.escape(p) for p in parts) + "$"
    )


def check(source: str, spec: dict) -> tuple[list[str], int, int]:
    """Return ``(problems, checked, skipped)`` for the seed source against the OpenAPI spec."""
    routes = [(_route(path), ops) for path, ops in spec["paths"].items()]
    problems: list[str] = []
    checked = skipped = 0
    for line, method, path, keys in seed_calls(source):
        if path is None:
            skipped += 1
            continue
        checked += 1
        concrete = API_PREFIX + path.replace(PLACEHOLDER, "x")
        operation = next(
            (
                ops[method.lower()]
                for matcher, ops in routes
                if matcher.match(concrete) and method.lower() in ops
            ),
            None,
        )
        if operation is None:
            problems.append(f"line {line}: {method} {API_PREFIX}{path} is no operation of the API")
            continue
        if not keys:
            continue
        content = operation.get("requestBody", {}).get("content", {})
        schema = next(iter(content.values()), {}).get("schema") if content else None
        if schema is None:
            problems.append(f"line {line}: {method} {API_PREFIX}{path} takes no request body")
            continue
        unknown = sorted(set(keys) - _schema_properties(schema, spec))
        if unknown:
            problems.append(
                f"line {line}: {method} {API_PREFIX}{path} sends unknown field(s) {unknown}"
            )
    if checked < MIN_CHECKED_CALLS:
        problems.append(
            f"only {checked} API call(s) found, expected at least {MIN_CHECKED_CALLS}: the seed's "
            f"call shape changed and this check no longer sees it"
        )
    return problems, checked, skipped


def run_check() -> int:
    """Check the repository's seed against its committed OpenAPI document."""
    problems, checked, skipped = check(
        SEED.read_text(encoding="utf-8"), json.loads(OPENAPI.read_text(encoding="utf-8"))
    )
    for problem in problems:
        print(f"seed-api-contract: {problem}", file=sys.stderr)
    print(f"seed-api-contract: {checked} call(s) checked, {skipped} run-time path(s) skipped")
    return 1 if problems else 0


def selftest() -> int:
    """Prove that each rule reports its violation and that a clean seed passes."""
    spec = {
        "paths": {
            "/api/v1/units/{id}": {
                "patch": {
                    "requestBody": {
                        "content": {"application/json": {"schema": {"$ref": "#/c/Unit"}}}
                    }
                }
            },
            "/api/v1/units": {"get": {}},
        },
        "components": {"schemas": {"Unit": {"properties": {"name": {}, "version": {}}}}},
    }
    padding = "\n".join(f'api.get(a, "/units?page={i}")' for i in range(MIN_CHECKED_CALLS))
    cases = {
        "clean": ('self.api.patch(a, f"/units/{u}", {"name": "x", **extra})', 0),
        "missing operation": ('self.api.patch(a, f"/units/{u}/flag", {"name": "x"})', 1),
        "unknown field": ('api.call(a, "PATCH", f"/units/{u}", {"name": "x", "gone": 1})', 1),
        "no body operation": ('self.api.get(a, "/units", {"name": "x"})', 1),
    }
    failures = 0
    for label, (line, expected) in cases.items():
        problems, _, _ = check(f"{padding}\n{line}\n", spec)
        ok = len(problems) == expected
        failures += 0 if ok else 1
        print(f"  {'ok  ' if ok else 'FAIL'} {label}: {problems or 'no problem'}")
    problems, _, skipped = check('api.get(a, f"{path}?page=1")\n', spec)
    floor_ok = skipped == 1 and any("expected at least" in p for p in problems)
    failures += 0 if floor_ok else 1
    print(f"  {'ok  ' if floor_ok else 'FAIL'} a run-time path is skipped and the floor holds")
    parse_ok = len(list(seed_calls(padding))) == MIN_CHECKED_CALLS
    failures += 0 if parse_ok else 1
    print(f"  {'ok  ' if parse_ok else 'FAIL'} every padding call is found")
    print(f"\n{'FAILED' if failures else 'OK'}: {failures} failure(s)")
    return 1 if failures else 0


def main() -> int:
    """Run the check, or the self-test with ``--selftest``."""
    return selftest() if sys.argv[1:] == ["--selftest"] else run_check()


if __name__ == "__main__":
    sys.exit(main())
