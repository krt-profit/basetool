#!/usr/bin/env python3
"""Collect the raw material for release notes from one starting point onward.

Prints the CHANGELOG.md sections in the window ([Unreleased] plus in-window version
sections) and the git log in range, bucketed by Conventional-Commit type.

--since / --until take a date (YYYY-MM-DD, whole day), a date+time
(YYYY-MM-DD HH:MM or YYYY-MM-DDTHH:MM), a tag or a commit SHA. Without --since the
start is read from the pointer kept by track_release_notes.py; exit 2 when there is
none.

Usage:
    python gather_changes.py                        # resume from the local pointer
    python gather_changes.py --since v0.3.40
    python gather_changes.py --since 2026-05-15 --until 2026-05-31
    python gather_changes.py --since "2026-05-15 14:30"
    python gather_changes.py --since 2026-05-15T14:30 --until 2026-05-16T09:00
    python gather_changes.py --since v0.3.40 --repo /path/to/basetool
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

TYPE_BUCKETS: dict[str, str] = {
    "feat": "NEU (feat) -- new features, usually user-facing",
    "fix": "BEHOBEN (fix) -- bug fixes, keep the ones a user could notice",
    "perf": "PERFORMANCE (perf) -- keep ONLY if the speed-up is perceptible",
    "revert": "REVERT -- check what was rolled back and whether users saw it",
}
INTERNAL_TYPES = {
    "chore", "refactor", "test", "docs", "build", "ci", "style", "deps", "release",
}

DATE_ONLY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DATE_TIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(?::\d{2})?$")
CC_RE = re.compile(r"^(?P<type>[a-z]+)(?:\([^)]*\))?!?:", re.IGNORECASE)


def run_git(repo: str, args: list[str]) -> str:
    """Run a git command in ``repo`` and return stdout, or exit with its stderr."""
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
        sys.exit(f"error: git {' '.join(args)} failed:\n{exc.stderr.strip()}")
    return result.stdout


VERSION_HEADER_RE = re.compile(
    r"^## \[v(\d+)\.(\d+)\.(\d+)\].* - (\d{4}-\d{2}-\d{2})\s*$")
UNRELEASED_HEADER_RE = re.compile(r"^## .*unreleased", re.IGNORECASE)
VERSION_REF_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def iter_level2_blocks(lines: list[str]):
    """Yield ``(header_line, body_lines)`` for every ``## `` section, in file order."""
    header: str | None = None
    body: list[str] = []
    for line in lines:
        if line.startswith("## "):
            if header is not None:
                yield header, body
            header, body = line, []
        elif header is not None:
            body.append(line)
    if header is not None:
        yield header, body


def version_ref_key(value: str) -> tuple[int, int, int] | None:
    """Parse ``v0.3.41`` / ``0.3.41`` into a comparable tuple, or ``None``."""
    match = VERSION_REF_RE.match(value)
    return tuple(int(part) for part in match.groups()) if match else None  # type: ignore[return-value]


