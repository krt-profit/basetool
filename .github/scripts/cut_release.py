#!/usr/bin/env python3
"""Cut the ``[Unreleased]`` block into a dated ``## [vX.Y.Z]`` release section.

Renames ``## [Unreleased]`` to ``## [vX.Y.Z](<repo>/releases/tag/vX.Y.Z) - YYYY-MM-DD``
and inserts a fresh empty ``## [Unreleased]`` above it; no tag needs to exist. Then folds
every fragment of ``changelog.d/`` into its section, or into the older tag's section that
already ships the fragment's commit, and deletes the fragments (REQ-OPS-051). Refuses to
run when the version section already exists or a fragment is malformed.

Usage:
    cut_release.py v0.3.55                       # cut, dated today, repo cwd
    cut_release.py v0.3.55 --date 2026-06-04
    cut_release.py v0.3.55 --repo /path --repo-url https://github.com/krt-profit/basetool
    cut_release.py --selftest
"""

from __future__ import annotations

import argparse
import datetime
import os
import re
import subprocess
import sys

from check_changelog_fragments import FRAGMENT_DIR, SECTIONS, Fragment, TempRepo, load_fragments

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


def fold(lines: list[str], version: str, additions: dict[str, list[tuple[str, ...]]]) -> list[str]:
    """Return ``lines`` with ``additions`` appended to the sections of the ``version`` block.

    Items join their ``### <section>`` after the entries already there; a missing section is
    created in the order of :data:`SECTIONS`.

    :param lines: the CHANGELOG split into physical lines.
    :param version: the tag whose ``## [<version>]`` block receives the items.
    :param additions: list items per section, each item as its physical lines.
    :return: the rewritten lines.
    """
    start = next(i for i, line in enumerate(lines) if line.startswith(f"## [{version}]"))
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    preamble: list[str] = []
    blocks: list[tuple[str, list[str]]] = []
    for line in lines[start + 1:end]:
        if line.startswith("### "):
            blocks.append((line, []))
        elif blocks:
            blocks[-1][1].append(line)
        else:
            preamble.append(line)

    for section in SECTIONS:
        items = additions.get(section)
        if not items:
            continue
        heading = f"### {section}"
        index = next((i for i, (head, _) in enumerate(blocks) if head.strip() == heading), None)
        if index is None:
            rank = SECTIONS.index(section)
            index = next((i for i, (head, _) in enumerate(blocks)
                          if head[4:].strip() in SECTIONS and SECTIONS.index(head[4:].strip()) > rank),
                         len(blocks))
            blocks.insert(index, (heading, []))
        body = _trim(blocks[index][1])
        for item in items:
            body.extend(([""] if body else []) + list(item))
        blocks[index] = (blocks[index][0], body)

    section_lines = _trim(preamble)
    for head, body in blocks:
        section_lines.extend(([""] if section_lines else []) + [head, ""] + _trim(body))
    tail = [""] if end < len(lines) else []
    return lines[:start + 1] + [""] + section_lines + tail + lines[end:]


def _trim(lines: list[str]) -> list[str]:
    """Return ``lines`` without leading and trailing blank lines."""
    first = next((i for i, line in enumerate(lines) if line.strip()), len(lines))
    last = next((i for i in range(len(lines) - 1, -1, -1) if lines[i].strip()), -1)
    return list(lines[first:last + 1])


def attribute(repo: str, fragments: list[Fragment]) -> list[tuple[Fragment, str | None, tuple]]:
    """Find for each fragment the earliest release tag that already ships it.

    :param repo: the repository root.
    :param fragments: the parsed fragments.
    :return: ``(fragment, tag or None, sort key)`` in merge order; ``None`` means unreleased.
    """
    attributed = []
    for fragment in fragments:
        path = f"{FRAGMENT_DIR}/{fragment.name}"
        added = run_git(repo, ["log", "-1", "--diff-filter=A", "--format=%H %ct", "--", path]).split()
        if not added:
            attributed.append((fragment, None, (float("inf"), fragment.name)))
            continue
        sha, timestamp = added
        tags = [tag for tag in run_git(repo, ["tag", "--contains", sha]).split()
                if VERSION_TAG_RE.match(tag)]
        earliest = min(tags, key=lambda tag: tuple(int(p) for p in tag[1:].split("."))) if tags else None
        attributed.append((fragment, earliest, (int(timestamp), fragment.name)))
    return sorted(attributed, key=lambda entry: entry[2])


