"""Claim 5: are the backend /api/v1/exchange/** operations (or their schemas) inside ExternalContractTest's CONTRACT set?"""
import json
import os
import re

REPO = r"$REPO"
TEST = os.path.join(REPO, r"backend\src\test\java\de\greluc\krt\profit\basetool\backend\api\ExternalContractTest.java")
OAS = os.path.join(REPO, r"backend\src\main\resources\api\openapi.json")
FROZEN = os.path.join(REPO, r"backend\src\test\resources\api\frozen-contract-types.txt")

lines = open(TEST, encoding="utf-8").read().splitlines()
block = "\n".join(lines[303:1833])
joined = re.sub(r'"\s*\+\s*"', "", block)
paths = sorted(set(re.findall(r'"(/api/v1/[^"]*)"', joined)))
print("CONTRACT block lines 304-1833: distinct /api/v1 path literals:", len(paths))
xch = [p for p in paths if p == "/api/v1/exchange" or p.startswith("/api/v1/exchange/")]
print("  of them under /api/v1/exchange/**:", xch)
mex = [p for p in paths if p.startswith("/api/v1/material-exchange")]
print("  (material-exchange paths, a different domain:", len(mex), ")")

doc = json.load(open(OAS, encoding="utf-8"))
schemas = doc["components"]["schemas"]

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
    seen = set()
    todo = list(roots)
    while todo:
        s = todo.pop()
        if s in seen or s not in schemas:
            continue
        seen.add(s)
        nxt = set()
        refs(schemas[s], nxt)
        todo.extend(nxt - seen)
    return seen

xops = {p: v for p, v in doc["paths"].items() if p == "/api/v1/exchange" or p.startswith("/api/v1/exchange/")}
nops = sum(1 for p, v in xops.items() for m in v if m in ("get", "post", "put", "patch", "delete"))
print("\nopenapi.json: /api/v1/exchange/** paths:", len(xops), " operations:", nops)
xroots = set()
refs(xops, xroots)
xcl = closure(xroots)
print("schemas reachable from the exchange operations:", len(xcl))

croots = set()
for p in paths:
    if p in doc["paths"]:
        refs(doc["paths"][p], croots)
ccl = closure(croots)
print("schemas reachable from CONTRACT paths (all verbs, upper bound):", len(ccl))
both = sorted(xcl & ccl)
print("exchange schemas also reachable from the CONTRACT set:", both)

frozen_schemas = set()
for ln in open(FROZEN, encoding="utf-8"):
    ln = ln.strip()
    if ln and "." in ln.split("=", 1)[0]:
        frozen_schemas.add(ln.split(".", 1)[0])
print("frozen-contract-types.txt schemas that exchange operations use:", sorted(xcl & frozen_schemas))