def select_changelog(changelog_path: str, since: str, until: str,
                     exclude_tags: set[str] | None = None) -> str:
    """Return ``[Unreleased]`` plus every version section inside the window.

    A tag ``since`` keeps strictly newer versions, a date ``since`` keeps sections
    dated on or after it; ``until`` bounds both the same way. For a commit ``since``,
    sections whose tag is not in ``exclude_tags`` are kept; without ``exclude_tags``
    only ``[Unreleased]`` is returned.
    """
    if not os.path.isfile(changelog_path):
        return f"(no CHANGELOG.md found at {changelog_path})"
    with open(changelog_path, encoding="utf-8") as handle:
        lines = handle.read().splitlines()

    since_ver = version_ref_key(since)
    until_ver = version_ref_key(until) if until and until != "HEAD" else None
    since_date = since.replace("T", " ")[:10] if (
        DATE_ONLY_RE.match(since) or DATE_TIME_RE.match(since)) else None
    until_date = until.replace("T", " ")[:10] if until and (
        DATE_ONLY_RE.match(until) or DATE_TIME_RE.match(until)) else None

    out: list[str] = []
    for header, body in iter_level2_blocks(lines):
        if UNRELEASED_HEADER_RE.match(header):
            out.append(header)
            out.extend(body)
            continue
        match = VERSION_HEADER_RE.match(header)
        if not match:
            continue
        ver = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
        date = match.group(4)
        if since_ver is not None:
            keep = ver > since_ver and (until_ver is None or ver <= until_ver)
        elif since_date is not None:
            keep = date >= since_date and (until_date is None or date <= until_date)
        elif exclude_tags is not None:
            keep = (f"v{ver[0]}.{ver[1]}.{ver[2]}" not in exclude_tags
                    and (until_ver is None or ver <= until_ver)
                    and (until_date is None or date <= until_date))
        else:
            keep = False
        if keep:
            out.append(header)
            out.extend(body)
    return "\n".join(out).strip() or "(no [Unreleased] or in-window version sections)"


def as_git_date(value: str, *, end: bool = False) -> str | None:
    """Normalise a start/end bound into a git timestamp, or return None for a ref.

    A bare date snaps to 00:00:00, or to 23:59:59 when ``end`` is set; a date+time is
    kept with ``T`` replaced by a space.
    """
    if DATE_TIME_RE.match(value):
        return value.replace("T", " ", 1)
    if DATE_ONLY_RE.match(value):
        return f"{value} 23:59:59" if end else f"{value} 00:00:00"
    return None


def collect_commits(repo: str, since: str, until: str) -> str:
    """Return the git log in range as tab-separated ``sha<TAB>date<TAB>subject``."""
    fmt = "%h%x09%ad%x09%s"
    base = ["log", "--no-merges", "--date=short", f"--pretty=format:{fmt}"]
    since_bound = as_git_date(since)
    if since_bound is not None:
        args = base + [f"--since={since_bound}"]
        if until and until != "HEAD":
            until_bound = as_git_date(until, end=True)
            if until_bound is not None:
                args.append(f"--until={until_bound}")
            else:
                print(f"# warning: --until={until!r} ignored "
                      "(a date/time --since needs a date/time --until)", file=sys.stderr)
    else:
        args = base + [f"{since}..{until or 'HEAD'}"]
    return run_git(repo, args).strip()


def bucket_commits(log: str) -> dict[str, list[str]]:
    """Group raw log lines into release-notes buckets by conventional-commit type."""
    buckets: dict[str, list[str]] = {label: [] for label in TYPE_BUCKETS.values()}
    buckets["PROBABLY INTERNAL -- skim, usually drop"] = []
    buckets["UNTYPED -- no conventional prefix, check each"] = []
    for line in log.splitlines():
        parts = line.split("\t", 2)
        subject = parts[2] if len(parts) == 3 else line
        match = CC_RE.match(subject)
        if not match:
            buckets["UNTYPED -- no conventional prefix, check each"].append(line)
            continue
        ctype = match.group("type").lower()
        if ctype in TYPE_BUCKETS:
            buckets[TYPE_BUCKETS[ctype]].append(line)
        elif ctype in INTERNAL_TYPES:
            buckets["PROBABLY INTERNAL -- skim, usually drop"].append(line)
        else:
            buckets["UNTYPED -- no conventional prefix, check each"].append(line)
    return buckets


def ddmm(value: str) -> str:
    """Convert ``YYYY-MM-DD`` (any trailing time ignored) to the German short ``DD.MM.``."""
    parts = value[:10].split("-")
    return f"{parts[2]}.{parts[1]}." if len(parts) == 3 else value


