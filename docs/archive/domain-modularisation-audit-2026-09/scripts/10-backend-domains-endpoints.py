"""Endpoint inventory: every controller mapping, the domains its body calls, its SpEL bean references.

An endpoint's responsibility domain is the domain of the non-platform services/mappers its body calls
(most calls wins; the controller's own domain on a tie or when it calls nothing foreign). Writes
10-backend-domains-endpoints.csv and prints the endpoints whose responsibility differs from the
controller's domain, plus the SpEL bean reference matrix.
"""
import csv
import importlib.util
import os
import re
from collections import Counter, defaultdict

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("common", os.path.join(here, "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

OUT = os.path.join(here, "10-backend-domains-endpoints.csv")
PLATFORM = {"shared-kernel", "infrastructure", "access", "audit"}
ANN = r"@[\w.]+(?:\s*\((?:[^()]|\((?:[^()]|\([^()]*\))*\))*\))?"
DECL = re.compile(r"((?:\s*" + ANN + r")+)\s*public\s+(?!class\b|final\s+class\b)[\w<>\[\],?. ]+?\s+(\w+)\s*\(")
MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping\b(\s*\([^)]*\))?")

def prepare(text):
    text = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.S)
    text = re.sub(r"(?m)^\s*//[^\n]*", "", text)
    lits = []

    def keep(m):
        lits.append(m.group(1))
        return '"S%d"' % (len(lits) - 1)

    text = re.sub(r'"((?:\\.|[^"\\\n])*)"', keep, text)
    return text, lits

def strs(fragment, lits):
    return [lits[int(i)] for i in re.findall(r'"S(\d+)"', fragment)]

def roles_constants():
    """Evaluate the String constants of support/Roles.java (literals joined by +)."""
    path = os.path.join(common.SRC, *common.BASE_PKG.split("."), "support", "Roles.java")
    with open(path, encoding="utf-8") as fh:
        t = fh.read()
    raw = dict(re.findall(r"public\s+static\s+final\s+String\s+(\w+)\s*=\s*([^;]+);", t))
    val = {}

    def ev(name, depth=0):
        if name in val:
            return val[name]
        if depth > 10 or name not in raw:
            return name
        out = []
        for tok in re.findall(r'"(?:\\.|[^"\\])*"|\w+', raw[name]):
            out.append(tok[1:-1] if tok.startswith('"') else ev(tok, depth + 1))
        val[name] = "".join(out)
        return val[name]

    for n in raw:
        ev(n)
    return val

ROLES = None

def pre_text(arg, lits):
    """Render a @PreAuthorize argument: string literals joined, Roles.X constants resolved."""
    global ROLES
    if ROLES is None:
        ROLES = roles_constants()
    parts = []
    for tok in re.findall(r'"S\d+"|Roles\.\w+', arg):
        if tok.startswith('"'):
            parts.append(lits[int(tok[2:-1])])
        else:
            parts.append(ROLES.get(tok.split(".", 1)[1], tok) + " [" + tok + "]")
    return " ".join(parts)

