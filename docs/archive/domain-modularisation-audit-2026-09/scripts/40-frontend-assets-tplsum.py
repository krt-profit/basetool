import json
import sys
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8")
BASE = r"$SCRATCHPAD"
tpl = json.load(open(BASE + r"\40-frontend-assets-tpl.json", encoding="utf-8"))
js = json.load(open(BASE + r"\40-frontend-assets-jsast.json", encoding="utf-8"))["results"]
mode = sys.argv[1] if len(sys.argv) > 1 else "totals"

if mode == "totals":
    attrs = Counter()
    for r in tpl:
        for k, v in r["attrs"].items():
            attrs[k] += v
    print("templates", len(tpl), "lines", sum(r["lines"] for r in tpl), "bytes", sum(r["bytes"] for r in tpl))
    print("attrs", dict(attrs))
    print("style elements", sum(r["styleElements"] for r in tpl), "template elements", sum(r["templateElements"] for r in tpl), "dialog elements", sum(r["dialogElements"] for r in tpl))
    print("utext", sum(len(r["utext"]) for r in tpl), "unescaped [( inline", sum(r["unescapedInline"] for r in tpl))
    print("forms", Counter({k: sum(r["forms"][k] for r in tpl) for k in ["post", "get", "thAction"]}))
    print("buttons without type", sum(r["buttonsNoType"] for r in tpl), "img without alt", sum(r["imgNoAlt"] for r in tpl))
    print("modal-wrapper calls", sum(r["modalWrapperCalls"] for r in tpl))
    ext = [s for r in tpl for s in r["scripts"] if s["src"]]
    inl = [s for r in tpl for s in r["scripts"] if not s["src"]]
    print("external script tags", len(ext), "defer", sum(1 for s in ext if s["defer"]), "async", sum(1 for s in ext if s["async"]), "module", sum(1 for s in ext if s["type"] == "module"), "nonce", sum(1 for s in ext if s["nonce"]))
    print("inline script blocks", len(inl), "th:inline js", sum(1 for s in inl if s["thInline"] == "javascript"), "nonce", sum(1 for s in inl if s["nonce"]), "lines", sum(s["lines"] for s in inl))
    dl = Counter()
    lg = Counter()
    fails = []
    for r in tpl:
        for s in r["scripts"]:
            if s["src"]:
                continue
            i = s["inline"]
            if i["parse"] != "ok":
                fails.append((r["file"], i["parse"][:80]))
                continue
            dl["data"] += i["data"]
            dl["logic"] += i["logic"]
            dl["var"] += i["vars"]
            for k, v in i["logicKinds"].items():
                lg[k] += v
    print("inline top-level statements", dict(dl))
    print("inline logic kinds", dict(lg))
    print("inline parse failures", fails)
    print("utext values:")
    for r in tpl:
        for u in r["utext"]:
            print("  ", r["file"], u)
elif mode == "inline":
    for r in tpl:
        for s in r["scripts"]:
            if s["src"]:
                continue
            i = s["inline"]
            if i["parse"] != "ok" or i["logic"]:
                print(r["file"], "lines", s["lines"], "thInline", s["thInline"], "cond", s["cond"], "data", i.get("data"), "logic", i.get("logic"), i.get("logicKinds"), i["parse"][:60])
elif mode == "pages":
    for r in tpl:
        if r["file"].startswith("fragments/"):
            continue
        own = [s["src"] for s in r["scripts"] if s["src"]]
        print(r["file"], "| eff scripts", len(r["effectiveScripts"]), "| own", own, "| links", [l for l in r["effectiveLinks"] if l != "css/styles.css" and l != "css/inline-migration.css"])
elif mode == "jsusage":
    use = defaultdict(set)
    for r in tpl:
        for s in r["scripts"]:
            if s["src"]:
                use[s["src"]].add(r["file"])
    for j in js:
        key = "js/" + j["file"]
        print(j["file"], sorted(use.get(key, [])))
    known = {"js/" + j["file"] for j in js}
    print("referenced but missing:", sorted(set(use) - known))
elif mode == "cssusage":
    use = defaultdict(set)
    for r in tpl:
        for l in r["links"]:
            use[l].add(r["file"])
    for k in sorted(use):
        print(k, sorted(use[k]))
elif mode == "frags":
    c = Counter()
    users = defaultdict(set)
    for r in tpl:
        for fr in r["fragRefs"]:
            key = fr["template"] + " :: " + fr["fragment"]
            c[key] += 1
            users[key].add(r["file"])
    for k, v in c.most_common():
        print(v, len(users[k]), k)
    print("defs:")
    for r in tpl:
        if r["fragDefs"]:
            print(" ", r["file"], r["fragDefs"])
elif mode == "triggers":
    c = Counter()
    for r in tpl:
        for k, v in r["dataTriggers"].items():
            c[k] += v
    print("data-trigger distinct", len(c), "total", sum(c.values()))
    print(c.most_common(40))
elif mode == "toastgap":
    js_by = {j["file"]: j for j in js}
    for r in tpl:
        if r["file"].startswith("fragments/") or r["file"].startswith("error"):
            continue
        eff = r["effectiveScripts"]
        has_toast = "js/toast.js" in eff
        writers = []
        for s in eff:
            f = s.replace("js/", "")
            j = js_by.get(f)
            if not j:
                continue
            uses = j["krtApiUse"]
            if any(k.startswith("krtFetch.write") or k.startswith("krtFetch.submitForm") for k in uses) or j["file"] == "krt-fetch.js" and False:
                writers.append(f)
        if writers and not has_toast:
            print(r["file"], "writes via", writers, "but no toast.js")
