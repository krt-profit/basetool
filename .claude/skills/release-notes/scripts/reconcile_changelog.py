#!/usr/bin/env python3
"""Reconcile CHANGELOG.md: move released ``[Unreleased]`` entries under their tag.

Each bullet is blamed line by line; its release is the earliest ``vN.N.N`` tag
containing any of its lines. Bullets in no tag stay in ``[Unreleased]``. Only the
``[Unreleased]`` block is rewritten. Default is a dry-run report; ``--write`` applies.

Usage:
    python reconcile_changelog.py                       # dry-run report, cwd repo
    python reconcile_changelog.py --write               # rewrite CHANGELOG.md
    python reconcile_changelog.py --repo /path/to/basetool --write
    python reconcile_changelog.py --rev HEAD --repo-url https://github.com/krt-profit/basetool
"""

from __future__ import annotations

import argparse
import contextlib
import os
import re
import subprocess
import sys

for _stream in (sys.stdout, sys.stderr):
    with contextlib.suppress(AttributeError, ValueError):
        _stream.reconfigure(encoding="utf-8")

VERSION_TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
PORCELAIN_HEADER_RE = re.compile(r"^(?P<sha>[0-9a-f]{40}) \d+ (?P<final>\d+)")
CANONICAL_ORDER = ["Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"]


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


def version_key(tag: str) -> tuple[int, int, int]:
    """Return the ``(major, minor, patch)`` sort key for a well-formed ``vN.N.N`` tag."""
    match = VERSION_TAG_RE.match(tag)
    if not match:  # pragma: no cover
        raise ValueError(f"not a version tag: {tag!r}")
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def derive_repo_url(repo: str) -> str:
    """Derive the ``https://github.com/<owner>/<repo>`` base from ``origin``.

    Handles HTTPS and SSH remotes; falls back to the project URL without ``origin``.
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


def blame_line_shas(repo: str, rev: str, path: str) -> dict[int, str]:
    """Map each 1-based final line number of ``path`` at ``rev`` to its blame SHA."""
    out = run_git(repo, ["blame", "--line-porcelain", rev, "--", path])
    line_sha: dict[int, str] = {}
    for line in out.splitlines():
        match = PORCELAIN_HEADER_RE.match(line)
        if match:
            line_sha[int(match.group("final"))] = match.group("sha")
    return line_sha


class TagResolver:
    """Resolve a commit SHA to the earliest well-formed release tag containing it, memoised."""

    def __init__(self, repo: str) -> None:
        """Bind the resolver to ``repo`` and start with an empty cache."""
        self._repo = repo
        self._cache: dict[str, str | None] = {}

    def earliest_tag(self, sha: str) -> str | None:
        """Return the earliest ``vN.N.N`` tag containing ``sha``, or ``None``."""
        if sha not in self._cache:
            out = run_git(self._repo, ["tag", "--contains", sha])
            versions = [t for t in out.split() if VERSION_TAG_RE.match(t)]
            self._cache[sha] = min(versions, key=version_key) if versions else None
        return self._cache[sha]


class DateResolver:
    """Resolve a tag to the short committer date of the commit it points at."""

    def __init__(self, repo: str) -> None:
        """Bind the resolver to ``repo`` and start with an empty cache."""
        self._repo = repo
        self._cache: dict[str, str] = {}

    def tag_date(self, tag: str) -> str:
        """Return the ``YYYY-MM-DD`` committer date of ``tag``'s commit."""
        if tag not in self._cache:
            out = run_git(self._repo, ["log", "-1", "--format=%cd", "--date=short", tag])
            self._cache[tag] = out.strip()
        return self._cache[tag]


class Entry:
    """One changelog bullet: its section, its text and its 1-based file lines.

    ``tags`` and ``release`` are filled in after blame.
    """

    def __init__(self, section: str, line_numbers: list[int], text: list[str]) -> None:
        """Capture an entry's section, spanned file lines, and trimmed text."""
        self.section = section
        self.line_numbers = line_numbers
        self.text = text
        self.tags: list[str] = []
        self.release: str | None = None

    def render(self) -> str:
        """Return the entry as a newline-joined markdown block (no trailing newline)."""
        return "\n".join(self.text)


