"""For each method annotated with a given propagation (default MANDATORY), list the method and the other main classes calling it.

Heuristic caller detection: a `.methodName(` token in another main source file that also references the declaring class's simple name
(field type or import), which is how services are injected here.
"""
import re
import sys
from pathlib import Path

ROOT = Path(r"$REPO\backend\src\main\java")
PROP = sys.argv[1] if len(sys.argv) > 1 else "MANDATORY"
files = list(ROOT.rglob("*.java"))
texts = {p: p.read_text(encoding="utf-8", errors="replace") for p in files}
decl_re = re.compile(r"(?:public|protected|private)?\s*(?:static\s+)?[\w<>\[\], ?.]+\s+(\w+)\s*\(")
results = []
for p, text in texts.items():
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if f"Propagation.{PROP}" in line and "@Transactional" in line:
            for j in range(i + 1, min(i + 8, len(lines))):
                m = decl_re.search(lines[j])
                if m and not lines[j].strip().startswith("@"):
                    results.append((p, i + 1, m.group(1)))
                    break
for p, ln, name in results:
    owner = p.stem
    callers = []
    for q, t in texts.items():
        if q == p:
            continue
        if re.search(r"\." + re.escape(name) + r"\(", t) and re.search(r"\b" + re.escape(owner) + r"\b", t):
            callers.append(q.stem)
    rel = p.relative_to(ROOT.parent.parent.parent)
    print(f"{rel}:{ln}\t{owner}.{name}\tcallers={sorted(set(callers))}")
