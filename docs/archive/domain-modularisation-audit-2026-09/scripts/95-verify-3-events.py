"""List record components of every event record in the backend event package and flag personal-data-looking names."""
import os
import re

ROOT = r"$REPO\backend\src\main\java\de\greluc\krt\profit\basetool\backend\event"
SUSPECT = re.compile(r"(handle|username|userName|email|Email|name|Name|nick|reason|comment|note|text|message|discord)", re.I)

hits = []
for fn in sorted(os.listdir(ROOT)):
    if not fn.endswith(".java"):
        continue
    src = open(os.path.join(ROOT, fn), encoding="utf-8").read()
    m = re.search(r"public\s+record\s+(\w+)\s*\((.*?)\)\s*(implements[^{]*)?\{", src, re.S)
    if not m:
        continue
    comps = [c.strip() for c in re.split(r",(?![^<]*>)", m.group(2)) if c.strip()]
    names = []
    for c in comps:
        c2 = re.sub(r"@\w+(\([^)]*\))?\s*", "", c).strip()
        parts = c2.split()
        if len(parts) >= 2:
            names.append((parts[-2], parts[-1]))
    flagged = [f"{t} {n}" for t, n in names if t == "String" and SUSPECT.search(n)]
    if flagged:
        hits.append((m.group(1), flagged, "NotificationEvent" in (m.group(3) or "")))

for rec, flagged, notif in hits:
    print(f"{rec} | notificationEvent={notif} | {', '.join(flagged)}")
print(f"TOTAL records with String components named like personal data: {len(hits)}")
