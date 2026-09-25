#!/usr/bin/env python3
"""Resolve the human-readable version string baked into the images.

1. A tag build resolves to the tag.
2. Otherwise, a newest dated CHANGELOG version without a tag yet (the release being
   cut) is used.
3. Otherwise, ``git describe --tags --always``.

Exit codes:
  0  -> the version was printed (or the selftest passed).
  1  -> the selftest failed.

Usage:
    app_version.py
    app_version.py --selftest
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CHANGELOG = REPO / "CHANGELOG.md"

DATED_SECTION = re.compile(r"^## \[(v\d+\.\d+\.\d+)\]\(.*?\)\s+-\s+\d{4}-\d{2}-\d{2}\s*$", re.MULTILINE)


def newest_changelog_version(text: str) -> str | None:
    """Return the newest dated CHANGELOG version, or ``None`` if there is none.

    Newest means first in the file, not highest version.

    :param text: the full CHANGELOG.md contents.
    :return: the version including its leading ``v``, or ``None``.
    """
    match = DATED_SECTION.search(text)
    return match.group(1) if match else None


def resolve(ref_type: str, ref_name: str, changelog: str, tag_exists, describe) -> str:
    """Return the version string for this build.

    :param ref_type: ``github.ref_type`` — ``"tag"`` for a tag build.
    :param ref_name: ``github.ref_name`` — the tag or branch name.
    :param changelog: the full CHANGELOG.md contents.
    :param tag_exists: predicate answering whether a tag is in the repository.
    :param describe: callable returning ``git describe --tags --always``.
    :return: the version string to bake into the image.
    """
    if ref_type == "tag":
        return ref_name

    pending = newest_changelog_version(changelog)
    if pending and not tag_exists(pending):
        return pending

    return describe()


def git_tag_exists(tag: str) -> bool:
    """Whether ``tag`` resolves to an object in this repository.

    :param tag: the tag name, e.g. ``v1.8.4``.
    :return: ``True`` when the tag exists locally.
    """
    return (
        subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", f"refs/tags/{tag}"],
            cwd=REPO,
            capture_output=True,
        ).returncode
        == 0
    )


def git_describe() -> str:
    """Return ``git describe --tags --always`` for HEAD.

    :return: the describe output, stripped.
    :raises subprocess.CalledProcessError: if git itself fails.
    """
    return subprocess.run(
        ["git", "describe", "--tags", "--always"],
        cwd=REPO,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()


def selftest() -> int:
    """Check every resolution branch and that the real CHANGELOG has a dated section.

    :return: 0 when every case resolved as expected, 1 otherwise.
    """
    cut = "## [v1.8.4](https://github.com/krt-profit/basetool/releases/tag/v1.8.4) - 2026-09-15\n"
    older = "## [v1.8.3](https://github.com/krt-profit/basetool/releases/tag/v1.8.3) - 2026-09-14\n"
    unreleased = "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- something\n\n"

    cases = [
        ("tag build wins outright", "tag", "v1.8.4", unreleased + cut + older, {"v1.8.4"}, "ignored", "v1.8.4"),
        ("release commit before its tag", "branch", "main", unreleased + cut + older, {"v1.8.3"}, "v1.8.3-9-gc2f77a5", "v1.8.4"),
        ("ordinary main after the tag", "branch", "main", unreleased + cut + older, {"v1.8.3", "v1.8.4"}, "v1.8.4-3-gdeadbee", "v1.8.4-3-gdeadbee"),
        ("ordinary main before a release", "branch", "main", unreleased + older, {"v1.8.3"}, "v1.8.3-7-gcafe123", "v1.8.3-7-gcafe123"),
        ("release branch, tag not yet cut", "branch", "release/v1.8.4", unreleased + cut + older, {"v1.8.3"}, "v1.8.3-9-gc2f77a5", "v1.8.4"),
        ("repository with no releases yet", "branch", "main", unreleased, set(), "abc1234", "abc1234"),
        ("unreleased only, never matched", "branch", "main", "# Changelog\n\n## [Unreleased]\n", set(), "abc1234", "abc1234"),
    ]

    failures = 0
    for name, ref_type, ref_name, changelog, tags, described, expected in cases:
        got = resolve(ref_type, ref_name, changelog, lambda t, tags=tags: t in tags, lambda d=described: d)
        if got != expected:
            print(f"SELFTEST FAIL: {name}: expected {expected!r}, got {got!r}")
            failures += 1

    if newest_changelog_version(CHANGELOG.read_text(encoding="utf-8")) is None:
        print("SELFTEST FAIL: no dated section found in the real CHANGELOG.md")
        failures += 1

    if failures:
        return 1
    print("selftest: ok")
    return 0


def main() -> int:
    """Print the version string, or run the selftest.

    :return: the process exit code.
    """
    if "--selftest" in sys.argv:
        return selftest()

    print(
        resolve(
            os.environ.get("GITHUB_REF_TYPE", "branch"),
            os.environ.get("GITHUB_REF_NAME", ""),
            CHANGELOG.read_text(encoding="utf-8"),
            git_tag_exists,
            git_describe,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
