import os
import re
import sys
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8")
root = r"$REPO\frontend\src\main\java"
call = re.compile(r"\bbackendApiClient\s*\.\s*(get|post|put|delete|patch|getCached)\s*\(")
total = 0
concat = 0
template = 0
by_verb = Counter()
by_pkg_file = Counter()
concat_files = Counter()
files_using = set()
for dp, dn, fn in os.walk(root):
    for name in fn:
        if not name.endswith(".java"):
            continue
        path = os.path.join(dp, name)
        with open(path, encoding="utf-8") as f:
            src = f.read()
        for m in call.finditer(src):
            total += 1
            files_using.add(name)
            by_verb[m.group(1)] += 1
            depth = 0
            i = m.end()
            arg = []
            while i < len(src):
                c = src[i]
                if c == "(":
                    depth += 1
                elif c == ")":
                    if depth == 0:
                        break
                    depth -= 1
                elif c == "," and depth == 0:
                    break
                arg.append(c)
                i += 1
            first = "".join(arg)
            if "+" in first:
                concat += 1
                concat_files[name] += 1
            if "{" in first and "}" in first:
                template += 1
print("backendApiClient call sites:", total)
print("by verb:", dict(by_verb))
print("first argument uses '+' concatenation:", concat)
print("first argument contains a {template}:", template)
print("files calling backendApiClient:", len(files_using))
print("top concat files:", concat_files.most_common(12))
hx = 0
for dp, dn, fn in os.walk(root):
    for name in fn:
        if name.endswith(".java"):
            with open(os.path.join(dp, name), encoding="utf-8") as f:
                s = f.read()
            if "@HttpExchange" in s or "HttpServiceProxyFactory" in s or "@GetExchange" in s:
                hx += 1
print("files using @HttpExchange/HttpServiceProxyFactory:", hx)
