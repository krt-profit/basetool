"""Print distinct incoming and outgoing class dependencies (jdeps edges) for given simple class names."""
import re
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).parent
module = sys.argv[1]
names = sys.argv[2:]
edges = defaultdict(set)
redges = defaultdict(set)
prefix = f"de.greluc.krt.profit.basetool.{module}."
for line in (HERE / f"jdeps-{module}.txt").read_text(encoding="utf-8", errors="replace").splitlines():
    m = re.match(r"\s+(\S+)\s+->\s+(\S+)", line)
    if not m:
        continue
    src = m.group(1).split("$")[0]
    dst = m.group(2).split("$")[0]
    if src == dst or not dst.startswith(prefix):
        continue
    edges[src].add(dst)
    redges[dst].add(src)
for n in names:
    fq = [k for k in set(edges) | set(redges) if k.endswith("." + n)]
    for f in fq:
        inc = sorted(s.replace(prefix, "") for s in redges.get(f, ()))
        out = sorted(d.replace(prefix, "") for d in edges.get(f, ()))
        print(f"== {f.replace(prefix, '')}: in={len(inc)} out={len(out)}")
        print("   IN : " + ", ".join(inc))
