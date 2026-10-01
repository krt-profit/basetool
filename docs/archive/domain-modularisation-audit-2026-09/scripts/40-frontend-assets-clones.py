import os
import re
import sys
import hashlib
from collections import defaultdict, Counter

sys.stdout.reconfigure(encoding="utf-8")
ROOT = r"$REPO\frontend\src\main\resources"
kind = sys.argv[1]
K = int(sys.argv[2]) if len(sys.argv) > 2 else 6
focus = sys.argv[3:] if len(sys.argv) > 3 else None

if kind == "js":
    base = os.path.join(ROOT, "static", "js")
    exts = (".js",)
elif kind == "css":
    base = os.path.join(ROOT, "static", "css")
    exts = (".css",)
else:
    base = os.path.join(ROOT, "templates")
    exts = (".html",)

TRIVIAL = re.compile(r"^[\s{}()\[\];,)]*$|^(\}\s*else\s*\{|\}\);?|\}\)\(\);?|return;|break;|continue;|\*/|/\*\*|\*|</?div>|</?th:block>|</?td>|</?tr>|</?span>|</?p>|</?li>|</?ul>|</?form>|</?section>|</?template>)$")

files = []
for dp, dn, fn in os.walk(base):
    for f in fn:
        if f.endswith(exts):
            files.append(os.path.join(dp, f))
files.sort()

norm_lines = {}
for p in files:
    rel = os.path.relpath(p, base).replace("\\", "/")
    raw = open(p, encoding="utf-8").read().split("\n")
    seq = []
    in_license = False
    for i, line in enumerate(raw):
        s = re.sub(r"\s+", " ", line.strip())
        if s.startswith("/*") and i < 3:
            in_license = True
        if in_license:
            if s.endswith("*/"):
                in_license = False
            continue
        if not s or TRIVIAL.match(s) or len(s) < 4:
            continue
        seq.append((i + 1, s))
    norm_lines[rel] = seq

windows = defaultdict(list)
for rel, seq in norm_lines.items():
    for j in range(len(seq) - K + 1):
        h = hashlib.sha1("\n".join(s for _, s in seq[j:j + K]).encode()).hexdigest()
        windows[h].append((rel, j))

dup_marks = defaultdict(set)
pair_lines = defaultdict(set)
for h, occ in windows.items():
    if len(occ) < 2:
        continue
    files_in = set(o[0] for o in occ)
    for rel, j in occ:
        others = [o for o in occ if o != (rel, j) and not (o[0] == rel and abs(o[1] - j) < K)]
        if not others:
            continue
        for t in range(j, j + K):
            dup_marks[rel].add(t)
        for o in others:
            if o[0] != rel:
                a, b = sorted([rel, o[0]])
                for t in range(K):
                    pair_lines[(a, b, rel)].add(j + t)

total_nontrivial = sum(len(s) for s in norm_lines.values())
total_dup = sum(len(v) for v in dup_marks.values())
print(f"kind={kind} K={K} files={len(files)} nontrivial_lines={total_nontrivial} duplicated_nontrivial_lines={total_dup} ({100.0*total_dup/max(1,total_nontrivial):.1f}%)")
per_file = sorted(((len(v), f) for f, v in dup_marks.items()), reverse=True)
print("top files by duplicated non-trivial lines:")
for n, f in per_file[:25]:
    print(f"  {n:5d} / {len(norm_lines[f]):5d}  {f}")
pairs = defaultdict(dict)
for (a, b, side), s in pair_lines.items():
    pairs[(a, b)][side] = len(s)
print("top cross-file pairs (lines duplicated on side a / side b):")
ranked = sorted(pairs.items(), key=lambda kv: -max(kv[1].values()))
for (a, b), sides in ranked[:40]:
    print(f"  {sides.get(a,0):5d} {sides.get(b,0):5d}  {a}  <->  {b}")
if focus:
    print("focus pairs:")
    for (a, b), sides in ranked:
        if a in focus or b in focus:
            print(f"  {sides.get(a,0):5d} {sides.get(b,0):5d}  {a}  <->  {b}")
