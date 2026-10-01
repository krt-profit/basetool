"""Locate classes (path + LOC) and count regex matches across the worktree's main sources.

Usage: python 82-prev-july-other-locate.py classes Name1 Name2 ...
       python 82-prev-july-other-locate.py grep '<regex>' [glob-subdir] [max]
"""
import re
import sys
from pathlib import Path

ROOT = Path(r"$REPO")
SKIP = ("\\build\\", "\\node_modules\\", "\\.gradle\\", "\\.claude\\worktrees\\versekit-client-auth-11774b\\.claude\\")

def java_files(sub="", main_only=True):
    """Yield Java source files under ROOT/sub, optionally restricted to src/main."""
    base = ROOT / sub if sub else ROOT
    for p in base.rglob("*.java"):
        s = str(p)
        if any(k in s for k in SKIP):
            continue
        if main_only and "\\src\\main\\" not in s:
            continue
        yield p

def classes(names):
    """Print path and line count of the main source file for each simple class name."""
    index = {}
    for p in java_files(main_only=False):
        index.setdefault(p.stem, []).append(p)
    for n in names:
        hits = index.get(n, [])
        if not hits:
            print(f"{n}\tMISSING")
        for h in hits:
            loc = sum(1 for _ in h.open(encoding="utf-8", errors="replace"))
            print(f"{n}\t{loc}\t{h.relative_to(ROOT)}")

def grep(pattern, sub="", maxn=40, exts=(".java",), main_only=True):
    """Print total match count and the first matches (path:line: text) of a regex."""
    rx = re.compile(pattern)
    base = ROOT / sub if sub else ROOT
    total = 0
    files = 0
    shown = 0
    for p in base.rglob("*"):
        s = str(p)
        if not p.is_file() or p.suffix not in exts or any(k in s for k in SKIP):
            continue
        if main_only and "\\src\\main\\" not in s:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        hit = False
        for i, line in enumerate(text, 1):
            if rx.search(line):
                total += 1
                hit = True
                if shown < maxn:
                    print(f"{p.relative_to(ROOT)}:{i}: {line.strip()[:200]}")
                    shown += 1
        files += hit
    print(f"TOTAL matches={total} files={files} pattern={pattern!r} sub={sub!r}")

if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "classes":
        classes(sys.argv[2:])
    elif mode == "grep":
        pat = sys.argv[2]
        sub = sys.argv[3] if len(sys.argv) > 3 else ""
        maxn = int(sys.argv[4]) if len(sys.argv) > 4 else 40
        exts = tuple(sys.argv[5].split(",")) if len(sys.argv) > 5 else (".java",)
        main_only = (sys.argv[6] != "all") if len(sys.argv) > 6 else True
        grep(pat, sub, maxn, exts, main_only)
