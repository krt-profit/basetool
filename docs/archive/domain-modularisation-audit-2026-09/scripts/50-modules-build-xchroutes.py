"""List the backend's exchange-layer endpoints: path, method, request body type, response type, @PreAuthorize."""
import os
import re

REPO = r"$REPO"
ROOT = os.path.join(REPO, "backend", "src", "main", "java", "de", "greluc", "krt", "profit", "basetool", "backend", "controller", "exchange")

CLASS_MAP = re.compile(r'@RequestMapping\(\s*(?:value\s*=\s*|path\s*=\s*)?"([^"]+)"')
CLASS_PRE = re.compile(r'@PreAuthorize\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)\s*(?:@[A-Za-z]+\s*)*public\s+class', re.S)
METHOD = re.compile(
    r'((?:@[A-Za-z]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)+)'
    r'public\s+([^\s(][^(]*?)\s+(\w+)\s*\(((?:[^()]|\([^()]*\))*)\)', re.S)
MAP = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*((?:[^()]|\([^()]*\))*)\))?')
PRE = re.compile(r'@PreAuthorize\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)', re.S)
BODY = re.compile(r'@RequestBody\s+(?:@\w+(?:\([^)]*\))?\s+)*([\w.<>, ?]+?)\s+\w+\s*(?:,|$)')

rows = []
for f in sorted(os.listdir(ROOT)):
    if not f.endswith(".java"):
        continue
    src = open(os.path.join(ROOT, f), encoding="utf-8").read()
    cm = CLASS_MAP.search(src)
    base = cm.group(1) if cm else ""
    cp = CLASS_PRE.search(src)
    class_pre = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', cp.group(1))) if cp else ""
    for m in METHOD.finditer(src):
        anns, ret, name, params = m.group(1), m.group(2), m.group(3), m.group(4)
        mm = MAP.search(anns)
        if not mm:
            continue
        verb = mm.group(1).upper()
        args = mm.group(2) or ""
        pm = re.search(r'(?:value\s*=\s*|path\s*=\s*)?"([^"]*)"', args)
        sub = pm.group(1) if pm else ""
        pre = PRE.search(anns)
        pre_expr = "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', pre.group(1))) if pre else "(class) " + class_pre
        bm = BODY.search(params + ",")
        body = bm.group(1).strip() if bm else "-"
        ret = re.sub(r'\s+', ' ', re.sub(r'@\w+\s*', '', ret)).strip()
        rows.append((verb, base + sub, f[:-5] + "." + name, body, ret, pre_expr))

print("| method | backend path | handler | request body | response | @PreAuthorize |")
print("| --- | --- | --- | --- | --- | --- |")
for r in rows:
    print("| " + " | ".join(x.replace("|", "\\|") for x in r) + " |")
print(f"\n{len(rows)} endpoints")
