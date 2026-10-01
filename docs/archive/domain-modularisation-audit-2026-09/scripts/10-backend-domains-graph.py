"""Domain x domain dependency matrix, edge kinds, strongly connected components, feedback edges and hubs.

Input: jdeps-backend.txt (folded Outer$Inner -> Outer, self-edges dropped, XMapperImpl folded onto XMapper)
and 10-backend-domains-classes.csv. Output: 10-backend-domains-matrix.csv plus 10-backend-domains-graph.json,
and a printed summary.
"""
import csv
import importlib.util
import json
import os
from collections import Counter, defaultdict

spec = importlib.util.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

NON_DOMAIN = {"shared-kernel", "infrastructure"}
MATRIX_CSV = os.path.join(common.HERE, "10-backend-domains-matrix.csv")
GRAPH_JSON = os.path.join(common.HERE, "10-backend-domains-graph.json")

def role(row):
    layer = row["layer"]
    st = row["stereotypes"].split("|") if row["stereotypes"] else []
    if layer == "model":
        return "entity" if ({"Entity", "MappedSuperclass", "Embeddable"} & set(st)) else "model-type"
    if layer in ("dto", "dto-external", "projection"):
        return "dto"
    return layer

def kind_label(sr, dr):
    table = {
        ("service", "repository"): "service->repository",
        ("service", "service"): "service->service",
        ("service", "entity"): "service->entity",
        ("service", "model-type"): "service->enum",
        ("service", "dto"): "service->dto",
        ("service", "mapper"): "service->mapper",
        ("service", "support"): "service->support",
        ("entity", "entity"): "entity->entity",
        ("entity", "model-type"): "entity->enum",
        ("mapper", "mapper"): "mapper->mapper",
        ("mapper", "entity"): "mapper->entity",
        ("mapper", "dto"): "mapper->dto",
        ("mapper", "support"): "mapper->support",
        ("mapper", "model-type"): "mapper->enum",
        ("controller", "service"): "controller->service",
        ("controller", "dto"): "controller->dto",
        ("controller", "support"): "controller->support",
        ("controller", "model-type"): "controller->enum",
        ("controller", "mapper"): "controller->mapper",
        ("controller", "entity"): "controller->entity",
        ("dto", "dto"): "dto->dto",
        ("dto", "model-type"): "dto->enum",
        ("repository", "entity"): "repository->entity",
        ("repository", "dto"): "repository->dto",
        ("repository", "model-type"): "repository->enum",
        ("event", "event"): "event->event",
        ("service", "event"): "service->event",
        ("support", "entity"): "support->entity",
        ("support", "dto"): "support->dto",
        ("support", "repository"): "support->repository",
        ("task", "service"): "task->service",
        ("task", "repository"): "task->repository",
    }
    return table.get((sr, dr), "%s->%s" % (sr, dr))

def load():
    cls = common.load_classes()
    _raw, edges = common.load_edges()

    def norm(n):
        if n in cls:
            return n
        if n.endswith("Impl") and n[:-4] in cls:
            return n[:-4]
        return None

    folded = set()
    for a, b in edges:
        na, nb = norm(a), norm(b)
        if na and nb and na != nb:
            folded.add((na, nb))
    return cls, folded

