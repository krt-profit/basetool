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
import tempfile

import release_state
import release_window

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
    "chore",
    "refactor",
    "test",
    "docs",
    "build",
    "ci",
    "style",
    "deps",
    "release",
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


VERSION_HEADER_RE = re.compile(r"^## \[(v\d+\.\d+\.\d+)\]")
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


def select_changelog(changelog_path: str, window_tags: set[str]) -> str:
    """Return ``[Unreleased]`` plus every version section whose tag is in ``window_tags``."""
    if not os.path.isfile(changelog_path):
        return f"(no CHANGELOG.md found at {changelog_path})"
    with open(changelog_path, encoding="utf-8") as handle:
        lines = handle.read().splitlines()

    out: list[str] = []
    for header, body in iter_level2_blocks(lines):
        match = VERSION_HEADER_RE.match(header)
        if UNRELEASED_HEADER_RE.match(header) or (match and match.group(1) in window_tags):
            out.append(header)
            out.extend(body)
    return "\n".join(out).strip() or "(no [Unreleased] or in-window version sections)"


def unreleased_fragments(repo: str, rev: str) -> str:
    """Return the changelog fragments in ``changelog.d/`` at ``rev``, each under its path.

    These are entries merged but not yet folded into a release section.
    """
    exists = (
        subprocess.run(
            ["git", "cat-file", "-e", f"{rev}:changelog.d"],
            cwd=repo,
            capture_output=True,
            check=False,
        ).returncode
        == 0
    )
    listing = run_git(repo, ["ls-tree", "--name-only", f"{rev}:changelog.d"]) if exists else ""
    out: list[str] = []
    for name in sorted(listing.split()):
        if name == "README.md" or not name.endswith(".md"):
            continue
        out.append(f"--- changelog.d/{name}")
        out.append(run_git(repo, ["show", f"{rev}:changelog.d/{name}"]).strip())
        out.append("")
    return "\n".join(out).strip() or "(no unreleased fragments in changelog.d/)"


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
                print(
                    f"# warning: --until={until!r} ignored "
                    "(a date/time --since needs a date/time --until)",
                    file=sys.stderr,
                )
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


def window_bound(repo: str, value: str, *, end: bool) -> release_window.Bound:
    """Build the window's start bound from ``value``, or with ``end`` its end bound.

    A date or date+time becomes a moment as :func:`as_git_date` normalises it; anything
    else is a ref, empty meaning ``HEAD``.
    """
    moment = as_git_date(value, end=end)
    if moment is None:
        return release_window.ref_bound(repo, value or "HEAD")
    return release_window.moment_bound(repo, moment, inclusive=end)


def tags_in_window(repo: str, since: str, until: str) -> set[str]:
    """Return the version tags after ``since`` and at or before ``until``; exit on a bad bound."""
    try:
        start = window_bound(repo, since, end=False)
        end = window_bound(repo, until, end=True)
    except ValueError as exc:
        sys.exit(f"error: {exc}")
    return release_window.window_version_tags(repo, start, end)


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


def resolve_since(repo: str, arg_since: str | None):
    """Resolve the effective start point and resume context.

    Returns ``(since, resumed, anchor_desc)``. An explicit ``arg_since`` is used
    verbatim; otherwise the tracking pointer's commit is used, and a missing or
    unknown pointer exits 2.
    """
    if arg_since is not None:
        return arg_since, False, None
    covered = (release_state.read_state(repo) or {}).get("last_covered") or {}
    sha = covered.get("sha")
    if not sha:
        sys.stderr.write(
            "no start point given and no local tracking pointer yet "
            f"({release_state.STATE_FILENAME} absent).\n"
            f"  looked in: {release_state.state_path(repo)}\n"
            "  -> Ask the user once for a start point (date or tag) and re-run with "
            "--since,\n"
            f"     then create the pointer after writing the notes:  python {TRACK_CMD} --set\n"
        )
        sys.exit(2)
    if not release_state.commit_exists(repo, sha):
        sys.stderr.write(
            f"the tracking pointer ({release_state.STATE_FILENAME}) points at {sha[:12]}, which is "
            "not in this repository\n  (history rewrite or fresh clone?). Ask the user for "
            f"a start point, re-run with --since,\n  then re-set the pointer:  python {TRACK_CMD} --set\n"
        )
        sys.exit(2)
    anchor = covered.get("tag") or sha[:12]
    return sha, True, f"{anchor} (covered through {covered.get('date', '?')})"


