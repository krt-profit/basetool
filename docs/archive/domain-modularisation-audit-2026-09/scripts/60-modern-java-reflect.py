"""JEP 500 exposure: which reflective field writes in tests target a final field?"""

import collections
import importlib.util
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("mjlex", os.path.join(HERE, "60-modern-java-lexer.py"))
L = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L)

FIELDS = collections.defaultdict(list)
CLASS_OF = {}
for m, s, p, rel in L.iter_java():
    src = L.read(p)
    code, _st, _cm = L.lex(src)
    for mt in re.finditer(r"(?<![\w$.@])(?:class|record|enum)\s+([A-Z][\w$]*)", code):
        CLASS_OF.setdefault(mt.group(1), []).append(rel)
    for fm in re.finditer(r"(?m)^[ \t]+((?:(?:private|protected|public|static|final|transient|volatile)\s+)+)[\w$.<>, ?\[\]]+?\s+([a-zA-Z_$][\w$]*)\s*(?:=|;)", code):
        mods = fm.group(1).split()
        FIELDS[fm.group(2)].append((rel, "final" in mods, "static" in mods))

rows = []
for m, s, p, rel in L.iter_java():
    if s == "main":
        continue
    src = L.read(p)
    code, strings, _cm = L.lex(src)
    lit = {off: text for (_l, kind, text, off) in strings}
    li = L.LineIndex(code)
    for mm in re.finditer(r"\bReflectionTestUtils\.setField\s*\(|\.setAccessible\s*\(\s*true|\bField\b[^;]*\.set\s*\(", code):
        if "setField" not in mm.group(0):
            rows.append((rel, li.line(mm.start()), "raw-reflection", "?", "?", snippetx := re.sub(r"\s+", " ", code[mm.start():mm.start() + 90])))
            continue
        pc = L.match_close(code, code.index("(", mm.start()))
        args = L.split_top(code[code.index("(", mm.start()) + 1:pc])
        target = args[0].strip() if args else "?"
        name_off = code.find('"', code.index("(", mm.start()))
        fname = lit.get(name_off, "?")
        decl = re.search(r"([A-Z][\w$]*)(?:<[^;=()]*>)?\s+%s\s*[;=]" % re.escape(target.split(".")[0]), code)
        ttype = decl.group(1) if decl else (target if re.match(r"[A-Z]", target) else "?")
        finals = [f for f in FIELDS.get(fname, []) if ttype != "?" and ttype in f[0].rsplit("/", 1)[-1]]
        cands = finals or FIELDS.get(fname, [])
        status = "unknown"
        if cands:
            if all(c[1] for c in cands):
                status = "FINAL"
            elif not any(c[1] for c in cands):
                status = "non-final"
            else:
                status = "mixed"
        rows.append((rel, li.line(mm.start()), status, ttype, fname, ""))

by = collections.Counter((r[0].split("/")[0], r[2]) for r in rows)
print("by module/status:", dict(by))
for r in rows:
    print(f"  {r[2]:14s} {r[3]:36s} {r[4]:28s} {r[0].split('/java/')[-1]}:{r[1]} {r[5]}")
