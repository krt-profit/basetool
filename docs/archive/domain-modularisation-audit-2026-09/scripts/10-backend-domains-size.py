"""Size figures: classes over 600 LOC per domain, and the services/controllers with the most public methods.

A public method is a line at the 2-space member indentation that starts with 'public' and declares a
method (not a nested type, not a constant). LOC = physical lines of the source file.
"""
import importlib.util
import os
import re
from collections import defaultdict

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("common", os.path.join(here, "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

PUB = re.compile(r"^  public\s+(?!class\b|record\b|enum\b|interface\b|static\s+final\b|final\s+class\b|static\s+class\b|static\s+record\b|abstract\s+class\b|sealed\b)(?:@\w+\s+)*(?:static\s+)?(?:@\w+\s+)*(?:<[^>]+>\s+)?(?:@\w+\s+)*[\w<>\[\],?. @]+?\s+\w+\s*\(", re.M)

def main():
    cls = common.load_classes()
    big = defaultdict(list)
    pubs = []
    for f, r in cls.items():
        loc = int(r["loc"])
        if loc > 600:
            big[r["domain"]].append((loc, r["simple"], r["layer"]))
        if r["layer"] in ("service", "controller") and r["kind"] == "class":
            with open(os.path.join(common.REPO, r["path"]), encoding="utf-8") as fh:
                t = fh.read()
            t = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), t, flags=re.S)
            n = len(PUB.findall(t))
            ctor = len(re.findall(r"^  (?:private|final|@)?.*\b" + re.escape(r["simple"]) + r"\s*\(", t, re.M))
            deps = len(re.findall(r"^  private final (?!static)[\w<>.,? ]+\s+\w+;", t, re.M))
            pubs.append((n, r["simple"], r["domain"], r["layer"], loc, deps))
    total = sum(len(v) for v in big.values())
    print("classes over 600 LOC: %d" % total)
    for d in sorted(big, key=lambda d: -len(big[d])):
        items = sorted(big[d], reverse=True)
        print("  %-18s %2d  %s" % (d, len(items), ", ".join("%s(%d,%s)" % (s, l, ly) for l, s, ly in items)))
    print("\nservices by public methods (top 25): n, class, domain, LOC, final-field deps")
    for n, s, d, ly, loc, deps in sorted([p for p in pubs if p[3] == "service"], reverse=True)[:25]:
        print("  %3d  %-40s %-16s loc=%5d deps=%2d" % (n, s, d, loc, deps))
    print("\ncontrollers by public methods (top 15):")
    for n, s, d, ly, loc, deps in sorted([p for p in pubs if p[3] == "controller"], reverse=True)[:15]:
        print("  %3d  %-40s %-16s loc=%5d deps=%2d" % (n, s, d, loc, deps))
    print("\nservices by final-field dependencies (top 15):")
    for n, s, d, ly, loc, deps in sorted([p for p in pubs if p[3] == "service"], key=lambda p: -p[5])[:15]:
        print("  deps=%2d  %-40s %-16s loc=%5d public=%d" % (deps, s, d, loc, n))

if __name__ == "__main__":
    main()