def split_sections(lines: list[str]) -> tuple[list[str], int, int, list[str]]:
    """Split the file into (preamble, unreleased_start, unreleased_end, tail).

    ``unreleased_start`` indexes the ``## [Unreleased]`` header, ``unreleased_end``
    the next ``## `` header or ``len(lines)``.
    """
    start = next(
        (i for i, ln in enumerate(lines)
         if ln.startswith("## ") and "unreleased" in ln.lower()),
        None,
    )
    if start is None:
        sys.exit("error: no '## [Unreleased]' header found in CHANGELOG")
    end = next(
        (i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")),
        len(lines),
    )
    return lines[:start], start, end, lines[end:]


def parse_entries(lines: list[str], start: int, end: int) -> tuple[list[Entry], list[int]]:
    """Parse the ``[Unreleased]`` body into entries; return (entries, anomaly_lines).

    A top-level ``- `` bullet absorbs following lines up to the next bullet or ``#``
    heading; its section is the first word of the preceding ``### `` header.
    ``anomaly_lines`` are 1-based numbers of non-blank lines outside any entry.
    """
    entries: list[Entry] = []
    anomalies: list[int] = []
    section: str | None = None
    i = start + 1
    while i < end:
        line = lines[i]
        if line.startswith("### "):
            section = line[4:].strip().split()[0] if line[4:].strip() else "Misc"
            i += 1
            continue
        if line.startswith("- "):
            block_start = i
            i += 1
            while i < end and not lines[i].startswith("- ") and not lines[i].startswith("#"):
                i += 1
            block = lines[block_start:i]
            line_numbers = [block_start + 1 + off for off, _ in enumerate(block)]
            text = list(block)
            while text and text[-1].strip() == "":
                text.pop()
            entries.append(Entry(section or "Misc", line_numbers, text))
            continue
        if line.strip():
            anomalies.append(i + 1)
        i += 1
    return entries, anomalies


def order_sections(present: list[str]) -> list[str]:
    """Order section names: canonical Keep-a-Changelog order, then extras first-seen."""
    ordered = [s for s in CANONICAL_ORDER if s in present]
    seen = set(ordered)
    for section in present:
        if section not in seen:
            ordered.append(section)
            seen.add(section)
    return ordered


def render_group(entries: list[Entry]) -> list[str]:
    """Render one release's entries as markdown lines, grouped by section in file order."""
    by_section: dict[str, list[Entry]] = {}
    for entry in entries:
        by_section.setdefault(entry.section, []).append(entry)
    out: list[str] = []
    for section in order_sections(list(by_section)):
        out.append(f"### {section}")
        out.append("")
        for entry in by_section[section]:
            out.append(entry.render())
            out.append("")
    return out


def build_changelog(
    preamble: list[str],
    tail: list[str],
    entries: list[Entry],
    dates: DateResolver,
    repo_url: str,
) -> str:
    """Assemble the rewritten CHANGELOG text from its parts.

    Order: ``[Unreleased]``, one ``## [vX.Y.Z] - DATE`` section per release in
    descending version order, then the verbatim ``tail``.
    """
    by_release: dict[str | None, list[Entry]] = {}
    for entry in entries:
        by_release.setdefault(entry.release, []).append(entry)

    out: list[str] = []
    out.extend(ln.rstrip("\n") for ln in preamble)
    while out and out[-1].strip() == "":
        out.pop()
    if out:
        out.append("")

    out.append("## [Unreleased]")
    out.append("")
    if None in by_release:
        out.extend(render_group(by_release[None]))

    released = sorted((t for t in by_release if t is not None), key=version_key, reverse=True)
    for tag in released:
        url = f"{repo_url}/releases/tag/{tag}"
        out.append(f"## [{tag}]({url}) - {dates.tag_date(tag)}")
        out.append("")
        out.extend(render_group(by_release[tag]))

    while out and out[-1].strip() == "":
        out.pop()
    out.append("")
    out.extend(ln.rstrip("\n") for ln in tail)

    return "\n".join(out).rstrip("\n") + "\n"


def print_report(entries: list[Entry], anomalies: list[int], dates: DateResolver) -> None:
    """Print the dry-run digest: per-release counts, multi-tag entries, anomalies."""
    by_release: dict[str | None, list[Entry]] = {}
    for entry in entries:
        by_release.setdefault(entry.release, []).append(entry)

    print("=" * 78)
    print("CHANGELOG RECONCILE -- DRY RUN (no files written; pass --write to apply)")
    print("=" * 78)
    print(f"total entries parsed: {len(entries)}")

    def describe(group: str | None) -> str:
        label = group if group else "[Unreleased] (no containing tag)"
        date = f"  ({dates.tag_date(group)})" if group else ""
        bucket = by_release.get(group, [])
        sections: dict[str, int] = {}
        for entry in bucket:
            sections[entry.section] = sections.get(entry.section, 0) + 1
        order = order_sections(list(sections))
        breakdown = ", ".join(f"{s}:{sections[s]}" for s in order)
        return f"  {label}{date}: {len(bucket)} entries [{breakdown}]"

    print("\n-- entries per release (descending) --")
    print(describe(None))
    for tag in sorted((t for t in by_release if t is not None), key=version_key, reverse=True):
        print(describe(tag))

    multi = [e for e in entries if len(set(e.tags)) > 1]
    print(f"\n-- entries whose lines span >1 release tag: {len(multi)} "
          "(release = earliest; verify these) --")
    for entry in multi[:25]:
        head = entry.text[0][:88] if entry.text else "(empty)"
        print(f"   {entry.release} <= {sorted(set(entry.tags), key=version_key)}  {head}")
    if len(multi) > 25:
        print(f"   ... and {len(multi) - 25} more")

    if anomalies:
        print(f"\n!! {len(anomalies)} non-blank line(s) fell outside any entry "
              f"(lines: {anomalies[:20]}{' ...' if len(anomalies) > 20 else ''})")
        print("   These would be LOST on --write. Inspect before applying.")
    else:
        print("\nOK: every non-blank line in [Unreleased] was captured by an entry.")


def main() -> None:
    """Parse args, map entries to releases, and either report or rewrite the file."""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=os.getcwd(), help="Repo root. Default: cwd.")
    parser.add_argument("--changelog", default=None,
                        help="Path to CHANGELOG.md. Default: <repo>/CHANGELOG.md.")
    parser.add_argument("--rev", default="HEAD",
                        help="Revision to blame the changelog at. Default: HEAD.")
    parser.add_argument("--repo-url", default=None,
                        help="GitHub base URL for version links. Default: derived "
                             "from the 'origin' remote.")
    parser.add_argument("--write", action="store_true",
                        help="Rewrite CHANGELOG.md in place (default is a dry run).")
    args = parser.parse_args()

    repo = os.path.abspath(args.repo)
    if not os.path.exists(os.path.join(repo, ".git")):
        sys.exit(f"error: {repo} is not a git repository")
    changelog = args.changelog or os.path.join(repo, "CHANGELOG.md")
    if not os.path.isfile(changelog):
        sys.exit(f"error: no CHANGELOG.md at {changelog}")
    repo_url = args.repo_url or derive_repo_url(repo)

    with open(changelog, encoding="utf-8") as handle:
        text = handle.read()
    if text.startswith("﻿"):
        sys.exit("error: CHANGELOG.md has a UTF-8 BOM; this repo's markdown is BOM-less")
    lines = text.split("\n")

    preamble, start, end, tail = split_sections(lines)
    entries, anomalies = parse_entries(lines, start, end)

    line_sha = blame_line_shas(repo, args.rev, os.path.relpath(changelog, repo))
    tags = TagResolver(repo)
    dates = DateResolver(repo)
    for entry in entries:
        resolved = []
        for number in entry.line_numbers:
            sha = line_sha.get(number)
            if sha and lines[number - 1].strip():
                resolved.append(tags.earliest_tag(sha))
        entry.tags = [t for t in resolved if t is not None]
        entry.release = min(entry.tags, key=version_key) if entry.tags else None

    print_report(entries, anomalies, dates)

    if not args.write:
        print("\n(dry run -- re-run with --write to rewrite CHANGELOG.md)")
        return
    if anomalies:
        sys.exit("\nrefusing to write: uncaptured non-blank lines would be lost "
                 "(see report above)")

    new_text = build_changelog(preamble, tail, entries, dates, repo_url)
    with open(changelog, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(new_text)
    print(f"\nwrote {changelog} ({len(new_text.splitlines())} lines)")


if __name__ == "__main__":
    main()
