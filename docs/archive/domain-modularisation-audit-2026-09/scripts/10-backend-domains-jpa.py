"""Find every JPA association in backend entities and flag the ones whose target belongs to another domain.

Writes 10-backend-domains-jpa.csv and prints a summary grouped by (owner domain -> target domain).
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

OUT = os.path.join(here, "10-backend-domains-jpa.csv")
ASSOC = re.compile(r"@(ManyToOne|OneToOne|OneToMany|ManyToMany|ElementCollection)\b(\s*\((?:[^()]|\([^()]*\))*\))?")
FIELD = re.compile(r"^\s*(?:private|protected|public)\s+(?:final\s+)?([\w.<>,?@ \[\]]+?)\s+(\w+)\s*(?:=[^;]*)?;", re.M)

def main():
    cls = common.load_classes()
    by_simple = defaultdict(list)
    for f, r in cls.items():
        by_simple[r["simple"]].append(r)
    rows = []
    for f, r in sorted(cls.items()):
        if r["layer"] != "model" or not ({"Entity", "MappedSuperclass", "Embeddable"} & set(r["stereotypes"].split("|"))):
            continue
        path = os.path.join(common.REPO, r["path"].replace("/", os.sep))
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        text = re.sub(r"/\*.*?\*/", lambda mm: re.sub(r"[^\n]", " ", mm.group(0)), text, flags=re.S)
        text = re.sub(r"//[^\n]*", "", text)
        for m in ASSOC.finditer(text):
            kind = m.group(1)
            args = (m.group(2) or "").strip()
            line = text.count("\n", 0, m.start()) + 1
            rest = text[m.end():]
            fm = FIELD.search(rest)
            if not fm:
                continue
            between = rest[: fm.start()]
            ftype, fname = fm.group(1).strip(), fm.group(2)
            ftype = re.sub(r"@\w+\s*", "", ftype)
            gm = re.search(r"<\s*([\w.]+)\s*>", ftype)
            target = gm.group(1) if gm else ftype
            target = target.split(".")[-1]
            fetch = re.search(r"fetch\s*=\s*FetchType\.(\w+)", args)
            fetch = fetch.group(1) if fetch else ("EAGER(default)" if kind in ("ManyToOne", "OneToOne") else "LAZY(default)")
            cascade = re.search(r"cascade\s*=\s*(\{[^}]*\}|[\w.]+)", args)
            cascade = cascade.group(1).replace("CascadeType.", "") if cascade else ""
            orphan = "true" if re.search(r"orphanRemoval\s*=\s*true", args) else ""
            mapped = re.search(r'mappedBy\s*=\s*"(\w+)"', args)
            mapped = mapped.group(1) if mapped else ""
            jc = re.search(r'@JoinColumn\s*\(\s*(?:name\s*=\s*)?"(\w+)"', between) or re.search(r'@JoinColumn\s*\([^)]*name\s*=\s*"(\w+)"', between)
            jcol = jc.group(1) if jc else ""
            ct = re.search(r'@CollectionTable\s*\([^)]*name\s*=\s*"(\w+)"', between)
            if ct:
                jcol = "table " + ct.group(1)
            jt = re.search(r'@JoinTable\s*\(\s*name\s*=\s*"(\w+)"', between)
            if jt:
                jcol = "table " + jt.group(1)
            tdom = by_simple[target][0]["domain"] if target in by_simple else "(value)"
            rows.append({
                "owner": r["simple"], "owner_domain": r["domain"], "field": fname, "kind": kind, "target": target,
                "target_domain": tdom, "cross": "yes" if tdom not in (r["domain"], "(value)") else "",
                "fetch": fetch, "cascade": cascade, "orphanRemoval": orphan, "mappedBy": mapped, "column": jcol,
                "where": "%s:%d" % (r["path"], line),
            })
    with open(OUT, "w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print("associations:", len(rows), " cross-domain:", sum(1 for x in rows if x["cross"]))
    print("by kind (all):", dict(Counter(x["kind"] for x in rows)))
    print("by kind (cross):", dict(Counter(x["kind"] for x in rows if x["cross"])))
    print("fetch (cross):", dict(Counter(x["fetch"] for x in rows if x["cross"])))
    print("cascade (cross):", dict(Counter(x["cascade"] or "-" for x in rows if x["cross"])))
    grp = defaultdict(list)
    for x in rows:
        if x["cross"]:
            grp[(x["owner_domain"], x["target_domain"])].append(x)
    print()
    for k in sorted(grp, key=lambda k: (-len(grp[k]), k)):
        print("%s -> %s (%d)" % (k[0], k[1], len(grp[k])))
        for x in grp[k]:
            print("   %-28s.%-26s %-11s -> %-22s fetch=%-14s cascade=%-10s orphan=%-4s mappedBy=%-10s col=%-24s %s" % (
                x["owner"], x["field"], x["kind"], x["target"], x["fetch"], x["cascade"] or "-", x["orphanRemoval"] or "-", x["mappedBy"] or "-", x["column"] or "-", x["where"].split("/")[-1]))

if __name__ == "__main__":
    main()
