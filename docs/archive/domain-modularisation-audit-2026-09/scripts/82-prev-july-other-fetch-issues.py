"""Fetch GitHub issues/PRs (read-only GET) and dump body + comments into scratchpad text files."""
import json
import subprocess
import sys
from pathlib import Path

REPO = "krt-profit/basetool"
OUT = Path(r"$SCRATCHPAD\82-prev-july-other-gh")
OUT.mkdir(exist_ok=True)

def gh_api(path):
    """Run a GET gh api call with pagination and return parsed JSON (list or dict)."""
    res = subprocess.run(["gh", "api", "--paginate", path], capture_output=True, text=True, encoding="utf-8")
    if res.returncode != 0:
        return {"error": res.stderr.strip()}
    txt = res.stdout.strip()
    if not txt:
        return []
    try:
        return json.loads(txt)
    except json.JSONDecodeError:
        parts = txt.replace("][", "]\n[").splitlines()
        merged = []
        for p in parts:
            merged.extend(json.loads(p))
        return merged

def dump(num):
    """Write issue/PR number `num` with its comments and review comments to a text file."""
    issue = gh_api(f"repos/{REPO}/issues/{num}")
    lines = []
    if isinstance(issue, dict) and "error" not in issue:
        is_pr = "pull_request" in issue
        lines.append(f"# #{num} [{'PR' if is_pr else 'ISSUE'}] {issue.get('title')}")
        lines.append(f"state={issue.get('state')} created={issue.get('created_at')} closed={issue.get('closed_at')} user={issue.get('user', {}).get('login')}")
        lines.append("labels=" + ",".join(l["name"] for l in issue.get("labels", [])))
        if is_pr:
            pr = gh_api(f"repos/{REPO}/pulls/{num}")
            if isinstance(pr, dict):
                lines.append(f"merged_at={pr.get('merged_at')} merge_commit={pr.get('merge_commit_sha')} head={pr.get('head', {}).get('ref')}")
        lines.append("")
        lines.append("## BODY")
        lines.append(issue.get("body") or "")
        comments = gh_api(f"repos/{REPO}/issues/{num}/comments")
        if isinstance(comments, list):
            for c in comments:
                lines.append("")
                lines.append(f"## COMMENT by {c['user']['login']} at {c['created_at']}")
                lines.append(c.get("body") or "")
        if is_pr:
            rc = gh_api(f"repos/{REPO}/pulls/{num}/comments")
            if isinstance(rc, list):
                for c in rc:
                    lines.append("")
                    lines.append(f"## REVIEW-COMMENT by {c['user']['login']} on {c.get('path')}:{c.get('line')}")
                    lines.append(c.get("body") or "")
            rv = gh_api(f"repos/{REPO}/pulls/{num}/reviews")
            if isinstance(rv, list):
                for c in rv:
                    if c.get("body"):
                        lines.append("")
                        lines.append(f"## REVIEW by {c['user']['login']} state={c.get('state')}")
                        lines.append(c.get("body") or "")
            commits = gh_api(f"repos/{REPO}/pulls/{num}/commits")
            if isinstance(commits, list):
                lines.append("")
                lines.append("## COMMITS")
                for c in commits:
                    lines.append(f"### {c['sha'][:10]}")
                    lines.append(c["commit"]["message"])
    else:
        lines.append(f"# #{num} ERROR {issue}")
    (OUT / f"{num}.txt").write_text("\n".join(lines), encoding="utf-8")
    print(num, "ok", len(lines))

if __name__ == "__main__":
    for arg in sys.argv[1:]:
        dump(int(arg))