HEAD_V100 ="## [v1.0.0](https://github.com/krt-profit/basetool/releases/tag/v1.0.0) - 2026-09-01"
HEAD_V110 = "## [v1.1.0](https://github.com/krt-profit/basetool/releases/tag/v1.1.0) - 2026-10-02"
BASE = f"# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- **Alt.** Noch offen.\n\n{HEAD_V100}\n\n" \
       "### Added\n\n- **Erstes Release.**\n"
CUT_ARGS = ("v1.1.0", "--date", "2026-10-02", "--repo-url", "https://github.com/krt-profit/basetool")


def _case_folds_and_deletes(repo) -> str | None:
    """Fragments join the new section per section after the old entries, then are deleted."""
    repo.write("changelog.d/README.md", "# Changelog fragments\n")
    repo.write("changelog.d/suche.md", "### Fixed\n\n- **Suche.** Leerer Filter behoben.\n")
    repo.commit("fix: suche", "2026-10-01T10:00:00+00:00")
    repo.write("changelog.d/hangar.md", "### Added\n\n- **Hangar.** Import\n  aus der Erweiterung.\n")
    repo.commit("feat: hangar", "2026-10-01T11:00:00+00:00")
    code, out = repo.run(SELF, *CUT_ARGS, "--repo", repo.path)
    expected = (
        f"# Changelog\n\n## [Unreleased]\n\n{HEAD_V110}\n\n"
        "### Added\n\n- **Hangar.** Import\n  aus der Erweiterung.\n\n"
        "### Fixed\n\n- **Alt.** Noch offen.\n\n- **Suche.** Leerer Filter behoben.\n\n"
        f"{HEAD_V100}\n\n### Added\n\n- **Erstes Release.**\n"
    )
    if code != 0:
        return f"expected exit 0, got {code}: {out}"
    if repo.read("CHANGELOG.md") != expected:
        return f"unexpected CHANGELOG.md:\n{repo.read('CHANGELOG.md')}"
    if repo.exists("changelog.d/suche.md") or repo.exists("changelog.d/hangar.md"):
        return "the folded fragments were not deleted"
    if not repo.exists("changelog.d/README.md"):
        return "the README was deleted"
    return None


HEAD_V101 = "## [v1.0.1](https://github.com/krt-profit/basetool/releases/tag/v1.0.1) - 2026-09-20"


def _case_already_shipped(repo) -> str | None:
    """A fragment whose commit is already in an older tag joins that tag's section."""
    repo.write("CHANGELOG.md", f"# Changelog\n\n## [Unreleased]\n\n{HEAD_V101}\n\n"
                               f"### Added\n\n- **Punkt.**\n\n{HEAD_V100}\n\n### Added\n\n- **Erstes Release.**\n")
    repo.write("changelog.d/nachzug.md", "### Fixed\n\n- **Nachzug.** Im Release-Fenster gemergt.\n")
    repo.commit("fix: nachzug", "2026-09-20T10:00:00+00:00")
    repo.git("tag", "v1.0.1")
    repo.write("changelog.d/neu.md", "### Fixed\n\n- **Neu.** Danach gemergt.\n")
    repo.commit("fix: neu", "2026-09-21T10:00:00+00:00")
    code, out = repo.run(SELF, *CUT_ARGS, "--repo", repo.path)
    expected = (
        f"# Changelog\n\n## [Unreleased]\n\n{HEAD_V110}\n\n### Fixed\n\n- **Neu.** Danach gemergt.\n\n"
        f"{HEAD_V101}\n\n### Added\n\n- **Punkt.**\n\n### Fixed\n\n- **Nachzug.** Im Release-Fenster gemergt.\n\n"
        f"{HEAD_V100}\n\n### Added\n\n- **Erstes Release.**\n"
    )
    if code != 0:
        return f"expected exit 0, got {code}: {out}"
    if repo.read("CHANGELOG.md") != expected:
        return f"unexpected CHANGELOG.md:\n{repo.read('CHANGELOG.md')}"
    return None


