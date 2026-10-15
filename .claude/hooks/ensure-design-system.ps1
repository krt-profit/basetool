<#
.SYNOPSIS
    Populates the DAS KARTELL design-system submodule and fast-forwards it to the remote main; always exits 0.
.DESCRIPTION
    A checkout with local changes, local commits or a branch that cannot fast-forward is left as it is,
    and a failed fetch keeps the current state (ADR-0259).
#>

$ErrorActionPreference = 'SilentlyContinue'
$env:GIT_TERMINAL_PROMPT = '0'
$sub = '.claude/skills/das-kartell-design'

$root = git rev-parse --show-toplevel 2>$null
if (-not $root) { exit 0 }

Set-Location -LiteralPath $root
$readme = Join-Path $root "$sub/README.md"

if (-not (Test-Path -LiteralPath $readme)) {
    git submodule update --init $sub 2>$null | Out-Null
}

if (-not (Test-Path -LiteralPath $readme)) {
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
}

$dir = Join-Path $root $sub
if (-not (Test-Path -LiteralPath (Join-Path $dir '.git'))) { exit 0 }

git -C $dir -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=10 fetch --quiet origin main 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { exit 0 }

$dirty = git -C $dir status --porcelain 2>$null
if ($dirty) { exit 0 }

git -C $dir merge-base --is-ancestor HEAD origin/main 2>$null
if ($LASTEXITCODE -ne 0) { exit 0 }

git -C $dir symbolic-ref -q HEAD 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
    git -C $dir merge --quiet --ff-only origin/main 2>$null | Out-Null
} else {
    git -C $dir checkout --quiet --detach origin/main 2>$null | Out-Null
}

exit 0
