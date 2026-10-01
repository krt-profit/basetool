"""Print the folded out- and in-neighbours of the given simple class names (exploration helper).

Usage: python 10-backend-domains-neighbors.py Name1 Name2 ...
"""
import importlib.util
import os
import sys

spec = importlib.util.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

def main(names):
    _raw, edges = common.load_edges()
    out, inc = common.adjacency(edges)
    inv = common.load_inventory()
    by_simple = {}
    for f in inv:
        by_simple.setdefault(common.simple(f), []).append(f)
    for n in names:
        for f in by_simple.get(n, []):
            r = inv[f]
            print("=== %s [%s] loc=%d :: %s" % (n, r["subpackage"], r["loc"], r["summary"][:160]))
            print("  OUT:", " ".join(sorted(common.simple(x) for x in out.get(f, ()))))
            print("  IN :", " ".join(sorted(common.simple(x) for x in inc.get(f, ()))))

if __name__ == "__main__":
    main(sys.argv[1:])
