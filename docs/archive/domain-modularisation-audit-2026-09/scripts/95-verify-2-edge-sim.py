"""Simulates docker/edge/include/api-allowlist.conf over every operation in openapi.json.

Gate 1 (lines 1-174): admission by $uri, else 404.
Gate 2 (lines 176-231): read-only family 'R', 'W' appended for non-GET/HEAD, resets to '' by
listed URIs and by the PUT-only carve-out; 'RW' -> 405.
"""
import json
import re
import sys
from collections import defaultdict

REPO = r"$REPO"
CONF = REPO + r"\docker\edge\include\api-allowlist.conf"
OPENAPI = REPO + r"\backend\src\main\resources\api\openapi.json"
CONTRACT_SRC = REPO + r"\backend\src\test\java\de\greluc\krt\profit\basetool\backend\api\ExternalContractTest.java"
SAMPLE_UUID = "00000000-0000-4000-8000-000000000000"
METHODS = ("get", "put", "post", "delete", "patch")

lines = open(CONF, encoding="utf-8").read().splitlines()
adm, resets, putonly = [], [], []
family = None
rule_re = re.compile(r'^\s*if\s*\(\s*\$uri\s*(=|~\*?)\s*"([^"]+)"\s*\)\s*\{\s*set\s+\$(\w+)\s+"?([^";]*)"?\s*;\s*\}\s*$')
for no, line in enumerate(lines, 1):
    m = rule_re.match(line)
    if not m:
        continue
    op, operand, var, val = m.groups()
    if op == "=":
        pred = (lambda o: (lambda u: u == o))(operand)
    elif op == "~":
        pred = (lambda r: (lambda u: r.search(u) is not None))(re.compile(operand))
    else:
        pred = (lambda r: (lambda u: r.search(u) is not None))(re.compile(operand, re.I))
    if var == "krt_api_allowed" and val == "1":
        adm.append((no, pred, operand))
    elif var == "krt_readonly_family" and val == "R":
        family = (no, pred, operand)
    elif var == "krt_readonly_family" and val == "":
        resets.append((no, pred, operand))
    elif var == "krt_put_only" and val == "P":
        putonly.append((no, pred, operand))
    else:
        print("UNPARSED RULE", no, line, file=sys.stderr)

print(f"parsed: {len(adm)} admission rules, family rule at line {family[0]}, {len(resets)} resets, {len(putonly)} put-only rules")

def edge(method, uri):
    hits = [no for no, p, _ in adm if p(uri)]
    if not hits:
        return 404, hits
    fam = "R" if family[1](uri) else ""
    if method.upper() not in ("GET", "HEAD"):
        fam += "W"
    if any(p(uri) for _, p, _ in resets):
        fam = ""
    pu = ("P" if any(p(uri) for _, p, _ in putonly) else "") + ("U" if method.upper() == "PUT" else "")
    if pu == "PU":
        fam = ""
    if fam == "RW":
        return 405, hits
    return 200, hits

doc = json.load(open(OPENAPI, encoding="utf-8"))
CANDIDATES = {
    "enabled": ["true", "false"],
    "roleCode": ["KOMMANDOLEITER"],
    "key": ["job_order.age_yellow_days", "job_order.age_red_days", "some.other.key"],
    "name": ["ADMIN"],
    "clientId": ["some-client"],
}

def samples(path, op, item):
    params = {p["name"]: p for p in op.get("parameters", []) + item.get("parameters", []) if p.get("in") == "path"}
    uris = [path]
    for name in re.findall(r"\{([^}]+)\}", path):
        sch = (params.get(name) or {}).get("schema", {})
        if sch.get("format") == "uuid":
            vals = [SAMPLE_UUID]
        elif sch.get("enum"):
            vals = list(sch["enum"])
        else:
            vals = CANDIDATES.get(name, ["x"])
        uris = [u.replace("{" + name + "}", v, 1) for u in uris for v in vals]
    return uris

documented = {}
for path, item in doc["paths"].items():
    for m, op in item.items():
        if m not in METHODS:
            continue
        outcomes = []
        for uri in samples(path, op, item):
            status, hits = edge(m, uri)
            outcomes.append((uri, status, hits))
        passed = [o for o in outcomes if o[1] == 200]
        documented[(m.upper(), path)] = (outcomes, passed)

src = open(CONTRACT_SRC, encoding="utf-8").read()
contract = set((m.upper(), p) for p, m in re.findall(r'new ContractOperation\(\s*"([^"]+)"\s*,\s*"([a-z]+)"', src))
print("contract operations parsed:", len(contract))
missing_doc = sorted(contract - set(documented))
print("contract ops not in openapi.json:", missing_doc)

admitted = {k for k, (outs, passed) in documented.items() if passed}
gate404 = {k for k, (outs, passed) in documented.items() if all(o[1] == 404 for o in outs)}
gate405 = {k for k, (outs, passed) in documented.items() if not passed and any(o[1] == 405 for o in outs)}
print(f"documented ops: {len(documented)} | pass both gates: {len(admitted)} | 404: {len(gate404)} | 405: {len(gate405)}")

blocked_contract = sorted(k for k in contract if k in documented and k not in admitted)
print("\nFROZEN ops the edge blocks:", len(blocked_contract))
for k in blocked_contract:
    outs, _ = documented[k]
    print("  ", k, [(o[0], o[1]) for o in outs])

extra = sorted(admitted - contract)
print("\nADMITTED but NOT frozen:", len(extra))
for k in extra:
    outs, passed = documented[k]
    rules = sorted({no for o in passed for no in o[2]})
    prefix = any(no in (2, 3) for no in rules)
    same_path_frozen = sorted(m for (m, p) in contract if p == k[1])
    print(f"   {k[0]:6} {k[1]:75} rules={rules} prefixRule={prefix} frozenMethodsOnPath={same_path_frozen} samples={[o[0] for o in passed][:2]}")

frozen_paths_405 = sorted(k for k in gate405 if any(p == k[1] for (_, p) in contract))
print("\n405-blocked ops on a frozen path (method gate doing its job):", len(frozen_paths_405))
for k in frozen_paths_405:
    print("  ", k)

print("\nnon-frozen ops refused 405 only (path admitted, method refused):", len(gate405))
