import json
import sys
from collections import Counter

p = r"$SCRATCHPAD\sept_audit_findings.json"
with open(p, encoding="utf-8") as f:
    data = json.load(f)

print(type(data), len(data))
print(Counter(d.get("area") for d in data))
sys.stdout.reconfigure(encoding="utf-8")
mode = sys.argv[1] if len(sys.argv) > 1 else "ids"
areas = sys.argv[2].split(",") if len(sys.argv) > 2 else ["Frontend", "CI", "Betrieb"]
for d in data:
    if d.get("area") in areas:
        if mode == "ids":
            print(d["id"], "|", d.get("prio"), "|", d.get("area"), "|", d.get("title"))
        else:
            print("=" * 100)
            for k, v in d.items():
                print(f"{k}: {v}")
