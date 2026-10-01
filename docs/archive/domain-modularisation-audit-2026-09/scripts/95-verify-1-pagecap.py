import os
import re

REPO = "$REPO/"
CTRL = REPO + "backend/src/main/java/de/greluc/krt/profit/basetool/backend/controller"
ALLOW = REPO + "docker/edge/include/api-allowlist.conf"

allow_exact = []
allow_re = []
with open(ALLOW, encoding="utf-8") as f:
    for line in f:
        if "set $krt_api_allowed 1" not in line:
            continue
        m = re.search(r'\$uri\s*=\s*"([^"]+)"', line)
        if m:
            allow_exact.append(m.group(1))
            continue
        m = re.search(r'\$uri\s*~\s*"([^"]+)"', line)
        if m:
            allow_re.append(re.compile(m.group(1)))

def allowed(path):
    if path in allow_exact:
        return True
    return any(r.search(path) for r in allow_re)

MAP = re.compile(r'@(Get|Post|Put|Delete|Patch|Request)Mapping(\((?:[^()]|\([^()]*\))*\))?')
PATH_IN = re.compile(r'"([^"]*)"')
UUID = "00000000-0000-4000-8000-000000000000"

rows = []
for d, _, fs in os.walk(CTRL):
    for fn in fs:
        if not fn.endswith(".java"):
            continue
        p = os.path.join(d, fn)
        with open(p, encoding="utf-8") as f:
            text = f.read()
        cls_idx = re.search(r'\bpublic\s+(final\s+)?class\s+\w+', text)
        if not cls_idx:
            continue
        head = text[:cls_idx.start()]
        base = ""
        for m in MAP.finditer(head):
            if m.group(1) == "Request" and m.group(2):
                ps = PATH_IN.findall(m.group(2))
                if ps:
                    base = ps[0]
        body = text[cls_idx.end():]
        offset = cls_idx.end()
        maps = list(MAP.finditer(body))
        for i, m in enumerate(maps):
            verb = m.group(1)
            if verb == "Request":
                continue
            args = m.group(2) or ""
            ps = PATH_IN.findall(args)
            sub = ps[0] if ps else ""
            start = m.end()
            end = maps[i + 1].start() if i + 1 < len(maps) else len(body)
            chunk = body[start:end]
            if "createPageRequest(" not in chunk and "createUnsortedPageRequest(" not in chunk:
                continue
            gate = re.findall(r'@PreAuthorize\(([^\n]*)', body[max(0, m.start() - 400):m.start()] + chunk[:400])
            full = base + sub
            probe = re.sub(r"\{[^}]+\}", UUID, full)
            clamp = "local clamp" if re.search(r"Math\.min\(\s*size", chunk) else ""
            line = text.count("\n", 0, offset + m.start()) + 1
            rows.append((fn + ":" + str(line), verb.upper(), full, allowed(probe), clamp, gate[-1][:90] if gate else "class-level only"))

rows.sort(key=lambda r: (not r[3], r[2]))
print("endpoints using PaginationUtil:", len(rows))
print("allow-listed on api.* vhost:", sum(1 for r in rows if r[3]))
for r in rows:
    print(("API-VHOST " if r[3] else "internal  ") + f"{r[1]:6s} {r[2]:60s} {r[0]:45s} {r[4]:11s} {r[5]}")
