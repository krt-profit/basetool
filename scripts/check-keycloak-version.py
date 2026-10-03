#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Fail when the keycloak-spi compile version and the Keycloak image the stack runs drift apart.

The provider JAR compiles against Keycloak's private SPIs at the catalog's ``keycloak`` version and
is loaded by the Keycloak image the stack pins (REQ-OPS-040).

* **Rule A.** ``gradle/libs.versions.toml`` names ``keycloak`` as ``X.Y.Z``.
* **Rule B.** Each runtime pin file (compose, Quadlet unit, sandbox Dockerfile) holds a
  ``quay.io/keycloak/keycloak:<tag>`` reference.
* **Rule C.** Every such reference in a tracked file outside the documentation agrees with the
  catalog: a ``X.Y`` tag in major and minor, a ``X.Y.Z`` tag exactly; any other tag is refused.
* **Rule D.** All references carry the same tag and the same digest, so every pin runs one build.

Usage::

    python3 scripts/check-keycloak-version.py            # check the repository
    python3 scripts/check-keycloak-version.py --selftest # prove every rule can fail
"""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys
import tempfile

CATALOG = "gradle/libs.versions.toml"
CATALOG_KEY = re.compile(r'^keycloak\s*=\s*"([^"]*)"\s*$', re.M)
RELEASE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
RUNTIME_PINS = (
    "docker-compose.yml",
    "quadlet/systemd/keycloak.container",
    "docker/sandbox/keycloak/Dockerfile",
)
IMAGE = re.compile(
    r"quay\.io/keycloak/keycloak:(?P<tag>[0-9][0-9A-Za-z._-]*)(?:@(?P<digest>sha256:[0-9a-f]{64}))?"
)
SELF = "scripts/check-keycloak-version.py"
EXEMPT_PREFIXES = ("docs/", "CHANGELOG")
EXEMPT_SUFFIXES = (".md", ".test.sh")


def is_exempt(name: str) -> bool:
    """Whether a tracked file only talks about the image instead of pinning it."""
    return name == SELF or name.startswith(EXEMPT_PREFIXES) or name.endswith(EXEMPT_SUFFIXES)


def catalog_version(root: pathlib.Path) -> tuple[str | None, list[str]]:
    """Rule A: the catalog's keycloak version, or a problem."""
    path = root / CATALOG
    if not path.is_file():
        return None, [f"{CATALOG}: missing"]
    match = CATALOG_KEY.search(path.read_text(encoding="utf-8"))
    if not match:
        return None, [f"{CATALOG}: no keycloak version key -- update this script if it moved"]
    if not RELEASE.match(match.group(1)):
        return None, [f"{CATALOG}: keycloak = \"{match.group(1)}\" is not a X.Y.Z release"]
    return match.group(1), []


def references(root: pathlib.Path, files: list[str]) -> list[tuple[str, int, str, str | None]]:
    """Every image reference in the given tracked files outside the documentation."""
    found: list[tuple[str, int, str, str | None]] = []
    for name in files:
        if is_exempt(name):
            continue
        path = root / name
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(text.splitlines(), start=1):
            for match in IMAGE.finditer(line):
                found.append((name, number, match.group("tag"), match.group("digest")))
    return found


def agrees(tag: str, version: str) -> str | None:
    """Rule C for one tag: ``None`` when it agrees with the catalog, otherwise why not."""
    parts = tag.split(".")
    if not all(part.isdigit() for part in parts) or len(parts) not in (2, 3):
        return f"tag {tag} is neither X.Y nor X.Y.Z, so it cannot be compared with {version}"
    wanted = version.split(".")[: len(parts)]
    if parts != wanted:
        scope = "major.minor" if len(parts) == 2 else "version"
        return f"tag {tag} differs in {scope} from the catalog's keycloak = \"{version}\""
    return None