def _case_invalid_changes_nothing(repo) -> str | None:
    """An invalid fragment stops the cut before CHANGELOG.md or any fragment changes."""
    repo.write("changelog.d/gut.md", "### Fixed\n\n- **Gut.**\n")
    repo.write("changelog.d/kaputt.md", "### Bugfixes\n\n- **Kaputt.**\n")
    repo.commit("fix: zwei")
    code, out = repo.run(SELF, *CUT_ARGS, "--repo", repo.path)
    if code == 0 or "changelog.d/kaputt.md" not in out:
        return f"expected a failure naming changelog.d/kaputt.md, got {code}: {out}"
    if repo.read("CHANGELOG.md") != BASE or not repo.exists("changelog.d/gut.md"):
        return "the failed cut changed CHANGELOG.md or deleted a fragment"
    return None


def _case_without_fragments(repo) -> str | None:
    """Without fragments the cut only renames [Unreleased], as before."""
    code, out = repo.run(SELF, *CUT_ARGS, "--repo", repo.path)
    expected = BASE.replace("## [Unreleased]\n\n", f"## [Unreleased]\n\n{HEAD_V110}\n\n")
    if code != 0:
        return f"expected exit 0, got {code}: {out}"
    if repo.read("CHANGELOG.md") != expected:
        return f"unexpected CHANGELOG.md:\n{repo.read('CHANGELOG.md')}"
    return None


def _case_uncommitted_fragment(repo) -> str | None:
    """A fragment not yet committed goes into the new section."""
    repo.write("changelog.d/lokal.md", "### Security\n\n- **Lokal.**\n")
    code, out = repo.run(SELF, *CUT_ARGS, "--repo", repo.path)
    if code != 0 or "### Security\n\n- **Lokal.**\n\n" + HEAD_V100 not in repo.read("CHANGELOG.md"):
        return f"expected the item in the new section, got {code}: {out}\n{repo.read('CHANGELOG.md')}"
    return None


SELF = os.path.abspath(__file__)
CASES = [
    _case_folds_and_deletes,
    _case_already_shipped,
    _case_invalid_changes_nothing,
    _case_without_fragments,
    _case_uncommitted_fragment,
]


def selftest() -> int:
    """Run every self-test case against its own throwaway repository.

    :return: 0 when every case passed, 1 otherwise.
    """
    failures = 0
    for case in CASES:
        repo = TempRepo()
        try:
            repo.write("CHANGELOG.md", BASE)
            repo.commit("chore: base", "2026-09-01T12:00:00+00:00")
            repo.git("tag", "v1.0.0")
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


def main() -> None:
    """Parse arguments and rewrite CHANGELOG.md with the new release section."""
    if "--selftest" in sys.argv:
        raise SystemExit(selftest())
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

    fragments, findings = load_fragments(repo)
    if findings:
        for finding in findings:
            print(f"::error::{finding}", file=sys.stderr)
        sys.exit("error: fix the changelog fragments first; nothing was changed")

    lines = text.split("\n")
    new_lines = cut(lines, args.version, date, repo_url)
    targets: dict[str, dict[str, list[tuple[str, ...]]]] = {}
    for fragment, tag, _ in attribute(repo, fragments):
        target = tag if tag and any(line.startswith(f"## [{tag}]") for line in new_lines) else args.version
        if target != args.version:
            print(f"{FRAGMENT_DIR}/{fragment.name} already shipped with {target}; folded there")
        targets.setdefault(target, {}).setdefault(fragment.section, []).extend(fragment.items)
    for target, additions in targets.items():
        new_lines = fold(new_lines, target, additions)
    new_text = "\n".join(new_lines).rstrip("\n") + "\n"

    with open(changelog, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(new_text)
    for fragment in fragments:
        os.remove(os.path.join(repo, FRAGMENT_DIR, fragment.name))
    print(f"cut {args.version} - {date} into {changelog}, folded {len(fragments)} fragment(s)")


if __name__ == "__main__":
    main()
