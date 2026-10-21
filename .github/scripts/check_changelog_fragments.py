#!/usr/bin/env python3
"""Check the changelog fragments in ``changelog.d/`` and keep ``[Unreleased]`` closed.

A user-visible change adds ``changelog.d/<name>.md`` instead of editing ``CHANGELOG.md``;
``cut_release.py`` folds the fragments into the release section (REQ-OPS-051, ADR-0306).

Exit codes:
  0  -> every fragment is well formed and ``[Unreleased]`` gained no entry, or the self-test passed.
  1  -> at least one finding, or the self-test failed; each finding names the file to change.

Usage:
    check_changelog_fragments.py
    check_changelog_fragments.py --base origin/main
    check_changelog_fragments.py --selftest
"""

from __future__ import annotations

import argparse
import contextlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass

for _stream in (sys.stdout, sys.stderr):
    with contextlib.suppress(AttributeError, ValueError):
        _stream.reconfigure(encoding="utf-8")

FRAGMENT_DIR = "changelog.d"
SECTIONS = ["Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"]
NAME_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*\.md$")


@dataclass(frozen=True)
class Fragment:
    """One parsed fragment: its file name, its section and its list items."""

    name: str
    section: str
    items: tuple[tuple[str, ...], ...]


def parse_fragment(name: str, text: str) -> tuple[Fragment | None, list[str]]:
    """Parse one fragment file.

    :param name: the file name inside ``changelog.d/``.
    :param text: the file content.
    :return: the fragment, or ``None`` with the findings when it is malformed.
    """
    path = f"{FRAGMENT_DIR}/{name}"
    if not NAME_RE.match(name):
        return None, [f"{path}: the name must be lowercase letters, digits and hyphens "
                      "ending in '.md', e.g. 'hangar-import.md' or '1234.md'"]
    if text.startswith("﻿"):
        return None, [f"{path}: starts with a UTF-8 BOM; save it without one"]
    if "\r" in text:
        return None, [f"{path}: has CRLF line endings; save it with LF"]
    lines = text.split("\n")
    heading = lines[0]
    if not heading.startswith("### ") or heading[4:].strip() not in SECTIONS:
        return None, [f"{path}: the first line must be '### <section>' with one of "
                      f"{', '.join(SECTIONS)}, found {heading!r}"]
    items: list[list[str]] = []
    findings: list[str] = []
    for number, line in enumerate(lines[1:], start=2):
        if line.startswith("- "):
            items.append([line])
        elif not line.strip():
            continue
        elif line.startswith("#"):
            findings.append(f"{path}:{number}: a fragment holds one section; move "
                            f"{line.strip()!r} and its items into a fragment of its own")
        elif line.startswith("  ") and items:
            items[-1].append(line)
        else:
            findings.append(f"{path}:{number}: loose text; every line is a '- ' item "
                            "or its continuation indented by two spaces")
    if not items:
        findings.append(f"{path}: no list item; add at least one line starting with '- '")
    if findings:
        return None, findings
    return Fragment(name, heading[4:].strip(), tuple(tuple(item) for item in items)), []


def load_fragments(repo: str) -> tuple[list[Fragment], list[str]]:
    """Parse every fragment under ``<repo>/changelog.d``.

    :param repo: the repository root.
    :return: the well-formed fragments sorted by name, and every finding.
    """
    directory = os.path.join(repo, FRAGMENT_DIR)
    fragments: list[Fragment] = []
    findings: list[str] = []
    if not os.path.isdir(directory):
        return fragments, findings
    for name in sorted(os.listdir(directory)):
        if name == "README.md":
            continue
        with open(os.path.join(directory, name), encoding="utf-8", newline="") as handle:
            fragment, problems = parse_fragment(name, handle.read())
        findings.extend(problems)
        if fragment is not None:
            fragments.append(fragment)
    return fragments, findings


