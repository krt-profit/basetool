#!/usr/bin/env python3
"""Collect the raw material for release notes from one starting point onward.

This script does the mechanical part of the release-notes workflow so the model
can focus on the editorial part (filtering + translating into friendly German).
It prints two things:

  1. The relevant slice of CHANGELOG.md -- the richest, already-curated
     descriptions. On a reconciled changelog this is the ``## [Unreleased]``
     block plus every dated ``## [vX.Y.Z] - DATE`` section whose tag/date falls
     in the window; on a legacy all-in-``[Unreleased]`` changelog it is just that
     one (huge) block. Either way the git log below defines the real boundary.
  2. The git history in range, bucketed by Conventional-Commit type, so it is
     obvious at a glance which commits are user-facing (feat / fix / perf) and
     which are almost certainly internal (chore / refactor / test / docs / ...).

The starting point may be a DATE (``2026-05-15``), a DATE WITH A TIME
(``"2026-05-15 14:30"`` or ``2026-05-15T14:30``, optionally with seconds), a git
TAG (``v0.3.40``), or a commit SHA. Dates/times are matched with ``git log
--since``; a ref becomes the range ``<ref>..<until>``. A bare date covers the whole
day; add a time to scope from a precise hour and minute. ``--until`` works the same
way (a bare end date stays inclusive through 23:59:59).

If ``--since`` is omitted entirely, the start point is read from the local progress
pointer maintained by ``track_release_notes.py`` (the range becomes
``<stored-commit>..HEAD`` and the matching post-anchor changelog sections are
included). When no pointer exists yet the script exits with guidance rather than
guessing a start point.

Usage:
    python gather_changes.py                        # resume from the local pointer
    python gather_changes.py --since v0.3.40
    python gather_changes.py --since 2026-05-15 --until 2026-05-31
    python gather_changes.py --since "2026-05-15 14:30"
    python gather_changes.py --since 2026-05-15T14:30 --until 2026-05-16T09:00
    python gather_changes.py --since v0.3.40 --repo /path/to/basetool

Note: a date+time contains a space, so quote it in the shell ("2026-05-15 14:30"),
or use the unquoted 'T' form (2026-05-15T14:30).
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

# The changelog is full of umlauts and arrows; Windows consoles default to
# cp1252 and would crash on them. Force UTF-8 so the digest prints anywhere.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):  # already wrapped / not reconfigurable
        pass

# Conventional-commit type -> (bucket label, is this normally user-facing?).
# The buckets mirror the three release-notes sections plus a "probably internal"
# catch-all the model should skim and usually drop.
TYPE_BUCKETS: dict[str, str] = {
    "feat": "NEU (feat) -- new features, usually user-facing",
    "fix": "BEHOBEN (fix) -- bug fixes, keep the ones a user could notice",
    "perf": "PERFORMANCE (perf) -- keep ONLY if the speed-up is perceptible",
    "revert": "REVERT -- check what was rolled back and whether users saw it",
}
INTERNAL_TYPES = {
    "chore", "refactor", "test", "docs", "build", "ci", "style", "deps", "release",
}

# A bare calendar date (2026-05-15) vs. a date carrying an optional hour:minute
# (:second), separated by a space or 'T' (2026-05-15 14:30, 2026-05-15T14:30:00).
# The second form is what lets a caller scope from a precise time of day, not just
# midnight. Both are anchored so a ref like "2026-05-release" is NOT misread.
DATE_ONLY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DATE_TIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(?::\d{2})?$")
# Leading conventional-commit token, e.g. "feat(orders)!:" -> type "feat".
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


# A reconciled version heading: "## [v0.3.41](url) - 2026-06-02". Anchor on the
# trailing " - DATE" (the URL may itself contain dashes, e.g. ".../krt-profit/...",
# so matching up to the first dash would break); the date's own dashes carry no
# surrounding spaces, so " - " before an end-anchored date is unambiguous.
VERSION_HEADER_RE = re.compile(
    r"^## \[v(\d+)\.(\d+)\.(\d+)\].* - (\d{4}-\d{2}-\d{2})\s*$")
UNRELEASED_HEADER_RE = re.compile(r"^## .*unreleased", re.IGNORECASE)
# A bare-or-prefixed semantic version, for turning a --since/--until tag into a key.
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

    The window mirrors the git-log boundary: when ``since`` is a tag, keep
    sections strictly newer than it (and ``<= until`` if ``until`` is also a tag);
    when ``since`` is a date, keep sections dated on/after it (and ``<= until`` if
    ``until`` is a date). When ``since`` is a commit SHA / ref, ``exclude_tags``
    (the release tags already reachable from that commit -- supplied for a resume
    run) drives selection instead: keep every version section whose tag is *not*
    in that set, i.e. the releases cut after the anchor. With no ``exclude_tags``
    a SHA ``since`` keeps only ``[Unreleased]`` and the git log carries the
    boundary -- the original behaviour. On a legacy un-reconciled changelog there
    are no version headings, so the whole ``[Unreleased]`` block (the entire
    history) is returned regardless.
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
        if not match:  # bogus placeholder / non-version section -> skip
            continue
        ver = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
        date = match.group(4)
        if since_ver is not None:
            keep = ver > since_ver and (until_ver is None or ver <= until_ver)
        elif since_date is not None:
            keep = date >= since_date and (until_date is None or date <= until_date)
        elif exclude_tags is not None:  # SHA/ref anchor (resume run): keep releases after it
            keep = (f"v{ver[0]}.{ver[1]}.{ver[2]}" not in exclude_tags
                    and (until_ver is None or ver <= until_ver)
                    and (until_date is None or date <= until_date))
        else:  # bare commit SHA -> rely on the git log for the boundary
            keep = False
        if keep:
            out.append(header)
            out.extend(body)
    return "\n".join(out).strip() or "(no [Unreleased] or in-window version sections)"


def as_git_date(value: str, *, end: bool = False) -> str | None:
    """Normalise a start/end bound into a git timestamp, or return None for a ref.

    ``None`` means ``value`` is not a date — it is a git tag or commit SHA, which the
    caller turns into a ``<ref>..<ref>`` range instead. A bare date snaps to the
    start of its day (or the last second when ``end`` is set, so a ``--until`` date
    stays inclusive). A value that already carries a time is honoured verbatim — the
    ``T`` separator is rewritten to the space git prefers — which is what makes
    scoping from a specific hour and minute work.
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
    if since_bound is not None:  # date or date+time -> time window
        args = base + [f"--since={since_bound}"]
        if until and until != "HEAD":
            until_bound = as_git_date(until, end=True)
            if until_bound is not None:
                args.append(f"--until={until_bound}")
            else:
                print(f"# warning: --until={until!r} ignored "
                      "(a date/time --since needs a date/time --until)", file=sys.stderr)
    else:  # tag or commit SHA -> revision range
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
    """Convert ``YYYY-MM-DD`` (with any trailing time) to the German short ``DD.MM.``.

    Only the leading 10-character date is used, so a ``2026-05-15 14:30`` start still
    yields ``15.05.`` — the optional time refines scoping, not the title.
    """
    parts = value[:10].split("-")
    return f"{parts[2]}.{parts[1]}." if len(parts) == 3 else value


