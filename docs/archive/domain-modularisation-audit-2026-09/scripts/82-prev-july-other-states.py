"""Print state, close date and title of a range or list of GitHub issues (GET only)."""
import json
import subprocess
import sys

REPO = "krt-profit/basetool"
nums = []
for arg in sys.argv[1:]:
    if "-" in arg:
        a, b = arg.split("-")
        nums.extend(range(int(a), int(b) + 1))
    else:
        nums.append(int(arg))
for n in nums:
    res = subprocess.run(["gh", "api", f"repos/{REPO}/issues/{n}"], capture_output=True, text=True, encoding="utf-8")
    if res.returncode != 0:
        print(f"#{n}\tERROR")
        continue
    d = json.loads(res.stdout)
    kind = "PR" if "pull_request" in d else "ISSUE"
    print(f"#{n}\t{kind}\t{d.get('state')}\t{(d.get('closed_at') or '')[:10]}\t{d.get('state_reason')}\t{d.get('title')[:110]}")
