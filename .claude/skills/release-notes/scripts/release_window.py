"""Decide which release tags lie inside a release-notes window.

A window runs from a start bound, exclusive, to an end bound, inclusive; each bound is a
ref or a git date. ``gather_changes.py`` prints the CHANGELOG version sections of the
tags :func:`window_version_tags` returns.
"""

from __future__ import annotations

from dataclasses import dataclass

import release_state


@dataclass(frozen=True)
class Bound:
    """One end of a window.

    ``time`` is the last instant the bound covers, in Unix seconds. For a ref bound,
    ``through`` holds the version tags at or before its commit and ``beyond`` those
    strictly after it; both are empty for a date bound.
    """

    time: int
    through: frozenset[str] = frozenset()
    beyond: frozenset[str] = frozenset()

    def covers(self, tag: str, tag_time: int) -> bool:
        """Return whether ``tag``, whose commit time is ``tag_time``, lies at or before the bound.

        Ancestry decides for a tag on the bound's line of history; the commit time decides
        for any other tag, such as one on a rewritten history or a side branch.
        """
        if tag in self.through:
            return True
        if tag in self.beyond:
            return False
        return tag_time <= self.time


def ref_bound(repo: str, ref: str) -> Bound:
    """Build the bound at the commit ``ref`` names.

    :raises ValueError: when ``ref`` names no commit.
    """
    stamp = release_state.run_git(repo, ["log", "-1", "--format=%ct", ref, "--"])
    if not stamp or not stamp.strip():
        raise ValueError(f"{ref!r} names no commit")
    through = release_state.merged_version_tags(repo, ref)
    containing = release_state.run_git(repo, ["tag", "--contains", ref]) or ""
    beyond = {
        tag for tag in containing.split() if release_state.VERSION_TAG_RE.match(tag)
    } - through
    return Bound(int(stamp), frozenset(through), frozenset(beyond))


def moment_bound(repo: str, moment: str, *, inclusive: bool) -> Bound:
    """Build the bound at a git date ``moment`` such as ``2026-05-15 14:30``, read as git reads it.

    An ``inclusive`` bound covers the moment itself, matching ``git log --until``; a
    non-inclusive one covers only the instants before it, matching ``git log --since``.

    :raises ValueError: when git cannot read ``moment``.
    """
    # git rev-parse turns --until=<date> into --min-age=<unix seconds>.
    out = release_state.run_git(repo, ["rev-parse", f"--until={moment}"]) or ""
    prefix, _, seconds = out.strip().partition("=")
    if prefix != "--min-age" or not seconds.isdigit():
        raise ValueError(f"git cannot read the date {moment!r}")
    epoch = int(seconds)
    return Bound(epoch if inclusive else epoch - 1)


def version_tag_times(repo: str) -> dict[str, int]:
    """Return the commit time, in Unix seconds, behind every ``vN.N.N`` tag of ``repo``."""
    out = (
        release_state.run_git(
            repo,
            [
                "for-each-ref",
                "--format=%(refname:lstrip=2)%09%(*committerdate:unix)%09%(committerdate:unix)",
                "refs/tags",
            ],
        )
        or ""
    )
    times: dict[str, int] = {}
    for line in out.splitlines():
        # Annotated tags carry the commit time in the peeled field, lightweight ones directly.
        tag, peeled, direct = line.split("\t")
        stamp = peeled or direct
        if release_state.VERSION_TAG_RE.match(tag) and stamp:
            times[tag] = int(stamp)
    return times


def window_version_tags(repo: str, start: Bound, end: Bound) -> set[str]:
    """Return the ``vN.N.N`` tags of ``repo`` that lie after ``start`` and at or before ``end``."""
    return {
        tag
        for tag, tag_time in version_tag_times(repo).items()
        if not start.covers(tag, tag_time) and end.covers(tag, tag_time)
    }
