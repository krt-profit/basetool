"""Derive the current foreign-key graph from the Flyway migrations and group it by domain.

Replays every versioned migration in order: CREATE TABLE (inline REFERENCES and table-level FOREIGN
KEY), ALTER TABLE ADD [COLUMN ... REFERENCES | CONSTRAINT ... FOREIGN KEY], DROP CONSTRAINT, DROP
COLUMN, DROP TABLE. Default constraint names follow PostgreSQL (<table>_<col>_fkey). Also lists the
triggers that survive, with the table they fire on.
"""
import collections
import importlib.util as U
import json
import os
import re

spec = U.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "20-crosscutting-common.py"))
common = U.module_from_spec(spec)
spec.loader.exec_module(common)

MIG = os.path.join(common.ROOT, "backend", "src", "main", "resources", "db", "migration")

def version(fname):
    m = re.match(r"V(\d+)(?:_(\d+))?__", fname)
    return (int(m.group(1)), int(m.group(2) or 0)) if m else None

def strip(sql):
    sql = re.sub(r"\$([A-Za-z_]*)\$.*?\$\1\$", " $BODY$ ", sql, flags=re.S)
    sql = re.sub(r"--[^\n]*", " ", sql)
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.S)
    return sql

def split_top(s):
    parts, depth, cur = [], 0, []
    for ch in s:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if cur:
        parts.append("".join(cur))
    return [p.strip() for p in parts if p.strip()]

IDENT = r'"?([A-Za-z_][\w]*)"?'

def unq(x):
    return x.strip().strip('"').lower()

