"""Inventory of every place in .github/ and scripts/ that names a Gradle module directory or project path."""
import os
import re

REPO = r"$REPO"
MODULES = ["backend", "frontend", "ingest", "keycloak-spi", "logging-support", "test-support"]
DIR_RE = re.compile(r"(?<![A-Za-z0-9_./-])(" + "|".join(re.escape(m) for m in MODULES) + r")/")
PROJ_RE = re.compile(r"(?<![A-Za-z0-9_-]):(" + "|".join(re.escape(m) for m in MODULES) + r")(?::|\b)")

roots = [
    os.path.join(REPO, ".github"),
    os.path.join(REPO, "scripts"),
    os.path.join(REPO, "docker"),
    os.path.join(REPO, "config"),
]
extra_files = [
    os.path.join(REPO, ".dockerignore"),
    os.path.join(REPO, "docker-compose.build.yml"),
    os.path.join(REPO, "docker-compose.e2e.yml"),
    os.path.join(REPO, "docker-compose.sandbox-build.yml"),
    os.path.join(REPO, ".gitattributes"),
    os.path.join(REPO, ".github", "dependabot.yml"),
]

per_file = {}
paths_block_hits = {}

def scan(path):
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.readlines()
    except (UnicodeDecodeError, OSError):
        return
    hits = []
    for i, line in enumerate(lines, 1):
        d = DIR_RE.findall(line)
        p = PROJ_RE.findall(line)
        if d or p:
            hits.append((i, sorted(set(d)), sorted(set(p)), line.rstrip()))
    if hits:
        per_file[os.path.relpath(path, REPO)] = hits

for root in roots:
    for dp, dn, fn in os.walk(root):
        dn[:] = [d for d in dn if d not in ("node_modules", "__pycache__")]
        for f in fn:
            if f.endswith((".yml", ".yaml", ".py", ".sh", ".json", ".xml", ".toml", ".txt", ".md", ".conf", ".tmpl")) or f in ("Dockerfile",):
                scan(os.path.join(dp, f))
for f in extra_files:
    if os.path.exists(f):
        scan(f)

wf_dir = os.path.join(REPO, ".github", "workflows")
for f in sorted(os.listdir(wf_dir)):
    path = os.path.join(wf_dir, f)
    with open(path, encoding="utf-8") as fh:
        lines = fh.readlines()
    in_paths = False
    indent = None
    entries = []
    for i, line in enumerate(lines, 1):
        s = line.rstrip("\n")
        m = re.match(r"^(\s*)(paths|paths-ignore):\s*$", s)
        if m:
            in_paths = True
            indent = len(m.group(1))
            kind = m.group(2)
            continue
        if in_paths:
            m2 = re.match(r"^(\s*)-\s*(.+)$", s)
            if m2 and len(m2.group(1)) > indent:
                entries.append((i, kind, m2.group(2).strip().strip("'\"")))
                continue
            if s.strip() == "":
                continue
            in_paths = False
    if entries:
        paths_block_hits[f] = entries

print("# Files naming module directories or :project paths")
total_lines = 0
for f in sorted(per_file):
    hits = per_file[f]
    total_lines += len(hits)
    mods = sorted({m for h in hits for m in h[1]} | {m for h in hits for m in h[2]})
    print(f"- {f}: {len(hits)} lines; modules {', '.join(mods)}")
print(f"Total files: {len(per_file)}, total lines: {total_lines}")

print()
print("# Workflow path filters (paths / paths-ignore)")
for f in sorted(paths_block_hits):
    entries = paths_block_hits[f]
    mod_entries = [e for e in entries if DIR_RE.search(e[2] + "/") or any(e[2].startswith(m) for m in MODULES)]
    print(f"## {f}: {len(entries)} entries, {len(mod_entries)} name a module")
    for (ln, kind, val) in entries:
        mark = "*" if (any(val.startswith(m + "/") or val == m for m in MODULES) or DIR_RE.search(val)) else " "
        print(f"  {mark} L{ln} {kind}: {val}")

print()
print("# Detail per file (first 60 chars of each hit)")
for f in sorted(per_file):
    print(f"## {f}")
    for (ln, d, p, text) in per_file[f]:
        print(f"  L{ln}: {text.strip()[:160]}")
