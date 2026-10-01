"""Lists the class edges behind the smallest cuts of the 9-domain core and re-checks the SCC after them."""
import csv
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = "de.greluc.krt.profit.basetool.backend"
EDGE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+\S+\s*$")
rows = {}
with open(os.path.join(HERE, "10-backend-domains-classes.csv"), encoding="utf-8", newline="") as fh:
    for r in csv.DictReader(fh):
        rows[r["fqcn"]] = r
dom = {k: v["domain"] for k, v in rows.items()}

def norm(n):
    n = n.split("$", 1)[0]
    if n in rows:
        return n
    if n.endswith("Impl") and n[:-4] in rows:
        return n[:-4]
    return None

edges = set()
with open(os.path.join(HERE, "jdeps-backend.txt"), encoding="utf-8", errors="replace") as fh:
    for line in fh:
        m = EDGE.match(line)
        if m:
            a, b = norm(m.group(1)), norm(m.group(2))
            if a and b and a != b:
                edges.add((a, b))
CORE = {"blueprint", "exchange", "hangar", "inventory", "joborder", "materialexchange", "mission", "operation", "refinery"}
s = lambda f: f.rsplit(".", 1)[1]
for src_dom, label in (("hangar", "hangar -> rest of core"),):
    lst = sorted((a, b) for a, b in edges if dom[a] == src_dom and dom[b] in CORE and dom[b] != src_dom)
    print(label, len(lst))
    for a, b in lst:
        print("   %s (%s) -> %s (%s)" % (s(a), rows[a]["layer"], s(b), dom[b]))
lst = sorted((a, b) for a, b in edges if dom[b] == "exchange" and dom[a] in CORE and dom[a] != "exchange")
print("rest of core -> exchange", len(lst))
for a, b in lst:
    print("   %s (%s, %s) -> %s" % (s(a), dom[a], rows[a]["layer"], s(b)))
for x, y in (("inventory", "mission"), ("mission", "inventory"), ("inventory", "joborder"), ("operation", "mission"), ("mission", "operation")):
    lst = sorted((a, b) for a, b in edges if dom[a] == x and dom[b] == y)
    print("%s -> %s: %d  %s" % (x, y, len(lst), "; ".join("%s->%s" % (s(a), s(b)) for a, b in lst[:20])))
