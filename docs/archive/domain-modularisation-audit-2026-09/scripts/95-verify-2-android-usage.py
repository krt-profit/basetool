"""For each unfrozen-but-admitted operation, show where Android v0.3.1 references its path and which
reader verb sits next to it. Read-only: uses `git grep` / `git show` on the tag."""
import re
import subprocess

ANDROID = r"$ANDROID_REPO"
TAG = "v0.3.1"
OPS = [
    ("DELETE", "/api/v1/hangar/ships"),
    ("DELETE", "/api/v1/personal-blueprints"),
    ("DELETE", "/api/v1/refinery-orders/{id}"),
    ("GET", "/api/v1/hangar/ships"),
    ("GET", "/api/v1/material-requests/{id}"),
    ("GET", "/api/v1/materials/matrix"),
    ("GET", "/api/v1/me/layout"),
    ("PATCH", "/api/v1/bank/holders/{id}"),
    ("POST", "/api/v1/bank/accounts"),
    ("POST", "/api/v1/bank/holders"),
    ("POST", "/api/v1/job-types"),
    ("POST", "/api/v1/orders"),
    ("PUT", "/api/v1/missions/{id}/participants/{participantId}/slim"),
    ("PUT", "/api/v1/refinery-orders/{id}"),
    ("POST", "/api/v1/operations"),
]

def git(*args):
    return subprocess.run(["git", "-C", ANDROID, *args], capture_output=True, text=True, encoding="utf-8").stdout

def const_defs():
    out = git("grep", "-n", "-E", r'(const val|val|fun) [A-Za-z_]+(\([^)]*\))? *=? *"/api/v1/', TAG, "--", "*.kt")
    return out.splitlines()

defs = const_defs()
for verb, path in OPS:
    static = path.split("{")[0].rstrip("/")
    tail = re.sub(r"\{[^}]+\}", r"[^\"]*", path)
    rx = re.compile('"' + tail + '"')
    hits = [d for d in defs if static in d and (rx.search(d) or d.rstrip().endswith('"' + static + '"'))]
    print(f"\n### {verb} {path}")
    if not hits:
        print("   no path constant in", TAG)
        continue
    for h in hits[:6]:
        print("   def:", h[:220])
        m = re.match(r"^[^:]+:([^:]+):\d+:.*?(?:const val|val|fun) ([A-Za-z_]+)", h)
        if not m:
            continue
        fname, ident = m.group(1), m.group(2)
        body = git("show", f"{TAG}:{fname}")
        for i, line in enumerate(body.splitlines(), 1):
            if ident in line and "val " + ident not in line and "fun " + ident not in line:
                ctx = " ".join(body.splitlines()[max(0, i - 4):i + 1])
                verbs = re.findall(r"\b(?:reader|http|client|api)?\.?(get|post|put|patch|delete)\s*\(", ctx)
                print(f"      use {fname.split('/')[-1]}:{i} verbs-near={sorted(set(verbs))} :: {line.strip()[:120]}")
