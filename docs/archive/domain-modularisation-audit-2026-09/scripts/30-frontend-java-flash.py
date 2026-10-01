"""Classify the value types of every addFlashAttribute call in the frontend main sources.

A flash attribute is written into the HTTP session (Spring Session on Redis), so its runtime class
must be on the ADR-0206 allow-list. This is a static approximation: it resolves the second argument
to a declared type where it can.
"""

import io
import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
scan = __import__("30-frontend-java-scan")

ROOT = scan.ROOT
CALL = re.compile(r"\baddFlashAttribute\s*\(")
results = []
for dirpath, _, files in os.walk(ROOT):
    for f in files:
        if not f.endswith(".java"):
            continue
        path = os.path.join(dirpath, f)
        src = scan.strip_comments(open(path, encoding="utf-8").read())
        for m in CALL.finditer(src):
            close = scan.matching_paren(src, m.end() - 1)
            args = scan.split_top_level(src[m.end():close])
            if len(args) == 1:
                results.append((f, "single-arg", args[0]))
                continue
            val = args[1].strip()
            kind = None
            if scan.STRING_LIT.match(val) or val.startswith("messageSource.getMessage") or \
                    val.startswith("msg(") or val.endswith(".getMessage()"):
                kind = "String"
            elif val in ("true", "false"):
                kind = "Boolean"
            elif re.match(r"^-?\d+L?$", val):
                kind = "Number"
            elif re.match(r"^[a-z]\w*$", val):
                decl = re.search(r"(?:^|[\s(,])((?:final\s+)?[A-Z][\w.]*(?:<[^;=()]*?>)?)\s+" + re.escape(val) + r"\s*[=,);]", src)
                kind = "var:" + (decl.group(1).replace("final ", "") if decl else "?")
            elif val.startswith("new "):
                kind = "new:" + re.match(r"new\s+([\w.]+)", val).group(1)
            elif re.match(r"^[A-Z]\w*\.[A-Z_]+$", val):
                kind = "constant"
            elif "List.of(" in val or "Map.of(" in val or "Set.of(" in val:
                kind = "collection-factory"
            elif val.startswith("String.valueOf") or ".toString()" in val or ".formatted(" in val or \
                    "String.format" in val or scan.has_top_level_plus(val):
                kind = "String"
            else:
                kind = "expr:" + val[:60]
            results.append((f, kind, val[:80]))

c = Counter(k for _, k, _ in results)
print("flash calls:", len(results))
for k, v in c.most_common():
    print(f"  {v:4d}  {k}")
