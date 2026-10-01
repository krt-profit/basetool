import json
import sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")
BASE = r"$SCRATCHPAD"
d = json.load(open(BASE + r"\40-frontend-assets-css.json", encoding="utf-8"))
res = d["results"]
mode = sys.argv[1] if len(sys.argv) > 1 else "totals"

tot = Counter()
fw = Counter()
for r in res:
    for k, v in r["metrics"].items():
        tot[k] += v
        if v:
            fw[k] += 1
if mode == "totals":
    print("files", len(res), "lines", sum(r["lines"] for r in res), "bytes", sum(r["bytes"] for r in res))
    print("layer statement present", sum(1 for r in res if r["layerStatement"]), "first node", sum(1 for r in res if r["layerStatement"] and r["layerStatement"]["index"] == 0))
    print("layer statements distinct", Counter(r["layerStatement"]["params"] if r["layerStatement"] else None for r in res))
    lu = Counter()
    for r in res:
        for k, v in r["layersUsed"].items():
            lu[k] += v
    print("rules per layer", lu)
    print("unlayered rules", sum(r["unlayered"] for r in res))
    print("nested rules", sum(r["nestedRules"] for r in res), "files", sum(1 for r in res if r["nestedRules"]), "ampersand", sum(r["ampersand"] for r in res))
    print("important", sum(r["important"] for r in res), "files", [(r["file"], r["important"]) for r in res if r["important"]])
    print("comments", sum(len(r["comments"]) for r in res), [(r["file"], len(r["comments"])) for r in res if r["comments"]][:30])
    print("custom props defined", sum(r["customPropsDefined"] for r in res), "var uses", sum(r["varUses"] for r in res), "hex", sum(r["hex"] for r in res))
    print("id selectors", sum(r["idSelectors"] for r in res))
    ps = Counter()
    for r in res:
        for k, v in r["pseudo"].items():
            ps[k] += v
    print("pseudo", ps.most_common(40))
    mp = Counter()
    for r in res:
        for k, v in r["mediaParams"].items():
            mp[k] += v
    print("media params", mp)
    for k in sorted(tot):
        print(f"{k}\t{tot[k]}\t{fw[k]}")
    print("max specificity top:", sorted(((tuple(r["maxSpecificity"]), r["file"]) for r in res), reverse=True)[:8])
    print("dead tokens", d["deadTokens"])
    print("undefined tokens", d["undefinedTokens"])
    print("krtm classes", d["migClasses"], "dead", d["deadMig"])
elif mode == "files":
    for r in res:
        print(r["file"], r["lines"], "layers", r["layersUsed"], "unlayered", r["unlayered"], "nested", r["nestedRules"], "imp", r["important"], "cmt", len(r["comments"]), "vars", r["varUses"], "hex", r["hex"])
