#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Assert that every cosign signer-identity regexp is anchored, and that all copies agree.

Covers every ``--certificate-identity-regexp`` in ``scripts/``, the workflows and the composite
actions (REQ-OPS-015). Asserts that every copy is anchored with ``^`` and ``$``, that the release
copies are identical after unquoting, that the main-only copy is the release copy narrowed to
``heads/main``, that each accepts and refuses the expected SAN subjects, and that copies were found.

Usage::

    scripts/check-cosign-identity.py [--root DIR]

Exit status 0 when every assertion holds, 1 otherwise (with one line per problem).
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path

REPO = "krt-profit/basetool"

REPO_VARIABLES = ("${REPO}", "${COSIGN_REPO}")

FLAG_RE = re.compile(r"""--certificate-identity-regexp\s+(["'])(?P<value>.*?)\1""")

DEFAULT_RE = re.compile(r"""^\s*COSIGN_IDENTITY_REGEXP="\$\{[A-Z_]+:-(?P<value>.*)\}"\s*$""")

BARE_VARIABLE_RE = re.compile(r"^\$\{?[A-Za-z_][A-Za-z0-9_]*\}?$")

RELEASE_REFS = r"(heads/main|tags/v[0-9]+\.[0-9]+\.[0-9]+)"
MAIN_ONLY_REFS = "heads/main"


@dataclass(frozen=True)
class Copy:
    """One identity regexp as it appears in a file, plus its normalised form."""

    path: str
    line: int
    raw: str
    normalised: str

    @property
    def is_release(self) -> bool:
        """True for a copy that admits release tags; False for the main-only reuse gate."""
        return "tags/" in self.normalised

    @property
    def where(self) -> str:
        """``path:line`` for reports."""
        return f"{self.path}:{self.line}"


def unquote_bash_double(value: str) -> str:
    """Undo bash double-quote escaping: ``\\\\`` -> ``\\``, ``\\$`` -> ``$``, ``\\"`` -> ``"``."""
    out = []
    i = 0
    while i < len(value):
        char = value[i]
        if char == "\\" and i + 1 < len(value) and value[i + 1] in '\\$"`':
            out.append(value[i + 1])
            i += 2
            continue
        out.append(char)
        i += 1
    return "".join(out)


def normalise(value: str) -> str:
    """Return ``value`` as cosign sees it, with the repository variable made literal."""
    result = unquote_bash_double(value)
    for variable in REPO_VARIABLES:
        result = result.replace(variable, REPO)
    return result


def candidate_files(root: Path) -> list[Path]:
    """Workflows, composite actions and operational shell scripts under ``root``.

    Self-tests (``*.test.sh``) are excluded: they quote refused identities on purpose.
    """
    found: list[Path] = []
    for sub in (".github/workflows", ".github/actions", "scripts"):
        base = root / sub
        if not base.is_dir():
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                if name.endswith(".test.sh"):
                    continue
                if name.endswith((".yml", ".yaml", ".sh")):
                    found.append(Path(dirpath) / name)
    return sorted(found)


def extract(root: Path) -> list[Copy]:
    """Every identity regexp literal written under ``root``."""
    copies: list[Copy] = []
    for path in candidate_files(root):
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(root).as_posix()
        for number, line in enumerate(text.splitlines(), start=1):
            if line.lstrip().startswith("#"):
                continue
            values = [m.group("value") for m in FLAG_RE.finditer(line)]
            match = DEFAULT_RE.match(line)
            if match:
                values.append(match.group("value"))
            for value in values:
                if BARE_VARIABLE_RE.match(value):
                    continue
                copies.append(Copy(rel, number, value, normalise(value)))
    return copies


def subject(ref: str, workflow: str = "release-images.yml", repo: str = REPO) -> str:
    """The Fulcio certificate SAN a keyless signature minted on ``ref`` carries."""
    return f"https://github.com/{repo}/.github/workflows/{workflow}@refs/{ref}"


