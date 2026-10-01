"""Aggregate analyses over 90-rest-api-mappings.csv (produced by 90-rest-api-controllers.py)."""
import csv
import os
import re
import sys
from collections import Counter, defaultdict

OUT = r"$SCRATCHPAD"

WRITE = {"POST", "PUT", "PATCH", "DELETE"}

def load():
    with open(os.path.join(OUT, "90-rest-api-mappings.csv"), encoding="utf-8") as fh:
        return list(csv.DictReader(fh))

def section(title):
    print()
    print("=" * 8, title)

def main():
    rows = load()
    api = [r for r in rows if r["path"].startswith("/api/")]
    section("per controller: base path(s), #mappings, class preauth, tag")
    by_cls = defaultdict(list)
    for r in rows:
        by_cls[r["class"]].append(r)
    for cls in sorted(by_cls):
        rs = by_cls[cls]
        bases = sorted(set("/".join(r["path"].split("/")[:4]) for r in rs))
        print(f"{cls:40s} n={len(rs):3d} tag={rs[0]['class_tag'] or '-':28s} classPre={rs[0]['class_preauth'] or '-'} bases={bases}")

    section("authorization")
    no_pre = [r for r in rows if not r["effective_preauth"]]
    print("mappings with no @PreAuthorize at method or class level:", len(no_pre))
    for r in no_pre:
        print("   ", r["verb"], r["path"], r["class"], r["file"] + ":" + r["line"])
    method_level = sum(1 for r in rows if r["method_preauth"])
    print("mappings with method-level @PreAuthorize:", method_level, "of", len(rows))
    only_class = [r for r in rows if not r["method_preauth"] and r["class_preauth"]]
    print("mappings relying on the class-level @PreAuthorize only:", len(only_class))
    c = Counter(r["class_preauth"] for r in only_class)
    for k, v in c.most_common():
        print(f"    {v:4d}  {k}")
    permit = [r for r in rows if "permitAll" in r["effective_preauth"]]
    print("permitAll mappings:", len(permit))
    for r in permit:
        print("   ", r["verb"], r["path"], r["file"] + ":" + r["line"])
    only_auth = [r for r in rows if r["effective_preauth"].strip() == "isAuthenticated()"]
    print("mappings whose only method guard is isAuthenticated():", len(only_auth))
    wo = Counter((r["class"]) for r in only_auth)
    for k, v in wo.most_common():
        print(f"    {v:4d}  {k}")
    expr = Counter()
    for r in rows:
        e = r["effective_preauth"]
        for tok in re.findall(r"@(\w+)\.(\w+)", e):
            expr["@" + tok[0] + "." + tok[1]] += 1
        for tok in re.findall(r"\b(hasRole|hasAnyRole|hasAuthority|hasAnyAuthority|isAuthenticated|permitAll|denyAll)\b", e):
            expr[tok] += 1
    print("SpEL building blocks (count of mappings using them):")
    for k, v in expr.most_common(60):
        print(f"    {v:4d}  {k}")
    beans = Counter()
    for r in rows:
        for tok in re.findall(r"@(\w+)\.", r["effective_preauth"]):
            beans[tok] += 1
    print("SpEL beans referenced:", dict(beans))

    section("request bodies")
    bodies = [r for r in rows if r["body_type"]]
    print("mappings with @RequestBody:", len(bodies))
    nov = [r for r in bodies if not r["body_valid"]]
    print("@RequestBody without @Valid/@Validated:", len(nov))
    for r in nov:
        print("   ", r["verb"], r["path"], r["body_type"], r["file"] + ":" + r["line"])
    grp = [r for r in bodies if r["body_groups"]]
    print("@Validated with groups:", len(grp))
    for r in grp:
        print("   ", r["verb"], r["path"], r["body_type"], r["body_groups"])
    suffix = Counter()
    for r in bodies:
        t = re.sub(r"<.*", "", r["body_type"]).split(".")[-1]
        m = re.search(r"(WriteRequest|CreateRequest|UpdateRequest|RequestDto|Request|Dto|Command|Payload|Body|Form)$", t)
        suffix[m.group(1) if m else ("List/collection" if r["body_type"].startswith(("List", "Set", "Collection")) else "other:" + t)] += 1
    print("request body type suffixes:", suffix.most_common())
    dto_bodies = sorted(set(r["body_type"] for r in bodies if re.search(r"Dto>?$", r["body_type"])))
    print("request bodies typed ...Dto:", len(dto_bodies), dto_bodies)
    coll = sorted(set(r["body_type"] for r in bodies if r["body_type"].startswith(("List", "Set", "Map", "Collection"))))
    print("collection/map request bodies:", coll)
    writes_without_body = [r for r in rows if r["verb"] in ("POST", "PUT", "PATCH") and not r["body_type"] and not r["multipart"]]
    print("POST/PUT/PATCH without body (path/query addressed):", len(writes_without_body))

    section("responses")
    rt = Counter()
    for r in rows:
        t = r["return_type"]
        if t.startswith("ResponseEntity<"):
            inner = t[len("ResponseEntity<"):-1]
            if inner in ("?", "Void", "Object"):
                rt["ResponseEntity<" + inner + ">"] += 1
            else:
                rt["ResponseEntity<T>"] += 1
        elif t.startswith("PageResponse"):
            rt["PageResponse<T>"] += 1
        elif t.startswith(("List", "Set", "Collection")):
            rt["List/Set<T>"] += 1
        elif t.startswith("Map"):
            rt["Map"] += 1
        elif t == "void":
            rt["void"] += 1
        elif t.startswith(("SseEmitter", "Flux", "Mono", "StreamingResponseBody", "ResponseBodyEmitter")):
            rt["stream:" + t.split("<")[0]] += 1
        else:
            rt["T"] += 1
    print(rt.most_common())
    weak = [r for r in rows if re.search(r"ResponseEntity<\?>|ResponseEntity<Object>|^Object$|Map<String, ?Object>|ResponseEntity<Map", r["return_type"])]
    print("weakly typed responses:", len(weak))
    for r in weak:
        print("   ", r["verb"], r["path"], r["return_type"], r["file"] + ":" + r["line"])
    lists = [r for r in rows if r["verb"] == "GET" and r["return_type"].startswith(("List", "Set", "ResponseEntity<List"))]
    print("GET returning an unpaged List/Set:", len(lists))
    maps = [r for r in rows if "Map<" in r["return_type"]]
    print("Map-typed responses:", [(r["verb"], r["path"], r["return_type"]) for r in maps])

    section("identity parameters")
    idc = Counter()
    for r in rows:
        for t in r["identity"].split("|"):
            if t:
                idc[t] += 1
    print(idc.most_common())
    jwt_classes = Counter(r["class"] for r in rows if "Jwt" in r["identity"] and "@CurrentUserId" not in r["identity"])
    print("classes reading the JWT directly (count of mappings):", len(jwt_classes), jwt_classes.most_common())

    section("transactions on controllers")
    txr = [r for r in rows if r["tx"]]
    print("mappings with @Transactional at controller level:", len(txr), Counter(r["tx"] for r in txr))
    print("classes:", Counter(r["class"] for r in txr).most_common())

    section("OpenAPI annotations")
    print("mappings with @Operation:", sum(1 for r in rows if r["has_operation"] == "True"), "of", len(rows))
    print("mappings with @ApiResponses/@ApiResponse:", sum(1 for r in rows if r["has_api_responses"] == "True"))
    print("classes with @Tag:", len(set(r["class"] for r in rows if r["class_tag"])), "of", len(set(r["class"] for r in rows)))
    print("classes without @Tag:", sorted(set(r["class"] for r in rows if not r["class_tag"])))

    section("deprecation")
    dep = [r for r in rows if r["api_deprecation"] or r["java_deprecated"] == "True" or r["op_deprecated"] == "True"]
    for r in dep:
        print("   ", r["verb"], r["path"], "api_dep=", r["api_deprecation"], "java=", r["java_deprecated"], "op=", r["op_deprecated"], r["file"] + ":" + r["line"])

    section("pagination parameters")
    pq = [r for r in rows if "page:" in r["query"]]
    print("mappings with a page query param:", len(pq))
    print("mappings with a Pageable param:", sum(1 for r in rows if r["pageable_param"] == "True"))
    sortq = [r for r in rows if "sort:" in r["query"]]
    print("mappings with a sort query param:", len(sortq))

    section("path shape")
    segs = Counter()
    first = Counter()
    for r in api:
        parts = [p for p in r["path"].split("/") if p]
        if len(parts) >= 3:
            first[parts[2]] += 1
        for p in parts[2:]:
            if not p.startswith("{"):
                segs[p] += 1
    print("first resource segments:", len(first))
    print(sorted(first.items()))
    slim = [r for r in api if r["path"].endswith("/slim") or "/slim/" in r["path"]]
    print("/slim mappings:", len(slim))
    actions = Counter()
    action_words = ["join", "leave", "cancel", "close", "reopen", "check-in", "check-out", "store", "deactivate", "activate", "confirm", "reject", "approve", "reversal", "read", "read-all", "import", "export", "preview", "apply", "reorder", "move", "transfer", "book-out", "personal-rebook", "bulk-checkout", "bulk-rebook", "bulk-org-unit", "bulk-stolen", "stolen", "org-unit", "complete", "start", "reset", "sync", "trigger", "purge", "wipe", "retry", "undo", "revoke", "restore", "archive", "duplicate", "copy", "assign", "unassign", "unlink", "link", "refresh", "recalculate", "promote", "handover", "handovers", "item-handovers", "production", "lookup", "search", "overview", "summary", "grouped", "aggregated", "statistics", "stats", "count", "unread-count", "stream", "changed", "ping", "test", "status", "priority", "delivered", "note"]
    for r in api:
        parts = [p for p in r["path"].split("/") if p]
        last = parts[-1] if parts else ""
        if last == "slim" and len(parts) > 1:
            last = parts[-2]
        if last in action_words:
            actions[(r["verb"], last)] += 1
    print("action-like trailing segments:", sorted(actions.items(), key=lambda x: -x[1])[:60])
    camel = sorted(set(p for r in api for p in r["path"].split("/") if re.search(r"[A-Z]", p) and not p.startswith("{")))
    print("path segments with upper-case letters:", camel)
    underscore = sorted(set(p for r in api for p in r["path"].split("/") if "_" in p and not p.startswith("{")))
    print("path segments with underscores:", underscore)
    trailing = [r["path"] for r in rows if r["path"].endswith("/") and len(r["path"]) > 1]
    print("paths ending with slash:", trailing)
    dup = Counter((r["verb"], r["path"]) for r in rows)
    print("duplicate verb+path in source:", [k for k, v in dup.items() if v > 1])
    by_path = defaultdict(set)
    for r in rows:
        by_path[r["path"]].add(r["class"])
    multi = {p: c for p, c in by_path.items() if len(c) > 1}
    print("paths served by >1 controller:", len(multi))
    for p, c in sorted(multi.items()):
        print("   ", p, sorted(c))
    pfx_cls = defaultdict(set)
    for r in api:
        parts = [p for p in r["path"].split("/") if p]
        pfx = "/".join(parts[:3])
        pfx_cls[pfx].add(r["class"])
    print("first-segment prefixes shared by >1 controller:")
    for p, c in sorted(pfx_cls.items()):
        if len(c) > 1:
            print("   ", p, sorted(c))

if __name__ == "__main__":
    sys.exit(main())
