import json
import re
from collections import Counter

REPO = r"$REPO"
doc = json.load(open(REPO + r"\backend\src\main\resources\api\openapi.json", encoding="utf-8"))
METHODS = ("get", "put", "post", "delete", "patch", "head", "options", "trace")
ops = 0
nonuuid = Counter()
examples = {}
for path, item in doc["paths"].items():
    for m, op in item.items():
        if m not in METHODS:
            continue
        ops += 1
        params = {p["name"]: p for p in op.get("parameters", []) + item.get("parameters", []) if p.get("in") == "path"}
        for name in re.findall(r"\{([^}]+)\}", path):
            p = params.get(name)
            sch = (p or {}).get("schema", {})
            if sch.get("format") != "uuid":
                key = (name, sch.get("type"), tuple(sch.get("enum", []))[:6])
                nonuuid[key] += 1
                examples.setdefault(key, f"{m.upper()} {path}")
print("paths", len(doc["paths"]), "operations", ops)
for k, v in sorted(nonuuid.items(), key=lambda kv: -kv[1]):
    print(v, k, examples[k])
