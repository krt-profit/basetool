"""Raw data for the module cards: per domain its entities, size, endpoints, inbound and outbound coupling."""
import csv
import importlib.util
import json
import os
from collections import Counter, defaultdict

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("graph", os.path.join(here, "10-backend-domains-graph.py"))
graph = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graph)

NON = {"shared-kernel", "infrastructure"}

def main():
    cls, folded = graph.load()
    dom = {f: r["domain"] for f, r in cls.items()}
    rol = {f: graph.role(r) for f, r in cls.items()}
    with open(os.path.join(here, "10-backend-domains-endpoints.csv"), encoding="utf-8") as fh:
        eps = list(csv.DictReader(fh))
    ep_by = Counter(e["controller_domain"] for e in eps)
    ctrl_by = defaultdict(set)
    for e in eps:
        ctrl_by[e["controller_domain"]].add(e["controller"])
    with open(os.path.join(here, "10-backend-domains-jpa.csv"), encoding="utf-8") as fh:
        jpa = list(csv.DictReader(fh))
    with open(os.path.join(here, "10-backend-domains-writes.csv"), encoding="utf-8") as fh:
        writes = list(csv.DictReader(fh))
    out = defaultdict(Counter)
    inc = defaultdict(Counter)
    for a, b in folded:
        if dom[a] != dom[b] and dom[a] not in NON and dom[b] not in NON:
            out[dom[a]][dom[b]] += 1
            inc[dom[b]][dom[a]] += 1
    cards = {}
    for d in sorted(set(dom.values()) - NON):
        members = [f for f in cls if dom[f] == d]
        ents = sorted(cls[f]["simple"] for f in members if rol[f] == "entity")
        loc = sum(int(cls[f]["loc"]) for f in members)
        jin = [j for j in jpa if j["cross"] and j["target_domain"] == d]
        jout = [j for j in jpa if j["cross"] and j["owner_domain"] == d]
        win = [w for w in writes if w["target_domain"] == d and w["caller_domain"] != d]
        wout = [w for w in writes if w["caller_domain"] == d and w["target_domain"] != d]
        cards[d] = {
            "classes": len(members), "loc": loc, "layers": dict(Counter(rol[f] for f in members)),
            "entities": ents, "endpoints": ep_by.get(d, 0), "controllers": sorted(ctrl_by.get(d, [])),
            "out": dict(out[d].most_common()), "in": dict(inc[d].most_common()),
            "jpa_in": len(jin), "jpa_out": len(jout),
            "writes_in": dict(Counter(w["caller_domain"] for w in win)),
            "writes_out": dict(Counter(w["target_domain"] for w in wout)),
        }
        c = cards[d]
        print("=== %s  classes=%d loc=%d endpoints=%d entities=%d jpa(in/out)=%d/%d" % (d, c["classes"], c["loc"], c["endpoints"], len(ents), c["jpa_in"], c["jpa_out"]))
        print("   entities:", ", ".join(ents))
        print("   out:", c["out"])
        print("   in :", c["in"])
        print("   foreign writes in (sites by caller domain):", c["writes_in"])
        print("   foreign writes out (sites by target domain):", c["writes_out"])
        print("   controllers:", ", ".join(c["controllers"]))
    with open(os.path.join(here, "10-backend-domains-cards.json"), "w", encoding="utf-8") as fh:
        json.dump(cards, fh, indent=1)

if __name__ == "__main__":
    main()
