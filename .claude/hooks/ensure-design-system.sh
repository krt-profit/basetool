#!/usr/bin/env bash

sub=".claude/skills/das-kartell-design"

root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
[ -n "$root" ] || exit 0
[ -f "$root/$sub/README.md" ] && exit 0

cd "$root" || exit 0

git submodule update --init "$sub" >/dev/null 2>&1
[ -f "$root/$sub/README.md" ] && exit 0

main="$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0, 10); exit}')"
if [ -n "${main:-}" ] && [ -f "$main/$sub/README.md" ]; then
  mkdir -p "$sub"
  cp -R "$main/$sub/." "$sub/" 2>/dev/null || true
fi

exit 0
