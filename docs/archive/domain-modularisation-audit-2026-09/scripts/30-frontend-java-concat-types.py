"""For every BackendApiClient call whose URI argument is built by concatenation, resolve the declared
type of each identifier concatenated into it (UUID, String, int, enum, ...). A String-typed value
concatenated into a URI is the REQ-SEC-051 / FE-SEC-01 defect class; UUID/number/enum are safe.
Static approximation: the declaration is searched in the same file. Read-only."""

import io
import json
import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
scanmod = __import__("30-frontend-java-scan")
data = json.load(open(os.path.join(HERE, "30-frontend-java-scan.json"), encoding="utf-8"))

HTTP = {"get", "post", "put", "delete", "patch"}
cache = {}

def src_of(rel):
    if rel not in cache:
        cache[rel] = scanmod.strip_comments(
            open(os.path.join(scanmod.ROOT, rel.replace("/", os.sep)), encoding="utf-8").read())
    return cache[rel]

def operands(expr):
    parts = []
    depth = 0
    cur = []
    i = 0
    while i < len(expr):
        c = expr[i]
        if c == '"':
            j = i + 1
            while j < len(expr) and expr[j] != '"':
                if expr[j] == "\\":
                    j += 1
                j += 1
            cur.append(expr[i:j + 1])
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "+" and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    parts.append("".join(cur).strip())
    return parts

def decl_type(src, name, pos):
    pat = re.compile(r"([A-Z][\w.]*(?:<[^;=()]*?>)?|int|long|boolean|double)\s+" + re.escape(name) + r"\b\s*[=,);:]")
    best = None
    for m in pat.finditer(src, 0, pos):
        best = m.group(1)
    return best

counts = Counter()
string_sites = []
for c in data["calls"]:
    if c["method"] not in HTTP or c["class"] != "concat":
        continue
    src = src_of(c["file"])
    pos = sum(len(l) + 1 for l in src.split("\n")[:c["line"] - 1])
    for op in operands(c["first"]):
        if not op or scanmod.STRING_LIT.match(op) or re.match(r"^[A-Z][A-Z0-9_]*$", op) or \
                re.match(r"^[A-Z]\w*\.[A-Z][A-Z0-9_]*$", op):
            continue
        base = re.match(r"^([a-z]\w*)", op)
        if not base:
            counts["expr:" + op[:30]] += 1
            continue
        name = base.group(1)
        if op != name:
            if re.match(r"^\w+\.(id|getId|version|getVersion)\(\)$", op) or op.endswith(".id()"):
                counts["accessor-id"] += 1
                continue
            counts["accessor/expr"] += 1
            string_sites.append((c["file"], c["line"], op[:60], "expr"))
            continue
        t = decl_type(src, name, pos)
        if t is None:
            counts["unresolved"] += 1
            string_sites.append((c["file"], c["line"], op, "unresolved"))
        elif t in ("UUID", "java.util.UUID"):
            counts["UUID"] += 1
        elif t in ("int", "long", "Integer", "Long", "double", "boolean", "Boolean"):
            counts["number/boolean"] += 1
        elif t == "String":
            counts["String"] += 1
            string_sites.append((c["file"], c["line"], op, "String"))
        else:
            counts["other:" + t] += 1
print("operand types concatenated into BackendApiClient URIs:")
for k, v in counts.most_common():
    print(f"  {v:4d}  {k}")
print()
print("String / unresolved / expression operands (review list):")
for f, line, op, kind in string_sites:
    print(f"  {kind:10s} {f}:{line}  {op}")
