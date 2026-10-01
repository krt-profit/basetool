"""Count SpEL bean references (@beanName.) inside @PreAuthorize/@PostAuthorize expressions per module."""
import collections
import os
import re
import sys

REPO = r"$REPO"
modules = sys.argv[1:] or ["backend", "frontend", "ingest"]
ANN = re.compile(r"@(PreAuthorize|PostAuthorize|PreFilter|PostFilter)\s*\(\s*((?:\"(?:[^\"\\]|\\.)*\"\s*\+?\s*)+)", re.S)
BEAN = re.compile(r"@([A-Za-z_][A-Za-z0-9_]*)\s*\.")
for m in modules:
    root = os.path.join(REPO, m, "src", "main", "java")
    total = 0
    with_bean = 0
    beans = collections.Counter()
    files_with = set()
    for dp, _, fn in os.walk(root):
        for f in fn:
            if not f.endswith(".java"):
                continue
            p = os.path.join(dp, f)
            with open(p, encoding="utf-8") as fh:
                src = fh.read()
            for am in ANN.finditer(src):
                total += 1
                expr = "".join(re.findall(r"\"((?:[^\"\\]|\\.)*)\"", am.group(2)))
                refs = BEAN.findall(expr)
                if refs:
                    with_bean += 1
                    files_with.add(os.path.relpath(p, root))
                    for r in refs:
                        beans[r] += 1
    print(f"{m}: {total} method-security annotations, {with_bean} reference a bean by name, in {len(files_with)} files")
    for b, n in beans.most_common():
        print(f"   {n:4d} @{b}")
