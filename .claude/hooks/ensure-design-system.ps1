<#
.SYNOPSIS
    Ensures the DAS KARTELL design-system git submodule is checked out in the
    current working tree.

.DESCRIPTION
    `git worktree add` (used by the Claude Code harness to create isolated
    worktrees under .claude/worktrees/) does NOT populate submodules. In a
    worktree session the design system at .claude/skills/das-kartell-design is
    therefore empty and CLAUDE.md's "visual source of truth" (README.md) is
    unreadable, so the design system silently gets ignored.

    This script is wired as a SessionStart hook (.claude/settings.json). On
    every session start it materialises the submodule if it is missing:
      1. offline-first via `git submodule update --init`, reusing the
         superproject's existing module object store (no network when the
         pinned commit is already present, which it is after a normal checkout);
      2. as a fallback, by copying the already-populated submodule from the
         main worktree.

    It is intentionally best-effort and NEVER fails the session (always exits 0)
    so a missing design system never blocks work.
#>

$ErrorActionPreference = 'SilentlyContinue'
$sub = '.claude/skills/das-kartell-design'

# Resolve the current working tree's root; bail quietly if not in a repo.
$root = git rev-parse --show-toplevel 2>$null
if (-not $root) { exit 0 }

$readme = Join-Path $root "$sub/README.md"
if (Test-Path -LiteralPath $readme) { exit 0 }   # already populated

Set-Location -LiteralPath $root

# 1) Offline-first: reuse the superproject's existing module object store.
git submodule update --init $sub 2>$null | Out-Null
if (Test-Path -LiteralPath $readme) { exit 0 }

# 2) Fallback: copy from the main worktree (first entry of `worktree list`).
$main = git worktree list --porcelain 2>$null |
    Where-Object { $_ -like 'worktree *' } |
    Select-Object -First 1
if ($main) {
    $main = $main -replace '^worktree ', ''
    $srcDir = Join-Path $main $sub
    if (Test-Path -LiteralPath (Join-Path $srcDir 'README.md')) {
        $dstDir = Join-Path $root $sub
        New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
        Copy-Item -Path (Join-Path $srcDir '*') -Destination $dstDir -Recurse -Force
    }
}

exit 0
