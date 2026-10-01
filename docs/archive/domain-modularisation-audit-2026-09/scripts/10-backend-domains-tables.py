"""Render the appendix tables of 10-backend-domains.md from the data files (printed as Markdown)."""
import csv
import json
import os
from collections import Counter, OrderedDict, defaultdict

here = os.path.dirname(os.path.abspath(__file__))

def load_csv(name):
    with open(os.path.join(here, name), encoding="utf-8") as fh:
        return list(csv.DictReader(fh))

def writes_table():
    rows = [r for r in load_csv("10-backend-domains-writes.csv") if r["target_domain"] != "audit" and r["caller_domain"] != "infrastructure"]
    grp = OrderedDict()
    for r in sorted(rows, key=lambda r: (r["caller_domain"], r["target_domain"], r["caller"], r["caller_method"], int(r["where"].rsplit(":", 1)[1]))):
        k = (r["caller_domain"], r["target_domain"], r["caller"], r["caller_method"], r["caller_tx"])
        grp.setdefault(k, []).append(r)
    print("| caller domain → target | caller method (tx) | lines | what it writes | pattern |")
    print("| --- | --- | --- | --- | --- |")
    for (cd, td, c, m, tx), lst in grp.items():
        lines = sorted({int(x["where"].rsplit(":", 1)[1]) for x in lst})
        what = Counter()
        pats = set()
        for x in lst:
            if x["kind"] == "entity-mutation":
                what["%s.set*" % x["target"]] += 1
            else:
                what["%s.%s" % (x["target"], x["target_method"])] += 1
            tags = x["target_tags"]
            if "MANDATORY" in tags:
                pats.add("MANDATORY")
            if "lock:" in tags:
                pats.add(tags.replace("lock:", ""))
            if "modifying" in tags:
                pats.add("@Modifying")
            if "REQUIRES_NEW" in tags or x["caller_tx"] == "REQUIRES_NEW":
                pats.add("REQUIRES_NEW")
        path = lst[0]["where"].rsplit(":", 1)[0].split("/")[-1]
        span = "%s:%s" % (path, ",".join(str(l) for l in lines[:6]) + ("…" if len(lines) > 6 else ""))
        print("| %s → %s | `%s.%s` (%s) | %s | %s | %s |" % (cd, td, c, m, tx, span, ", ".join("%s×%d" % kv if kv[1] > 1 else kv[0] for kv in what.items()), ", ".join(sorted(pats)) or "-"))
    print("\n%d sites in %d caller methods" % (len(rows), len(grp)))

def domain_table():
    cards = json.load(open(os.path.join(here, "10-backend-domains-cards.json"), encoding="utf-8"))
    print("| module | classes | LOC | endpoints | entities | Ce edges → modules | Ca edges ← modules |")
    print("| --- | ---: | ---: | ---: | ---: | --- | --- |")
    for d, c in sorted(cards.items(), key=lambda kv: -kv[1]["loc"]):
        ce = sum(c["out"].values())
        ca = sum(c["in"].values())
        print("| %s | %d | %d | %d | %d | %d → %d | %d ← %d |" % (d, c["classes"], c["loc"], c["endpoints"], len(c["entities"]), ce, len(c["out"]), ca, len(c["in"])))

if __name__ == "__main__":
    domain_table()
    print()
    writes_table()
