"""Print the September audit findings relevant to the frontend Java side, UTF-8 safe."""

import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
data = json.load(open(os.path.join(HERE, "sept_audit_findings.json"), encoding="utf-8"))
want = set(sys.argv[1:])
for x in data:
    if want and x["id"] not in want:
        continue
    if not want and not (x["id"].startswith("FE") or (x.get("area") or "") == "Frontend"
                         or "Frontend" in (x.get("where") or "") or "BackendApiClient" in json.dumps(x)):
        continue
    print("=" * 100)
    for k in ("id", "prio", "dims", "area", "title", "where", "recommendation", "guard", "risk",
              "effort", "verification", "note"):
        print(f"{k}: {x.get(k)}")
