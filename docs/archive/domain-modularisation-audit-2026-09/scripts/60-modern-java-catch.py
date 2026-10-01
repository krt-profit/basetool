"""Classify catch clauses whose parameter is unused: what the body does instead."""

import collections
import importlib.util
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("mjlex", os.path.join(HERE, "60-modern-java-lexer.py"))
L = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L)

rows = collections.Counter()
examples = collections.defaultdict(list)
for m, s, p, rel in L.iter_java():
    if s != "main":
        continue
    src = L.read(p)
    code, _st, _cm = L.lex(src)
    li = L.LineIndex(code)
    for mm in re.finditer(r"(?<![\w$.])catch\s*\(", code):
        pp = code.index("(", mm.start())
        pc = L.match_close(code, pp)
        ptxt = L.strip_annotations(code[pp + 1:pc]).strip()
        nm = re.search(r"([\w$]+)\s*$", ptxt)
        name = nm.group(1) if nm else "?"
        b = L.skip_ws(code, pc + 1)
        be = L.match_close(code, b)
        body = code[b + 1:be]
        if re.search(r"(?<![\w$.])%s(?![\w$])" % re.escape(name), body):
            continue
        t = body.strip()
        if not t:
            kind = "empty"
        elif re.match(r"throw\s+new\b", t):
            kind = "rethrow-new-without-cause"
        elif re.match(r"return\b", t):
            kind = "return-fallback"
        elif re.match(r"log\.\w+\(", t):
            kind = "log-without-exception"
        elif re.search(r"\bthrow\b", t):
            kind = "other-with-throw"
        else:
            kind = "other"
        rows[(m, kind)] += 1
        if len(examples[kind]) < 12:
            examples[kind].append(f"{rel.split('/java/')[-1]}:{li.line(mm.start())} catch ({ptxt}) {{ {re.sub(chr(92)+'s+', ' ', t)[:90]} }}")
for k in sorted(rows):
    print(rows[k], k)
for k, v in examples.items():
    print("\n==", k)
    for e in v:
        print("  ", e)
