"""Exact minimum weighted feedback-arc set (class edges) over the 21-domain SCC and the 9-domain core,
compared with the DOM agent's heuristic (211 back edges in 52 pairs) and its target layering (§5.5).

Subset DP: f[S] = min over v in S of f[S minus v] + weight(S minus v -> v); an arc x->y (x depends on y)
is a back arc when x sits below y.
"""
import csv
import os
import re
from collections import defaultdict

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = "de.greluc.krt.profit.basetool.backend"
EDGE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+\S+\s*$")
KERNEL = {"shared-kernel", "infrastructure"}

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
        if not m:
            continue
        a, b = norm(m.group(1)), norm(m.group(2))
        if a and b and a != b:
            edges.add((a, b))
cell = defaultdict(int)
for a, b in edges:
    da, db = dom[a], dom[b]
    if da != db and da not in KERNEL and db not in KERNEL:
        cell[(da, db)] += 1

def exact_fas(nodes):
    n = len(nodes)
    N = 1 << n
    ix = {d: i for i, d in enumerate(nodes)}
    w = np.zeros((n, n), dtype=np.int64)
    for (x, y), c in cell.items():
        if x in ix and y in ix:
            w[ix[x], ix[y]] = c
    m = np.arange(N, dtype=np.int64)
    bits = [((m >> u) & 1).astype(np.int32) for u in range(n)]
    pc = np.zeros(N, dtype=np.int32)
    for u in range(n):
        pc += bits[u]
    in_w = []
    for v in range(n):
        arr = np.zeros(N, dtype=np.int32)
        for u in range(n):
            if w[u, v]:
                arr += bits[u] * int(w[u, v])
        in_w.append(arr)
    big = np.int32(1 << 30)
    f = np.full(N, big, dtype=np.int32)
    f[0] = 0
    order_idx = np.argsort(pc, kind="stable")
    bounds = np.searchsorted(pc[order_idx], np.arange(n + 2))
    for k in range(1, n + 1):
        M = order_idx[bounds[k]:bounds[k + 1]]
        for v in range(n):
            sel = M[bits[v][M] == 1]
            prev = sel ^ (1 << v)
            cand = f[prev] + in_w[v][prev]
            f[sel] = np.minimum(f[sel], cand)
    full = N - 1
    order = []
    mask = full
    while mask:
        for v in range(n):
            if mask >> v & 1:
                rest = mask ^ (1 << v)
                if f[rest] + in_w[v][rest] == f[mask]:
                    order.append(nodes[v])
                    mask = rest
                    break
    order.reverse()
    pos = {d: i for i, d in enumerate(order)}
    back = sorted(((x, y, c) for (x, y), c in cell.items() if x in pos and y in pos and pos[x] < pos[y]), key=lambda t: -t[2])
    return int(f[full]), order, back

def layering_cost(nodes, rank):
    back = sorted(((x, y, c) for (x, y), c in cell.items() if x in nodes and y in nodes and rank[x] < rank[y]), key=lambda t: -t[2])
    same = sorted(((x, y, c) for (x, y), c in cell.items() if x in nodes and y in nodes and rank[x] == rank[y]), key=lambda t: -t[2])
    return back, same

SCC21 = ["access", "audit", "bank", "blueprint", "catalogue", "exchange", "hangar", "identity", "inventory",
         "joborder", "leadership", "livesync", "materialexchange", "mission", "notification", "operation",
         "orgchart", "orgunit", "personalinventory", "promotion", "refinery"]
CORE9 = ["blueprint", "exchange", "hangar", "inventory", "joborder", "materialexchange", "mission", "operation", "refinery"]

for label, nodes in (("core-9", CORE9), ("scc-21", SCC21)):
    cost, order, back = exact_fas(nodes)
    total = sum(c for (x, y), c in cell.items() if x in nodes and y in nodes)
    print("[%s] class edges inside: %d  exact minimum back edges: %d in %d domain pairs" % (label, total, cost, len(back)))
    print("   optimal order (foundation first):", " < ".join(order))
    print("   back arcs:", back)

DOM_RANK = {"audit": 1, "notification": 1, "livesync": 1, "catalogue": 2, "identity": 3, "orgunit": 4, "leadership": 4,
            "access": 5, "admin": 6, "dashboard": 6, "orgchart": 7, "promotion": 7, "personalinventory": 7, "hangar": 7,
            "blueprint": 7, "inventory": 8, "mission": 9, "refinery": 10, "joborder": 10, "materialexchange": 10,
            "operation": 11, "bank": 11, "exchange": 12}
back, same = layering_cost(CORE9, DOM_RANK)
print("\nDOM §5.5 ranks applied to the core without re-homing: back edges %d in %d pairs; same-rank edges %d %s" % (
    sum(t[2] for t in back), len(back), sum(t[2] for t in same), same))
print("   back arcs:", back)
