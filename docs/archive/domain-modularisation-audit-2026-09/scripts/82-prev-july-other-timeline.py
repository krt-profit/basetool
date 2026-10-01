"""Print closing/cross-reference events from issue timelines (GET only)."""
import json
import subprocess
import sys

REPO = "krt-profit/basetool"

def timeline(num):
    """Return the parsed timeline event list of issue `num`."""
    res = subprocess.run(
        ["gh", "api", "--paginate", "-H", "Accept: application/vnd.github+json", f"repos/{REPO}/issues/{num}/timeline"],
        capture_output=True, text=True, encoding="utf-8")
    txt = res.stdout.strip().replace("][", ",")
    return json.loads(txt) if txt else []

for arg in sys.argv[1:]:
    print(f"=== #{arg}")
    for ev in timeline(int(arg)):
        kind = ev.get("event")
        if kind in ("closed", "referenced", "cross-referenced", "connected", "reopened", "commented"):
            info = {"event": kind, "at": ev.get("created_at"), "commit": (ev.get("commit_id") or "")[:10]}
            src = ev.get("source", {}).get("issue") if kind == "cross-referenced" else None
            if src:
                info["src"] = f"#{src.get('number')} {src.get('title')} state={src.get('state')} pr={'pull_request' in src}"
            if kind == "commented":
                info["body"] = (ev.get("body") or "")[:1500]
                info["user"] = ev.get("user", {}).get("login")
            print(json.dumps(info, ensure_ascii=False))
