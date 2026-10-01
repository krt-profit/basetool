import os
import re
import sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")
root = r"$REPO\frontend\src\main\java"
pat = re.compile(
    r"catch\s*\(\s*BackendServiceException\s+\w+\s*\)\s*\{[^{}]*?propagateBackendError\([^{}]*?\}\s*catch\s*\(\s*Exception\s+\w+\s*\)\s*\{[^{}]*?(internalServerError\(\)|HttpStatus\.INTERNAL_SERVER_ERROR|status\(500\))",
    re.S)
relay_use = re.compile(r"(?<![\w.])relay\(\s*\n?\s*log\s*,|BackendErrorResponses\.relay\(|(?<![\w.])relay\(")
hand = Counter()
uses = Counter()
for dp, dn, fn in os.walk(root):
    for name in fn:
        if not name.endswith(".java") or name == "BackendErrorResponses.java":
            continue
        with open(os.path.join(dp, name), encoding="utf-8") as f:
            s = f.read()
        n = len(pat.findall(s))
        if n:
            hand[name] = n
        u = len(re.findall(r"(?<![\w.])relay\(", s))
        if u:
            uses[name] = u
print("hand-written catch(BackendServiceException)->propagate + catch(Exception)->500 blocks:", sum(hand.values()), "in", len(hand), "files")
for k, v in hand.most_common():
    print("  ", k, v)
print("relay( call sites:", sum(uses.values()), "in", len(uses), "files")
