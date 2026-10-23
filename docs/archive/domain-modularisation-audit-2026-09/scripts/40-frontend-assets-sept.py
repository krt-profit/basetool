import json
import sys

sys.stdout.reconfigure(encoding="utf-8")
path = r"$SCRATCHPAD\sept_audit_findings.json"
with open(path, encoding="utf-8") as fh:
    data = json.load(fh)
wanted = sys.argv[1:] if len(sys.argv) > 1 else None
for f in data:
    fid = f["id"]
    if wanted:
        if fid not in wanted:
            continue
    elif not fid.startswith("FE"):
        continue
    print("=" * 100)
    for k in ["id", "prio", "dims", "area", "title", "where", "recommendation", "guard", "risk", "effort", "verification", "note"]:
        print(f"{k}: {f.get(k)}")
