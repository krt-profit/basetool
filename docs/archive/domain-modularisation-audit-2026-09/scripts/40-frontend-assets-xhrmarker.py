import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")
BASE = r"$SCRATCHPAD"
JS = r"$REPO\frontend\src\main\resources\static\js"
d = json.load(open(os.path.join(BASE, "40-frontend-assets-jsast.json"), encoding="utf-8"))["results"]
tot = 0
marked = 0
unmarked = []
for r in d:
    if r["file"] in ("krt-fetch.js", "krt-client-error.js"):
        continue
    lines = open(os.path.join(JS, r["file"]), encoding="utf-8").read().split("\n")
    for f in r["fetchCalls"]:
        tot += 1
        win = "\n".join(lines[f["line"] - 1:f["line"] + 8])
        if any(k in win for k in ("X-Requested-With", "ajaxHeaders", "csrfRequestInit", "XHR_HEADERS", "HEADERS")):
            marked += 1
        else:
            unmarked.append((r["file"], f["line"]))
print("raw GET fetch", tot, "marker visible within 8 lines", marked, "not visible", len(unmarked))
for u in unmarked:
    print("  ", u)
