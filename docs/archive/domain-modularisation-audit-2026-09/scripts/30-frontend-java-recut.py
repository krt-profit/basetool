"""Re-cut impact per backend-resource domain: where the frontend references each domain's backend
paths (own-domain classes, other-domain classes, kernel classes), and how often the frontend tests and
the E2E sources reference them. Read-only."""

import io
import json
import os
import re
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
urls = __import__("30-frontend-java-urls")
print("=" * 30, "re-cut table", "=" * 30)
scanmod = __import__("30-frontend-java-scan")
dmap = json.load(open(os.path.join(HERE, "30-frontend-java-domainmap.json"), encoding="utf-8"))["domain_map"]
REPO = scanmod.REPO

own = Counter()
other = Counter()
kernel = Counter()
other_callers = defaultdict(set)
kernel_callers = defaultdict(set)
for key, segs in urls.per_ctrl.items():
    cd = dmap.get(key, "?")
    for d, n in segs.items():
        if cd == d:
            own[d] += n
        elif cd.startswith("kernel"):
            kernel[d] += n
            kernel_callers[d].add(key.split(".")[-1])
        else:
            other[d] += n
            other_callers[d].add(key.split(".")[-1])

def count_dir(root):
    c = Counter()
    for dp, _, fs in os.walk(root):
        for f in fs:
            if not f.endswith(".java"):
                continue
            s = open(os.path.join(dp, f), encoding="utf-8", errors="replace").read()
            for p in re.findall(r'"(/api/v1/[^"]*)"', s):
                d = urls.SEG_DOMAIN.get(urls.seg(p), "?" + urls.seg(p))
                c[d] += 1
    return c

tests = count_dir(os.path.join(REPO, "frontend", "src", "test", "java"))
e2e = count_dir(os.path.join(REPO, "frontend", "src", "e2e", "java"))
doms = sorted(set(own) | set(other) | set(kernel) | set(tests) | set(e2e),
              key=lambda d: -(own[d] + other[d] + kernel[d]))
print(f'{"backend domain":20s} {"own":>5s} {"other":>6s} {"kernel":>7s} {"tests":>6s} {"e2e":>5s}  other-domain callers / kernel callers')
for d in doms:
    print(f"{d:20s} {own[d]:5d} {other[d]:6d} {kernel[d]:7d} {tests[d]:6d} {e2e[d]:5d}  "
          f"{sorted(other_callers[d])} / {sorted(kernel_callers[d])}")
print("totals: own", sum(own.values()), "other", sum(other.values()), "kernel", sum(kernel.values()),
      "tests", sum(tests.values()), "e2e", sum(e2e.values()))
