import subprocess
import sys

repo = r"$REPO"
target = sys.argv[1]
out = subprocess.run(
    ["git", "-C", repo, "log", "--first-parent", "--format=%h %ad %s", "--date=short", "--reverse",
     "--since=2026-09-15", "origin/main"],
    capture_output=True, text=True, encoding="utf-8").stdout.splitlines()
for line in out:
    h = line.split()[0]
    r = subprocess.run(["git", "-C", repo, "merge-base", "--is-ancestor", target, h])
    if r.returncode == 0:
        print("first first-parent commit containing", target, "->", line)
        break
