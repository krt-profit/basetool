"""Ingest package metrics: classes, LOC per package, package-level dependency edges from jdeps."""
import os
import re
import collections

REPO = r"$REPO"
SCRATCH = os.path.dirname(os.path.abspath(__file__))
ROOT_PKG = "de.greluc.krt.profit.basetool.ingest"
SRC = os.path.join(REPO, "ingest", "src", "main", "java", *ROOT_PKG.split("."))

def pkg_of(cls):
    outer = cls.split("$")[0]
    return outer.rsplit(".", 1)[0]

def short(pkg):
    if pkg == ROOT_PKG:
        return "(root)"
    return pkg[len(ROOT_PKG) + 1:]

loc = collections.Counter()
classes = collections.Counter()
per_class_loc = {}
for dirpath, _, files in os.walk(SRC):
    for f in files:
        if f.endswith(".java"):
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(dirpath, SRC)
            pkg = ROOT_PKG if rel == "." else ROOT_PKG + "." + rel.replace(os.sep, ".")
            with open(p, encoding="utf-8") as fh:
                n = sum(1 for _ in fh)
            loc[pkg] += n
            classes[pkg] += 1
            per_class_loc[pkg + "." + f[:-5]] = n

edges = collections.Counter()
class_edges = set()
line_re = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+main\s*$")
with open(os.path.join(SCRATCH, "jdeps-ingest.txt"), encoding="utf-8", errors="replace") as fh:
    for line in fh:
        m = line_re.match(line)
        if not m:
            continue
        a, b = m.group(1), m.group(2)
        if not (a.startswith(ROOT_PKG) and b.startswith(ROOT_PKG)):
            continue
        oa, ob = a.split("$")[0], b.split("$")[0]
        if oa == ob:
            continue
        class_edges.add((oa, ob))
for oa, ob in class_edges:
    pa, pb = pkg_of(oa), pkg_of(ob)
    if pa != pb:
        edges[(short(pa), short(pb))] += 1

print("## Packages")
print("| package | classes | lines |")
print("| --- | ---: | ---: |")
for pkg in sorted(classes, key=lambda p: -loc[p]):
    print(f"| {short(pkg)} | {classes[pkg]} | {loc[pkg]} |")
print(f"| total | {sum(classes.values())} | {sum(loc.values())} |")

print()
print("## Package edges (distinct class pairs)")
print("| from | to | class pairs |")
print("| --- | --- | ---: |")
for (a, b), n in sorted(edges.items()):
    print(f"| {a} | {b} | {n} |")

print()
print("## Cycles (package pairs both ways)")
for (a, b) in sorted(edges):
    if (b, a) in edges and a < b:
        print(f"- {a} <-> {b}: {edges[(a, b)]} / {edges[(b, a)]}")

print()
print("## Largest classes")
for c, n in sorted(per_class_loc.items(), key=lambda x: -x[1])[:15]:
    print(f"- {c[len(ROOT_PKG) + 1:]}: {n}")

print()
print("## Class-level edges crossing packages, detailed")
for oa, ob in sorted(class_edges):
    pa, pb = pkg_of(oa), pkg_of(ob)
    if pa != pb:
        print(f"{short(pa)}.{oa.rsplit('.', 1)[1]} -> {short(pb)}.{ob.rsplit('.', 1)[1]}")
