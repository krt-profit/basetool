"""Coupling hidden in query strings, which jdeps cannot see.

For every repository/service/config source: collect the string literals (JPQL, native SQL, table lists),
find entity names (JPQL) and table names (SQL) owned by another domain, and count them per file.
Also lists the ScopeSpecifications constants each repository concatenates into its @Query strings.
"""
import importlib.util
import os
import re
from collections import Counter, defaultdict

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("common", os.path.join(here, "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

def snake(name):
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()

def main():
    cls = common.load_classes()
    entities = {}
    tables = {}
    for f, r in cls.items():
        if r["layer"] == "model" and "Entity" in r["stereotypes"]:
            with open(os.path.join(common.REPO, r["path"]), encoding="utf-8") as fh:
                t = fh.read()
            m = re.search(r'@Table\s*\(\s*name\s*=\s*"(\w+)"', t)
            table = m.group(1) if m else snake(r["simple"])
            entities[r["simple"]] = r["domain"]
            tables[table] = r["domain"]
            for jt in re.findall(r'@(?:JoinTable|CollectionTable)\s*\(\s*name\s*=\s*"(\w+)"', t):
                tables[jt] = r["domain"]
    per_file = []
    for f, r in sorted(cls.items()):
        if r["layer"] not in ("repository", "service", "config", "support"):
            continue
        with open(os.path.join(common.REPO, r["path"]), encoding="utf-8") as fh:
            raw = fh.read()
        code = re.sub(r"/\*.*?\*/", "", raw, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code)
        lits = re.findall(r'"""(.*?)"""', code, re.S) + re.findall(r'"((?:\\.|[^"\\\n])*)"', re.sub(r'""".*?"""', "", code, flags=re.S))
        text = "\n".join(lits)
        ent_refs = Counter()
        for m in re.finditer(r"\b(FROM|JOIN|UPDATE|INTO|TYPE\([^)]*\)\s*=|MEMBER\s+OF|EXISTS\s*\(\s*SELECT[^)]*?FROM)\s+(\w+)", text, re.I):
            name = m.group(2)
            if name in entities and entities[name] != r["domain"]:
                ent_refs[name] += 1
        for m in re.finditer(r"TYPE\(\s*\w+\s*\)\s*(?:=|IN\s*\()\s*(\w+)", text):
            name = m.group(1)
            if name in entities and entities[name] != r["domain"]:
                ent_refs[name] += 1
        tab_refs = Counter()
        for m in re.finditer(r"\b([a-z][a-z0-9_]+)\b", text):
            w = m.group(1)
            if w in tables and tables[w] != r["domain"] and "_" in w or (w in tables and tables[w] != r["domain"] and w in ("mission", "ship", "notification", "operation")):
                tab_refs[w] += 1
        scope = sorted(set(re.findall(r"ScopeSpecifications\.(\w+)", code)))
        if ent_refs or tab_refs or scope:
            per_file.append((r["simple"], r["domain"], r["layer"], dict(ent_refs), dict(tab_refs), scope))
    print("files with foreign entity/table names in string literals or ScopeSpecifications use:", len(per_file))
    dom_pairs = Counter()
    for simple, d, layer, er, tr, sc in per_file:
        doms = set(entities[e] for e in er) | set(tables[t] for t in tr)
        for x in doms:
            dom_pairs[(d, x)] += 1
        print("  %-42s %-16s %-10s JPQL-foreign=%s SQL-foreign=%s scope=%s" % (simple, d, layer, er, tr, ",".join(sc)))
    print("\nfile counts per (file domain -> referenced domain):")
    for (a, b), n in dom_pairs.most_common():
        print("  %-16s -> %-16s %d" % (a, b, n))

if __name__ == "__main__":
    main()
