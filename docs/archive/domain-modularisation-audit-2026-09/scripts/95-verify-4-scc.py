"""Independent re-computation of DOM-01: domain-level and class-level SCCs, robustness variants,
minimum class-edge sets that keep the business core strongly connected, cut and feedback sizes.

Inputs: jdeps-backend.txt, 10-backend-domains-classes.csv (both in this directory). Own loader,
does not import the DOM scripts.
"""
import csv
import itertools
import os
import re
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = "de.greluc.krt.profit.basetool.backend"
EDGE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+\S+\s*$")
KERNEL = {"shared-kernel", "infrastructure"}

rows = {}
with open(os.path.join(HERE, "10-backend-domains-classes.csv"), encoding="utf-8", newline="") as fh:
    for r in csv.DictReader(fh):
        rows[r["fqcn"]] = r
dom = {k: v["domain"] for k, v in rows.items()}
layer = {k: v["layer"] for k, v in rows.items()}
stereo = {k: set(v["stereotypes"].split("|")) if v["stereotypes"] else set() for k, v in rows.items()}
ambiguous = {k for k, v in rows.items() if v["ambiguous"]}

def norm(n):
    n = n.split("$", 1)[0]
    if n in rows:
        return n
    if n.endswith("Impl") and n[:-4] in rows:
        return n[:-4]
    return None

raw = 0
unknown = Counter()
edges = set()
with open(os.path.join(HERE, "jdeps-backend.txt"), encoding="utf-8", errors="replace") as fh:
    for line in fh:
        m = EDGE.match(line)
        if not m:
            continue
        raw += 1
        a0, b0 = m.group(1), m.group(2)
        if not (a0.startswith(BASE) and b0.startswith(BASE)):
            continue
        a, b = norm(a0), norm(b0)
        if a is None:
            unknown[a0.split("$", 1)[0]] += 1
            continue
        if b is None:
            unknown[b0.split("$", 1)[0]] += 1
            continue
        if a != b:
            edges.add((a, b))

print("raw jdeps edge lines:", raw)
print("folded backend class edges (self dropped):", len(edges))
print("edge endpoints not in CSV (distinct classes):", len(unknown), list(unknown.items())[:10])

def role(c):
    ly = layer[c]
    if ly == "model":
        return "entity" if stereo[c] & {"Entity", "MappedSuperclass", "Embeddable"} else "model-type"
    if ly in ("dto", "dto-external", "projection"):
        return "dto"
    return ly

def tarjan(nodes, adj):
    """Iterative Tarjan; returns list of SCCs (sorted lists)."""
    index, low, on, stack, out = {}, {}, set(), [], []
    counter = 0
    for root in sorted(nodes):
        if root in index:
            continue
        work = [(root, iter(sorted(adj.get(root, ()))))]
        index[root] = low[root] = counter
        counter += 1
        stack.append(root)
        on.add(root)
        while work:
            v, it = work[-1]
            advanced = False
            for w in it:
                if w not in nodes:
                    continue
                if w not in index:
                    index[w] = low[w] = counter
                    counter += 1
                    stack.append(w)
                    on.add(w)
                    work.append((w, iter(sorted(adj.get(w, ())))))
                    advanced = True
                    break
                elif w in on:
                    low[v] = min(low[v], index[w])
            if advanced:
                continue
            work.pop()
            if work:
                u = work[-1][0]
                low[u] = min(low[u], low[v])
            if low[v] == index[v]:
                comp = []
                while True:
                    w = stack.pop()
                    on.discard(w)
                    comp.append(w)
                    if w == v:
                        break
                out.append(sorted(comp))
    return out

def domain_graph(edge_set, drop_classes=frozenset()):
    cell = defaultdict(list)
    for a, b in edge_set:
        if a in drop_classes or b in drop_classes:
            continue
        da, db = dom[a], dom[b]
        if da != db and da not in KERNEL and db not in KERNEL:
            cell[(da, db)].append((a, b))
    return cell

ALL = sorted({d for d in dom.values() if d not in KERNEL})
print("non-kernel categories:", len(ALL))

def scc_report(label, cell, nodes):
    adj = defaultdict(set)
    for (x, y) in cell:
        if x in nodes and y in nodes:
            adj[x].add(y)
    comps = [c for c in tarjan(set(nodes), adj) if len(c) > 1]
    comps.sort(key=len, reverse=True)
    sizes = [len(c) for c in comps]
    outside = sorted(set(nodes) - set().union(*map(set, comps))) if comps else sorted(nodes)
    print("\n[%s] nodes=%d  SCC sizes=%s" % (label, len(nodes), sizes))
    for c in comps:
        print("   SCC:", ", ".join(c))
    print("   outside any SCC:", ", ".join(outside))
    return comps

