"""Inventory JPA associations in the backend model: target type, fetch type, and collection EAGER mappings."""
import os
import re

ROOT = r"$REPO\backend\src\main\java\de\greluc\krt\profit\basetool\backend\model"
ORG_TYPES = {"OrgUnit", "Squadron", "SpecialCommand", "Bereich", "Organisationsleitung"}
ANN = re.compile(r"@(ManyToOne|OneToOne|OneToMany|ManyToMany|ElementCollection)\b(\([^)]*\))?")

org_refs = []
eager = []
all_assoc = []
for dp, _, fns in os.walk(ROOT):
    for fn in sorted(fns):
        if not fn.endswith(".java"):
            continue
        path = os.path.join(dp, fn)
        lines = open(path, encoding="utf-8").read().split("\n")
        i = 0
        while i < len(lines):
            m = ANN.search(lines[i])
            if m and not lines[i].strip().startswith(("*", "/**", "//")):
                kind = m.group(1)
                args = m.group(2) or ""
                j = i
                block = lines[i]
                while j + 1 < len(lines) and not re.search(r"^\s*(private|protected|public)\s+[^(]*;", lines[j]):
                    j += 1
                    block += " " + lines[j]
                    if j - i > 12:
                        break
                decl = lines[j].strip()
                fm = re.search(r"fetch\s*=\s*FetchType\.(\w+)", block)
                fetch = fm.group(1) if fm else ("DEFAULT_EAGER" if kind in ("ManyToOne", "OneToOne") else "DEFAULT_LAZY")
                tm = re.search(r"(private|protected|public)\s+([\w<>, ?]+)\s+(\w+)\s*(=|;)", decl)
                ftype = tm.group(2) if tm else "?"
                fname = tm.group(3) if tm else "?"
                rec = (fn[:-5], i + 1, kind, fetch, ftype, fname)
                all_assoc.append(rec)
                inner = re.sub(r".*<\s*(?:\? extends\s+)?(\w+)\s*>.*", r"\1", ftype)
                if ftype in ORG_TYPES or inner in ORG_TYPES:
                    org_refs.append(rec)
                if fetch in ("EAGER", "DEFAULT_EAGER") and kind in ("OneToMany", "ManyToMany", "ElementCollection"):
                    eager.append(rec)
                if fetch in ("EAGER", "DEFAULT_EAGER"):
                    pass
                i = j
            i += 1

print("ASSOCIATIONS TARGETING THE ORGUNIT HIERARCHY:")
for r in org_refs:
    print(f"  {r[0]}.java:{r[1]} @{r[2]} fetch={r[3]} {r[4]} {r[5]}")
print()
print("EAGER COLLECTION MAPPINGS (OneToMany/ManyToMany/ElementCollection):")
for r in eager:
    print(f"  {r[0]}.java:{r[1]} @{r[2]} fetch={r[3]} {r[4]} {r[5]}")
print()
print("EAGER OR DEFAULT-EAGER TO-ONE MAPPINGS:")
for r in all_assoc:
    if r[2] in ("ManyToOne", "OneToOne") and r[3] in ("EAGER", "DEFAULT_EAGER"):
        print(f"  {r[0]}.java:{r[1]} @{r[2]} fetch={r[3]} {r[4]} {r[5]}")
print()
print(f"TOTAL associations scanned: {len(all_assoc)}")
