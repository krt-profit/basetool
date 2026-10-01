"""Which backend operations does each consumer use?

- web frontend: backendApiClient.<verb>(...) call sites in frontend/src/main/java (templated)
- Android app: the frozen contract set in ExternalContractTest (the app's recorded reads/writes)
- exchange gateway: ingest ExchangeController relay paths (BACKEND + ...)
- keycloak-spi: the /internal endpoint
Joins them with openapi.json and the controller inventory to find operations nobody consumes.
"""
import csv
import json
import os
import re
import sys
from collections import Counter, defaultdict

REPO = r"$REPO"
OUT = r"$SCRATCHPAD"
VERBS = {"get": "GET", "getCached": "GET", "post": "POST", "put": "PUT", "delete": "DELETE", "patch": "PATCH", "getTermsDocumentAnonymously": "GET"}

def balanced_args(text, start):
    depth = 0
    i = start
    in_str = False
    args = []
    cur = []
    while i < len(text):
        c = text[i]
        if in_str:
            cur.append(c)
            if c == "\\":
                cur.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
                cur.append(c)
            elif c in "([{":
                depth += 1
                if depth > 1:
                    cur.append(c)
            elif c in ")]}":
                depth -= 1
                if depth == 0:
                    args.append("".join(cur).strip())
                    return args
                cur.append(c)
            elif c == "," and depth == 1:
                args.append("".join(cur).strip())
                cur = []
            else:
                cur.append(c)
        i += 1
    return args

def template_of(expr, consts):
    parts = re.findall(r"\"(?:[^\"\\]|\\.)*\"|[A-Za-z_][\w.]*(?:\([^)]*\))?", expr)
    out = []
    for p in parts:
        if p.startswith('"'):
            out.append(p[1:-1])
        elif p in consts:
            out.append(consts[p])
        else:
            out.append("{}")
    t = "".join(out)
    t = t.split("?")[0]
    t = re.sub(r"\{\}(\{\})+", "{}", t)
    return t

def frontend_calls():
    root = os.path.join(REPO, "frontend", "src", "main", "java")
    calls = []
    unresolved = 0
    for dp, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith(".java"):
                continue
            with open(os.path.join(dp, f), encoding="utf-8") as fh:
                t = fh.read()
            consts = {}
            for m in re.finditer(r"static\s+final\s+String\s+(\w+)\s*=\s*\"([^\"]*)\"\s*;", t):
                consts[m.group(1)] = m.group(2)
            for m in re.finditer(r"backendApiClient\s*\.\s*(\w+)\s*\(", t):
                verb = VERBS.get(m.group(1))
                if not verb:
                    continue
                args = balanced_args(t, m.end() - 1)
                if not args:
                    continue
                tpl = template_of(args[0], consts)
                if not tpl.startswith("/api/") and not tpl.startswith("/internal"):
                    unresolved += 1
                    continue
                calls.append((verb, tpl, f))
    return calls, unresolved

def main():
    with open(os.path.join(REPO, "backend", "src", "main", "resources", "api", "openapi.json"), encoding="utf-8") as fh:
        oas = json.load(fh)
    ops = {}
    for p, item in oas["paths"].items():
        for v in ("get", "post", "put", "delete", "patch"):
            if v in item:
                ops[(v.upper(), re.sub(r"\{[^}]*\}", "{}", p))] = p
    calls, unresolved = frontend_calls()
    fe_ops = Counter()
    unmatched = Counter()
    for verb, tpl, f in calls:
        key = (verb, tpl)
        if key in ops:
            fe_ops[ops[key]] += 1
            fe_ops[(verb, ops[key])] += 1
        else:
            unmatched[(verb, tpl)] += 1
    fe_set = set(k for k in fe_ops if isinstance(k, tuple))
    print("frontend backendApiClient calls resolved:", len(calls), "unresolved first-arg:", unresolved)
    print("distinct frontend (verb, op) matched:", len(fe_set))
    print("unmatched call templates:", len(unmatched))
    for k, v in sorted(unmatched.items())[:60]:
        print("   ", v, k)
    ext = os.path.join(REPO, "backend", "src", "test", "java", "de", "greluc", "krt", "profit", "basetool", "backend", "api", "ExternalContractTest.java")
    with open(ext, encoding="utf-8") as fh:
        et = fh.read()
    start = et.find("private static final List<ContractOperation> CONTRACT")
    end = et.find("ADDRESSED_BY_NO_QUERY_PARAMETER =")
    block = et[start:end]
    app = set()
    for m in re.finditer(r"new ContractOperation\(\s*\"([^\"]+)\"\s*,\s*\"(\w+)\"", block):
        app.add((m.group(2).upper(), m.group(1)))
    print("android contract set operations:", len(app))
    missing_app = [x for x in app if x[1] not in oas["paths"] or x[0].lower() not in oas["paths"][x[1]]]
    print("contract entries not in openapi.json:", missing_app)
    xch = set()
    for p, item in oas["paths"].items():
        if p.startswith("/api/v1/exchange/"):
            for v in item:
                if v in ("get", "post", "put", "delete", "patch"):
                    xch.add((v.upper(), p))
    print("exchange relay operations (backend side):", len(xch))
    with open(os.path.join(OUT, "90-rest-api-mappings.csv"), encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    by_op = {(r["verb"], r["path"]): r for r in rows}
    consumers = {}
    for key in by_op:
        c = []
        if key in fe_set:
            c.append("web")
        if key in app:
            c.append("app")
        if key in xch:
            c.append("exchange")
        if key[1].startswith("/internal/"):
            c.append("keycloak-spi")
        if key[1].startswith("${"):
            c.append("servlet-error")
        consumers[key] = c
    none = sorted(k for k, v in consumers.items() if not v)
    print("operations with no consumer found:", len(none))
    for k in none:
        r = by_op[k]
        print("   ", k[0], k[1], "|", r["class"], "|", r["effective_preauth"][:60])
    app_only = sorted(k for k, v in consumers.items() if v == ["app"])
    print("operations used only by the app:", len(app_only))
    for k in app_only:
        print("   ", k[0], k[1])
    both = sum(1 for v in consumers.values() if "web" in v and "app" in v)
    print("operations used by web AND app:", both)
    print("web-only:", sum(1 for v in consumers.values() if v == ["web"]))
    with open(os.path.join(OUT, "90-rest-api-consumers.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["verb", "path", "class", "consumers"])
        for k, v in sorted(consumers.items()):
            w.writerow([k[0], k[1], by_op[k]["class"], "|".join(v)])

if __name__ == "__main__":
    sys.exit(main())