def selftest_git(repo: str, *args: str, date: str | None = None) -> str:
    """Run git in the self-test repository ``repo`` as a throwaway, unsigned identity.

    ``date`` sets the author and committer date of what the command creates.
    """
    env = dict(
        os.environ,
        GIT_AUTHOR_NAME="Selftest",
        GIT_AUTHOR_EMAIL="selftest@example.invalid",
        GIT_COMMITTER_NAME="Selftest",
        GIT_COMMITTER_EMAIL="selftest@example.invalid",
        GIT_CONFIG_NOSYSTEM="1",
        GIT_CONFIG_GLOBAL=os.devnull,
    )
    if date is not None:
        env.update(GIT_AUTHOR_DATE=date, GIT_COMMITTER_DATE=date)
    return subprocess.run(
        ["git", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args],
        cwd=repo,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=True,
    ).stdout


def selftest_write(repo: str, path: str, text: str) -> None:
    """Write ``text`` with LF line endings to ``path`` inside ``repo``, creating its directory."""
    os.makedirs(os.path.dirname(os.path.join(repo, path)), exist_ok=True)
    with open(os.path.join(repo, path), "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def check_fragments(repo: str) -> list[str]:
    """Return the failures of the fragment check in the empty repository ``repo``.

    The fragments at the window end must appear; the README and later fragments must not.
    """
    selftest_git(repo, "init", "-q", "-b", "main")
    selftest_write(repo, "changelog.d/README.md", "# Changelog fragments\n")
    selftest_write(repo, "changelog.d/suche.md", "### Fixed\n\n- **Suche.** Behoben.\n")
    selftest_git(repo, "add", "-A")
    selftest_git(repo, "commit", "-q", "-m", "fix: suche")
    end = selftest_git(repo, "rev-parse", "HEAD").strip()
    selftest_write(repo, "changelog.d/spaeter.md", "### Added\n\n- **Später.** Nach dem Fenster.\n")
    selftest_git(repo, "add", "-A")
    selftest_git(repo, "commit", "-q", "-m", "feat: spaeter")
    shown = unreleased_fragments(repo, end)
    failures = []
    if "changelog.d/suche.md" not in shown or "- **Suche.** Behoben." not in shown:
        failures.append(f"the fragment at the window end is missing:\n{shown}")
    if "README" in shown or "Später" in shown:
        failures.append(f"the README or a later fragment was shown:\n{shown}")
    return failures


WINDOW_SELFTEST_LINES: dict[str, list[tuple[str, str]]] = {
    "old": [("v1.0.0", "2026-01-01T10:00:00"), ("v1.1.0", "2026-01-02T10:00:00")],
    "main": [
        ("v1.2.0", "2026-01-03T10:00:00"),
        ("v1.3.0", "2026-01-04T10:00:00"),
        ("v1.4.0", "2026-01-05T10:00:00"),
        ("v1.5.0", "2026-01-06T10:00:00"),
    ],
    "side": [("v1.3.1", "2026-01-04T12:00:00"), ("v1.5.1", "2026-01-07T10:00:00")],
}
"""Release tags per root line of history: ``old`` is a history that ``main`` rewrote, so no
tag of ``old`` is an ancestor of any ``main`` commit, and ``side`` is unrelated to both."""

WINDOW_SELFTEST_EXPECTED = {"v1.3.0", "v1.3.1", "v1.4.0"}
"""The tags after ``v1.2.0`` and at or before ``v1.4.0``, by ancestry or by commit time."""


def check_window(repo: str) -> list[str]:
    """Return the failures of the window check in the empty repository ``repo``.

    Builds :data:`WINDOW_SELFTEST_LINES` with lightweight tags on ``old`` and ``main`` and
    annotated ones, tagged later than their commits, on ``side``; then the window from
    ``v1.2.0`` to ``v1.4.0``, given as tags, as commits or as dates, must print exactly the
    sections of :data:`WINDOW_SELFTEST_EXPECTED`.
    """
    selftest_git(repo, "init", "-q")
    sections = ["# Changelog", "", "## [Unreleased]", ""]
    for line, releases in WINDOW_SELFTEST_LINES.items():
        selftest_git(repo, "symbolic-ref", "HEAD", f"refs/heads/{line}")
        for tag, date in releases:
            selftest_git(repo, "commit", "-q", "--allow-empty", "-m", tag, date=date)
            if line == "side":
                selftest_git(repo, "tag", "-a", tag, "-m", tag)
            else:
                selftest_git(repo, "tag", tag)
            sections += [f"## [{tag}](https://example.invalid/{tag}) - {date[:10]}", "", f"- {tag}"]
    changelog = os.path.join(repo, "CHANGELOG.md")
    selftest_write(repo, "CHANGELOG.md", "\n".join(sections) + "\n")
    start, end = (
        selftest_git(repo, "rev-parse", f"{tag}^{{commit}}").strip() for tag in ("v1.2.0", "v1.4.0")
    )
    windows = {
        "tags": ("v1.2.0", "v1.4.0"),
        "commits": (start, end),
        "dates": ("2026-01-04", "2026-01-05"),
    }
    failures = []
    for label, (since, until) in windows.items():
        shown = select_changelog(changelog, tags_in_window(repo, since, until))
        printed = {
            match.group(1) for match in map(VERSION_HEADER_RE.match, shown.splitlines()) if match
        }
        if printed != WINDOW_SELFTEST_EXPECTED:
            failures.append(
                f"the window given as {label} printed {sorted(printed)}, "
                f"expected {sorted(WINDOW_SELFTEST_EXPECTED)}"
            )
    return failures


def selftest() -> int:
    """Run the fragment and window checks, each in a fresh temporary repository.

    :return: 0 when every check passed, 1 otherwise.
    """
    failures = []
    for check in (check_fragments, check_window):
        with tempfile.TemporaryDirectory(prefix="gather-selftest-") as repo:
            failures += check(repo)
    for failure in failures:
        print(f"SELFTEST FAIL: {failure}")
    if failures:
        return 1
    print("selftest: ok")
    return 0


def main() -> None:
    """Parse arguments, gather both sources, and print the digest to stdout."""
    if "--selftest" in sys.argv:
        raise SystemExit(selftest())
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--since",
        default=None,
        help="Starting point: a date (YYYY-MM-DD), a date+time "
        '("YYYY-MM-DD HH:MM" or YYYY-MM-DDTHH:MM, seconds '
        "optional), a git tag, or a commit SHA. If omitted, "
        "resume from the local tracking pointer (see "
        "track_release_notes.py).",
    )
    parser.add_argument(
        "--until",
        default="HEAD",
        help="End point: same forms as --since (date / date+time / ref). Default: HEAD / now.",
    )
    parser.add_argument(
        "--repo", default=os.getcwd(), help="Repository root. Default: current directory."
    )
    args = parser.parse_args()

    repo = os.path.abspath(args.repo)
    if not os.path.exists(os.path.join(repo, ".git")):
        sys.exit(f"error: {repo} is not a git repository")

    since, resumed, anchor_desc = resolve_since(repo, args.since)
    log = collect_commits(repo, since, args.until)
    shown_since = f"{since[:12]} [tracked]" if resumed else since

    print("=" * 78)
    print(f"RELEASE-NOTES SOURCE DIGEST   since={shown_since}  until={args.until}")
    print(f"SUGGESTED TITLE (use verbatim as the first HTML block): {suggested_title(log, since)}")
    subtitle = suggested_subtitle(repo, args.until, log)
    if subtitle:
        print(f"SUGGESTED SUBTITLE (mandatory 2nd block, use verbatim): {subtitle}")
    elif log:
        print(
            "SUGGESTED SUBTITLE: no release tag reachable at the window end -- use the "
            "planned next version, e.g. <p><em>Version vX.Y.Z · Stand DD.MM.YYYY</em></p>"
        )
    print(
        "OUTPUT FORMAT: CKEditor-ready HTML fragment for the WoltLab forum post -- "
        "h2/h3/p/ul/li/strong/em/a only, href the only attribute, no wrapper element. "
        "See SKILL.md 'Ausgabeformat'."
    )
    if resumed:
        print(f"RESUMED from local pointer {release_state.STATE_FILENAME}: {anchor_desc}")
    print("=" * 78)

    print("\n### SOURCE 1 -- CHANGELOG.md: [Unreleased] + version sections in range")
    print("# Reconciled changelogs carry dated '## [vX.Y.Z] - DATE' sections; those whose")
    print("#   tag lies after the start and at or before the end are printed below")
    print("#   (richest descriptions). [Unreleased] then holds only not-yet-released")
    print("#   entries. A legacy all-in-[Unreleased] changelog prints whole. Cross-check")
    print("#   the git log for the exact boundary.\n")
    print(
        select_changelog(
            os.path.join(repo, "CHANGELOG.md"), tags_in_window(repo, since, args.until)
        )
    )

    print("\n### SOURCE 1b -- changelog.d/: fragments merged but not yet in a release section")
    print("# Each fragment is one '### <section>' with its items; release-prepare folds them")
    print("#   into the version section. Shown as of the window end.\n")
    print(unreleased_fragments(repo, window_end_commit(repo, args.until)))

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
