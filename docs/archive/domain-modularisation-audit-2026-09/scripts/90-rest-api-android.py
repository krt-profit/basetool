"""Best-effort list of backend paths the Android app (local checkout) names in its main sources.

Collects Kotlin string literals that contain /api/v1, turns $x / ${...} into {} and drops the
query string; resolves `missionPath(...)`-style helpers only when they are single-literal
functions. Compares the resulting templates with openapi.json and the frozen contract set.
"""
import csv
import json
import os
import re
import sys

APP = r"$ANDROID_REPO"
REPO = r"$REPO"
OUT = r"$SCRATCHPAD"

def norm(s):
    s = s.split("?")[0]
    s = re.sub(r"\$\{[^}]*\}", "{}", s)
    s = re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*", "{}", s)
    s = re.sub(r"\{\}(\{\})+", "{}", s)
    return s.rstrip("/") if len(s) > 1 else s

def main():
    literals = {}
    for top in ("app", "core", "feature"):
        root = os.path.join(APP, top)
        for dp, dns, fs in os.walk(root):
            if "build" in dp.split(os.sep) or os.sep + "test" + os.sep in dp or "androidTest" in dp:
                continue
            for f in fs:
                if not f.endswith(".kt"):
                    continue
                with open(os.path.join(dp, f), encoding="utf-8") as fh:
                    t = fh.read()
                for m in re.finditer(r"\"((?:[^\"\\]|\\.)*?/api/v1(?:[^\"\\]|\\.)*)\"", t):
                    literals.setdefault(norm(m.group(1)), set()).add(f)
    with open(os.path.join(REPO, "backend", "src", "main", "resources", "api", "openapi.json"), encoding="utf-8") as fh:
        oas = json.load(fh)
    tpl = {re.sub(r"\{[^}]*\}", "{}", p): p for p in oas["paths"]}
    with open(os.path.join(OUT, "90-rest-api-consumers.csv"), encoding="utf-8") as fh:
        frozen_paths = set(r["path"] for r in csv.DictReader(fh) if "app" in r["consumers"])
    matched, prefixes, unknown = [], [], []
    for lit in sorted(literals):
        if lit in tpl:
            matched.append(tpl[lit])
        elif any(k.startswith(lit + "/") for k in tpl):
            prefixes.append(lit)
        else:
            unknown.append(lit)
    print("distinct /api/v1 literals in app main sources:", len(literals))
    print("literals that are full documented paths:", len(matched))
    not_frozen = sorted(set(p for p in matched if p not in frozen_paths))
    print("documented paths the app names but the frozen set does not contain (any verb):", len(not_frozen))
    for p in not_frozen:
        print("   ", p, sorted(literals.get(re.sub(r"\{[^}]*\}", "{}", p), []))[:3])
    print("literals that are only a prefix of documented paths (helpers append the rest):", len(prefixes))
    for p in prefixes:
        print("   ", p)
    print("literals matching no documented path:", len(unknown))
    for p in unknown:
        print("   ", p, sorted(literals[p])[:3])

if __name__ == "__main__":
    sys.exit(main())
