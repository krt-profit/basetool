#!/usr/bin/env python3
"""Advance (or show) the local release-notes progress pointer.

Run after writing release notes so the next no-argument run resumes here. The pointer
only moves forward unless ``--force`` is given. Exit codes: 1 unresolvable ref,
3 refused rewind.

Usage:
    python track_release_notes.py --show              # print the current pointer
    python track_release_notes.py --set               # advance to HEAD (after writing notes)
    python track_release_notes.py --set v0.3.57       # advance to a specific tag/commit
    python track_release_notes.py --set 2026-06-04    # advance to the last commit that day
    python track_release_notes.py --set HEAD --force  # move even if it would rewind
    python track_release_notes.py --repo /path/to/basetool --set
"""

from __future__ import annotations

import argparse
import os
import sys

import release_state as st


def show(repo: str) -> None:
    """Print the current pointer for ``repo`` in a human-readable form."""
    state = st.read_state(repo)
    covered = (state or {}).get("last_covered") or {}
    if not covered.get("sha"):
        print(f"No release-notes pointer yet ({st.STATE_FILENAME} absent).")
        print(f"  looked in : {st.state_path(repo)}")
        print("The next no-argument /release-notes run will ask for a start point;")
        print("running --set after writing those notes creates the pointer.")
        return
    tag = covered.get("tag") or "(no release tag yet)"
    print(f"Release-notes pointer ({st.STATE_FILENAME}):")
    print(f"  file            : {st.state_path(repo)}")
    print(f"  covered through : {covered.get('sha', '?')[:12]}  ({covered.get('date', '?')})")
    print(f"  newest release  : {tag}")
    print(f"  marked as       : {covered.get('ref', '?')}")
    print(f"  updated at      : {(state or {}).get('updated_at', '?')}")
    print(f"  commit-safe now : {'yes' if st.verify_ignored(repo) else 'NO -- run --set to fix'}")


def do_set(repo: str, ref: str, force: bool) -> int:
    """Resolve ``ref``, advance the pointer if allowed, and report; return an exit code.

    Returns 0 on a successful write, 1 if ``ref`` cannot be resolved to a commit,
    and 3 if the move would rewind the pointer and ``--force`` was not given.
    """
    resolved = st.resolve_commit(repo, ref)
    if not resolved:
        print(f"error: could not resolve {ref!r} to a commit "
              "(unknown tag/commit, or a date before the first commit).", file=sys.stderr)
        return 1
    sha, date = resolved

    state = st.read_state(repo)
    old_sha = ((state or {}).get("last_covered") or {}).get("sha")
    if old_sha and old_sha != sha and not force:
        if st.commit_exists(repo, old_sha) and not st.is_ancestor(repo, old_sha, sha):
            print(f"refusing to move the pointer: {sha[:12]} ({date}) is not ahead of the "
                  f"current pointer {old_sha[:12]}.", file=sys.stderr)
            print("This would make the next resume re-emit already-covered changes. "
                  "Pass --force to override (e.g. after an intentional history rewrite).",
                  file=sys.stderr)
            return 3

    tag = st.newest_version_tag(repo, sha)
    result = st.write_state(repo, ref=ref, sha=sha, date=date, tag=tag)

    where = tag or f"{sha[:12]} ({date})"
    print(f"Release-notes pointer set: notes now tracked as covered through {where}.")
    print(f"  file        : {result['path']}")
    if result["location"] == "git-common-dir":
        print("  storage     : shared git dir (git rev-parse --git-common-dir) -- "
              "shared by every worktree, never committed")
    else:
        print(f"  .gitignore  : {result['gitignore']} (entry {st.GITIGNORE_ENTRY})")
    if result["ignored"]:
        print("  commit-safe : yes -- the pointer cannot be committed.")
    else:
        print("  commit-safe : WARNING -- not protected from commits; check .gitignore.",
              file=sys.stderr)
    return 0


def main() -> None:
    """Parse arguments and either show or advance the pointer."""
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", default=os.getcwd(),
                        help="Repository root. Default: current directory.")
    parser.add_argument("--set", nargs="?", const="HEAD", default=None, metavar="REF",
                        help="Advance the pointer to REF (a tag, commit, or date). "
                             "Bare --set means HEAD. Run this after writing the notes.")
    parser.add_argument("--show", action="store_true",
                        help="Print the current pointer and exit (the default action).")
    parser.add_argument("--force", action="store_true",
                        help="Allow --set to move the pointer even if it would rewind.")
    args = parser.parse_args()

    repo = os.path.abspath(args.repo)
    if not st.is_git_repo(repo):
        sys.exit(f"error: {repo} is not a git repository")

    if args.set is not None:
        code = do_set(repo, args.set, args.force)
        if code == 0:
            print()
            show(repo)
        sys.exit(code)
    show(repo)


if __name__ == "__main__":
    main()
