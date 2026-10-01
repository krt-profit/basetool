"""Check schema-name uniqueness: every components.schemas name vs Java simple names in backend main."""
import json
import os
import re
import sys
from collections import defaultdict

REPO = r"$REPO"
SRC = os.path.join(REPO, "backend", "src", "main", "java")

def main():
    names = defaultdict(list)
    for dp, _, fs in os.walk(SRC):
        for f in fs:
            if not f.endswith(".java"):
                continue
            p = os.path.join(dp, f)
            with open(p, encoding="utf-8") as fh:
                t = fh.read()
            for m in re.finditer(r"\b(?:record|class|enum|interface)\s+([A-Z]\w*)", t):
                names[m.group(1)].append(os.path.relpath(p, SRC).replace("\\", "/"))
    with open(os.path.join(REPO, "backend", "src", "main", "resources", "api", "openapi.json"), encoding="utf-8") as fh:
        schemas = json.load(fh)["components"]["schemas"]
    dup = {n: sorted(set(v)) for n, v in names.items() if len(set(v)) > 1}
    exposed_dup = {n: v for n, v in dup.items() if n in schemas}
    print("schemas:", len(schemas))
    print("java simple names declared in more than one file:", len(dup))
    print("of which also a schema name:", len(exposed_dup))
    for n, v in sorted(exposed_dup.items()):
        print("  ", n, v)
    generic = [s for s in schemas if s.startswith("PageResponse")]
    print("PageResponse* schemas (generic instantiations):", len(generic))
    unmatched = [s for s in schemas if s not in names and not s.startswith("PageResponse")]
    print("schema names with no same-named Java type:", len(unmatched), unmatched[:20])

if __name__ == "__main__":
    sys.exit(main())
