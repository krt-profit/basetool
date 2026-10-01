"""Inline fully-qualified type references in code (not imports, strings or comments)."""

import collections
import importlib.util
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("mjlex", os.path.join(HERE, "60-modern-java-lexer.py"))
L = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L)

FQN = re.compile(r"(?<![\w$.])(?:java|javax|jakarta|org|com|de|lombok|io|tools|reactor|net)\.(?:[a-z_][\w$]*\.)+[A-Z][\w$]*")
counts = collections.Counter()
top = collections.Counter()
for m, s, p, rel in L.iter_java():
    code, _st, _cm = L.lex(L.read(p))
    body = re.sub(r"(?m)^\s*(?:import|package)\s+[^;]*;", "", code)
    n = len(FQN.findall(body))
    if n:
        counts[f"{m}/{s}"] += n
        top[rel] += n
for k, v in sorted(counts.items()):
    print(v, k)
for rel, n in top.most_common(10):
    print("  ", n, rel.split("/java/")[-1])
