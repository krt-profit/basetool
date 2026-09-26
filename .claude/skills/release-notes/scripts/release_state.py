#!/usr/bin/env python3
"""Local, never-committed pointer for how far release notes have been written.

Imported by ``gather_changes.py`` (:func:`read_state`) and ``track_release_notes.py``
(:func:`write_state`). The pointer is :data:`STATE_FILENAME` in the shared git
directory, falling back to the working-tree root behind a ``.gitignore`` entry; it
records the last covered commit, its date and the newest release tag reachable from it.
"""

from __future__ import annotations

import contextlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

for _stream in (sys.stdout, sys.stderr):
    with contextlib.suppress(AttributeError, ValueError):
        _stream.reconfigure(encoding="utf-8")

STATE_FILENAME = ".release-notes-state.json"
STATE_SCHEMA = 1
GITIGNORE_ENTRY = f"/{STATE_FILENAME}"
GITIGNORE_COMMENT = "### Release-notes local progress tracker (never commit) ###"

VERSION_TAG_RE = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
DATE_ONLY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
DATE_TIME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(?::\d{2})?$")


def run_git(repo: str, args: list[str]) -> str | None:
    """Run ``git <args>`` in ``repo``; return stdout, or ``None`` on any failure.

    Never exits the process, so a non-zero exit can serve as a "no" answer.
    """
    try:
        result = subprocess.run(
            ["git", *args],
            cwd=repo,
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=True,
        )
    except (FileNotFoundError, subprocess.CalledProcessError):
        return None
    return result.stdout


def is_git_repo(repo: str) -> bool:
    """Return whether ``repo`` is a git working tree (``.git`` directory or worktree file)."""
    return os.path.exists(os.path.join(repo, ".git"))


def version_key(tag: str) -> tuple[int, int, int]:
    """Return the ``(major, minor, patch)`` sort key for a ``vN.N.N`` tag."""
    match = VERSION_TAG_RE.match(tag)
    if not match:  # pragma: no cover
        raise ValueError(f"not a version tag: {tag!r}")
    return tuple(int(part) for part in match.groups())  # type: ignore[return-value]


def common_git_dir(repo: str) -> str | None:
    """Return the absolute shared git directory (``--git-common-dir``) for ``repo``, or ``None``.

    The same directory is returned from every worktree. ``None`` when git is absent or
    the path is not a directory.
    """
    out = run_git(repo, ["rev-parse", "--git-common-dir"])
    if not out or not out.strip():
        return None
    path = out.strip()
    if not os.path.isabs(path):
        path = os.path.join(repo, path)
    path = os.path.abspath(path)
    return path if os.path.isdir(path) else None


def state_path(repo: str) -> str:
    """Return the pointer file's path: in :func:`common_git_dir`, else the working-tree root."""
    return os.path.join(common_git_dir(repo) or repo, STATE_FILENAME)


def read_state(repo: str) -> dict | None:
    """Load the pointer file, or ``None`` if it is missing or unreadable (warning on stderr)."""
    path = state_path(repo)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError) as exc:
        print(f"# warning: ignoring unreadable {STATE_FILENAME}: {exc}", file=sys.stderr)
        return None


def commit_exists(repo: str, sha: str) -> bool:
    """Return whether ``sha`` names a commit object that exists in ``repo``."""
    return run_git(repo, ["cat-file", "-e", f"{sha}^{{commit}}"]) is not None


def is_ancestor(repo: str, ancestor: str, descendant: str) -> bool:
    """Return whether ``ancestor`` is an ancestor of (or equal to) ``descendant``; a bad ref is ``False``."""
    return run_git(repo, ["merge-base", "--is-ancestor", ancestor, descendant]) is not None


