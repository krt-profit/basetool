#!/usr/bin/env python3
"""Resolve the human-readable version string baked into the images.

``git describe --tags`` alone is wrong here, and it is wrong in exactly one
place: **the release commit itself**. Since REQ-OPS-021 / ADR-0137 an image is
built once per commit and the tag run *re-tags that digest* rather than
rebuilding, so the string is fixed at the moment ``main`` is built — which is
before ``release-publish.yml`` creates the tag. Measured on 2026-09-15: the main
build ran at 12:50:47 and the tag run at 12:51:14, twenty-seven seconds later and
with the build skipped. The footer therefore read ``v1.8.3-9-gc2f77a5cb`` for a
release whose tag, ``v1.8.4``, sits on that very commit.

The workflow's own comment claimed "tag pushes resolve to the tag name verbatim",
which stopped being true the day the reuse path landed. Nothing failed: a version
string cannot be wrong loudly.

Rebuilding on the tag would fix it and cost more than it is worth — it is the
whole of ADR-0137's saving, and it breaks the guarantee that ``:1.8.4`` and
``:sha-<short>`` are the same bytes, so a rollback to either lands on identical
content. The release version is knowable *without* the tag: the release commit
carries it in the CHANGELOG, which ``release-prepare.yml`` wrote there.

The string names the commit that BUILT the frontend image, and since ADR-0210 that is not always
the commit whose tag points at it: a main push that leaves the frontend's inputs alone re-tags the
previous frontend image, chip included, while it rebuilds the backend or ingest. So on ``:edge`` and
``:sha-<short>`` the chip can name an earlier commit than the tag -- the code it describes is the
same, which is the reuse condition. A release commit always rebuilds all three images, so a release's
chip is always the release (rule 2 below).

So:

1. A tag build resolves to the tag, verbatim — unchanged, and still the
   authority when it is available.
2. Otherwise, if the newest **dated** CHANGELOG section names a version that has
   **no tag yet**, that is the release being cut and its name is used. This is
   true for the release commit and for the release branch, and false everywhere
   else: one commit later the section is tagged and rule 3 applies again.
3. Otherwise, ``git describe --tags --always`` — the ordinary main-branch
   ``vX.Y.Z-<n>-g<sha>``, or a bare short SHA in a repository with no tags.

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

# The dated section heading `release-prepare.yml` writes, e.g.
# `## [v1.8.4](https://github.com/.../tag/v1.8.4) - 2026-09-15`. `[Unreleased]`
# carries no date and no version, so it cannot match and needs no special case.
DATED_SECTION = re.compile(r"^## \[(v\d+\.\d+\.\d+)\]\(.*?\)\s+-\s+\d{4}-\d{2}-\d{2}\s*$", re.MULTILINE)


def newest_changelog_version(text: str) -> str | None:
    """Return the newest dated CHANGELOG version, or ``None`` if there is none.

    Newest means *first in the file*: the changelog is written newest-first and
    ``release-prepare.yml`` inserts at the top. Reading order rather than version
    order is deliberate — it makes a re-released or hand-edited older section
    unable to win, which a max() over parsed versions would allow.

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
    """Every branch, including the one the real defect took.

    :return: 0 when every case resolved as expected, 1 otherwise.
    """
    cut = "## [v1.8.4](https://github.com/krt-profit/basetool/releases/tag/v1.8.4) - 2026-09-15\n"
    older = "## [v1.8.3](https://github.com/krt-profit/basetool/releases/tag/v1.8.3) - 2026-09-14\n"
    unreleased = "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- something\n\n"

    cases = [
        # name, ref_type, ref_name, changelog, tagged, describe -> expected
        ("tag build wins outright", "tag", "v1.8.4", unreleased + cut + older, {"v1.8.4"}, "ignored", "v1.8.4"),
        # THE DEFECT: main, on the release commit, tag not created yet.
        ("release commit before its tag", "branch", "main", unreleased + cut + older, {"v1.8.3"}, "v1.8.3-9-gc2f77a5", "v1.8.4"),
        # One commit later the tag exists, so describe is right again.
        ("ordinary main after the tag", "branch", "main", unreleased + cut + older, {"v1.8.3", "v1.8.4"}, "v1.8.4-3-gdeadbee", "v1.8.4-3-gdeadbee"),
        ("ordinary main before a release", "branch", "main", unreleased + older, {"v1.8.3"}, "v1.8.3-7-gcafe123", "v1.8.3-7-gcafe123"),
        ("release branch, tag not yet cut", "branch", "release/v1.8.4", unreleased + cut + older, {"v1.8.3"}, "v1.8.3-9-gc2f77a5", "v1.8.4"),
        ("repository with no releases yet", "branch", "main", unreleased, set(), "abc1234", "abc1234"),
        # An [Unreleased] heading must never be read as a version.
        ("unreleased only, never matched", "branch", "main", "# Changelog\n\n## [Unreleased]\n", set(), "abc1234", "abc1234"),
    ]

    failures = 0
    for name, ref_type, ref_name, changelog, tags, described, expected in cases:
        got = resolve(ref_type, ref_name, changelog, lambda t, tags=tags: t in tags, lambda d=described: d)
        if got != expected:
            print(f"SELFTEST FAIL: {name}: expected {expected!r}, got {got!r}")
            failures += 1

    # And the real file must still parse, or the heading regex has rotted and
    # every build would silently fall back to describe — which is the defect.
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
