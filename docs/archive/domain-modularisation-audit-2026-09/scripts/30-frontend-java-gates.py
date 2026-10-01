"""Per-handler authorization coverage of the frontend controllers: which handler methods carry
neither a class-level nor a method-level @PreAuthorize (they then rely on the SecurityConfig
backstop `anyRequest().authenticated()` only). Static source scan, comments stripped. Read-only."""

import io
import os
import re
import sys
from collections import Counter

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
scanmod = __import__("30-frontend-java-scan")
CTRL = os.path.join(scanmod.ROOT, "controller")
MAPPING = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping\b")
PUBLIC = {"AppLinkController", "AssetLinksController", "HomeController", "ImpressumController",
          "OssLicensesController", "PrivacyController", "TermsController", "WebAppManifestController"}

total = 0
ungated = []
expr = Counter()
per_class = {}
for f in sorted(os.listdir(CTRL)):
    src = scanmod.strip_comments(open(os.path.join(CTRL, f), encoding="utf-8").read())
    if not re.search(r"^@(Rest)?Controller\b", src, re.M):
        continue
    cls = f[:-5]
    head = src[:re.search(r"^public (final )?class", src, re.M).start()]
    class_gate = re.search(r"^@PreAuthorize\((.*)\)\s*$", head, re.M)
    if class_gate:
        expr[class_gate.group(1)[:80]] += 1
    lines = src.split("\n")
    body_start = src[:re.search(r"^public (final )?class", src, re.M).start()].count("\n")
    i = body_start + 1
    handlers = 0
    missing = 0
    while i < len(lines):
        line = lines[i]
        if re.match(r"^  @", line):
            block = []
            j = i
            while j < len(lines) and not re.match(r"^  (public|protected|private|static|final|[A-Za-z<])[^@]*\(", lines[j]) :
                block.append(lines[j])
                j += 1
                if j - i > 40:
                    break
            text = "\n".join(block)
            if re.search(r"^  @(Get|Post|Put|Patch|Delete|Request)Mapping\b", text, re.M) or \
                    re.search(r"^  @org\.springframework\.web\.bind\.annotation\.(Get|Post|Put|Patch|Delete|Request)Mapping", text, re.M):
                handlers += 1
                total += 1
                m = re.search(r"^  @PreAuthorize\((.*)", text, re.M)
                if m:
                    expr[m.group(1)[:80]] += 1
                elif not class_gate:
                    missing += 1
                    sig = lines[j].strip() if j < len(lines) else "?"
                    ungated.append((cls, sig[:90], cls in PUBLIC))
            i = max(j, i + 1)
            continue
        i += 1
    per_class[cls] = (handlers, bool(class_gate), missing)

print("handlers scanned:", total)
print("handlers without class- or method-level @PreAuthorize:", len(ungated))
print("  of which in PUBLIC_BY_DESIGN controllers:", sum(1 for u in ungated if u[2]))
for cls, sig, pub in ungated:
    print(f"  {'PUBLIC ' if pub else '       '}{cls}: {sig}")
print()
print("gate expressions (class or method), top:")
for e, n in expr.most_common(25):
    print(f"  {n:4d}  {e}")
