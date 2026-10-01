"""Summarise the backend openapi.json: schema count, tags, paths per first segment, and which
schemas each tag references (to judge per-domain generation). Read-only."""

import io
import json
import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
REPO = r"$REPO"
spec = json.load(open(os.path.join(REPO, "backend", "src", "main", "resources", "api", "openapi.json"),
                      encoding="utf-8"))
schemas = spec.get("components", {}).get("schemas", {})
paths = spec.get("paths", {})
print("openapi:", spec.get("openapi"), "schemas:", len(schemas), "paths:", len(paths))
ops = 0
tag_ops = Counter()
seg_ops = Counter()
tag_schemas = defaultdict(set)
op_ids = Counter()
deprecated = 0

def refs(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str):
                out.add(v.split("/")[-1])
            else:
                refs(v, out)
    elif isinstance(node, list):
        for v in node:
            refs(v, out)

for p, item in paths.items():
    seg = p.split("/")[3] if p.startswith("/api/v1/") and len(p.split("/")) > 3 else p
    for method, op in item.items():
        if method not in ("get", "post", "put", "patch", "delete"):
            continue
        ops += 1
        seg_ops[seg] += 1
        if op.get("deprecated"):
            deprecated += 1
        for t in op.get("tags", ["(none)"]):
            tag_ops[t] += 1
            r = set()
            refs(op, r)
            tag_schemas[t] |= r
        op_ids[op.get("operationId")] += 1
print("operations:", ops, "deprecated:", deprecated, "tags:", len(tag_ops))
print("duplicate operationIds:", sum(1 for k, v in op_ids.items() if v > 1))
print("top segments:", seg_ops.most_common(60))
print("tags:", tag_ops.most_common(80))

schema_tags = defaultdict(set)
for t, ss in tag_schemas.items():
    for s in ss:
        schema_tags[s].add(t)
shared = [s for s, ts in schema_tags.items() if len(ts) > 1]
print("schemas referenced directly by >1 tag:", len(shared), "of", len(schema_tags))
constructs = Counter()

def walk(node):
    if isinstance(node, dict):
        for k in ("allOf", "oneOf", "anyOf", "discriminator", "not"):
            if k in node:
                constructs[k] += 1
        for v in node.values():
            walk(v)
    elif isinstance(node, list):
        for v in node:
            walk(v)

walk(schemas)
print("polymorphism constructs in schemas:", dict(constructs))