full = domain_graph(edges)
print("cross-domain class edges between non-kernel categories:", sum(len(v) for v in full.values()))
print("two-way domain pairs:", sum(1 for (x, y) in full if (y, x) in full and x < y))
c_full = scc_report("full", full, ALL)

PLATFORM = {"access", "audit", "notification", "livesync"}
nodes_a = [d for d in ALL if d not in PLATFORM]
c_a = scc_report("(a) without access/audit/notification/livesync", full, nodes_a)
nodes_b = [d for d in nodes_a if d not in {"identity", "orgunit", "catalogue"}]
c_b = scc_report("(b) also without identity/orgunit/catalogue (prompt)", full, nodes_b)
nodes_b2 = [d for d in nodes_b if d != "leadership"]
c_b2 = scc_report("(b') also without leadership (DOM wording)", full, nodes_b2)

print("\n--- robustness variants on the full node set ---")
scc_report("(c) ambiguous classes (42) removed", domain_graph(edges, frozenset(ambiguous)), ALL)
scc_report("(c) ambiguous removed, platform removed", domain_graph(edges, frozenset(ambiguous)), nodes_a)
scc_report("(c) ambiguous removed, platform+identity/orgunit/catalogue removed", domain_graph(edges, frozenset(ambiguous)), nodes_b)

STRONG_T = {"service", "repository", "entity"}
strong = {(a, b) for (a, b) in edges if role(b) in STRONG_T}
scc_report("(d) only edges whose target is a service/repository/entity", domain_graph(strong), ALL)
scc_report("(d) same, platform removed", domain_graph(strong), nodes_a)
scc_report("(d) same, platform+identity/orgunit/catalogue removed", domain_graph(strong), nodes_b)

BEH_T = {"service", "repository"}
beh = {(a, b) for (a, b) in edges if role(b) in BEH_T}
scc_report("(e) only edges whose target is a service/repository", domain_graph(beh), ALL)
scc_report("(e) same, platform removed", domain_graph(beh), nodes_a)
scc_report("(e) same, platform+identity/orgunit/catalogue removed", domain_graph(beh), nodes_b)

ent = {(a, b) for (a, b) in edges if role(a) == "entity" and role(b) == "entity"}
scc_report("(f) only entity->entity edges (JPA model)", domain_graph(ent), ALL)

nodto = {(a, b) for (a, b) in edges if role(b) not in {"dto", "model-type", "exception", "event"}}
scc_report("(g) drop edges into DTO/enum/exception/event types", domain_graph(nodto), ALL)
scc_report("(g) same, platform+identity/orgunit/catalogue removed", domain_graph(nodto), nodes_b)

core = c_b2[0] if c_b2 else []
print("\n=== core analysis on:", core)
cell = {k: v for k, v in full.items() if k[0] in core and k[1] in core}
arcs = sorted(cell)
print("domain arcs inside the core:", len(arcs), " class edges inside the core:", sum(len(v) for v in cell.values()))
for (x, y) in sorted(arcs, key=lambda k: -len(cell[k])):
    ex = sorted(cell[(x, y)])[0]
    print("   %-17s -> %-17s %3d   e.g. %s -> %s" % (x, y, len(cell[(x, y)]), ex[0].rsplit(".", 1)[1], ex[1].rsplit(".", 1)[1]))

n = len(core)
idx = {d: i for i, d in enumerate(core)}
w = [[0] * n for _ in range(n)]
for (x, y), v in cell.items():
    w[idx[x]][idx[y]] = len(v)

ham = []
first = core[0]
for perm in itertools.permutations(core[1:]):
    cyc = (first,) + perm
    if all(w[idx[cyc[i]]][idx[cyc[(i + 1) % n]]] for i in range(n)):
        ham.append(cyc)
print("\nHamiltonian cycles among the core's domain arcs:", len(ham))
PREF = ["entity", "service", "repository", "mapper", "controller", "support", "dto", "model-type"]

def pick(x, y):
    lst = sorted(cell[(x, y)], key=lambda e: (PREF.index(role(e[1])) if role(e[1]) in PREF else 99, e))
    return lst[0]

if ham:
    best = max(ham, key=lambda c: min(w[idx[c[i]]][idx[c[(i + 1) % n]]] for i in range(n)))
    print("  a minimum strongly-connected spanning set therefore has %d class edges; one of them:" % n)
    for i in range(n):
        x, y = best[i], best[(i + 1) % n]
        a, b = pick(x, y)
        print("   %-17s -> %-17s (%d backing)  %s -> %s" % (x, y, len(cell[(x, y)]), a.rsplit(".", 1)[1], b.rsplit(".", 1)[1]))
    lo = min(ham, key=lambda c: sum(w[idx[c[i]]][idx[c[(i + 1) % n]]] for i in range(n)))
    print("  weakest Hamiltonian cycle (fewest backing edges):", " -> ".join(lo), [w[idx[lo[i]]][idx[lo[(i + 1) % n]]] for i in range(n)])
    strongest = max(ham, key=lambda c: min(w[idx[c[i]]][idx[c[(i + 1) % n]]] for i in range(n)))
    print("  most redundant Hamiltonian cycle (max of min backing):", " -> ".join(strongest), [w[idx[strongest[i]]][idx[strongest[(i + 1) % n]]] for i in range(n)])

