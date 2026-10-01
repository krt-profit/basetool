"""DTO convention analysis over the controller inventory and the DTO sources.

- dual-use DTOs (the same type is a @RequestBody and appears in a response type)
- write DTOs (request bodies of PUT/PATCH) without a version-like component
- request-body records without any Jakarta constraint
- DTO naming suffix census per package
"""
import csv
import os
import re
import sys
from collections import Counter, defaultdict

REPO = r"$REPO"
PKG = os.path.join(REPO, "backend", "src", "main", "java", "de", "greluc", "krt", "profit", "basetool", "backend")
OUT = r"$SCRATCHPAD"

JAKARTA = re.compile(r"@(NotBlank|NotNull|NotEmpty|Size|Min|Max|DecimalMin|DecimalMax|Pattern|Positive|PositiveOrZero|Negative|NegativeOrZero|Email|Past|PastOrPresent|Future|FutureOrPresent|AssertTrue|AssertFalse|Digits|Valid)\b")

def load_rows():
    with open(os.path.join(OUT, "90-rest-api-mappings.csv"), encoding="utf-8") as fh:
        return list(csv.DictReader(fh))

def index_types():
    idx = {}
    for root, _, files in os.walk(PKG):
        for f in files:
            if f.endswith(".java"):
                idx.setdefault(f[:-5], []).append(os.path.join(root, f))
    return idx

def record_components(path):
    with open(path, encoding="utf-8") as fh:
        t = fh.read()
    imports = t
    m = re.search(r"\brecord\s+(\w+)\s*(<[^>]*>)?\s*\(", t)
    if not m:
        c = re.search(r"\bclass\s+(\w+)", t)
        return None, t, "class" if c else "other", imports
    start = m.end() - 1
    depth = 0
    i = start
    while i < len(t):
        if t[i] == "(":
            depth += 1
        elif t[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    return t[start + 1:i], t, "record", imports

def jakarta_imported(text):
    return bool(re.search(r"import\s+jakarta\.validation\.constraints\.", text))

def main():
    rows = load_rows()
    idx = index_types()
    body_types = defaultdict(list)
    resp_types = defaultdict(list)
    for r in rows:
        if r["body_type"]:
            base = re.sub(r"<.*", "", r["body_type"]).strip()
            body_types[base].append(r)
        for t in re.findall(r"[A-Z]\w+", r["return_type"]):
            resp_types[t].append(r)
    dual = sorted(t for t in body_types if t in resp_types and t not in ("List", "Set", "String", "UUID", "Map"))
    print("dual-use DTOs (request body AND in a response type):", len(dual))
    for t in dual:
        ex_b = body_types[t][0]
        ex_r = resp_types[t][0]
        print(f"   {t:40s} body e.g. {ex_b['verb']} {ex_b['path']}  | response e.g. {ex_r['verb']} {ex_r['path']}")
    print()
    print("write bodies (PUT/PATCH/POST) and their version/constraint status:")
    no_version_updates = []
    no_constraints = []
    for t, rs in sorted(body_types.items()):
        if t in ("List", "Set", "String", "Map"):
            continue
        paths = idx.get(t)
        if not paths:
            print("   [not found]", t)
            continue
        comps, text, kind, _ = record_components(paths[0])
        has_version = bool(re.search(r"\b(Long|long|Integer|int)\s+(\w*[vV]ersion)\b", comps or text))
        constraints = len(JAKARTA.findall(comps or text))
        verbs = sorted(set(r["verb"] for r in rs))
        if ("PUT" in verbs or "PATCH" in verbs) and not has_version:
            no_version_updates.append((t, verbs, [r["verb"] + " " + r["path"] for r in rs if r["verb"] in ("PUT", "PATCH")]))
        if constraints == 0:
            no_constraints.append((t, verbs, [r["verb"] + " " + r["path"] for r in rs][:3]))
    print("PUT/PATCH body types without any *version component:", len(no_version_updates))
    for t, v, eps in no_version_updates:
        print("   ", t, v, eps[:3])
    print("body types with zero Jakarta constraints:", len(no_constraints))
    for t, v, eps in no_constraints:
        print("   ", t, v, eps)
    print()
    suffix = Counter()
    pk = Counter()
    for sub in ("model/dto", "model/dto/request", "model/dto/exchange", "dto"):
        d = os.path.join(PKG, *sub.split("/"))
        for f in os.listdir(d):
            if not f.endswith(".java"):
                continue
            n = f[:-5]
            m = re.search(r"(WriteRequest|CreateRequest|UpdateRequest|RequestDto|Request|ResponseDto|Response|Dto|Row|Result|View|Summary|Command|Event)$", n)
            suffix[(sub, m.group(1) if m else "other")] += 1
            pk[sub] += 1
    print("DTO files per package:", dict(pk))
    for (sub, s), c in sorted(suffix.items()):
        print(f"   {sub:22s} {s:14s} {c}")

if __name__ == "__main__":
    sys.exit(main())
