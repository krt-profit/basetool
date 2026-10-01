"""Per exchange operation: which of its schemas are incidentally frozen by the Android CONTRACT record."""
import json
import os

REPO = r"$REPO"
OAS = os.path.join(REPO, r"backend\src\main\resources\api\openapi.json")
FROZEN = os.path.join(REPO, r"backend\src\test\resources\api\frozen-contract-types.txt")
doc = json.load(open(OAS, encoding="utf-8"))
schemas = doc["components"]["schemas"]
frozen = set()
for ln in open(FROZEN, encoding="utf-8"):
    ln = ln.strip()
    if ln and "." in ln.split("=", 1)[0]:
        frozen.add(ln.split(".", 1)[0])

def refs(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str) and v.startswith("#/components/schemas/"):
                out.add(v.rsplit("/", 1)[1])
            else:
                refs(v, out)
    elif isinstance(node, list):
        for v in node:
            refs(v, out)

def closure(roots):
    seen, todo = set(), list(roots)
    while todo:
        s = todo.pop()
        if s in seen or s not in schemas:
            continue
        seen.add(s)
        nxt = set()
        refs(schemas[s], nxt)
        todo.extend(nxt - seen)
    return seen

for p, v in sorted(doc["paths"].items()):
    if not (p == "/api/v1/exchange" or p.startswith("/api/v1/exchange/")):
        continue
    for m, op in v.items():
        if m not in ("get", "post", "put", "patch", "delete"):
            continue
        r = set()
        refs(op, r)
        cl = closure(r)
        print("%-6s %-45s own=%-45s frozen-by-app-contract=%s" % (m.upper(), p, ",".join(sorted(r - {"ProblemDetail"})), sorted((cl & frozen))))
