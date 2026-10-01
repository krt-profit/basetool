"""Backend URL usage per frontend domain: call sites per domain and construction style, and the
backend resources each controller reaches outside its own domain. Read-only."""

import io
import json
import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
scan = json.load(open(os.path.join(HERE, "30-frontend-java-scan.json"), encoding="utf-8"))
dmap = json.load(open(os.path.join(HERE, "30-frontend-java-domainmap.json"), encoding="utf-8"))["domain_map"]
sys.path.insert(0, HERE)
scanmod = __import__("30-frontend-java-scan")

SEG_DOMAIN = {
    "missions": "mission", "finance-entries": "mission", "operations": "operation", "orders": "joborder",
    "inventory": "inventory", "personal-inventory": "personalinventory", "blueprints": "blueprint",
    "personal-blueprints": "blueprint", "admin/default-blueprints": "blueprint",
    "admin/personal-blueprints": "blueprint", "admin/personal-inventory": "personalinventory",
    "hangar": "hangar", "material-exchange": "materialexchange", "material-requests": "materialexchange",
    "refinery-orders": "refinery", "bank": "bank", "org-units/bank": "bank", "notifications": "notification",
    "notification-rules": "notification", "materials": "catalogue", "material-categories": "catalogue",
    "material-external-aliases": "catalogue", "locations": "catalogue", "cities": "catalogue",
    "outposts": "catalogue", "pois": "catalogue", "space-stations": "catalogue", "terminals": "catalogue",
    "star-systems": "catalogue", "ship-types": "catalogue", "manufacturers": "catalogue",
    "refining-methods": "catalogue", "job-types": "catalogue", "frequency-types": "catalogue",
    "uex": "catalogue", "sync-reports": "catalogue", "admin/import": "catalogue", "audit": "audit",
    "promotion": "promotion", "org-chart": "orgchart", "leitung": "leadership",
    "kommando-groups": "leadership", "squadrons": "orgunit", "special-commands": "orgunit",
    "org-hierarchy": "orgunit", "org-units": "orgunit", "users": "identity",
    "admin/registrations": "identity", "admin/deletion-requests": "identity",
    "admin/person-search": "identity", "admin/users": "identity", "terms": "identity",
    "admin/terms": "identity", "me": "identity", "announcement": "dashboard", "settings": "settings",
    "admin/exchange-clients": "exchange", "admin/exchange-settings": "exchange",
    "admin/exchange-undo-runs": "exchange", "connected-apps": "exchange",
}

API_LIT = re.compile(r'"(/api/v1/[^"]*)"')

def seg(path):
    rest = path[len("/api/v1/"):]
    parts = [p.split("?")[0] for p in rest.split("/")]
    if parts[0] == "org-units" and len(parts) > 1 and parts[1] == "bank":
        return "org-units/bank"
    if parts[0] in ("admin", "me") and len(parts) > 1 and parts[1] and not parts[1].startswith("{"):
        return parts[0] + "/" + parts[1]
    return parts[0]

ROOT = scanmod.ROOT
per_ctrl = {}
unknown = Counter()
for c in scan["classes"]:
    key = c["package"] + "." + c["name"]
    path = os.path.join(ROOT, c["file"].replace("/", os.sep))
    src = scanmod.strip_comments(open(path, encoding="utf-8").read())
    segs = Counter()
    for p in API_LIT.findall(src):
        s = seg(p)
        d = SEG_DOMAIN.get(s)
        if d is None:
            unknown[s] += 1
            d = "?" + s
        segs[d] += 1
    per_ctrl[key] = segs

print("unknown segments:", dict(unknown))
print()
print("classes whose /api/v1 literals reach another domain's backend resources:")
rows = []
for key, segs in per_ctrl.items():
    own = dmap.get(key, "?")
    foreign = {d: n for d, n in segs.items() if d != own}
    if foreign and not own.startswith("kernel"):
        rows.append((key, own, dict(segs), foreign))
    elif foreign and own.startswith("kernel"):
        rows.append((key, own, dict(segs), foreign))
rows.sort(key=lambda r: (-len(r[3]), r[0]))
for key, own, segs, foreign in rows:
    print(f"  {key.split('.')[-1]:45s} [{own}] foreign={foreign}")
print("count:", len(rows))

calls = [c for c in scan["calls"] if c["method"] in
         ("get", "post", "put", "delete", "patch", "getCached", "getTermsDocumentAnonymously")]
dom_calls = Counter()
dom_style = defaultdict(Counter)
for c in calls:
    key = c["file"].replace("/", ".")[:-5]
    d = dmap.get(key, "?")
    dom_calls[d] += 1
    style = c["class"]
    if style == "variable" and c["resolved"]:
        style = c["resolved"]
    if c["method"] == "get" and c["nargs"] >= 3:
        style = "template+vars"
    dom_style[d][style] += 1
print()
print("BackendApiClient HTTP call sites per class domain:")
for d, n in dom_calls.most_common():
    print(f"  {d:20s} {n:4d}  {dict(dom_style[d])}")
lits = Counter()
for key, segs in per_ctrl.items():
    for d, n in segs.items():
        lits[d] += n
print()
print("/api/v1 string literals per backend-resource domain (all classes):", sum(lits.values()))
for d, n in lits.most_common():
    print(f"  {d:20s} {n}")
