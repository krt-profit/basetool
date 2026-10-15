#!/usr/bin/env bash

sub=".claude/skills/das-kartell-design"
export GIT_TERMINAL_PROMPT=0

root="$(git rev-parse --show-toplevel 2>/dev/null)" || exit 0
[ -n "$root" ] || exit 0
cd "$root" || exit 0

if [ ! -f "$root/$sub/README.md" ]; then
  git submodule update --init "$sub" >/dev/null 2>&1
fi

if [ ! -f "$root/$sub/README.md" ]; then
  main="$(git worktree list --porcelain 2>/dev/null | awk '/^worktree /{print substr($0, 10); exit}')"
  if [ -n "${main:-}" ] && [ -f "$main/$sub/README.md" ]; then
    mkdir -p "$sub"
    cp -R "$main/$sub/." "$sub/" 2>/dev/null || true
  fi
  exit 0
fi

dir="$root/$sub"
[ -e "$dir/.git" ] || exit 0

git -C "$dir" -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=10 fetch --quiet origin main >/dev/null 2>&1 || exit 0
[ -z "$(git -C "$dir" status --porcelain 2>/dev/null)" ] || exit 0
git -C "$dir" merge-base --is-ancestor HEAD origin/main 2>/dev/null || exit 0

if git -C "$dir" symbolic-ref -q HEAD >/dev/null 2>&1; then
  git -C "$dir" merge --quiet --ff-only origin/main >/dev/null 2>&1
else
  git -C "$dir" checkout --quiet --detach origin/main >/dev/null 2>&1
fi

exit 0