def resolve_commit(repo: str, ref: str) -> tuple[str, str] | None:
    """Resolve a ref/date into ``(full_sha, YYYY-MM-DD committer date)``, or ``None``.

    A date or date+time selects the last commit up to that moment; a bare date is
    inclusive through 23:59:59.
    """
    if DATE_TIME_RE.match(ref):
        out = run_git(repo, ["log", "-1", f"--until={ref.replace('T', ' ', 1)}",
                             "--format=%H%x09%cd", "--date=short"])
    elif DATE_ONLY_RE.match(ref):
        out = run_git(repo, ["log", "-1", f"--until={ref} 23:59:59",
                             "--format=%H%x09%cd", "--date=short"])
    else:
        out = run_git(repo, ["log", "-1", "--format=%H%x09%cd", "--date=short", ref])
    if not out or not out.strip():
        return None
    sha, _, date = out.strip().partition("\t")
    return (sha.strip(), date.strip()) if sha.strip() else None


def merged_version_tags(repo: str, sha: str) -> set[str]:
    """Return every well-formed ``vN.N.N`` tag reachable from ``sha``."""
    out = run_git(repo, ["tag", "--merged", sha]) or ""
    return {tag for tag in out.split() if VERSION_TAG_RE.match(tag)}


def newest_version_tag(repo: str, sha: str) -> str | None:
    """Return the highest-versioned ``vN.N.N`` tag reachable from ``sha``, or ``None``."""
    tags = merged_version_tags(repo, sha)
    return max(tags, key=version_key) if tags else None


def iso_now() -> str:
    """Return the current UTC time as an ``YYYY-MM-DDTHH:MM:SSZ`` stamp."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def ensure_gitignore(repo: str) -> str:
    """Make sure ``.gitignore`` ignores the pointer file; return what happened.

    Returns ``"present"``, ``"added"`` (appended, creating the file if absent) or
    ``"error: ..."``. Existing content and newline style are preserved.
    """
    path = os.path.join(repo, ".gitignore")
    content = ""
    if os.path.isfile(path):
        try:
            with open(path, encoding="utf-8", newline="") as handle:
                content = handle.read()
        except OSError as exc:
            return f"error: {exc}"
    if any(line.strip() == GITIGNORE_ENTRY for line in content.splitlines()):
        return "present"
    newline = "\r\n" if "\r\n" in content else "\n"
    addition = ""
    if content and not content.endswith(("\n", "\r")):
        addition += newline
    if content:
        addition += newline
    addition += GITIGNORE_COMMENT + newline + GITIGNORE_ENTRY + newline
    try:
        with open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(content + addition)
    except OSError as exc:
        return f"error: {exc}"
    return "added"


def verify_ignored(repo: str) -> bool:
    """Return whether the pointer file is safe from ever being committed.

    Always true inside the shared git directory; otherwise ``git check-ignore -q`` decides.
    """
    if common_git_dir(repo) is not None:
        return True
    return run_git(repo, ["check-ignore", "-q", STATE_FILENAME]) is not None


def write_state(repo: str, *, ref: str, sha: str, date: str, tag: str | None) -> dict:
    """Write the pointer file for ``repo`` and lock it out of git; return a summary.

    ``ref`` is what was marked, ``sha`` its full commit, ``date`` its committer date and
    ``tag`` the newest release reachable from it. In the working-tree-root fallback the
    ``.gitignore`` entry is written first. The summary carries ``path``, ``location``,
    ``gitignore`` and ``ignored``.
    """
    git_dir = common_git_dir(repo)
    in_git_dir = git_dir is not None
    gitignore_status = (
        "n/a (inside the git dir -- outside every working tree)"
        if in_git_dir else ensure_gitignore(repo))
    payload = {
        "schema": STATE_SCHEMA,
        "last_covered": {"ref": ref, "sha": sha, "date": date, "tag": tag},
        "updated_at": iso_now(),
        "note": (
            "Local progress pointer for the release-notes skill, kept in the shared "
            "git dir so it is shared across worktrees and never committed. Records "
            "how far the last release notes went so the next no-argument run resumes "
            "here. Safe to delete; it is never committed."
        ),
    }
    path = os.path.join(git_dir or repo, STATE_FILENAME)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return {
        "path": path,
        "location": "git-common-dir" if in_git_dir else "worktree-root",
        "gitignore": gitignore_status,
        "ignored": verify_ignored(repo),
    }
