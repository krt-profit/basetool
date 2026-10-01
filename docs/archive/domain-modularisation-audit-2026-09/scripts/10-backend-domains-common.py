"""Shared loaders for the 10-backend-domains scripts: the folded jdeps graph and the class inventory."""
import csv
import json
import os
import re
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = r"$REPO"
SRC = os.path.join(REPO, "backend", "src", "main", "java")
BASE_PKG = "de.greluc.krt.profit.basetool.backend"
JDEPS = os.path.join(HERE, "jdeps-backend.txt")
INVENTORY = os.path.join(HERE, "10-backend-domains-inventory.json")
CLASSES_CSV = os.path.join(HERE, "10-backend-domains-classes.csv")

EDGE_RE = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+\S+\s*$")

def fold(name):
    """Fold an inner class name Outer$Inner onto its top-level Outer."""
    return name.split("$", 1)[0]

def load_edges():
    """Return (raw_edge_count, folded set of (src, dst)) with self-edges dropped, backend classes only."""
    raw = 0
    edges = set()
    with open(JDEPS, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            m = EDGE_RE.match(line)
            if not m:
                continue
            raw += 1
            a, b = fold(m.group(1)), fold(m.group(2))
            if not (a.startswith(BASE_PKG) and b.startswith(BASE_PKG)):
                continue
            if a == b:
                continue
            edges.add((a, b))
    return raw, edges

def load_inventory():
    with open(INVENTORY, encoding="utf-8") as fh:
        rows = json.load(fh)
    return {r["fqcn"]: r for r in rows}

def load_classes():
    """Return fqcn -> row dict from the classification CSV."""
    out = {}
    with open(CLASSES_CSV, encoding="utf-8", newline="") as fh:
        for row in csv.DictReader(fh):
            out[row["fqcn"]] = row
    return out

def simple(fqcn):
    return fqcn.rsplit(".", 1)[1]

def adjacency(edges):
    out = defaultdict(set)
    inc = defaultdict(set)
    for a, b in edges:
        out[a].add(b)
        inc[b].add(a)
    return out, inc
