"""Count inline fully-qualified type references in Java sources (imports, package lines,
comments and string literals excluded). Usage: python 80-prev-sept-a-fqn.py <root> [<root> ...]"""
import os
import re
import sys
from collections import Counter

FQN = re.compile(r"(?<![\w.])(?:java|javax|jakarta|org|com|de|lombok|io|reactor|tools|net|jdk|sun)\.(?:[a-z_][a-z0-9_]*\.)+[A-Z]\w*")

def strip(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * src.count("\n", i, j))
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append('""' + "\n" * src.count("\n", i, j))
            i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            out.append('""')
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            out.append("''")
            i = j + 1
        else:
            out.append(c)
            i += 1
    return "".join(out)

def count(root):
    total = 0
    per_file = Counter()
    for dp, _, fns in os.walk(root):
        if os.sep + "build" + os.sep in dp + os.sep:
            continue
        for fn in fns:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dp, fn)
            with open(p, encoding="utf-8", errors="replace") as f:
                code = strip(f.read())
            for line in code.splitlines():
                s = line.strip()
                if s.startswith("import ") or s.startswith("package "):
                    continue
                k = len(FQN.findall(line))
                if k:
                    total += k
                    per_file[os.path.relpath(p, root)] += k
    return total, per_file

if __name__ == "__main__":
    for r in sys.argv[1:]:
        t, pf = count(r)
        print(f"{r}: {t} inline FQN references in {len(pf)} files")
        for fn, k in pf.most_common(5):
            print(f"    {k:4d} {fn}")