def tarjan(nodes, adj):
    index = {}
    low = {}
    stack = []
    on = set()
    out = []
    counter = [0]

    def strong(v):
        index[v] = low[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on.add(v)
        for w in adj.get(v, ()):
            if w not in index:
                strong(w)
                low[v] = min(low[v], low[w])
            elif w in on:
                low[v] = min(low[v], index[w])
        if low[v] == index[v]:
            comp = []
            while True:
                w = stack.pop()
                on.discard(w)
                comp.append(w)
                if w == v:
                    break
            out.append(sorted(comp))

    for v in sorted(nodes):
        if v not in index:
            strong(v)
    return out

def eades_order(nodes, weight):
    """Weighted Eades-Lin-Smyth heuristic: an order with few (light) back edges."""
    remaining = set(nodes)
    s1, s2 = [], []

    def wout(v):
        return sum(w for (a, b), w in weight.items() if a == v and b in remaining and b != v)

    def win(v):
        return sum(w for (a, b), w in weight.items() if b == v and a in remaining and a != v)

    while remaining:
        changed = True
        while changed:
            changed = False
            for v in sorted(remaining):
                if wout(v) == 0:
                    s2.insert(0, v)
                    remaining.discard(v)
                    changed = True
            for v in sorted(remaining):
                if win(v) == 0:
                    s1.append(v)
                    remaining.discard(v)
                    changed = True
        if remaining:
            v = max(sorted(remaining), key=lambda x: wout(x) - win(x))
            s1.append(v)
            remaining.discard(v)
    return s1 + s2

def main():
    cls, folded = load()
    dom = {f: r["domain"] for f, r in cls.items()}
    rol = {f: role(r) for f, r in cls.items()}
    simple = {f: r["simple"] for f, r in cls.items()}

    cell = defaultdict(list)
    for a, b in folded:
        if dom[a] != dom[b]:
            cell[(dom[a], dom[b])].append((a, b))

    domains = sorted(set(dom.values()))
    kinds_all = Counter()
    for (sa, sb), lst in cell.items():
        for a, b in lst:
            kinds_all[kind_label(rol[a], rol[b])] += 1
    main_kinds = [k for k, _ in kinds_all.most_common()]

    with open(MATRIX_CSV, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["src_domain", "dst_domain", "class_edges", "distinct_src_classes", "distinct_dst_classes"] + main_kinds + ["example_edges"])
        for (sa, sb) in sorted(cell, key=lambda k: (-len(cell[k]), k)):
            lst = cell[(sa, sb)]
            kc = Counter(kind_label(rol[a], rol[b]) for a, b in lst)
            ex = "; ".join("%s->%s" % (simple[a], simple[b]) for a, b in sorted(lst)[:12])
            w.writerow([sa, sb, len(lst), len({a for a, _ in lst}), len({b for _, b in lst})] + [kc.get(k, 0) for k in main_kinds] + [ex])

    print("folded class edges:", len(folded))
    intra = sum(1 for a, b in folded if dom[a] == dom[b])
    print("intra-domain:", intra, " cross-domain:", len(folded) - intra)
    biz = [d for d in domains if d not in NON_DOMAIN]
    cross_biz = sum(len(v) for (x, y), v in cell.items() if x in biz and y in biz)
    print("cross-domain between non-kernel/non-infra categories:", cross_biz)
    to_kernel = sum(len(v) for (x, y), v in cell.items() if x in biz and y == "shared-kernel")
    to_infra = sum(len(v) for (x, y), v in cell.items() if x in biz and y == "infrastructure")
    infra_to_dom = sum(len(v) for (x, y), v in cell.items() if x == "infrastructure" and y in biz)
    kernel_to_dom = sum(len(v) for (x, y), v in cell.items() if x == "shared-kernel" and y in biz)
    print("domain->shared-kernel:", to_kernel, " domain->infrastructure:", to_infra, " infrastructure->domain:", infra_to_dom, " shared-kernel->domain:", kernel_to_dom)
    print("\nEdge kinds over all cross-domain edges:")
    for k, n in kinds_all.most_common(40):
        print("  %-28s %5d" % (k, n))

    adj = defaultdict(set)
    weight = {}
    for (x, y), v in cell.items():
        if x in biz and y in biz:
            adj[x].add(y)
            weight[(x, y)] = len(v)
    sccs = [c for c in tarjan(biz, adj) if len(c) > 1]
    print("\nSCCs over %d categories (excluding shared-kernel, infrastructure):" % len(biz))
    for c in sccs:
        print("  size %d: %s" % (len(c), ", ".join(c)))
    singles = [d for d in biz if not any(d in c for c in sccs)]
    print("  acyclic singletons:", ", ".join(singles))

    result = {"sccs": sccs, "singletons": singles, "feedback": []}
    for c in sccs:
        wsub = {(a, b): n for (a, b), n in weight.items() if a in c and b in c}
        order = eades_order(c, wsub)
        pos = {d: i for i, d in enumerate(order)}
        back = sorted(((a, b, n) for (a, b), n in wsub.items() if pos[a] > pos[b]), key=lambda t: -t[2])
        fwd = sum(n for (a, b), n in wsub.items() if pos[a] < pos[b])
        print("\n  heuristic order (lower first = depended upon last): %s" % " > ".join(order))
        print("  forward class edges %d, back class edges %d in %d domain pairs" % (fwd, sum(t[2] for t in back), len(back)))
        for a, b, n in back:
            lst = sorted(cell[(a, b)])
            kc = Counter(kind_label(rol[x], rol[y]) for x, y in lst)
            print("    BACK %-17s -> %-17s %4d  %s" % (a, b, n, dict(kc.most_common(4))))
            for x, y in lst[:8]:
                print("        %s -> %s" % (simple[x], simple[y]))
        result["feedback"].append({"order": order, "back": [(a, b, n) for a, b, n in back]})

    dep_domains = defaultdict(set)
    dep_classes = defaultdict(set)
    for a, b in folded:
        if dom[a] != dom[b] and dom[a] not in NON_DOMAIN:
            dep_domains[b].add(dom[a])
            dep_classes[b].add(a)
    ranked = sorted(dep_domains, key=lambda c: (-len(dep_domains[c]), -len(dep_classes[c]), simple[c]))
    print("\nTop hubs among domain classes (by distinct other domains depending on them):")
    hubs = []
    n = 0
    for c in ranked:
        if dom[c] in NON_DOMAIN:
            continue
        n += 1
        hubs.append((simple[c], dom[c], rol[c], len(dep_domains[c]), len(dep_classes[c]), int(cls[c]["loc"]), sorted(dep_domains[c])))
        print("  %2d %-40s %-14s %-10s domains=%2d classes=%3d loc=%4s  %s" % (n, simple[c], dom[c], rol[c], len(dep_domains[c]), len(dep_classes[c]), cls[c]["loc"], ",".join(sorted(dep_domains[c]))))
        if n >= 40:
            break
    print("\nTop shared-kernel / infrastructure hubs:")
    m = 0
    for c in ranked:
        if dom[c] not in NON_DOMAIN:
            continue
        m += 1
        print("  %2d %-40s %-14s domains=%2d classes=%3d loc=%4s" % (m, simple[c], dom[c], len(dep_domains[c]), len(dep_classes[c]), cls[c]["loc"]))
        if m >= 15:
            break
    result["hubs"] = hubs

    print("\nPer-domain coupling (distinct categories; class edges out / in, excluding kernel+infra):")
    stats = []
    for d in biz:
        eff = {y for (x, y) in cell if x == d and y in biz}
        aff = {x for (x, y) in cell if y == d and x in biz}
        ce = sum(len(v) for (x, y), v in cell.items() if x == d and y in biz)
        ca = sum(len(v) for (x, y), v in cell.items() if y == d and x in biz)
        size = sum(1 for f in cls if dom[f] == d)
        loc = sum(int(cls[f]["loc"]) for f in cls if dom[f] == d)
        inst = ce / (ce + ca) if (ce + ca) else 0.0
        stats.append((d, size, loc, len(eff), len(aff), ce, ca, inst))
    for s in sorted(stats, key=lambda t: t[7]):
        print("  %-18s classes=%4d loc=%6d  Ce(domains)=%2d Ca(domains)=%2d  Ce(edges)=%4d Ca(edges)=%4d  I=%.2f" % s)
    result["stats"] = stats

    pivot = {x: {y: 0 for y in domains} for x in domains}
    for (x, y), v in cell.items():
        pivot[x][y] = len(v)
    result["pivot"] = pivot
    result["domains"] = domains
    with open(GRAPH_JSON, "w", encoding="utf-8") as fh:
        json.dump(result, fh, indent=1)

if __name__ == "__main__":
    main()
