"""Summarise the switch inventory written by 60-modern-java-scan.py."""

import collections
import json
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
D = json.load(open(os.path.join(HERE, "60-modern-java-data.json"), encoding="utf-8"))
SW = D["switches"]

SEC = re.compile(r"(Role|Permission|Capability|Authority|Scope|Access|Grant|Approv|Status|State|Audit|Kind|Visibility|Level|Gate|Decision|Outcome|Verdict|Mode|Action|Grantee|Relative|Membership)", re.I)

def kind_of(sw):
    lk = set(sw["label_kinds"])
    if sw.get("enum_type") or (sw["enum_candidates"] and lk <= {"const", "default", "null"}):
        return "enum"
    if lk & {"pattern", "recordpattern"}:
        return "pattern"
    if lk and lk <= {"string", "default", "null"}:
        return "string"
    if lk and lk <= {"int", "char", "default"}:
        return "int"
    if "const" in lk and not sw["enum_candidates"]:
        return "const-nonenum"
    return "other:" + ",".join(sorted(lk))

rows = collections.Counter()
for sw in SW:
    k = kind_of(sw)
    rows[(sw["group"], k, "expr" if sw["expr"] else "stmt", "+".join(sw["forms"]) or "-", "default" if sw["has_default"] else "nodefault")] += 1

print("== switches by group/kind/expr/form/default ==")
for key, n in sorted(rows.items()):
    print(n, key)

tot = collections.Counter()
for sw in SW:
    tot[(sw["group"].split("/")[1], "expr" if sw["expr"] else "stmt")] += 1
print("\n== totals by set/expr ==", dict(tot))
forms = collections.Counter((sw["group"].split("/")[1], "+".join(sw["forms"])) for sw in SW)
print("== forms ==", dict(forms))

print("\n== enum switches (main) with enum type ==")
enum_rows = []
for sw in SW:
    if not sw["group"].endswith("/main"):
        continue
    if kind_of(sw) != "enum":
        continue
    et = sw.get("enum_type") or sw.get("enum_type_guess") or "|".join(sw["enum_candidates"] or [])
    src = "bytecode" if sw.get("enum_type") else "labels"
    enum_rows.append((et, sw, src))
enum_rows.sort(key=lambda r: (r[0], r[1]["rel"], r[1]["line"]))
for et, sw, src in enum_rows:
    d = sw["default"]["kind"] + ": " + sw["default"]["text"][:70] if sw["has_default"] else "-"
    print(f"{et.split('.')[-1]:38s} {'EXPR' if sw['expr'] else 'STMT'} {'+'.join(sw['forms']):6s} def={d:80s} {sw['rel'].split('/')[-1]}:{sw['line']} [{src}] n_labels={len(sw['labels'])}")

print("\n== summary enum switches main ==")
c = collections.Counter()
for et, sw, src in enum_rows:
    c[("expr" if sw["expr"] else "stmt", "default" if sw["has_default"] else "nodefault")] += 1
print(dict(c))
print("security-relevant enum names among them:")
c2 = collections.Counter()
for et, sw, src in enum_rows:
    if SEC.search(et.split(".")[-1]):
        c2[("expr" if sw["expr"] else "stmt", "default" if sw["has_default"] else "nodefault", sw["default"]["kind"] if sw["has_default"] else "-")] += 1
print(dict(c2))

print("\n== pattern switches ==")
for sw in SW:
    if kind_of(sw) == "pattern":
        print(sw["rel"], sw["line"], sw["labels"][:6], "default" if sw["has_default"] else "")

print("\n== const-nonenum / other ==")
for sw in SW:
    k = kind_of(sw)
    if k.startswith("other") or k == "const-nonenum":
        print(k, sw["rel"], sw["line"], sw["labels"][:5], sw["selector"])

print("\n== string switches (main) ==")
for sw in SW:
    if kind_of(sw) == "string" and sw["group"].endswith("/main"):
        print(sw["rel"].split("/")[-1], sw["line"], "EXPR" if sw["expr"] else "STMT", "+".join(sw["forms"]), "default:" + (sw["default"]["kind"] if sw["has_default"] else "-"), len(sw["labels"]))

print("\n== bytecode switch events without a matching source switch ==")
bc = json.load(open(os.path.join(HERE, "60-modern-java-bytecode.json"), encoding="utf-8"))["events"]
matched = set()
for sw in SW:
    for e in sw.get("bytecode", []):
        matched.add((e["source"], e["line"], e["kind"]))
for e in bc:
    if (e["source"], e["line"], e["kind"]) not in matched:
        print("UNMATCHED", e["kind"], e["source"], e["line"], e["detail"])
