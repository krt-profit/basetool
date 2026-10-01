import os
import re

ROOT = "$REPO/backend/src/main/java/de/greluc/krt/profit/basetool/backend"
MAP = re.compile(r"@(Get|Post|Put|Delete|Patch)Mapping\b")

total_rb = 0
missing = []
for d, _, fs in os.walk(ROOT):
    for fn in fs:
        if not fn.endswith(".java"):
            continue
        p = os.path.join(d, fn)
        with open(p, encoding="utf-8") as f:
            text = f.read()
        if "@RequestBody" not in text:
            continue
        lines = text.split("\n")
        cls_m = re.search(r"\bpublic\s+(?:final\s+)?class\s+\w+", text)
        cls_line = text.count("\n", 0, cls_m.start()) if cls_m else 0
        class_gate = None
        for l in lines[max(0, cls_line - 12):cls_line + 1]:
            m = re.search(r"^\s*@PreAuthorize\((.*)\)\s*$", l)
            if m:
                class_gate = m.group(1)
        class_validated = any(re.search(r"^\s*@Validated\b", l) for l in lines[max(0, cls_line - 12):cls_line + 1])
        for m in re.finditer(r"@RequestBody\b", text):
            total_rb += 1
            start = text.rfind("(", 0, m.start())
            seg_start = max(text.rfind(",", 0, m.start()), start)
            seg_end_candidates = [i for i in (text.find(",", m.end()), text.find(")", m.end())) if i >= 0]
            seg_end = min(seg_end_candidates)
            param = text[seg_start + 1:seg_end]
            line_no = text.count("\n", 0, m.start()) + 1
            if "@Valid" in param or "@Validated" in param:
                continue
            method_gate = None
            mapping = None
            for k in range(line_no - 1, max(0, line_no - 40), -1):
                l = lines[k]
                g = re.search(r"^\s*@PreAuthorize\((.*)", l)
                if g and method_gate is None:
                    method_gate = g.group(1).strip()
                mm = MAP.search(l)
                if mm and mapping is None:
                    mapping = l.strip()
                if re.search(r"^\s*\*/\s*$", l):
                    break
            ptype = re.sub(r"@\w+(\([^)]*\))?", "", param).strip()
            missing.append((os.path.relpath(p, ROOT).replace(os.sep, "/"), line_no, mapping, method_gate or ("class: " + str(class_gate)), ptype, class_validated))

print("@RequestBody parameters in backend main:", total_rb)
print("without @Valid/@Validated on the parameter:", len(missing))
for r in sorted(missing):
    print(f"{r[0]}:{r[1]}  {r[2]}  gate={r[3]}  param='{r[4]}'  classValidated={r[5]}")
