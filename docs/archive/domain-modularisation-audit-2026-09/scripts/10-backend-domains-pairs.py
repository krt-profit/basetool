"""List every class edge between two domains, both directions, with the edge kind.

Usage: python 10-backend-domains-pairs.py domA:domB [domC:domD ...]
"""
import importlib.util
import os
import sys
from collections import Counter

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("common", os.path.join(here, "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)
spec2 = importlib.util.spec_from_file_location("graph", os.path.join(here, "10-backend-domains-graph.py"))
graph = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(graph)

def main(args):
    cls, folded = graph.load()
    dom = {f: r["domain"] for f, r in cls.items()}
    rol = {f: graph.role(r) for f, r in cls.items()}
    sim = {f: r["simple"] for f, r in cls.items()}
    for pair in args:
        a, b = pair.split(":")
        for x, y in ((a, b), (b, a)):
            lst = sorted((s, t) for s, t in folded if dom[s] == x and dom[t] == y)
            kc = Counter(graph.kind_label(rol[s], rol[t]) for s, t in lst)
            print("=== %s -> %s : %d edges %s" % (x, y, len(lst), dict(kc.most_common())))
            for s, t in lst:
                print("    %-42s -> %-40s [%s]" % (sim[s], sim[t], graph.kind_label(rol[s], rol[t])))

if __name__ == "__main__":
    main(sys.argv[1:])
