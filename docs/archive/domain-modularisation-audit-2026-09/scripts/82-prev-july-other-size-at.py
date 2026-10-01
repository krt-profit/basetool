"""Count main-source Java files over a line threshold at a given commit, via git cat-file (read-only)."""
import subprocess
import sys

REPO = r"$REPO"
rev = sys.argv[1]
threshold = int(sys.argv[2]) if len(sys.argv) > 2 else 600
tree = subprocess.run(["git", "-C", REPO, "ls-tree", "-r", rev], capture_output=True, text=True, check=True).stdout
blobs = []
for line in tree.splitlines():
    meta, path = line.split("\t", 1)
    parts = meta.split()
    if parts[1] != "blob" or not path.endswith(".java") or "/src/main/java/" not in path:
        continue
    blobs.append((parts[2], path))
proc = subprocess.Popen(["git", "-C", REPO, "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
rows = []
for sha, path in blobs:
    proc.stdin.write((sha + "\n").encode())
    proc.stdin.flush()
    header = proc.stdout.readline().decode().split()
    size = int(header[2])
    data = proc.stdout.read(size)
    proc.stdout.read(1)
    lines = data.count(b"\n") + (0 if data.endswith(b"\n") or not data else 1)
    if lines > threshold:
        rows.append((lines, path))
proc.stdin.close()
proc.wait()
rows.sort(reverse=True)
mods = {}
for _, p in rows:
    m = p.split("/")[0]
    mods[m] = mods.get(m, 0) + 1
print(f"rev={rev} main java files={len(blobs)} over {threshold}: {len(rows)} {mods}")
for lines, p in rows[:15]:
    print(f"  {lines}\t{p}")
