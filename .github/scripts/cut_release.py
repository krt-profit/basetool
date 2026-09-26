#!/usr/bin/env python3
"""Cut the ``[Unreleased]`` block into a dated ``## [vX.Y.Z]`` release section.

Renames ``## [Unreleased]`` to ``## [vX.Y.Z](<repo>/releases/tag/vX.Y.Z) - YYYY-MM-DD``
and inserts a fresh empty ``## [Unreleased]`` above it; no tag needs to exist. Refuses
to run when the version section already exists.

Usage:
    cut_release.py v0.3.55                       # cut, dated today, repo cwd
    cut_release.py v0.3.55 --date 2026-06-04
    cut_release.py v0.3.55 --repo /path --repo-url https://github.com/krt-profit/basetool
"""

from __future__ import annotations

import argparse
import datetime
import os
import re
import subprocess
import sys

VERSION_TAG_RE = re.compile(r"^v\d+\.\d+\.\d+$")


def run_git(repo: str, args: list[str]) -> str:
    """Run ``git <args>`` in ``repo`` and return stdout, or exit with its stderr."""
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=repo,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=True,
        )
    except FileNotFoundError:
        sys.exit("error: git is not on PATH")
    except subprocess.CalledProcessError as exc:
        sys.exit(f"error: git {' '.join(args)} failed:\n{(exc.stderr or '').strip()}")
    return result.stdout


def derive_repo_url(repo: str) -> str:
    """Derive ``https://github.com/<owner>/<repo>`` from the ``origin`` remote.

    Falls back to the project URL without ``origin``.

    :param repo: repository root to read ``origin`` from.
    :return: the GitHub base URL without a trailing ``.git``.
    """
    try:
        url = run_git(repo, ["remote", "get-url", "origin"]).strip()
    except SystemExit:
        return "https://github.com/krt-profit/basetool"
    url = re.sub(r"\.git$", "", url)
    ssh = re.match(r"git@([^:]+):(.+)$", url)
    if ssh:
        return f"https://{ssh.group(1)}/{ssh.group(2)}"
    return url


def cut(lines: list[str], version: str, date: str, repo_url: str) -> list[str]:
    """Return ``lines`` with ``[Unreleased]`` renamed to the dated version section.

    :param lines: the CHANGELOG split into physical lines (no trailing newlines).
    :param version: the release tag, e.g. ``v0.3.55``.
    :param date: the release date as ``YYYY-MM-DD``.
    :param repo_url: GitHub base URL for the version heading link.
    :return: the rewritten lines with a fresh empty ``[Unreleased]`` on top.
    :raises SystemExit: if no ``[Unreleased]`` heading exists or the version was
        already cut.
    """
    if any(line.startswith(f"## [{version}]") for line in lines):
        sys.exit(f"error: a '## [{version}]' section already exists -- nothing to cut")

    start = next(
        (i for i, line in enumerate(lines)
         if line.startswith("## ") and "unreleased" in line.lower()),
        None,
    )
    if start is None:
        sys.exit("error: no '## [Unreleased]' heading found in CHANGELOG")

    url = f"{repo_url}/releases/tag/{version}"
    replacement = [
        "## [Unreleased]",
        "",
        f"## [{version}]({url}) - {date}",
    ]
    return lines[:start] + replacement + lines[start + 1:]


def main() -> None:
    """Parse arguments and rewrite CHANGELOG.md with the new release section."""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("version", help="Release tag to cut, e.g. v0.3.55.")
    parser.add_argument("--repo", default=os.getcwd(), help="Repo root. Default: cwd.")
    parser.add_argument("--changelog", default=None,
                        help="Path to CHANGELOG.md. Default: <repo>/CHANGELOG.md.")
    parser.add_argument("--date", default=None,
                        help="Release date YYYY-MM-DD. Default: today (UTC).")
    parser.add_argument("--repo-url", default=None,
                        help="GitHub base URL. Default: derived from 'origin'.")
    args = parser.parse_args()

    if not VERSION_TAG_RE.match(args.version):
        sys.exit(f"error: {args.version!r} is not a vMAJOR.MINOR.PATCH tag")

    repo = os.path.abspath(args.repo)
    changelog = args.changelog or os.path.join(repo, "CHANGELOG.md")
    if not os.path.isfile(changelog):
        sys.exit(f"error: no CHANGELOG.md at {changelog}")
    date = args.date or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    repo_url = args.repo_url or derive_repo_url(repo)

    with open(changelog, encoding="utf-8") as handle:
        text = handle.read()
    if text.startswith("﻿"):
        sys.exit("error: CHANGELOG.md has a UTF-8 BOM; this repo's markdown is BOM-less")

    lines = text.split("\n")
    new_lines = cut(lines, args.version, date, repo_url)
    new_text = "\n".join(new_lines).rstrip("\n") + "\n"

    with open(changelog, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(new_text)
    print(f"cut {args.version} - {date} into {changelog}")


if __name__ == "__main__":
    main()
