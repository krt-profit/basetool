#!/usr/bin/env bash
#
# Ensure the DAS KARTELL design-system git submodule is checked out in the
# current working tree. Portable POSIX/bash counterpart to
# ensure-design-system.ps1 for Linux / macOS / CI (see that file for the full
# rationale).
#
# `git worktree add` (used by the Claude Code harness for isolated worktrees
# under .claude/worktrees/) does NOT populate submodules, so in a worktree
# session the design system at .claude/skills/das-kartell-design is empty and
# CLAUDE.md's "visual source of truth" README is unreadable — the design system
# silently gets ignored.
#
# Wired as a SessionStart hook (.claude/settings.json), this materialises the
# submodule when missing:
#   1. offline-first via `git submodule update --init`, reusing the
#      superproject's existing module object store;
#   2. as a fallback, by copying the already-populated submodule from the
#      main worktree.
#
# Best-effort and NEVER fails the session (always exits 0).

sub=".claude/skills/das-kartell-design"

# Resolve the current working tree's root; bail quietly if not in a repo.
root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
[ -n "$root" ] || exit 0
[ -f "$root/$sub/README.md" ] && exit 0   # already populated

cd "$root" || exit 0

# 1) Offline-first: reuse the superproject's existing module object store.
git submodule update --init "$sub" >/dev/null 2>&1
[ -f "$root/$sub/README.md" ] && exit 0

# 2) Fallback: copy from the main worktree (first entry of `worktree list`).
#    substr($0, 10) strips the leading "worktree " (9 chars) so paths with
#    spaces survive intact.
main="$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0, 10); exit}')"
if [ -n "${main:-}" ] && [ -f "$main/$sub/README.md" ]; then
  mkdir -p "$sub"
  cp -R "$main/$sub/." "$sub/" 2>/dev/null || true
fi

exit 0
