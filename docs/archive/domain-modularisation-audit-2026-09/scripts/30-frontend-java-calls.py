"""Summarise the BackendApiClient call sites found by 30-frontend-java-scan.py."""

import json
import os
import re
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
data = json.load(open(os.path.join(HERE, "30-frontend-java-scan.json"), encoding="utf-8"))
calls = data["calls"]

HTTP = {"get", "post", "put", "delete", "patch", "getCached", "getTermsDocumentAnonymously"}
http_calls = [c for c in calls if c["method"] in HTTP]
print("HTTP call sites:", len(http_calls))
print("non-HTTP:", Counter(c["method"] for c in calls if c["method"] not in HTTP))

def style(c):
    cls = c["class"]
    if cls == "variable" and c["resolved"]:
        return c["resolved"]
    if c["method"] == "get" and c["nargs"] >= 3:
        return "uri-template+vars(" + cls + ")"
    return cls

st = Counter(style(c) for c in http_calls)
for k, v in st.most_common():
    print(f"  {k:40s} {v}")

print()
print("3+-arg get calls:")
for c in http_calls:
    if c["method"] == "get" and c["nargs"] >= 3:
        print("  ", c["file"], c["line"], c["first"][:100])

print()
print("constant-first calls (sample):")
for c in [c for c in http_calls if c["class"] == "constant"][:50]:
    print("  ", c["file"], c["line"], c["method"], c["first"][:90])

print()
print("helper-call / other:")
for c in http_calls:
    if c["class"] in ("helper-call", "other") or (c["class"] == "variable"):
        print("  ", c["file"], c["line"], c["method"], c["class"], c["resolved"], c["first"][:100])

print()
print("uri-builder:")
for c in http_calls:
    if "uri-builder" in style(c):
        print("  ", c["file"], c["line"], c["method"], c["first"][:100])

print()
print("literal with query string or placeholders:")
for c in http_calls:
    if c["class"] == "literal" and ("?" in c["first"] or "{" in c["first"]):
        print("  ", c["file"], c["line"], c["method"], c["first"][:100])

per_file = defaultdict(Counter)
for c in http_calls:
    per_file[c["file"]][style(c)] += 1
json.dump({k: dict(v) for k, v in per_file.items()},
          open(os.path.join(HERE, "30-frontend-java-callstyles.json"), "w"), indent=1)
