import json
import sys
from collections import Counter

BASE = r"$SCRATCHPAD"

with open(BASE + r"\sept_audit_findings.json", encoding="utf-8") as f:
    data = json.load(f)

print("total", len(data))
print(Counter(d.get("area") for d in data))
wanted = {"Backend", "Ingest", "Keycloak", "Build"}
sel = [d for d in data if d.get("area") in wanted]
print("selected", len(sel))
with open(BASE + r"\80-prev-sept-a-selected.json", "w", encoding="utf-8") as f:
    json.dump(sel, f, ensure_ascii=False, indent=1)
mode = sys.argv[1] if len(sys.argv) > 1 else "ids"
for d in sel:
    if mode == "ids":
        print(d["id"], d["prio"], d["area"], "|", d["title"])
    else:
        print("=" * 100)
        for k in ["id", "prio", "dims", "area", "title", "where", "recommendation", "guard", "risk", "effort", "verification", "note"]:
            print(k + ":", d.get(k))
