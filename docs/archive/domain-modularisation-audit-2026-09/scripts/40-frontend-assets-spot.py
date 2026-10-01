import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
BASE = r"$SCRATCHPAD"
JS = r"$REPO\frontend\src\main\resources\static\js"
d = {r["file"]: r for r in json.load(open(os.path.join(BASE, "40-frontend-assets-jsast.json"), encoding="utf-8"))["results"]}
for f, key, n in [("bank.js", "optChain", 6), ("orders-detail.js", "nullish", 3), ("org-chart.js", "atMinus1", 2)]:
    src = open(os.path.join(JS, f), encoding="utf-8").read().split("\n")
    for ln in d[f]["candidates"][key][:n]:
        print(f, key, ln, src[ln - 1].strip()[:110])