def suggested_title(log: str, since: str) -> str:
    """Build the release-notes H1 ``<h1>Release Notes (DD.MM. -> DD.MM.)</h1>``.

    The left date is the start the user asked for (the ``--since`` date itself when
    it is a date or date+time, otherwise the oldest change actually in range). The
    right date is the newest change in range -- i.e. the last thing the notes cover.
    The title stays date-only even when ``--since`` carries a time.

    Returned wrapped in ``<h1>`` because the notes ship as CKEditor-ready HTML, so
    the printed line can be pasted into the document as-is.
    """
    dates = sorted(line.split("\t")[1] for line in log.splitlines() if line.count("\t") >= 2)
    if not dates:
        return "<h1>Release Notes</h1>"
    left = ddmm(since) if as_git_date(since) is not None else ddmm(dates[0])
    return f"<h1>Release Notes ({left} → {ddmm(dates[-1])})</h1>"


def ddmmyyyy(value: str) -> str:
    """Convert ``YYYY-MM-DD`` (with any trailing time) to the German ``DD.MM.YYYY``.

    Used for the ``Stand`` date in the mandatory version subtitle. Like :func:`ddmm`
    it reads only the leading 10-character date, so a start/end carrying a time still
    yields a clean calendar date.
    """
    parts = value[:10].split("-")
    return f"{parts[2]}.{parts[1]}.{parts[0]}" if len(parts) == 3 else value


