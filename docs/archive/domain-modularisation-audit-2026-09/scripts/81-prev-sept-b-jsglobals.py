import os
import re
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
root = r"$REPO\frontend\src\main\resources\static\js"
defs = defaultdict(set)
srcs = {}
for name in sorted(os.listdir(root)):
    if not name.endswith(".js") or name.endswith(".min.js"):
        continue
    with open(os.path.join(root, name), encoding="utf-8") as f:
        s = f.read()
    srcs[name] = s
    for m in re.finditer(r"window\.(krt[A-Za-z0-9_]*)\s*=(?!=)", s):
        defs[m.group(1)].add(name)
    for m in re.finditer(r"root\.(escapeHtml|escapeAttr)\s*=", s):
        defs[m.group(1)].add(name)
users = defaultdict(set)
for g in defs:
    pat = re.compile(r"(?<![\w.])(?:window\.)?" + re.escape(g) + r"\b")
    for name, s in srcs.items():
        if name in defs[g]:
            continue
        if pat.search(s):
            users[g].add(name)
rows = sorted(defs.items(), key=lambda kv: -len(users[kv[0]]))
print(f"{'global':32} {'defined in':34} users")
for g, d in rows:
    print(f"{g:32} {', '.join(sorted(d))[:34]:34} {len(users[g]):3}  {', '.join(sorted(users[g]))[:150]}")