def suggested_title(log: str, since: str) -> str:
    """Build the release-notes title ``<h2>Release Notes (DD.MM. -> DD.MM.)</h2>``.

    The left date is the ``since`` date, or the oldest change when ``since`` is a ref;
    the right date is the newest change in range.
    """
    dates = sorted(line.split("\t")[1] for line in log.splitlines() if line.count("\t") >= 2)
    if not dates:
        return "<h2>Release Notes</h2>"
    left = ddmm(since) if as_git_date(since) is not None else ddmm(dates[0])
    return f"<h2>Release Notes ({left} → {ddmm(dates[-1])})</h2>"


def ddmmyyyy(value: str) -> str:
    """Convert ``YYYY-MM-DD`` (any trailing time ignored) to the German ``DD.MM.YYYY``."""
    parts = value[:10].split("-")
    return f"{parts[2]}.{parts[1]}.{parts[0]}" if len(parts) == 3 else value


def window_end_commit(repo: str, until: str) -> str:
    """Resolve the window's end into a commit-ish for ``git tag --merged``.

    A ref is used as-is, empty means ``HEAD``, and a date resolves to the last commit
    up to that moment.
    """
    bound = as_git_date(until, end=True)
    if bound is None:
        return until or "HEAD"
    return run_git(repo, ["rev-list", "-1", f"--until={bound}", "HEAD"]).strip() or "HEAD"


def newest_release_tag(repo: str, until: str) -> str | None:
    """Return the highest well-formed ``vN.N.N`` tag reachable at the window end, or ``None``."""
    out = run_git(repo, ["tag", "--merged", window_end_commit(repo, until)])
    tags = [t for t in out.split() if t.startswith("v") and version_ref_key(t)]
    return max(tags, key=version_ref_key) if tags else None


def suggested_subtitle(repo: str, until: str, log: str) -> str | None:
    """Build the mandatory italic version subtitle, or ``None`` if it cannot.

    Form: ``<p><em>Version <tag> · Stand <DD.MM.YYYY></em></p>``, with the newest
    release tag and the date of the last change in range. ``None`` for an empty window
    or a repository without release tags.
    """
    dates = sorted(line.split("\t")[1] for line in log.splitlines() if line.count("\t") >= 2)
    tag = newest_release_tag(repo, until)
    if not dates or not tag:
        return None
    return f"<p><em>Version {tag} · Stand {ddmmyyyy(dates[-1])}</em></p>"


TRACK_CMD = ".claude/skills/release-notes/scripts/track_release_notes.py"


def load_state_module():
    """Import the sibling ``release_state`` helper, or return ``None`` if absent."""
    try:
        import release_state  # noqa: PLC0415
        return release_state
    except ImportError:
        return None


def resolve_since(repo: str, arg_since: str | None, state):
    """Resolve the effective start point and resume context.

    Returns ``(since, resumed, anchor_desc)``. An explicit ``arg_since`` is used
    verbatim; otherwise the tracking pointer's commit is used, and a missing or
    unknown pointer exits 2.
    """
    if arg_since is not None:
        return arg_since, False, None
    if state is None:
        sys.exit("error: no --since given and the release_state.py tracking helper "
                 "is missing next to this script.")
    covered = (state.read_state(repo) or {}).get("last_covered") or {}
    sha = covered.get("sha")
    if not sha:
        sys.stderr.write(
            "no start point given and no local tracking pointer yet "
            f"({state.STATE_FILENAME} absent).\n"
            f"  looked in: {state.state_path(repo)}\n"
            "  -> Ask the user once for a start point (date or tag) and re-run with "
            "--since,\n"
            f"     then create the pointer after writing the notes:  python {TRACK_CMD} --set\n")
        sys.exit(2)
    if not state.commit_exists(repo, sha):
        sys.stderr.write(
            f"the tracking pointer ({state.STATE_FILENAME}) points at {sha[:12]}, which is "
            "not in this repository\n  (history rewrite or fresh clone?). Ask the user for "
            f"a start point, re-run with --since,\n  then re-set the pointer:  python {TRACK_CMD} --set\n")
        sys.exit(2)
    anchor = covered.get("tag") or sha[:12]
    return sha, True, f"{anchor} (covered through {covered.get('date', '?')})"