def check(repo: str, base: str | None) -> list[str]:
    """Return every finding for the working tree of ``repo``.

    :param repo: the repository root.
    :param base: the ref whose ``[Unreleased]`` block the working tree may not extend, or ``None``.
    :return: the findings, empty when the change is clean.
    """
    _, findings = load_fragments(repo)
    if base is None:
        return findings
    with open(os.path.join(repo, "CHANGELOG.md"), encoding="utf-8") as handle:
        head_items = unreleased_items(handle.read())
    base_items = set(unreleased_items(git_show(repo, base, "CHANGELOG.md")))
    for item in head_items:
        if item not in base_items:
            first = item.split("\n", 1)[0]
            findings.append(
                f"CHANGELOG.md: a new entry under [Unreleased]; move it into "
                f"{FRAGMENT_DIR}/<name>.md (see {FRAGMENT_DIR}/README.md): {first[:120]}")
    return findings


def unreleased_items(text: str) -> list[str]:
    """Return the list items of the ``[Unreleased]`` block, each with its continuation lines.

    :param text: the content of ``CHANGELOG.md``.
    :return: every item as its lines joined by newlines, in file order.
    """
    items: list[list[str]] = []
    inside = False
    for line in text.splitlines():
        if line.startswith("## "):
            inside = "unreleased" in line.lower()
            continue
        if not inside:
            continue
        if line.startswith("- "):
            items.append([line.rstrip()])
        elif line.startswith("  ") and line.strip() and items:
            items[-1].append(line.rstrip())
    return ["\n".join(item) for item in items]


def git_show(repo: str, rev: str, path: str) -> str:
    """Return ``path`` at ``rev``, or an empty string when it does not exist there.

    :param repo: the repository root.
    :param rev: any commit-ish.
    :param path: the repository-relative path.
    :return: the file content.
    :raises SystemExit: when ``rev`` does not resolve.
    """
    resolved = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}"],
        cwd=repo, capture_output=True, text=True, encoding="utf-8")
    if resolved.returncode != 0:
        sys.exit(f"error: base {rev!r} is not a commit; fetch it first")
    shown = subprocess.run(
        ["git", "show", f"{resolved.stdout.strip()}:{path}"],
        cwd=repo, capture_output=True, text=True, encoding="utf-8")
    return shown.stdout if shown.returncode == 0 else ""


class TempRepo:
    """A throwaway git repository for the self-tests of the changelog scripts."""

    def __init__(self) -> None:
        """Create and initialise the repository in a fresh temporary directory."""
        self.path = tempfile.mkdtemp(prefix="changelog-selftest-")
        self.git("init", "-q", "-b", "main")

    def git(self, *args: str) -> str:
        """Run git in the repository with a fixed identity and return stdout."""
        env = dict(os.environ)
        env.update({
            "GIT_AUTHOR_NAME": "Selftest", "GIT_AUTHOR_EMAIL": "selftest@example.invalid",
            "GIT_COMMITTER_NAME": "Selftest", "GIT_COMMITTER_EMAIL": "selftest@example.invalid",
            "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull,
        })
        return subprocess.run(
            ["git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false",
             "-c", "core.autocrlf=false", *args],
            cwd=self.path, env=env, capture_output=True, text=True, encoding="utf-8",
            check=True,
        ).stdout

    def write(self, path: str, text: str) -> None:
        """Write ``text`` to ``path`` inside the repository with LF line endings."""
        full = os.path.join(self.path, path)
        os.makedirs(os.path.dirname(full) or self.path, exist_ok=True)
        with open(full, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)

    def read(self, path: str) -> str:
        """Return the content of ``path`` inside the repository."""
        with open(os.path.join(self.path, path), encoding="utf-8", newline="") as handle:
            return handle.read()

    def exists(self, path: str) -> bool:
        """Tell whether ``path`` exists inside the repository."""
        return os.path.exists(os.path.join(self.path, path))

    def commit(self, message: str, date: str = "2026-10-01T12:00:00+00:00") -> str:
        """Stage everything, commit it at ``date`` and return the commit SHA."""
        self.git("add", "-A")
        os.environ["GIT_COMMITTER_DATE"] = date
        os.environ["GIT_AUTHOR_DATE"] = date
        try:
            self.git("commit", "-q", "-m", message)
        finally:
            del os.environ["GIT_COMMITTER_DATE"]
            del os.environ["GIT_AUTHOR_DATE"]
        return self.git("rev-parse", "HEAD").strip()

    def run(self, script: str, *args: str) -> tuple[int, str]:
        """Run ``script`` with ``args`` inside the repository and return exit code and output."""
        env = dict(os.environ, PYTHONIOENCODING="utf-8")
        result = subprocess.run(
            [sys.executable, script, *args], cwd=self.path, env=env,
            capture_output=True, text=True, encoding="utf-8",
        )
        return result.returncode, result.stdout + result.stderr

    def close(self) -> None:
        """Delete the repository."""
        shutil.rmtree(self.path, ignore_errors=True)


