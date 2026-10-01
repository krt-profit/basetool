"""Summarise the committed backend openapi.json and the ingest exchange contract.

Writes a CSV of every backend operation and prints aggregate counts.
"""
import collections
import csv
import json
import os
import sys

REPO = r"$REPO"
OUT = r"$SCRATCHPAD"

METHODS = ("get", "put", "post", "delete", "patch", "head", "options", "trace")

def ref_name(schema):
    if not isinstance(schema, dict):
        return ""
    if "$ref" in schema:
        return schema["$ref"].rsplit("/", 1)[-1]
    if schema.get("type") == "array":
        return "array<" + ref_name(schema.get("items", {})) + ">"
    if "oneOf" in schema:
        return "oneOf(" + ",".join(ref_name(s) for s in schema["oneOf"]) + ")"
    t = schema.get("type", "")
    f = schema.get("format", "")
    if t == "object" and "additionalProperties" in schema:
        return "map<" + ref_name(schema["additionalProperties"]) + ">"
    return t + (":" + f if f else "")

def body_schema(content):
    if not content:
        return "", ""
    types = sorted(content.keys())
    first = content[types[0]]
    return ",".join(types), ref_name(first.get("schema", {}))

def main():
    path = os.path.join(REPO, "backend", "src", "main", "resources", "api", "openapi.json")
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    print("openapi", doc.get("openapi"))
    print("info", json.dumps(doc.get("info"), ensure_ascii=False)[:400])
    print("servers", doc.get("servers"))
    print("top-level security", doc.get("security"))
    comps = doc.get("components", {})
    print("securitySchemes", list(comps.get("securitySchemes", {}).keys()))
    print("schemas", len(comps.get("schemas", {})))
    print("top-level tags", len(doc.get("tags", []) or []))
    paths = doc["paths"]
    print("paths", len(paths))
    rows = []
    tag_counter = collections.Counter()
    method_counter = collections.Counter()
    deprecated = []
    op_security = collections.Counter()
    ext_keys = collections.Counter()
    for p, item in paths.items():
        for m in METHODS:
            if m not in item:
                continue
            op = item[m]
            method_counter[m.upper()] += 1
            tags = op.get("tags", [])
            for t in tags:
                tag_counter[t] += 1
            if op.get("deprecated"):
                deprecated.append((m.upper(), p))
            sec = op.get("security")
            op_security[json.dumps(sec, sort_keys=True) if sec is not None else "<none>"] += 1
            for k in op:
                if k.startswith("x-"):
                    ext_keys[k] += 1
            req_ct, req_schema = body_schema((op.get("requestBody") or {}).get("content"))
            responses = op.get("responses", {})
            codes = sorted(responses.keys())
            succ = [c for c in codes if c.startswith("2")]
            resp_ct, resp_schema = ("", "")
            if succ:
                resp_ct, resp_schema = body_schema(responses[succ[0]].get("content"))
            params = op.get("parameters", [])
            qparams = [x.get("name") for x in params if x.get("in") == "query"]
            hparams = [x.get("name") for x in params if x.get("in") == "header"]
            rows.append({
                "method": m.upper(),
                "path": p,
                "operationId": op.get("operationId", ""),
                "tags": "|".join(tags),
                "deprecated": bool(op.get("deprecated")),
                "summary": (op.get("summary") or "")[:80],
                "req_ct": req_ct,
                "req_schema": req_schema,
                "req_required": bool((op.get("requestBody") or {}).get("required")),
                "resp_codes": "|".join(codes),
                "succ_code": succ[0] if succ else "",
                "resp_ct": resp_ct,
                "resp_schema": resp_schema,
                "query": "|".join(qparams),
                "headers": "|".join(hparams),
                "security": json.dumps(sec) if sec is not None else "",
            })
    print("operations", len(rows))
    print("methods", dict(method_counter))
    print("distinct tags", len(tag_counter))
    print("ops without tags", sum(1 for r in rows if not r["tags"]))
    print("deprecated", len(deprecated), deprecated[:20])
    print("op security variants", op_security.most_common(10))
    print("x- extension keys", dict(ext_keys))
    with open(os.path.join(OUT, "90-rest-api-operations.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print("tags by count:")
    for t, c in tag_counter.most_common():
        print(f"  {c:4d}  {t}")
    prefixes = collections.Counter()
    for r in rows:
        parts = r["path"].split("/")
        pre = "/".join(parts[:4]) if len(parts) > 3 else r["path"]
        prefixes[pre] += 1
    print("path prefixes (first three segments):", len(prefixes))
    for pfx, c in sorted(prefixes.items()):
        print(f"  {c:4d}  {pfx}")
    resp_ct = collections.Counter(r["resp_ct"] for r in rows)
    print("response content types", dict(resp_ct))
    req_ct = collections.Counter(r["req_ct"] for r in rows)
    print("request content types", dict(req_ct))
    succ = collections.Counter(r["succ_code"] for r in rows)
    print("first success codes", dict(succ))
    non_v1 = [r["path"] for r in rows if not r["path"].startswith("/api/v1/")]
    print("non /api/v1 paths", len(non_v1), non_v1[:30])
    page_resp = sum(1 for r in rows if r["resp_schema"].startswith("PageResponse"))
    print("PageResponse responses", page_resp)
    page_like = collections.Counter(r["resp_schema"] for r in rows if "Page" in r["resp_schema"])
    print("page-like response schemas", page_like.most_common(15))
    print("ops with page query param", sum(1 for r in rows if "page" in r["query"].split("|")))
    print("ops with sort query param", sum(1 for r in rows if "sort" in r["query"].split("|")))
    print("ops with pageable object param", sum(1 for r in rows if "pageable" in r["query"].split("|")))

if __name__ == "__main__":
    sys.exit(main())