def behaviour_problems(copy: Copy) -> list[str]:
    """Run ``copy`` against the subjects it must accept and refuse."""
    problems: list[str] = []
    try:
        pattern = re.compile(copy.normalised)
    except re.error as err:
        return [f"{copy.where}: does not compile as a regexp ({err}): {copy.normalised}"]

    if copy.is_release:
        accept = [subject("heads/main"), subject("tags/v1.9.2"), subject("tags/v10.20.300")]
        refuse = [subject("heads/main-x"), subject("heads/maintenance"), subject("tags/vfoo"),
                  subject("tags/v1.9.2-rc1"), subject("tags/v1.9"), subject("heads/feature")]
    else:
        accept = [subject("heads/main")]
        refuse = [subject("heads/main-x"), subject("heads/maintenance"), subject("tags/v1.9.2")]
    refuse += [
        subject("heads/main", workflow="promote.yml"),
        subject("heads/main", repo=REPO + "-fork"),
        subject("heads/main", repo="attacker/" + REPO.split("/")[1]),
        "https://evil.example/?" + subject("heads/main"),
        subject("heads/main") + "/extra",
    ]
    for item in accept:
        if not pattern.search(item):
            problems.append(f"{copy.where}: must accept {item}")
    for item in refuse:
        if pattern.search(item):
            problems.append(f"{copy.where}: must refuse {item}")
    return problems


def check(root: Path) -> list[str]:
    """All problems found under ``root``; an empty list means the gate passes."""
    copies = extract(root)
    problems: list[str] = []
    releases = [c for c in copies if c.is_release]
    mains = [c for c in copies if not c.is_release]

    if not releases:
        problems.append("no release identity regexp found (promote / deploy.sh) -- the checker "
                        "no longer recognises the call sites, so it would pass vacuously")
    if not mains:
        problems.append("no main-only identity regexp found (release-images.yml reuse gate) -- "
                        "the checker no longer recognises the call site")
    if not any(c.path.startswith("scripts/") for c in releases):
        problems.append("no identity regexp found in scripts/ -- deploy.sh's host-side default "
                        "is the copy that matters most and was not seen")

    for copy in copies:
        if not copy.normalised.startswith("^"):
            problems.append(f"{copy.where}: not anchored at the start (missing '^'): {copy.raw}")
        if not copy.normalised.endswith("$"):
            problems.append(f"{copy.where}: not anchored at the end (missing '$'): {copy.raw}")

    if releases:
        reference = releases[0]
        for copy in releases[1:]:
            if copy.normalised != reference.normalised:
                problems.append(
                    f"{copy.where}: differs from {reference.where}:\n"
                    f"    {copy.normalised}\n    {reference.normalised}")
        if RELEASE_REFS not in reference.normalised:
            problems.append(f"{reference.where}: the ref alternation is not exactly "
                            f"{RELEASE_REFS}: {reference.normalised}")
        expected_main = reference.normalised.replace(RELEASE_REFS, MAIN_ONLY_REFS)
        for copy in mains:
            if copy.normalised != expected_main:
                problems.append(
                    f"{copy.where}: the main-only copy must be the release copy narrowed to "
                    f"heads/main:\n    {copy.normalised}\n    {expected_main}")

    for copy in copies:
        problems.extend(behaviour_problems(copy))
    return problems


def main(argv: list[str] | None = None) -> int:
    """Print every identity regexp found and report each problem; return the exit status."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--root", type=Path,
                        default=Path(__file__).resolve().parent.parent,
                        help="repository root to scan (default: this checkout)")
    args = parser.parse_args(argv)

    copies = extract(args.root)
    for copy in copies:
        kind = "release" if copy.is_release else "main-only"
        print(f"  {kind:9} {copy.where}: {copy.normalised}")
    problems = check(args.root)
    if problems:
        print(f"FAIL: {len(problems)} problem(s) with the cosign signer identity:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"OK: {len(copies)} identity regexp(s), all anchored and in agreement")
    return 0


if __name__ == "__main__":
    sys.exit(main())