def main():
    fks = {}
    tables = set()
    triggers = {}
    files = sorted((f for f in os.listdir(MIG) if f.endswith(".sql") and version(f)), key=version)
    for f in files:
        raw = common.read(os.path.join(MIG, f))
        sql = strip(raw)
        for stmt in sql.split(";"):
            s = " ".join(stmt.split())
            if not s:
                continue
            tm = re.match(r"CREATE\s+(?:OR\s+REPLACE\s+)?(?:CONSTRAINT\s+)?TRIGGER\s+(\w+)\s+(.*?)\s+ON\s+(\w+)", s, re.I)
            if tm:
                triggers[(tm.group(1).lower(), tm.group(3).lower())] = f
                continue
            tm = re.match(r"DROP\s+TRIGGER\s+(?:IF\s+EXISTS\s+)?(\w+)\s+ON\s+(\w+)", s, re.I)
            if tm:
                triggers.pop((tm.group(1).lower(), tm.group(2).lower()), None)
                continue
            tm = re.match(r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(.*)", s, re.I)
            if tm:
                gone = {unq(x.split()[0]) for x in tm.group(1).split(",") if x.strip()}
                for k in [k for k in triggers if k[1] in gone]:
                    triggers.pop(k)
            m = re.match(r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?" + IDENT + r"\s*\((.*)\)", s, re.I)
            if m:
                t = unq(m.group(1))
                tables.add(t)
                for part in split_top(m.group(2)):
                    cm = re.match(r"(?:CONSTRAINT\s+" + IDENT + r"\s+)?FOREIGN\s+KEY\s*\(([^)]*)\)\s*REFERENCES\s+" + IDENT, part, re.I)
                    if cm:
                        cols = [unq(c) for c in cm.group(2).split(",")]
                        name = unq(cm.group(1)) if cm.group(1) else "%s_%s_fkey" % (t, "_".join(cols))
                        fks[(t, name)] = {"table": t, "cols": cols, "ref": unq(cm.group(3)), "file": f}
                        continue
                    if re.match(r"(CONSTRAINT|PRIMARY|UNIQUE|CHECK|EXCLUDE)\b", part, re.I):
                        continue
                    im = re.match(IDENT + r"\s+.*?\bREFERENCES\s+" + IDENT, part, re.I)
                    if im:
                        col = unq(im.group(1))
                        nm = re.search(r"CONSTRAINT\s+" + IDENT + r"\s+REFERENCES", part, re.I)
                        name = unq(nm.group(1)) if nm else "%s_%s_fkey" % (t, col)
                        fks[(t, name)] = {"table": t, "cols": [col], "ref": unq(im.group(2)), "file": f}
                continue
            m = re.match(r"ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:ONLY\s+)?" + IDENT + r"\s+(.*)", s, re.I)
            if m:
                t = unq(m.group(1))
                for action in split_top(m.group(2)):
                    am = re.match(r"ADD\s+(?:CONSTRAINT\s+" + IDENT + r"\s+)?FOREIGN\s+KEY\s*\(([^)]*)\)\s*REFERENCES\s+" + IDENT, action, re.I)
                    if am:
                        cols = [unq(c) for c in am.group(2).split(",")]
                        name = unq(am.group(1)) if am.group(1) else "%s_%s_fkey" % (t, "_".join(cols))
                        fks[(t, name)] = {"table": t, "cols": cols, "ref": unq(am.group(3)), "file": f}
                        continue
                    am = re.match(r"ADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?" + IDENT + r"\s+.*?\bREFERENCES\s+" + IDENT, action, re.I)
                    if am and am.group(1).upper() not in ("CONSTRAINT",):
                        col = unq(am.group(1))
                        nm = re.search(r"CONSTRAINT\s+" + IDENT + r"\s+REFERENCES", action, re.I)
                        name = unq(nm.group(1)) if nm else "%s_%s_fkey" % (t, col)
                        fks[(t, name)] = {"table": t, "cols": [col], "ref": unq(am.group(2)), "file": f}
                        continue
                    dm = re.match(r"DROP\s+CONSTRAINT\s+(?:IF\s+EXISTS\s+)?" + IDENT, action, re.I)
                    if dm:
                        fks.pop((t, unq(dm.group(1))), None)
                        continue
                    dm = re.match(r"DROP\s+(?:COLUMN\s+)?(?:IF\s+EXISTS\s+)?" + IDENT, action, re.I)
                    if dm and dm.group(1).upper() not in ("CONSTRAINT", "NOT", "DEFAULT"):
                        col = unq(dm.group(1))
                        for k in [k for k, v in fks.items() if v["table"] == t and col in v["cols"]]:
                            fks.pop(k)
                        continue
                    rm = re.match(r"RENAME\s+CONSTRAINT\s+" + IDENT + r"\s+TO\s+" + IDENT, action, re.I)
                    if rm and (t, unq(rm.group(1))) in fks:
                        fks[(t, unq(rm.group(2)))] = fks.pop((t, unq(rm.group(1))))
                    rc = re.match(r"RENAME\s+(?:COLUMN\s+)?" + IDENT + r"\s+TO\s+" + IDENT, action, re.I)
                    if rc and rc.group(1).upper() != "CONSTRAINT":
                        for v in fks.values():
                            if v["table"] == t:
                                v["cols"] = [unq(rc.group(2)) if c == unq(rc.group(1)) else c for c in v["cols"]]
                continue
            m = re.match(r"DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?(.*)", s, re.I)
            if m:
                for t in [unq(x.split()[0]) for x in m.group(1).split(",") if x.strip()]:
                    tables.discard(t)
                    for k in [k for k, v in fks.items() if v["table"] == t]:
                        fks.pop(k)
    return fks, tables, triggers

def table_domains():
    ann = common.load_annotations()["backend"]
    mapping = {}
    for fq, rec in ann.items():
        names = [a for a in rec["class"] if a["type"] == "jakarta.persistence.Table"]
        ents = [a for a in rec["class"] if a["type"] == "jakarta.persistence.Entity"]
        if not ents:
            continue
        tn = None
        if names:
            mm = re.search(r'name="([^"]+)"', names[0]["text"])
            tn = mm.group(1).lower() if mm else None
        if not tn:
            s = common.simple_name(fq)
            tn = re.sub(r"(?<!^)(?=[A-Z])", "_", s).lower()
        mapping.setdefault(tn, (fq, common.domain_of(fq)))
    return mapping

TABLE_PREFIX_DOMAIN = [
    (r"^app_user|^user_|^role|^terms|^deletion_request|^discord", "identity"),
    (r"^org_unit|^squadron|^special_command", "orgunit"),
    (r"^mission", "mission"), (r"^operation", "operation"), (r"^job_order|^material_claim", "joborder"),
    (r"^inventory", "inventory"), (r"^personal_inventory", "personalinventory"),
    (r"^personal_blueprint|^default_blueprint|^blueprint", "blueprint"), (r"^ship$|^ship_", "hangar"),
    (r"^material_exchange", "materialexchange"), (r"^refinery", "refinery"), (r"^bank", "bank"),
    (r"^notification", "notification"), (r"^audit", "audit"), (r"^promotion|^member_evaluation|^rank_requirement", "promotion"),
    (r"^org_chart", "orgchart"), (r"^kommando", "leadership"), (r"^exchange", "exchange"),
    (r"^announcement", "dashboard"), (r"^system_setting|^p4k", "admin"),
]

def main2():
    fks, tables, triggers = main()
    tdom = table_domains()

    def dom(t):
        if t in tdom:
            return tdom[t][1]
        for rx, d in TABLE_PREFIX_DOMAIN:
            if re.search(rx, t):
                return d
        return "catalogue?"

    edges = collections.Counter()
    detail = collections.defaultdict(list)
    for v in fks.values():
        a, b = dom(v["table"]), dom(v["ref"])
        if a != b:
            edges[(a, b)] += 1
            detail[(a, b)].append("%s(%s)->%s" % (v["table"], ",".join(v["cols"]), v["ref"]))
    to_user = [v for v in fks.values() if v["ref"] == "app_user"]
    to_org = [v for v in fks.values() if v["ref"] in ("org_unit", "squadron")]
    res = {
        "fk_total": len(fks),
        "tables": len(tables),
        "cross_domain_fk_total": sum(edges.values()),
        "fk_to_app_user": len(to_user),
        "fk_to_org_unit_or_squadron": len(to_org),
        "edges": {"%s -> %s" % k: {"count": n, "fks": detail[k]} for k, n in edges.most_common()},
        "triggers": sorted("%s ON %s (%s) [%s -> %s]" % (k[0], k[1], f, dom(k[1]), "?") for k, f in triggers.items()),
        "unmapped_tables": sorted(t for t in tables if t not in tdom),
    }
    with open(os.path.join(common.SCRATCH, "20-crosscutting-fk.json"), "w", encoding="utf-8") as fh:
        json.dump(res, fh, indent=1)
    print("tables", res["tables"], "fks", res["fk_total"], "cross-domain", res["cross_domain_fk_total"],
          "-> app_user", res["fk_to_app_user"], "-> org_unit/squadron", res["fk_to_org_unit_or_squadron"])
    excl_hubs = {k: v for k, v in res["edges"].items() if not k.endswith("-> identity") and not k.endswith("-> orgunit")}
    print("cross-domain edges excluding to identity/orgunit:", sum(v["count"] for v in excl_hubs.values()))
    for k, v in res["edges"].items():
        print("  %-40s %3d  %s" % (k, v["count"], "; ".join(v["fks"][:6])))
    print("triggers:")
    for t in res["triggers"]:
        print("  ", t)
    print("tables without an entity:", res["unmapped_tables"])

if __name__ == "__main__":
    main2()