def window_end_commit(repo: str, until: str) -> str:
    """Resolve the window's end into a commit-ish for ``git tag --merged``.

    ``until`` may be a ref/SHA (used as-is), ``HEAD``/empty (→ ``HEAD``), or a
    date/date+time -- in which case the last commit up to that moment is resolved, so
    the "current released version" is computed as of the window end, not as of now.
    """
    bound = as_git_date(until, end=True)
    if bound is None:
        return until or "HEAD"
    return run_git(repo, ["rev-list", "-1", f"--until={bound}", "HEAD"]).strip() or "HEAD"


def newest_release_tag(repo: str, until: str) -> str | None:
    """Return the highest ``vN.N.N`` tag reachable at the window end, or ``None``.

    This is the *current released version* the notes belong to -- the newest
    well-formed release tag merged into the window-end commit. Typo tags (``v.0.1``,
    ``v-0.2.3``) are ignored. ``None`` means the repo has no release tag yet.
    """
    out = run_git(repo, ["tag", "--merged", window_end_commit(repo, until)])
    tags = [t for t in out.split() if t.startswith("v") and version_ref_key(t)]
    return max(tags, key=version_ref_key) if tags else None


def suggested_subtitle(repo: str, until: str, log: str) -> str | None:
    """Build the mandatory italic version subtitle, or ``None`` if it cannot.

    Form: ``<p><em>Version <current released version> · Stand <DD.MM.YYYY></em></p>``.
    The version is :func:`newest_release_tag` at the window end; ``Stand`` is the date
    of the last change in range (the same date the title's right bound uses), carrying
    the year. Both inputs must exist -- an empty window or a tag-less repo yields
    ``None`` and the caller falls back to a planned-version hint.

    Like :func:`suggested_title` this comes back as the finished HTML block, so it can
    be pasted straight into the CKEditor-ready document.
    """
    dates = sorted(line.split("\t")[1] for line in log.splitlines() if line.count("\t") >= 2)
    tag = newest_release_tag(repo, until)
    if not dates or not tag:
        return None
    return f"<p><em>Version {tag} · Stand {ddmmyyyy(dates[-1])}</em></p>"


# Path (relative to the repo root) the model copies to advance the pointer; kept
# in sync with the command shown in SKILL.md so the reminder is paste-ready.
TRACK_CMD = ".claude/skills/release-notes/scripts/track_release_notes.py"


def load_state_module():
    """Import the sibling ``release_state`` helper, or return ``None`` if absent.

    The tracker is optional sugar; if its module is missing the explicit
    ``--since`` path must keep working, so this degrades to ``None`` rather than
    raising. Python puts this script's directory on ``sys.path``, so the sibling
    import resolves when run as ``python .../gather_changes.py``.
    """
    try:
        import release_state  # noqa: PLC0415 -- sibling script, same directory
        return release_state
    except ImportError:
        return None


def resolve_since(repo: str, arg_since: str | None, state):
    """Resolve the effective start point and resume context.

    Returns ``(since, resumed, anchor_desc)``. An explicit ``arg_since`` is used
    verbatim (``resumed=False``). With no start point the local tracking pointer
    is read: a healthy pointer yields its commit SHA as ``since`` (``resumed=True``)
    so the run picks up exactly where the last notes ended; a missing or stale
    pointer prints guidance and exits 2, signalling the caller to ask the user.
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
        # Worktrees keep .git as a file, not a dir -- accept either.
        if not os.path.exists(os.path.join(repo, ".git")):
            sys.exit(f"error: {repo} is not a git repository")

    state = load_state_module()
    since, resumed, anchor_desc = resolve_since(repo, args.since, state)

    # A SHA/ref start point (a resume run, or a hand-passed commit) cannot be
    # mapped to a version like a tag/date can; feed select_changelog the set of
    # releases already reachable from it so it keeps only the *newer* sections.
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
    print("OUTPUT FORMAT: CKEditor-ready HTML fragment -- h1/h2/p/ul/li/strong/em only, "
          "no attributes, no wrapper element. See SKILL.md 'Ausgabeformat'.")
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