def main():
    cls = common.load_classes()
    by_simple = {r["simple"]: r for r in cls.values()}
    bean_to_class = {}
    for f, r in cls.items():
        if r["layer"] in ("service", "support", "config") and r["kind"] == "class":
            with open(os.path.join(common.REPO, r["path"]), encoding="utf-8") as fh:
                t = fh.read()
            m = re.search(r'@(?:Service|Component)\(\s*(?:value\s*=\s*)?"(\w+)"', t)
            name = m.group(1) if m else r["simple"][0].lower() + r["simple"][1:]
            bean_to_class[name] = r["simple"]
    rows = []
    spel = Counter()
    spel_examples = defaultdict(set)
    for f, r in sorted(cls.items()):
        if r["layer"] != "controller":
            continue
        with open(os.path.join(common.REPO, r["path"]), encoding="utf-8") as fh:
            text, lits = prepare(fh.read())
        cm = re.search(r"((?:\s*" + ANN + r")+)\s*public\s+(?:final\s+)?class", text)
        class_anns = cm.group(1) if cm else ""
        bm = re.search(r"@RequestMapping\b(\s*\([^)]*\))?", class_anns)
        base = (strs(bm.group(1) or "", lits) or [""])[0] if bm else ""
        cpre = re.search(r"@PreAuthorize\s*\(([^)]*)\)", class_anns)
        class_pre = pre_text(cpre.group(1), lits) if cpre else ""
        fields = dict((n, t) for t, n in re.findall(r"^\s+private\s+final\s+(?:@\w+\s+)*([\w.]+)(?:<[^>]*>)?\s+(\w+)\s*;", text, re.M))
        body_start = cm.end() if cm else 0
        decls = [d for d in DECL.finditer(text, body_start) if MAPPING.search(d.group(1))]
        for i, d in enumerate(decls):
            end = decls[i + 1].start() if i + 1 < len(decls) else len(text)
            body = text[d.end():end]
            anns = d.group(1)
            mm = MAPPING.search(anns)
            verb = mm.group(1).upper() if mm.group(1) != "Request" else "REQUEST"
            paths = strs(mm.group(2) or "", lits) or [""]
            pre = re.search(r"@PreAuthorize\s*\(((?:[^()]|\([^()]*\))*)\)", anns)
            pre_txt = pre_text(pre.group(1), lits) if pre else (class_pre + " (class)" if class_pre else "")
            beans = sorted(set(re.findall(r"@(\w+)\.", pre_txt)))
            calls = Counter()
            for v, _meth in re.findall(r"\b(\w+)\.(\w+)\s*\(", body):
                t = fields.get(v)
                if t in by_simple and by_simple[t]["layer"] in ("service", "mapper", "support"):
                    dd = by_simple[t]["domain"]
                    if dd not in PLATFORM:
                        calls[dd] += 1
            own = r["domain"]
            if calls:
                top_n = max(calls.values())
                resp = own if calls.get(own, 0) == top_n else sorted(k for k, v in calls.items() if v == top_n)[0]
            else:
                resp = own
            for p in paths:
                path = base + p
                for b in beans:
                    target = bean_to_class.get(b)
                    tdom = by_simple[target]["domain"] if target in by_simple else "?"
                    spel[(r["domain"], tdom)] += 1
                    spel_examples[(r["domain"], tdom)].add("%s->@%s" % (r["simple"], b))
                rows.append([r["simple"], own, verb, path, d.group(2), resp, ";".join("%s:%d" % kv for kv in sorted(calls.items())), " ".join(beans), pre_txt[:200]])
    with open(OUT, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["controller", "controller_domain", "verb", "path", "method", "responsibility_domain", "domain_calls", "spel_beans", "preauthorize"])
        w.writerows(rows)
    print("endpoints:", len(rows), " controllers:", len({x[0] for x in rows}))
    print("per controller domain:", dict(Counter(x[1] for x in rows).most_common()))
    print("with @PreAuthorize (method or class):", sum(1 for x in rows if x[8]))
    mis = [x for x in rows if x[5] != x[1]]
    print("\nendpoints whose body mainly serves another domain: %d" % len(mis))
    for x in sorted(mis, key=lambda x: (x[1], x[0], x[3])):
        print("  %-30s %-12s %-7s %-60s -> %-16s calls=%s" % (x[0], x[1], x[2], x[3][:60], x[5], x[6]))
    mixed = [x for x in rows if len([c for c in x[6].split(";") if c]) > 1]
    print("\nendpoints whose body calls more than one non-platform domain: %d" % len(mixed))
    for x in sorted(mixed, key=lambda x: (x[1], x[0], x[3])):
        print("  %-30s %-7s %-58s calls=%s" % (x[0], x[2], x[3][:58], x[6]))
    print("\nSpEL bean references in @PreAuthorize (controller domain -> bean domain): endpoints")
    for (a, b), n in sorted(spel.items(), key=lambda kv: -kv[1]):
        print("  %-16s -> %-16s %4d  %s" % (a, b, n, ", ".join(sorted(spel_examples[(a, b)]))[:220]))
    beans_total = Counter()
    for x in rows:
        for b in x[7].split():
            beans_total[b] += 1
    print("\nSpEL beans by endpoint count:", beans_total.most_common())

if __name__ == "__main__":
    main()
