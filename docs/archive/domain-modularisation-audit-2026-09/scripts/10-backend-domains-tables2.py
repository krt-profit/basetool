"""Render the cross-domain JPA association table and the layering backlog table as Markdown."""
import csv
import os
import re
from collections import OrderedDict, defaultdict

here = os.path.dirname(os.path.abspath(__file__))

def jpa():
    with open(os.path.join(here, "10-backend-domains-jpa.csv"), encoding="utf-8") as fh:
        rows = [r for r in csv.DictReader(fh) if r["cross"]]
    grp = defaultdict(list)
    for r in rows:
        grp[(r["owner_domain"], r["target_domain"])].append(r)
    print("| owner → target | n | associations (`Entity.field` kind → Target @ line; LAZY unless marked) |")
    print("| --- | ---: | --- |")
    for k in sorted(grp, key=lambda k: (-len(grp[k]), k)):
        items = []
        for r in grp[k]:
            mark = ""
            if r["fetch"] == "EAGER":
                mark = " **EAGER**"
            if r["mappedBy"]:
                mark += " (inverse, mappedBy=%s)" % r["mappedBy"]
            items.append("`%s.%s` %s→%s @%s%s" % (r["owner"], r["field"], {"ManyToOne": "M:1", "OneToMany": "1:M", "ManyToMany": "M:M", "OneToOne": "1:1"}.get(r["kind"], r["kind"]), r["target"], r["where"].split(":")[-1], mark))
        print("| %s → %s | %d | %s |" % (k[0], k[1], len(grp[k]), "; ".join(items)))

def layering():
    txt = open(os.path.join(here, "10-backend-domains-layering.out.txt"), encoding="utf-8").read()
    blocks = re.split(r"\n(?=[a-z\-]+\(\d+\) -> [a-z\-]+\(\d+\): \d+)", txt)
    print("| from (rank) → to (rank) | edges | source classes → targets |")
    print("| --- | ---: | --- |")
    for b in blocks[1:]:
        head, *body = b.strip().split("\n")
        m = re.match(r"([a-z\-]+)\((\d+)\) -> ([a-z\-]+)\((\d+)\): (\d+)", head)
        srcs = []
        for line in body:
            mm = re.match(r"\s+(\w+)\s+-> (.*)", line)
            if mm:
                srcs.append("`%s` → %s" % (mm.group(1), mm.group(2).strip()))
        print("| %s(%s) → %s(%s) | %s | %s |" % (m.group(1), m.group(2), m.group(3), m.group(4), m.group(5), "; ".join(srcs)))

if __name__ == "__main__":
    jpa()
    print()
    layering()
