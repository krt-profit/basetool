import json
import subprocess
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")
items = []
page = 1
while True:
    out = subprocess.run(
        ["gh", "api", f"repos/krt-profit/basetool/actions/caches?per_page=100&page={page}"],
        capture_output=True, text=True, encoding="utf-8")
    if out.returncode != 0:
        print("gh api failed:", out.stderr)
        break
    data = json.loads(out.stdout)
    batch = data.get("actions_caches", [])
    items.extend(batch)
    if len(batch) < 100:
        break
    page += 1
groups = defaultdict(lambda: [0, 0])
for c in items:
    key = c["key"]
    prefix = key.split("-")[0]
    for p in ("codeql-dependencies", "gradle-dependencies", "gradle-home", "gradle-build-results", "nvd-feed", "playwright", "cache-trivy", "setup-java", "gradle-wrapper", "gradle-caches"):
        if key.startswith(p):
            prefix = p
            break
    groups[prefix][0] += 1
    groups[prefix][1] += c["size_in_bytes"]
total = sum(c["size_in_bytes"] for c in items)
print("caches:", len(items), "total bytes:", total, f"({total/1024**3:.2f} GiB)")
for k, (n, s) in sorted(groups.items(), key=lambda kv: -kv[1][1]):
    print(f"  {k:28} count={n:3} {s/1024**2:9.0f} MiB")
for c in items:
    if c["key"].startswith("codeql") or c["key"].startswith("nvd"):
        print("  ", c["key"][:60], c["ref"], c["created_at"], c["last_accessed_at"], c["size_in_bytes"] // 1048576, "MiB")