BASE_CHANGELOG = (
    "# Changelog\n\n## [Unreleased]\n\n"
    "## [v1.0.0](https://github.com/krt-profit/basetool/releases/tag/v1.0.0) - 2026-09-01\n\n"
    "### Added\n\n- **Erstes Release.**\n"
)
GOOD_FRAGMENT = "### Fixed\n\n- **Suche.** Ein leerer Filter blendet nichts mehr aus.\n"


def _case_unknown_section(repo: TempRepo) -> str | None:
    """A fragment with a section outside Keep a Changelog fails and names the file."""
    repo.write("changelog.d/suche.md", "### Bugfixes\n\n- **Suche.** Behoben.\n")
    code, out = repo.run(SELF, "--repo", repo.path)
    if code != 1 or "changelog.d/suche.md" not in out:
        return f"expected exit 1 naming changelog.d/suche.md, got {code}: {out}"
    return None


def _case_good_fragment(repo: TempRepo) -> str | None:
    """A well-formed fragment and the README pass."""
    repo.write("changelog.d/README.md", "# Changelog fragments\n\nFrei formulierter Text.\n")
    repo.write("changelog.d/suche.md", GOOD_FRAGMENT)
    code, out = repo.run(SELF, "--repo", repo.path)
    if code != 0:
        return f"expected exit 0, got {code}: {out}"
    return None


def _case_no_item(repo: TempRepo) -> str | None:
    """A fragment with a section but no list item fails."""
    repo.write("changelog.d/leer.md", "### Fixed\n\n")
    code, out = repo.run(SELF, "--repo", repo.path)
    if code != 1 or "changelog.d/leer.md" not in out:
        return f"expected exit 1 naming changelog.d/leer.md, got {code}: {out}"
    return None


def _case_heading_in_body(repo: TempRepo) -> str | None:
    """A second heading inside a fragment fails, so one file holds one section."""
    repo.write("changelog.d/zwei.md", GOOD_FRAGMENT + "\n### Added\n\n- **Neu.** Etwas.\n")
    code, out = repo.run(SELF, "--repo", repo.path)
    if code != 1 or "changelog.d/zwei.md" not in out:
        return f"expected exit 1 naming changelog.d/zwei.md, got {code}: {out}"
    return None


def _case_loose_text(repo: TempRepo) -> str | None:
    """Text that is neither a list item nor its indented continuation fails."""
    repo.write("changelog.d/lose.md", "### Fixed\n\nEinleitung.\n\n- **Suche.** Behoben.\n")
    code, out = repo.run(SELF, "--repo", repo.path)
    if code != 1 or "changelog.d/lose.md" not in out:
        return f"expected exit 1 naming changelog.d/lose.md, got {code}: {out}"
    return None


def _case_bad_name(repo: TempRepo) -> str | None:
    """A file name outside lowercase letters, digits and hyphens fails."""
    repo.write("changelog.d/Neue_Suche.md", GOOD_FRAGMENT)
    code, out = repo.run(SELF, "--repo", repo.path)
    if code != 1 or "changelog.d/Neue_Suche.md" not in out:
        return f"expected exit 1 naming changelog.d/Neue_Suche.md, got {code}: {out}"
    return None


