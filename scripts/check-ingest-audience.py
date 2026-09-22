#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
"""Fail when the ingest gateway's audience is paired with the backend's audience again.

WHY THIS GATE EXISTS
--------------------
The ingest gateway checks the JWT audience ``basetool-ingest``; the backend checks
``basetool-backend``. They are NOT a copy-paste pair (ADR-0018 amendment 1, REQ-INGEST-011): every
frontend session token carries ``basetool-backend``, so a gateway configured with it admits exactly
the web-session tokens the client-identity gate exists to refuse -- and it does so silently, because
the check still *passes*.

It has gone wrong twice already. A runbook written for the backend's audience gate swept the gateway
along on 2026-08-18 (fixed in 3be2f24b0 in four places), and the fifth place -- the comment on
``expected-audiences`` in ``ingest/src/main/resources/application.yml`` -- kept telling operators to
set the backend's value until 2026-09-22. Both containers read the same key inside
(``APP_SECURITY_JWT_EXPECTED_AUDIENCES``), which is why nobody noticed; a human reading the config
cannot tell the two apart either. This check can.

THE RULES
---------
* **Rule A -- the gateway's Spring config.** In ``ingest/src/main/resources/application*.yml``, the
  value of every ``expected-audiences:`` key and the comment block directly above it must not name
  ``basetool-backend``. The comment is in scope on purpose: the 2026-09 defect *was* a comment.
  ``application.yml`` must carry the key at all, or there is nothing to check (vacuity guard).
* **Rule B -- the gateway's environment.** No tracked file may assign ``basetool-backend`` to
  ``IRI_INGEST_EXPECTED_AUDIENCES`` -- as ``NAME=value`` (.env, runbooks), ``NAME: value`` (YAML)
  or a ``${NAME:-value}`` default (compose). Historical records are exempt: the CHANGELOGs and
  ``docs/archive/`` / ``docs/adr/`` describe what was, not what to do.
* **Rule C -- the rendered ingest environment template.** ``quadlet/env.d/ingest.env.tmpl`` must not
  hand ``APP_SECURITY_JWT_EXPECTED_AUDIENCES`` the backend's audience as a literal or a default.

Usage::

    python3 scripts/check-ingest-audience.py            # check the repository
    python3 scripts/check-ingest-audience.py --selftest # prove every rule can fail
"""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import tempfile

BACKEND_AUDIENCE = "basetool-backend"

INGEST_CONFIG_GLOB = "ingest/src/main/resources/application*.yml"
INGEST_MAIN_CONFIG = "ingest/src/main/resources/application.yml"
INGEST_ENV_TEMPLATE = "quadlet/env.d/ingest.env.tmpl"

# Paths that record history rather than prescribe configuration.
EXEMPT_PREFIXES = ("docs/archive/", "docs/adr/", "CHANGELOG")
# This checker and its selftest necessarily spell out the forbidden pairing.
SELF = "scripts/check-ingest-audience.py"

KEY_LINE = re.compile(r"^(\s*)expected-audiences\s*:(.*)$")
COMMENT_LINE = re.compile(r"^\s*#")
ENV_ASSIGNMENT = re.compile(
    r"IRI_INGEST_EXPECTED_AUDIENCES\s*(?:=|:-|:)\s*[\"'`]?\s*[\w.,-]*" + re.escape(BACKEND_AUDIENCE)
)
TEMPLATE_ASSIGNMENT = re.compile(
    r"APP_SECURITY_JWT_EXPECTED_AUDIENCES\s*=.*" + re.escape(BACKEND_AUDIENCE)
)
TEXT_SUFFIXES = {
    ".md", ".yml", ".yaml", ".env", ".example", ".tmpl", ".properties", ".txt", ".sh", ".py",
    ".java", ".kts", ".json", ".conf", ".container", ".service", ".j2", "",
}


def check_ingest_yaml(name: str, text: str) -> list[str]:
    """Rule A for one ingest Spring config file."""
    problems: list[str] = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = KEY_LINE.match(line)
        if not match:
            continue
        if BACKEND_AUDIENCE in match.group(2):
            problems.append(
                f"{name}:{index + 1}: expected-audiences is set to the backend's audience "
                f"({BACKEND_AUDIENCE}); the gateway's is basetool-ingest"
            )
        above = index - 1
        while above >= 0 and COMMENT_LINE.match(lines[above]):
            if BACKEND_AUDIENCE in lines[above]:
                problems.append(
                    f"{name}:{above + 1}: the comment on expected-audiences names "
                    f"{BACKEND_AUDIENCE}; an operator reading it would configure the backend's "
                    "audience on the gateway (describe it as 'the backend's audience' instead)"
                )
            above -= 1
    return problems


def has_audience_key(text: str) -> bool:
    """Whether a config file carries the expected-audiences key at all."""
    return any(KEY_LINE.match(line) for line in text.splitlines())


def check_env_assignments(name: str, text: str) -> list[str]:
    """Rule B for one tracked file."""
    return [
        f"{name}:{number}: IRI_INGEST_EXPECTED_AUDIENCES is assigned the backend's audience "
        f"({BACKEND_AUDIENCE}); the gateway's is basetool-ingest"
        for number, line in enumerate(text.splitlines(), start=1)
        if ENV_ASSIGNMENT.search(line)
    ]