def main() -> None:
    """Parse arguments, gather both sources, and print the digest to stdout."""
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--since", default=None,
                        help="Starting point: a date (YYYY-MM-DD), a date+time "
                             "(\"YYYY-MM-DD HH:MM\" or YYYY-MM-DDTHH:MM, seconds "
                             "optional), a git tag, or a commit SHA. If omitted, "
                             "resume from the local tracking pointer (see "
                             "track_release_notes.py).")
    parser.add_argument("--until", default="HEAD",
                        help="End point: same forms as --since (date / date+time / "
                             "ref). Default: HEAD / now.")
    parser.add_argument("--repo", default=os.getcwd(),
                        help="Repository root. Default: current directory.")
    args = parser.parse_args()

    repo = os.path.abspath(args.repo)
    if not os.path.isdir(os.path.join(repo, ".git")):
        if not os.path.exists(os.path.join(repo, ".git")):
            sys.exit(f"error: {repo} is not a git repository")

    state = load_state_module()
    since, resumed, anchor_desc = resolve_since(repo, args.since, state)

    since_is_ref = as_git_date(since) is None and version_ref_key(since) is None
    exclude_tags = None
    if since_is_ref and state is not None and state.commit_exists(repo, since):
        exclude_tags = state.merged_version_tags(repo, since)

    log = collect_commits(repo, since, args.until)
    shown_since = f"{since[:12]} [tracked]" if resumed else since

    print("=" * 78)
    print(f"RELEASE-NOTES SOURCE DIGEST   since={shown_since}  until={args.until}")
    print(f"SUGGESTED TITLE (use verbatim as the first HTML block): {suggested_title(log, since)}")
    subtitle = suggested_subtitle(repo, args.until, log)
    if subtitle:
        print(f"SUGGESTED SUBTITLE (mandatory 2nd block, use verbatim): {subtitle}")
    elif log:
        print("SUGGESTED SUBTITLE: no release tag reachable at the window end -- use the "
              "planned next version, e.g. <p><em>Version vX.Y.Z · Stand DD.MM.YYYY</em></p>")
    print("OUTPUT FORMAT: CKEditor-ready HTML fragment for the WoltLab forum post -- "
          "h2/h3/p/ul/li/strong/em/a only, href the only attribute, no wrapper element. "
          "See SKILL.md 'Ausgabeformat'.")
    if resumed:
        print(f"RESUMED from local pointer {state.STATE_FILENAME}: {anchor_desc}")
    print("=" * 78)

    print("\n### SOURCE 1 -- CHANGELOG.md: [Unreleased] + version sections in range")
    print("# Reconciled changelogs carry dated '## [vX.Y.Z] - DATE' sections; the")
    print("#   in-window ones are printed below (richest descriptions). [Unreleased]")
    print("#   then holds only not-yet-released entries. A legacy all-in-[Unreleased]")
    print("#   changelog prints whole. Cross-check the git log for the exact boundary.\n")
    print(select_changelog(os.path.join(repo, "CHANGELOG.md"), since, args.until, exclude_tags))

    print("\n\n### SOURCE 2 -- git log in range, bucketed by Conventional-Commit type")
    print("# feat/fix/perf are the user-facing candidates; the internal bucket is")
    print("# almost always dropped from user release notes.\n")
    if not log:
        if resumed:
            print(f"(no commits since the tracking pointer {anchor_desc} -- nothing new to write)")
        else:
            print("(no commits in range -- check the --since value)")
        return
    for label, entries in bucket_commits(log).items():
        print(f"\n-- {label} [{len(entries)}]")
        for entry in entries:
            print(f"   {entry}")

    print("\n" + "-" * 78)
    print("REMINDER: once the notes are written (and the changelog reconciled), advance the")
    print("local tracking pointer (kept in the shared git dir, never committed) so the next")
    print("no-argument run resumes from here:")
    print(f"   python {TRACK_CMD} --set")


if __name__ == "__main__":
    main()