best_cut = None
for mask in range(1, (1 << n) - 1):
    s = [i for i in range(n) if mask >> i & 1]
    t = [i for i in range(n) if not mask >> i & 1]
    cut = sum(w[i][j] for i in s for j in t)
    if best_cut is None or cut < best_cut[0]:
        best_cut = (cut, [core[i] for i in s], [core[j] for j in t])
print("\nmin class edges to delete so the core is no longer one SCC: %d  (all edges from {%s} to {%s})" % (best_cut[0], ", ".join(best_cut[1]), ", ".join(best_cut[2])))
cuts = []
for mask in range(1, (1 << n) - 1):
    s = [i for i in range(n) if mask >> i & 1]
    t = [i for i in range(n) if not mask >> i & 1]
    cut = sum(w[i][j] for i in s for j in t)
    cuts.append((cut, sorted(core[i] for i in s)))
cuts.sort()
print("  five smallest cuts:", cuts[:5])

INF = 10 ** 9
f = [INF] * (1 << n)
choice = [None] * (1 << n)
f[0] = 0
for mask in range(1, 1 << n):
    for v in range(n):
        if not mask >> v & 1:
            continue
        rest = mask ^ (1 << v)
        cost = f[rest] + sum(w[u][v] for u in range(n) if rest >> u & 1)
        if cost < f[mask]:
            f[mask] = cost
            choice[mask] = v
order = []
mask = (1 << n) - 1
while mask:
    v = choice[mask]
    order.append(core[v])
    mask ^= 1 << v
order.reverse()
print("\nexact minimum feedback (class edges to remove to make the core acyclic): %d" % f[(1 << n) - 1])
print("  optimal layering (foundation first):", " < ".join(order))
pos = {d: i for i, d in enumerate(order)}
back = sorted(((x, y, len(cell[(x, y)])) for (x, y) in cell if pos[x] < pos[y]), key=lambda t: -t[2])
print("  back arcs (lower depends on higher):", back)

print("\n=== class-level SCCs (folded class graph, all 1389 classes) ===")
adjc = defaultdict(set)
for a, b in edges:
    adjc[a].add(b)
comps = [c for c in tarjan(set(rows), adjc) if len(c) > 1]
comps.sort(key=len, reverse=True)
print("class-level SCCs with >1 class:", len(comps), " sizes (top 10):", [len(c) for c in comps[:10]])
big = comps[0]
bd = Counter(dom[c] for c in big)
print("largest class-level SCC: %d classes spanning %d categories: %s" % (len(big), len(bd), sorted(bd.items(), key=lambda t: -t[1])))
nk = sorted(d for d in bd if d not in KERNEL)
print("  non-kernel categories in it: %d -> %s" % (len(nk), ", ".join(nk)))
missing = sorted(set(c_full[0]) - set(nk))
print("  categories of the domain-level SCC NOT in the largest class-level SCC:", missing)
for c in comps[1:6]:
    print("  next SCC: %d classes, categories %s" % (len(c), sorted(Counter(dom[x] for x in c).items())))

edges_nk = {(a, b) for (a, b) in edges if dom[a] not in KERNEL and dom[b] not in KERNEL}
adjn = defaultdict(set)
for a, b in edges_nk:
    adjn[a].add(b)
comps2 = [c for c in tarjan({c for c in rows if dom[c] not in KERNEL}, adjn) if len(c) > 1]
comps2.sort(key=len, reverse=True)
bd2 = Counter(dom[c] for c in comps2[0])
print("class-level, kernel/infrastructure classes removed: largest SCC %d classes, %d categories: %s" % (len(comps2[0]), len(bd2), ", ".join(sorted(bd2))))
core_set = set(core)
edges_core = {(a, b) for (a, b) in edges if dom[a] in core_set and dom[b] in core_set}
adjk = defaultdict(set)
for a, b in edges_core:
    adjk[a].add(b)
comps3 = [c for c in tarjan({c for c in rows if dom[c] in core_set}, adjk) if len(c) > 1]
comps3.sort(key=len, reverse=True)
if comps3:
    bd3 = Counter(dom[c] for c in comps3[0])
    print("class-level restricted to the core's classes: largest SCC %d classes, %d categories: %s" % (len(comps3[0]), len(bd3), sorted(bd3.items(), key=lambda t: -t[1])))
