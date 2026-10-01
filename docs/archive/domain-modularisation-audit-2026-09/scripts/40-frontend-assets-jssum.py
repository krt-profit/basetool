import json
import sys
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8")
BASE = r"$SCRATCHPAD"
d = json.load(open(BASE + r"\40-frontend-assets-jsast.json", encoding="utf-8"))
res = d["results"]
mode = sys.argv[1] if len(sys.argv) > 1 else "totals"

tot = Counter()
files_with = Counter()
for r in res:
    for k, v in r["metrics"].items():
        tot[k] += v
        if v:
            files_with[k] += 1

if mode == "totals":
    print("files", len(res), "lines", sum(r["lines"] for r in res), "bytes", sum(r["bytes"] for r in res))
    print("tsCheck", sum(1 for r in res if r["tsCheck"]), "lines under ts-check", sum(r["lines"] for r in res if r["tsCheck"]))
    print("sourceType", Counter(r["sourceType"] for r in res))
    print("wrappedInIife", sum(1 for r in res if r["topLevel"]["wrappedInIife"]), "useStrictFile", sum(1 for r in res if r["useStrictFile"]), "useStrictFn files", sum(1 for r in res if r["useStrictFn"]))
    keys = sorted(tot)
    for k in keys:
        print(f"{k}\t{tot[k]}\t{files_with[k]}")
elif mode == "comments":
    c = Counter()
    for r in res:
        for k in ["jsdoc", "jsdocLines", "lineProse", "blockProse", "directives", "typeCasts"]:
            c[k] += r["comments"][k]
    print(c)
    print("files with prose comments:")
    for r in res:
        cm = r["comments"]
        if cm["lineProse"] or cm["blockProse"]:
            print(" ", r["file"], "line", cm["lineProse"], "block", cm["blockProse"])
    ed = Counter()
    for r in res:
        for e in r["comments"]["eslintDisable"]:
            ed[e] += 1
    print("eslint directives:")
    for k, v in ed.most_common():
        print(" ", v, k)
elif mode == "candidates":
    agg = Counter()
    for r in res:
        for k, v in r["candidates"].items():
            agg[k] += len(v) if isinstance(v, list) else v
    for k, v in agg.items():
        print(k, v)
    print("per-file optChain/nullish/at/hasOwn/jsonClone/sliceSort/indexOf:")
    for r in res:
        c = r["candidates"]
        row = [len(c[k]) for k in ["optChain", "nullish", "atMinus1", "hasOwnProperty", "jsonClone", "sliceSort", "indexOfCmp"]]
        if sum(row):
            print(" ", r["file"], row)
elif mode == "sinks":
    cls = Counter()
    kinds = Counter()
    for r in res:
        for s in r["sinks"]:
            cls[s["cls"]] += 1
            kinds[s["kind"]] += 1
    print("sink kinds", kinds)
    print("sink classes", cls)
    print("trusted sinks", tot["trustedSink:setTrustedHtml"], tot["trustedSink:replaceWithTrustedHtml"])
    for r in res:
        for s in r["sinks"]:
            if s["cls"] == "unescaped-dynamic" or s["cls"] == "n/a":
                print(" ", r["file"], s["line"], s["kind"], s["cls"], s["leaves"])
    per = Counter()
    for r in res:
        per[r["file"]] = len(r["sinks"])
    print("per file sinks:", per.most_common(25))
elif mode == "fetch":
    mc = Counter()
    for r in res:
        for f in r["fetchCalls"]:
            mc[f["method"]] += 1
            print(" ", r["file"], f["line"], f["method"])
    print(mc)
elif mode == "deps":
    for r in res:
        print(r["file"], "deps:", {k: v for k, v in r["deps"].items()}, "| boot:", len(r["unresolvedBoot"]), "| other:", r["unresolvedOther"])
elif mode == "collisions":
    for c in d["collisions"]:
        print(c)
elif mode == "toplevel":
    for r in res:
        t = r["topLevel"]
        print(r["file"], "iife" if t["wrappedInIife"] else "", "fn", len(t["functions"]), "const", len(t["consts"]), "let", len(t["lets"]), "var", len(t["vars"]), "class", len(t["classes"]), "other", t["otherStatements"], "winW", len(r["windowWrites"]))
elif mode == "krtapi":
    agg = Counter()
    for r in res:
        for k, v in r["krtApiUse"].items():
            agg[k] += v
    for k, v in agg.most_common():
        print(v, k)
elif mode == "events":
    ev = Counter()
    for r in res:
        for a in r["krtEventsActions"]:
            ev[a] += 1
    print("krtEvents.on registrations", sum(ev.values()), "distinct", len(ev))
    listen = Counter()
    for k, v in tot.items():
        if k.startswith("listen:"):
            listen[k] = v
    print(listen.most_common(20))
elif mode == "files":
    for r in res:
        m = r["metrics"]
        print(r["file"], r["lines"], "tsc" if r["tsCheck"] else "-", "arrow", m.get("node:ArrowFunctionExpression", 0), "fnexpr", m.get("node:FunctionExpression", 0), "fndecl", m.get("node:FunctionDeclaration", 0), "opt", m.get("optionalChain", 0), "??", m.get("logical:??", 0), "tpl", m.get("templateLiteral:interp", 0) + m.get("templateLiteral:plain", 0), "concat", r["candidates"]["concatStr"], "sinks", len(r["sinks"]), "fetch", len(r["fetchCalls"]))
