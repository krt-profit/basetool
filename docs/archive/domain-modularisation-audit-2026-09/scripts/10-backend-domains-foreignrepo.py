"""Which repositories, entities and services are used from other domains, and by whom.

Prints, per target domain, its classes that foreign domains depend on, grouped by target role
(repository / service / entity / dto / mapper / enum / support), with the using domains and classes.
This is the raw material for each module's minimal public API.
"""
import importlib.util
import os
import sys
from collections import defaultdict

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("graph", os.path.join(here, "10-backend-domains-graph.py"))
graph = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graph)

SKIP_SRC = {"shared-kernel", "infrastructure"}

def main(only):
    cls, folded = graph.load()
    dom = {f: r["domain"] for f, r in cls.items()}
    rol = {f: graph.role(r) for f, r in cls.items()}
    sim = {f: r["simple"] for f, r in cls.items()}
    used = defaultdict(lambda: defaultdict(set))
    for a, b in folded:
        if dom[a] != dom[b] and dom[a] not in SKIP_SRC:
            used[b][dom[a]].add(sim[a])
    by_dom = defaultdict(list)
    for t, users in used.items():
        by_dom[dom[t]].append(t)
    for d in sorted(by_dom):
        if only and d not in only:
            continue
        ts = by_dom[d]
        print("=== %s: %d classes used from outside (by %d domains)" % (d, len(ts), len({u for t in ts for u in used[t]})))
        for role in ("repository", "service", "entity", "model-type", "dto", "mapper", "support", "event", "exception", "controller", "config", "validation", "task", "integration"):
            sel = sorted((t for t in ts if rol[t] == role), key=lambda t: (-len(used[t]), sim[t]))
            if not sel:
                continue
            print("  [%s] %d" % (role, len(sel)))
            for t in sel:
                desc = "; ".join("%s(%s)" % (u, ",".join(sorted(used[t][u]))[:90]) for u in sorted(used[t]))
                print("    %-40s <- %s" % (sim[t], desc[:260]))

if __name__ == "__main__":
    main(set(sys.argv[1:]))
