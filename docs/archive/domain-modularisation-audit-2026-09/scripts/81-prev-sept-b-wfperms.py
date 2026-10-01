import os
import sys

import yaml

sys.stdout.reconfigure(encoding="utf-8")
root = r"$REPO\.github\workflows"
jobs_total = 0
jobs_with_perms = 0
rows = []
write_top = []
for name in sorted(os.listdir(root)):
    if not name.endswith(".yml"):
        continue
    with open(os.path.join(root, name), encoding="utf-8") as f:
        doc = yaml.safe_load(f)
    top = doc.get("permissions", "<unset>")
    on = doc.get(True, doc.get("on"))
    triggers = list(on.keys()) if isinstance(on, dict) else on
    jobs = doc.get("jobs", {}) or {}
    jp = 0
    for jn, j in jobs.items():
        jobs_total += 1
        if isinstance(j, dict) and "permissions" in j:
            jobs_with_perms += 1
            jp += 1
    if isinstance(top, dict) and any(v == "write" for v in top.values()):
        write_top.append((name, top))
    rows.append((name, top, len(jobs), jp, triggers))
for r in rows:
    print(f"{r[0]:32} top={r[1]!s:60} jobs={r[2]} job-perms={r[3]} on={r[4]}")
print("jobs total:", jobs_total, "jobs with job-level permissions:", jobs_with_perms)
print("workflows with a top-level write permission:", write_top)
