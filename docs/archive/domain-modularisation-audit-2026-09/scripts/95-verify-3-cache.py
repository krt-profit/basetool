"""Inventory cached single-entity getters, their in-class (self-invocation) callers, and cross-bean callers."""
import os
import re

ROOT = r"$REPO\backend\src\main\java"

files = {}
for dp, _, fns in os.walk(ROOT):
    for fn in fns:
        if fn.endswith(".java"):
            p = os.path.join(dp, fn)
            files[p] = open(p, encoding="utf-8").read().split("\n")

cached = []
for p, lines in files.items():
    for i, line in enumerate(lines):
        if "@Cacheable" in line:
            for j in range(i + 1, min(i + 6, len(lines))):
                m = re.search(r"public\s+([\w<>, ?]+)\s+(\w+)\s*\(", lines[j])
                if m:
                    cached.append((p, i + 1, m.group(1).strip(), m.group(2), j + 1))
                    break

def method_at(lines, idx):
    for k in range(idx, -1, -1):
        m = re.search(r"^\s{2}(public|protected|private|)\s*[\w<>, ?\[\]]*\s+(\w+)\s*\([^;]*$", lines[k])
        if m and not lines[k].strip().startswith(("if", "for", "while", "return", "switch", "catch", "else")):
            return m.group(2), k + 1
    return None, None

single = [c for c in cached if not c[2].startswith(("Page", "List", "Set", "Map", "Optional"))]
print("CACHED SINGLE-ENTITY GETTERS:")
for p, ln, rt, name, mln in single:
    cls = os.path.basename(p)[:-5]
    print(f"  {cls}.{name} -> {rt}  (@Cacheable at {cls}.java:{ln})")

print()
print("IN-CLASS CALLERS (self-invocation, bypass proxy):")
for p, ln, rt, name, mln in single:
    cls = os.path.basename(p)[:-5]
    lines = files[p]
    for i, line in enumerate(lines):
        if i + 1 == mln:
            continue
        if re.search(r"(?<![\w.])" + name + r"\s*\(", line) and "public " + rt not in line:
            meth, mstart = method_at(lines, i)
            print(f"  {cls}.java:{i + 1} in {meth}(): {line.strip()}")

print()
print("CROSS-BEAN CALLERS (through the proxy, may return the cached instance):")
for p, ln, rt, name, mln in single:
    cls = os.path.basename(p)[:-5]
    field_pat = re.compile(r"\b(\w+)\." + name + r"\s*\(")
    for q, lines in files.items():
        if q == p:
            continue
        src = "\n".join(lines)
        if cls not in src:
            continue
        for i, line in enumerate(lines):
            m = field_pat.search(line)
            if m and re.search(r"\b" + re.escape(m.group(1)) + r"\b", src) and re.search(cls + r"\s+" + re.escape(m.group(1)) + r"\b", src):
                meth, mstart = method_at(lines, i)
                print(f"  {os.path.basename(q)}:{i + 1} in {meth}(): {line.strip()}")
