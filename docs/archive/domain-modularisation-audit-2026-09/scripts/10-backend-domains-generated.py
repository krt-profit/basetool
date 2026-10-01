"""List classes that appear in the jdeps graph but have no source file under backend/src/main/java."""
import importlib.util
import os
from collections import Counter

spec = importlib.util.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

_raw, edges = common.load_edges()
inv = common.load_inventory()
nodes = {a for a, _ in edges} | {b for _, b in edges}
missing = sorted(n for n in nodes if n not in inv)
print("graph nodes:", len(nodes), "inventory:", len(inv), "graph-only:", len(missing))
print(Counter(("Impl" if n.endswith("Impl") else "other") for n in missing))
for n in missing:
    if not n.endswith("MapperImpl"):
        print("  ", n)
print("inventory classes with no edge at all:", len([f for f in inv if f not in nodes]))
for f in inv:
    if f not in nodes:
        print("   isolated:", f)
