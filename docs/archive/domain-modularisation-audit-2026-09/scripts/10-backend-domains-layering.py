"""Check today's class graph against a proposed target layering of domain modules.

Pure re-homings that need no behaviour change are applied first (the privacy split out of identity,
leadership into orgunit, the access split into access-core and scope, three small value types).
Then every class edge from a lower-ranked module to a higher-ranked one is a violation: the backlog
of dependencies that must be inverted (SPI, event, id reference) before the module cut can be gated.
Same-rank edges are allowed only when the same-rank subgraph stays acyclic; any cycle is printed.
"""
import importlib.util
import os
from collections import Counter, defaultdict

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("graph", os.path.join(here, "10-backend-domains-graph.py"))
graph = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graph)
spec2 = importlib.util.spec_from_file_location("sim", os.path.join(here, "10-backend-domains-simulate.py"))
sim_mod = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(sim_mod)

RANK = {
    "shared-kernel": 0, "infrastructure": 0, "access-core": 0,
    "audit": 1, "notification": 1, "livesync": 1,
    "catalogue": 2,
    "identity": 3,
    "orgunit": 4,
    "scope": 5,
    "admin": 6, "dashboard": 6,
    "orgchart": 7, "promotion": 7, "personalinventory": 7, "hangar": 7, "blueprint": 7,
    "inventory": 8,
    "mission": 9,
    "refinery": 10, "joborder": 10, "materialexchange": 10,
    "operation": 11, "bank": 11,
    "exchange": 12,
    "privacy": 13,
    "app": 14,
}
APP = {"SecurityConfig", "DataInitializer", "BusinessMetricsCollector", "BackendApplication"}
ACCESS_CORE = {"AuthHelperService", "AuthenticatedSubject", "SubjectAuthentication", "Roles", "Permissions",
               "AuthoritiesCacheProperties", "PartialRoleScopeProperties", "OrgUnitContextualAuthority"}
REHOME = dict([(c, "privacy") for c in sim_mod.PRIVACY])
REHOME.update({"HandleAnonymisation": "shared-kernel", "PayoutPreference": "identity"})

def main():
    cls, folded = graph.load()
    dom = {}
    for f, r in cls.items():
        d = r["domain"]
        if d == "leadership":
            d = "orgunit"
        if d == "access":
            d = "access-core" if r["simple"] in ACCESS_CORE else "scope"
        d = REHOME.get(r["simple"], d)
        if r["simple"] in APP:
            d = "app"
        dom[f] = d
    rol = {f: graph.role(r) for f, r in cls.items()}
    sim = {f: r["simple"] for f, r in cls.items()}
    viol = defaultdict(list)
    same = defaultdict(int)
    ok = 0
    for a, b in folded:
        da, db = dom[a], dom[b]
        if da == db:
            continue
        if RANK[da] < RANK[db]:
            viol[(da, db)].append((a, b))
        elif RANK[da] == RANK[db]:
            same[(da, db)] += 1
        else:
            ok += 1
    total = sum(len(v) for v in viol.values())
    print("module sizes after re-homing:", dict(Counter(dom.values()).most_common()))
    print("allowed downward edges: %d, same-rank edges: %d, violations: %d class edges in %d module pairs" % (ok, sum(same.values()), total, len(viol)))
    print("same-rank pairs:", dict(same))
    adj = defaultdict(set)
    for (x, y) in same:
        adj[x].add(y)
    cyc = [c for c in graph.tarjan({x for k in same for x in k}, adj) if len(c) > 1]
    print("same-rank cycles:", cyc)
    by_src = Counter()
    for (x, y), lst in viol.items():
        by_src[x] += len(lst)
    print("violations by source module:", dict(by_src.most_common()))
    for (x, y), lst in sorted(viol.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        kc = Counter(graph.kind_label(rol[a], rol[b]) for a, b in lst)
        print("\n%s(%d) -> %s(%d): %d  %s" % (x, RANK[x], y, RANK[y], len(lst), dict(kc.most_common())))
        srcs = defaultdict(list)
        for a, b in sorted(lst):
            srcs[sim[a]].append(sim[b])
        for s, ts in sorted(srcs.items()):
            print("     %-40s -> %s" % (s, ", ".join(ts)))

if __name__ == "__main__":
    main()
