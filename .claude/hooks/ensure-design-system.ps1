<#
.SYNOPSIS
    Populates the DAS KARTELL design-system submodule in the current worktree; always exits 0.
#>

$ErrorActionPreference = 'SilentlyContinue'
$sub = '.claude/skills/das-kartell-design'

$root = git rev-parse --show-toplevel 2>$null
if (-not $root) { exit 0 }

$readme = Join-Path $root "$sub/README.md"
if (Test-Path -LiteralPath $readme) { exit 0 }

Set-Location -LiteralPath $root

git submodule update --init $sub 2>$null | Out-Null
if (Test-Path -LiteralPath $readme) { exit 0 }

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