def check(root: pathlib.Path, files: list[str]) -> list[str]:
    """Run every rule over the given repository-relative file list."""
    version, problems = catalog_version(root)
    pins = references(root, sorted(set(files) | set(RUNTIME_PINS)))

    for required in RUNTIME_PINS:
        if not any(name == required for name, _, _, _ in pins):
            problems.append(
                f"{required}: no quay.io/keycloak/keycloak:<tag> reference -- the check has "
                "nothing to compare; update RUNTIME_PINS if the pin moved"
            )

    if version is not None:
        for name, number, tag, _ in pins:
            reason = agrees(tag, version)
            if reason:
                problems.append(f"{name}:{number}: {reason}")

    tags = sorted({tag for _, _, tag, _ in pins})
    digests = sorted({digest or "<none>" for _, _, _, digest in pins})
    if len(tags) > 1 or len(digests) > 1:
        where = ", ".join(f"{name}:{number}={tag}@{digest}" for name, number, tag, digest in pins)
        problems.append(f"the Keycloak image pins disagree with each other: {where}")
    return problems


def tracked_files(root: pathlib.Path) -> list[str]:
    output = subprocess.run(
        ["git", "ls-files"], cwd=root, check=True, capture_output=True, text=True
    ).stdout
    return [line for line in output.splitlines() if line]


def selftest() -> int:
    """Build a clean fixture, require it to pass, then break it once per rule."""
    digest = "sha256:" + "a" * 64
    other = "sha256:" + "b" * 64

    def pins(tag: str, sha: str = digest) -> dict[str, str]:
        return {
            "docker-compose.yml": f"  keycloak:\n    image: quay.io/keycloak/keycloak:{tag}@{sha}\n",
            "quadlet/systemd/keycloak.container": f"[Container]\nImage=quay.io/keycloak/keycloak:{tag}@{sha}\n",
            "docker/sandbox/keycloak/Dockerfile": f"FROM quay.io/keycloak/keycloak:{tag}@{sha} AS base\n",
        }

    catalog = '[versions]\nkeycloak = "26.8.0"\njunit = "6.1.3"\n'
    cases = {
        "clean: minor tag": ({}, 0),
        "clean: exact tag": (pins("26.8.0"), 0),
        "A: catalog key gone": ({CATALOG: '[versions]\njunit = "6.1.3"\n'}, 1),
        "A: catalog version not X.Y.Z": ({CATALOG: '[versions]\nkeycloak = "26.8"\n'}, 1),
        "B: a runtime pin vanished": ({"docker/sandbox/keycloak/Dockerfile": "FROM scratch\n"}, 1),
        "C: catalog moved to another minor": ({CATALOG: catalog.replace("26.8.0", "26.9.0")}, 3),
        "C: catalog moved to another major": ({CATALOG: catalog.replace("26.8.0", "27.8.0")}, 3),
        "C: catalog patch moves under a minor tag": ({CATALOG: catalog.replace("26.8.0", "26.8.4")}, 0),
        "C: exact tag, other patch": (pins("26.8.1"), 3),
        "C: tag too loose to compare": (pins("26"), 3),
        "C+D: one pin bumped alone": (
            {"docker-compose.yml": "    image: quay.io/keycloak/keycloak:26.9@" + digest + "\n"},
            2,
        ),
        "C+D: a new tracked file pins another minor": (
            {"docker-compose.extra.yml": "    image: quay.io/keycloak/keycloak:26.7@" + digest + "\n"},
            2,
        ),
        "D: one pin on another digest": (
            {"docker/sandbox/keycloak/Dockerfile": "FROM quay.io/keycloak/keycloak:26.8@" + other + "\n"},
            1,
        ),
        "documentation is exempt": (
            {"docs/keycloak/README.md": "Keycloak 26.7 (`quay.io/keycloak/keycloak:26.7`)\n"},
            0,
        ),
    }
    failures = 0
    for label, (overrides, expected) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            fixture = {CATALOG: catalog, **pins("26.8")}
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
            "\nkeycloak-spi compiles against Keycloak's private SPIs at the catalog's keycloak "
            "version and is loaded by the pinned image (REQ-OPS-040). Move the catalog and every "
            "image pin together, then rebuild and re-test the SPI."
        )
        return 1
    print("keycloak version: OK -- the catalog and every image pin name one Keycloak line")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
