import os
import re

ROOT = "$REPO/backend/src/main/java/de/greluc/krt/profit/basetool/backend"
ANN = re.compile(r"@(PreAuthorize|PostAuthorize|PreFilter|PostFilter)\b")
METHOD_DECL = re.compile(
    r"^\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?"
    r"(?:<[^>]+>\s*)?[\w.<>\[\], ?@]+\s+(\w+)\s*\(",
)

def strip_comments_and_strings(src):
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(re.sub(r"[^\n]", " ", src[i:j]))
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and src[j] != '"':
                    if src[j] == "\\":
                        j += 1
                    j += 1
                j += 1
            out.append('"' + re.sub(r"[^\n]", " ", src[i + 1:j - 1]) + '"')
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)

results = []
for d, _, fs in os.walk(ROOT):
    if "/controller" in d.replace(os.sep, "/"):
        continue
    for fn in fs:
        if not fn.endswith(".java"):
            continue
        p = os.path.join(d, fn)
        with open(p, encoding="utf-8") as f:
            raw = f.read()
        if not ANN.search(raw):
            continue
        src = strip_comments_and_strings(raw)
        lines = raw.split("\n")
        cls = re.search(r"\b(class|interface|record)\s+(\w+)", src)
        cls_line = src.count("\n", 0, cls.start()) + 1 if cls else 0
        class_level = any(ANN.search(l) for l in lines[max(0, cls_line - 15):cls_line])
        annotated = []
        for idx, line in enumerate(lines):
            if ANN.search(line) and idx + 1 > cls_line:
                k = idx
                while k < len(lines) and "(" not in re.sub(r"@\w+(\([^)]*\))?", "", lines[k]) or (k < len(lines) and lines[k].strip().startswith("@")):
                    k += 1
                    if k - idx > 12:
                        break
                m = None
                for kk in range(idx, min(idx + 14, len(lines))):
                    s = lines[kk]
                    if s.strip().startswith("@"):
                        continue
                    m = re.search(r"\b(\w+)\s*\(", s)
                    if m and m.group(1) not in ("if", "for", "while", "switch", "return", "new"):
                        annotated.append((m.group(1), kk + 1, ANN.search(line).group(1)))
                        break
        names = sorted(set(a[0] for a in annotated))
        if class_level:
            names = sorted(set(names) | set(re.findall(r"\n\s*public\s+(?:static\s+)?[\w.<>\[\], ?@]+\s+(\w+)\s*\(", src)))
        calls = []
        for name in names:
            for m in re.finditer(r"(?<![\w.])(?:this\.)?" + re.escape(name) + r"\s*\(", src):
                line_no = src.count("\n", 0, m.start()) + 1
                line_text = lines[line_no - 1]
                if re.search(r"\b(public|protected|private)\b[^=;]*\b" + re.escape(name) + r"\s*\(", line_text):
                    continue
                if re.match(r"^\s*[\w.<>\[\], ?@]+\s+" + re.escape(name) + r"\s*\(", line_text) and "=" not in line_text and "return" not in line_text:
                    continue
                calls.append((name, line_no, line_text.strip()[:110]))
        rel = os.path.relpath(p, ROOT).replace(os.sep, "/")
        results.append((rel, class_level, annotated, calls))

total = 0
for rel, class_level, annotated, calls in sorted(results):
    total += len(annotated)
    print(f"== {rel}  class-level={class_level}  method-annotations={len(annotated)}")
    for name, line, kind in annotated:
        print(f"     @{kind} {name} :{line}")
    for name, line, text in calls:
        print(f"   SELF-CALL {name} at :{line}  | {text}")
print("TOTAL method-level annotations outside controllers:", total)
