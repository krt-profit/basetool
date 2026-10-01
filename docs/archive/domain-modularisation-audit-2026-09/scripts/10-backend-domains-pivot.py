"""Print the domain x domain pivot (class edges) and the mutual (two-way) pairs from 10-backend-domains-graph.json."""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(HERE, "10-backend-domains-graph.json"), encoding="utf-8") as fh:
    g = json.load(fh)

ABBR = {
    "access": "acc", "admin": "adm", "audit": "aud", "bank": "bnk", "blueprint": "bp", "catalogue": "cat",
    "dashboard": "dsh", "exchange": "xch", "hangar": "hng", "identity": "idn", "infrastructure": "INF",
    "inventory": "inv", "joborder": "job", "leadership": "lead", "livesync": "lsy", "materialexchange": "mxc",
    "mission": "msn", "notification": "ntf", "operation": "op", "orgchart": "och", "orgunit": "org",
    "personalinventory": "pinv", "promotion": "prm", "refinery": "ref", "shared-kernel": "SK",
}
order = ["shared-kernel", "infrastructure", "catalogue", "identity", "orgunit", "access", "audit", "notification", "livesync",
         "admin", "dashboard", "leadership", "orgchart", "promotion", "personalinventory", "hangar", "blueprint",
         "inventory", "mission", "refinery", "operation", "joborder", "materialexchange", "bank", "exchange"]
p = g["pivot"]
print("rows = depending domain (source), columns = depended-upon domain (target); '.' = 0")
print("%-6s" % "" + "".join("%5s" % ABBR[d] for d in order))
for s in order:
    print("%-6s" % ABBR[s] + "".join(("%5d" % p[s][t]) if p[s][t] else "%5s" % "." for t in order))
print()
print("Two-way pairs (A->B and B->A), excluding shared-kernel/infrastructure:")
seen = set()
biz = [d for d in order if d not in ("shared-kernel", "infrastructure")]
pairs = []
for a in biz:
    for b in biz:
        if a < b and p[a][b] and p[b][a]:
            pairs.append((min(p[a][b], p[b][a]), a, b, p[a][b], p[b][a]))
for w, a, b, ab, ba in sorted(pairs, reverse=True):
    print("  %-17s <-> %-17s  %4d / %4d   (lighter direction %d)" % (a, b, ab, ba, w))
print("count of two-way pairs:", len(pairs))