def check_env_template(name: str, text: str) -> list[str]:
    """Rule C for the rendered ingest environment template."""
    return [
        f"{name}:{number}: APP_SECURITY_JWT_EXPECTED_AUDIENCES carries the backend's audience "
        f"({BACKEND_AUDIENCE}) in the ingest environment"
        for number, line in enumerate(text.splitlines(), start=1)
        if TEMPLATE_ASSIGNMENT.search(line)
    ]


def is_exempt(name: str) -> bool:
    return name == SELF or name.startswith(EXEMPT_PREFIXES)


def is_text_candidate(name: str) -> bool:
    return pathlib.PurePosixPath(name).suffix in TEXT_SUFFIXES


def check(root: pathlib.Path, files: list[str]) -> list[str]:
    """Run every rule over the given repository-relative file list."""
    problems: list[str] = []

    main_config = root / INGEST_MAIN_CONFIG
    if not main_config.is_file() or not has_audience_key(main_config.read_text(encoding="utf-8")):
        problems.append(
            f"{INGEST_MAIN_CONFIG}: no expected-audiences key found -- the gate has nothing to "
            "check; update this script if the key moved"
        )

    for path in sorted(root.glob(INGEST_CONFIG_GLOB)):
        name = path.relative_to(root).as_posix()
        problems.extend(check_ingest_yaml(name, path.read_text(encoding="utf-8")))

    template = root / INGEST_ENV_TEMPLATE
    if template.is_file():
        problems.extend(check_env_template(INGEST_ENV_TEMPLATE, template.read_text(encoding="utf-8")))

    for name in files:
        if is_exempt(name) or not is_text_candidate(name):
            continue
        path = root / name
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        problems.extend(check_env_assignments(name, text))
    return problems


def tracked_files(root: pathlib.Path) -> list[str]:
    output = subprocess.run(
        ["git", "ls-files"], cwd=root, check=True, capture_output=True, text=True
    ).stdout
    return [line for line in output.splitlines() if line]


def selftest() -> int:
    """Build a clean fixture, require it to pass, then break it once per rule."""
    clean_yaml = (
        "app:\n"
        "  security:\n"
        "    jwt:\n"
        "      # The gateway's value is basetool-ingest; NOT the backend's audience.\n"
        "      expected-audiences: ${APP_SECURITY_JWT_EXPECTED_AUDIENCES:}\n"
    )
    cases = {
        "clean": ({}, 0),
        "A: value is the backend audience": (
            {INGEST_MAIN_CONFIG: clean_yaml.replace("${APP_SECURITY_JWT_EXPECTED_AUDIENCES:}",
                                                    "basetool-backend")},
            1,
        ),
        "A: comment prescribes the backend audience (the 2026-09 defect)": (
            {INGEST_MAIN_CONFIG: clean_yaml.replace(
                "      # The gateway's",
                "      # Opt-in. Set to `basetool-backend` ONLY after both tokens carry it.\n"
                "      # The gateway's")},
            1,
        ),
        "A: vacuity -- the key vanished": (
            {INGEST_MAIN_CONFIG: "app:\n  security:\n    jwt: {}\n"},
            1,
        ),
        "B: .env assignment": (
            {".env.example": "IRI_INGEST_EXPECTED_AUDIENCES=basetool-backend\n"},
            1,
        ),
        "B: compose default": (
            {"docker-compose.yml":
                "      APP_SECURITY_JWT_EXPECTED_AUDIENCES: ${IRI_INGEST_EXPECTED_AUDIENCES:-basetool-backend}\n"},
            1,
        ),
        "B: runbook with a list value": (
            {"docs/RUNBOOK.md": "    IRI_INGEST_EXPECTED_AUDIENCES=basetool-ingest,basetool-backend\n"},
            1,
        ),
        "B: history is exempt": (
            {"docs/archive/OLD.md": "IRI_INGEST_EXPECTED_AUDIENCES=basetool-backend\n"},
            0,
        ),
        "B: prose that warns against it passes": (
            {"docs/SETUP.md": "Never set `IRI_INGEST_EXPECTED_AUDIENCES` to the backend value.\n"},
            0,
        ),
        "C: env template literal": (
            {INGEST_ENV_TEMPLATE: "APP_SECURITY_JWT_EXPECTED_AUDIENCES=basetool-backend\n"},
            1,
        ),
    }
    failures = 0
    for label, (overrides, expected) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = {
                INGEST_MAIN_CONFIG: clean_yaml,
                INGEST_ENV_TEMPLATE: "APP_SECURITY_JWT_EXPECTED_AUDIENCES=${IRI_INGEST_EXPECTED_AUDIENCES:-}\n",
                ".env.example": "#IRI_INGEST_EXPECTED_AUDIENCES=basetool-ingest\n",
            }
            fixture.update(overrides)
            for name, content in fixture.items():
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content, encoding="utf-8")
            found = check(root, sorted(fixture))
            if len(found) != expected:
                failures += 1
                print(f"SELFTEST FAIL [{label}]: expected {expected} finding(s), got {found}")
            else:
                print(f"selftest ok   [{label}]")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    if "--selftest" in argv:
        return selftest()
    root = pathlib.Path(__file__).resolve().parent.parent
    problems = check(root, tracked_files(root))
    for problem in problems:
        print(f"ERROR: {problem}")
    if problems:
        print(
            "\nThe ingest gateway's audience is basetool-ingest (ADR-0018 amendment 1, "
            "REQ-INGEST-011). basetool-backend is carried by every frontend session token, so "
            "configuring it on the gateway admits the tokens the client gate exists to refuse."
        )
        return 1
    print("ingest audience: OK -- no ingest config or environment pairs it with basetool-backend")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
