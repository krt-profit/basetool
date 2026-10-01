import json
import os
import re
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
vault = r"$VAULT"
p = r"$SCRATCHPAD\sept_audit_findings.json"
with open(p, encoding="utf-8") as f:
    data = json.load(f)
ids = [d["id"] for d in data if d["area"] in ("Frontend", "CI", "Betrieb", "Android", "Extractor", "P4K Reader")]
hits = defaultdict(list)
for dp, dn, fn in os.walk(vault):
    if ".obsidian" in dp or ".git" in dp:
        continue
    for name in fn:
        if not name.endswith(".md"):
            continue
        path = os.path.join(dp, name)
        rel = os.path.relpath(path, vault)
        with open(path, encoding="utf-8") as f:
            lines = f.read().split("\n")
        for i, line in enumerate(lines):
            for fid in ids:
                if re.search(r"(?<![A-Z0-9-])" + re.escape(fid) + r"(?![0-9a-z])", line):
                    hits[fid].append(f"{rel}:{i+1}")
for fid in ids:
    locs = hits.get(fid, [])
    files = sorted(set(l.rsplit(":", 1)[0] for l in locs))
    print(f"{fid:14} {len(locs):3}  {'; '.join(files)[:220]}")
