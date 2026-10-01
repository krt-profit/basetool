import json
import re
import sys
from pathlib import Path

src = Path(sys.argv[1]).read_text(encoding="utf-8")
start = src.index("const R = [")
body_start = src.index("[", start)
depth = 0
in_str = False
esc = False
end = None
for i in range(body_start, len(src)):
    ch = src[i]
    if in_str:
        if esc:
            esc = False
        elif ch == chr(92):
            esc = True
        elif ch == '"':
            in_str = False
        continue
    if ch == '"':
        in_str = True
    elif ch == "[":
        depth += 1
    elif ch == "]":
        depth -= 1
        if depth == 0:
            end = i + 1
            break
raw = src[body_start:end]
raw = re.sub(r",\s*\]", "]", raw)
rows = json.loads(raw)
keys = ["id", "prio", "dims", "area", "title", "where", "recommendation", "guard", "risk", "effort", "verification", "note"]
out = [dict(zip(keys, r)) for r in rows]
Path(sys.argv[2]).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
by_prio = {}
by_area = {}
for r in out:
    by_prio[r["prio"]] = by_prio.get(r["prio"], 0) + 1
    by_area[r["area"]] = by_area.get(r["area"], 0) + 1
print(len(out), "findings")
print("by prio", dict(sorted(by_prio.items())))
print("by area", dict(sorted(by_area.items(), key=lambda kv: -kv[1])))
