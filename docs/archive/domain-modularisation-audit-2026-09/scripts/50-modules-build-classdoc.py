"""Print the first Javadoc sentence, kind, Spring stereotype and line count of every main class of a module."""
import os
import re
import sys

REPO = r"$REPO"
module = sys.argv[1] if len(sys.argv) > 1 else "ingest"
root = os.path.join(REPO, module, "src", "main", "java")
STEREO = re.compile(r"^@(Component|Service|Configuration|RestController|Controller|ControllerAdvice|RestControllerAdvice|ConfigurationProperties|Bean)\b", re.M)
DECL = re.compile(r"^(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*(class|record|enum|interface|@interface)\s+(\w+)", re.M)
rows = []
for dp, _, fn in os.walk(root):
    for f in sorted(fn):
        if not f.endswith(".java"):
            continue
        p = os.path.join(dp, f)
        with open(p, encoding="utf-8") as fh:
            src = fh.read()
        m = DECL.search(src)
        kind = m.group(1) if m else "?"
        doc = ""
        jd = re.search(r"/\*\*(.*?)\*/\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:public\s+|final\s+|abstract\s+|sealed\s+)*(?:class|record|enum|interface)", src, re.S)
        if jd:
            text = re.sub(r"^\s*\*\s?", "", jd.group(1), flags=re.M)
            text = " ".join(text.split())
            doc = text.split(". ")[0][:170]
        st = sorted(set(STEREO.findall(src)))
        rel = os.path.relpath(p, root).replace(os.sep, "/")
        rel = rel.split("/basetool/", 1)[-1] if "/basetool/" in rel else rel
        n = src.count("\n")
        rows.append((rel, kind, ",".join(st), n, doc))
for r in rows:
    print(f"{r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]}")
