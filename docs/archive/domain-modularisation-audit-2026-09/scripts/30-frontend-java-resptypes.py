"""Distribution of the response-type argument of every BackendApiClient HTTP call site: typed mirror
DTO, raw Map/Object/JsonNode, Void, String, byte[], ParameterizedTypeReference constant. Read-only."""

import io
import json
import os
import re
import sys
from collections import Counter

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
scanmod = __import__("30-frontend-java-scan")
data = json.load(open(os.path.join(HERE, "30-frontend-java-scan.json"), encoding="utf-8"))

cache = {}

def src_of(rel):
    if rel not in cache:
        cache[rel] = scanmod.strip_comments(
            open(os.path.join(scanmod.ROOT, rel.replace("/", os.sep)), encoding="utf-8").read())
    return cache[rel]

kinds = Counter()
raw_sites = []
for c in data["calls"]:
    m = c["method"]
    if m not in ("get", "post", "put", "patch", "delete", "getCached"):
        continue
    src = src_of(c["file"])
    lines = src.split("\n")
    pos = sum(len(l) + 1 for l in lines[:c["line"] - 1])
    mm = re.compile(r"\bbackendApiClient\s*\.\s*" + m + r"\s*\(").search(src, pos)
    if not mm:
        continue
    close = scanmod.matching_paren(src, mm.end() - 1)
    args = scanmod.split_top_level(src[mm.end():close])
    if m in ("get", "getCached", "delete") and len(args) >= 2:
        t = args[1]
    elif m in ("post", "put", "patch") and len(args) >= 3:
        t = args[2]
    elif m == "delete" and len(args) >= 3:
        t = args[2]
    else:
        t = "?"
    t = re.sub(r"\s+", " ", t)
    if re.search(r"\bMap\b|Map\.class|Object\.class|JsonNode", t):
        k = "raw Map/Object/JsonNode"
        raw_sites.append((c["file"], c["line"], m, t[:60]))
    elif "Void.class" in t:
        k = "Void"
    elif "String.class" in t:
        k = "String"
    elif "byte[]" in t:
        k = "byte[]"
    elif re.match(r"^[A-Z][A-Za-z0-9]*(Dto|Response|Result|Request|View)\.class$", t) or \
            re.match(r"^[A-Z][A-Za-z0-9]*\.class$", t):
        k = "typed class"
    elif re.match(r"^[A-Z_][A-Z0-9_]*$", t) or "ParameterizedTypeReference" in t or re.match(r"^[a-z]\w*$", t):
        k = "type reference (constant/var)"
    else:
        k = "other"
    kinds[k] += 1
print("response type argument kinds:")
for k, v in kinds.most_common():
    print(f"  {v:4d}  {k}")
print()
print("raw sites by file:")
per = Counter(f for f, _, _, _ in raw_sites)
for f, n in per.most_common():
    print(f"  {n:3d}  {f}")