def _case_crlf(repo: TempRepo) -> str | None:
    """A fragment with CRLF line endings fails."""
    repo.write("changelog.d/crlf.md", GOOD_FRAGMENT.replace("\n", "\r\n"))
    code, out = repo.run(SELF, "--repo", repo.path)
    if code != 1 or "changelog.d/crlf.md" not in out:
        return f"expected exit 1 naming changelog.d/crlf.md, got {code}: {out}"
    return None


LEGACY_CHANGELOG = BASE_CHANGELOG.replace(
    "## [Unreleased]\n\n", "## [Unreleased]\n\n### Fixed\n\n- **Alt.** Noch offen.\n\n")


def _case_new_unreleased_item(repo: TempRepo) -> str | None:
    """A pull request that adds an item under [Unreleased] fails against its base."""
    base = repo.git("rev-parse", "HEAD").strip()
    repo.write("CHANGELOG.md", BASE_CHANGELOG.replace(
        "## [Unreleased]\n\n", "## [Unreleased]\n\n### Added\n\n- **Neu.** Direkt eingetragen.\n\n"))
    code, out = repo.run(SELF, "--repo", repo.path, "--base", base)
    if code != 1 or "**Neu.**" not in out or "changelog.d/" not in out:
        return f"expected exit 1 naming the item and changelog.d/, got {code}: {out}"
    return None


def _case_legacy_items_stay(repo: TempRepo) -> str | None:
    """Items that already stood under [Unreleased] on the base pass unchanged."""
    repo.write("CHANGELOG.md", LEGACY_CHANGELOG)
    base = repo.commit("chore: legacy entry")
    repo.write("changelog.d/suche.md", GOOD_FRAGMENT)
    code, out = repo.run(SELF, "--repo", repo.path, "--base", base)
    if code != 0:
        return f"expected exit 0, got {code}: {out}"
    return None


def _case_release_cut_passes(repo: TempRepo) -> str | None:
    """The release pull request, which empties [Unreleased] into a version, passes."""
    repo.write("CHANGELOG.md", LEGACY_CHANGELOG)
    base = repo.commit("chore: legacy entry")
    repo.write("CHANGELOG.md", LEGACY_CHANGELOG.replace(
        "## [Unreleased]\n\n",
        "## [Unreleased]\n\n## [v1.1.0](https://github.com/krt-profit/basetool/releases/tag/v1.1.0)"
        " - 2026-10-02\n\n"))
    code, out = repo.run(SELF, "--repo", repo.path, "--base", base)
    if code != 0:
        return f"expected exit 0, got {code}: {out}"
    return None


SELF = os.path.abspath(__file__)
CASES = [
    _case_new_unreleased_item,
    _case_legacy_items_stay,
    _case_release_cut_passes,
    _case_unknown_section,
    _case_good_fragment,
    _case_no_item,
    _case_heading_in_body,
    _case_loose_text,
    _case_bad_name,
    _case_crlf,
]


def selftest() -> int:
    """Run every self-test case against its own throwaway repository.

    :return: 0 when every case passed, 1 otherwise.
    """
    failures = 0
    for case in CASES:
        repo = TempRepo()
        try:
            repo.write("CHANGELOG.md", BASE_CHANGELOG)
            repo.commit("chore: base")
            problem = case(repo)
        finally:
            repo.close()
        if problem:
            print(f"SELFTEST FAIL: {case.__doc__}\n  {problem}")
            failures += 1
    if failures:
        return 1
    print(f"selftest: ok ({len(CASES)} cases)")
    return 0


def main() -> int:
    """Parse arguments, run the check or the self-test, and print the findings.

    :return: the process exit code.
    """
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=os.getcwd(), help="Repo root. Default: cwd.")
    parser.add_argument("--base", default="",
                        help="Ref whose [Unreleased] block may not grow. Default: none.")
    parser.add_argument("--selftest", action="store_true", help="Run the self-test.")
    args = parser.parse_args()
    if args.selftest:
        return selftest()

    findings = check(os.path.abspath(args.repo), args.base or None)
    for finding in findings:
        print(f"::error::{finding}")
    if findings:
        return 1
    print("changelog fragments: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
