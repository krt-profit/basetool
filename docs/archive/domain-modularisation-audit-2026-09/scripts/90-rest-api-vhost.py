"""Evaluate the api.* vhost allow-list against every documented backend operation.

Parses docker/edge/include/api-allowlist.conf the way ExternalContractTest does (one-line
`if ($uri = "...")` / `~` / `~*` rules), probes every openapi.json path with a sample UUID, and
reports: operations admitted, admitted-but-not-frozen, frozen-but-not-admitted, and write verbs the
read-only family rule would still refuse (approximation of the second half of the include).
"""
import csv
import json
import os
import re
import sys
from collections import Counter

REPO = r"$REPO"
OUT = r"$SCRATCHPAD"
UUID = "00000000-0000-4000-8000-000000000000"
PLACEHOLDERS = {"{roleCode}": "KRT_MEMBER", "{enabled}": "true", "{key}": "job_order.age_red_days", "{domain}": "bank", "{name}": "ADMIN"}
RULE = re.compile(r'^\s*if \(\$uri (=|~|~\*) "([^"]*)"\)\s*\{\s*set \$krt_api_allowed 1;\s*\}\s*$')

def probe(path):
    for k, v in PLACEHOLDERS.items():
        path = path.replace(k, v)
    return re.sub(r"\{[^}]+\}", UUID, path)

def main():
    with open(os.path.join(REPO, "docker", "edge", "include", "api-allowlist.conf"), encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    rules = []
    for ln in lines:
        m = RULE.match(ln)
        if not m:
            continue
        op, operand = m.group(1), m.group(2)
        if op == "=":
            rules.append(("=", operand, lambda u, o=operand: u == o))
        else:
            flags = re.IGNORECASE if op == "~*" else 0
            rx = re.compile(operand, flags)
            rules.append((op, operand, lambda u, r=rx: r.search(u) is not None))
    print("admission rules parsed:", len(rules))
    print("exact rules:", sum(1 for r in rules if r[0] == "="), "regex rules:", sum(1 for r in rules if r[0] != "="))
    prefix_rules = [r[1] for r in rules if r[0] != "=" and not r[1].endswith("$")]
    print("unanchored-end (prefix) regex rules:", prefix_rules)
    ro = None
    for ln in lines:
        m = re.search(r'if \(\$uri ~ "(\^/api/v1/\([^"]+\))"\) \{ set \$krt_readonly_family "R"; \}', ln)
        if m:
            ro = re.compile(m.group(1))
    with open(os.path.join(REPO, "backend", "src", "main", "resources", "api", "openapi.json"), encoding="utf-8") as fh:
        oas = json.load(fh)
    with open(os.path.join(OUT, "90-rest-api-consumers.csv"), encoding="utf-8") as fh:
        cons = {(r["verb"], r["path"]): r["consumers"] for r in csv.DictReader(fh)}
    admitted = []
    for p, item in oas["paths"].items():
        for v in ("get", "post", "put", "patch", "delete"):
            if v not in item:
                continue
            u = probe(p)
            if any(r[2](u) for r in rules):
                admitted.append((v.upper(), p))
    frozen = set(k for k, c in cons.items() if "app" in c)
    print("documented operations whose path the allow-list admits:", len(admitted))
    not_frozen = sorted(set(admitted) - frozen)
    print("admitted by path but not in the frozen contract set:", len(not_frozen))
    for k in not_frozen:
        u = probe(k[1])
        ro_hit = bool(ro and ro.search(u))
        print("   ", k[0], k[1], "| read-only family" if ro_hit else "", "| consumers:", cons.get(k, ""))
    missing = sorted(frozen - set(admitted))
    print("frozen but not admitted:", missing)
    fam = Counter()
    for k in admitted:
        parts = [x for x in k[1].split("/") if x]
        fam[parts[2] if len(parts) > 2 else k[1]] += 1
    print("admitted operations per first segment:", sorted(fam.items()))

if __name__ == "__main__":
    sys.exit(main())
