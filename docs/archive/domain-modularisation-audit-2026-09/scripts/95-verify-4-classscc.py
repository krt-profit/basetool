"""Prints the members of the class-level SCCs with more than two classes (folded class graph)."""
import importlib.util
import os
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("v", os.path.join(HERE, "95-verify-4-scc.py"))
import contextlib
import io

buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    v = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(v)
adj = defaultdict(set)
for a, b in v.edges:
    adj[a].add(b)
comps = sorted((c for c in v.tarjan(set(v.rows), adj) if len(c) > 2), key=len, reverse=True)
for c in comps:
    print(len(c), ", ".join("%s[%s]" % (x.rsplit(".", 1)[1], v.dom[x]) for x in c))
