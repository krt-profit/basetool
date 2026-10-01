import importlib.util
import json
import os
import sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("d", os.path.join(HERE, "81-prev-sept-b-data.py"))
d = importlib.util.module_from_spec(spec)
spec.loader.exec_module(d)

with open(os.path.join(HERE, "sept_audit_findings.json"), encoding="utf-8") as f:
    src = json.load(f)

IN_SCOPE = ("Frontend", "CI", "Betrieb")
SIBLING = ("Android", "Extractor", "P4K Reader")
AREA_ORDER = {"Frontend": 0, "CI": 1, "Betrieb": 2, "Android": 3, "Extractor": 4, "P4K Reader": 5}

rows = []
missing = []
for f in src:
    area = f["area"]
    if area in IN_SCOPE:
        e = d.E.get(f["id"])
        if e is None:
            missing.append(f["id"])
            continue
        rows.append({
            "id": f["id"], "area": area, "prio_old": f"P{f['prio']}", "title_de": f["title"],
            "status": e["status"], "evidence": e["evidence"], "verdict": e["verdict"],
            "prio_new": e["prio_new"], "reasoning": e["reasoning"],
            "domain_effect": e["domain_effect"], "security_note": e["security_note"],
        })
    elif area in SIBLING:
        rows.append({
            "id": f["id"], "area": area, "prio_old": f"P{f['prio']}", "title_de": f["title"],
            "status": "OUT-OF-SCOPE",
            "evidence": "Vault status (not verified - sibling repository outside this audit): " + d.SIB[f["id"]],
            "verdict": "OUT-OF-SCOPE", "prio_new": "-",
            "reasoning": "Listed only; the goal covers the main repository.",
            "domain_effect": "n/a (sibling repository)", "security_note": "n/a (not re-evaluated)",
        })
extra = sorted(set(d.E) - {r["id"] for r in rows})
assert not missing, missing
assert not extra, extra
rows.sort(key=lambda r: (AREA_ORDER[r["area"]], int(r["prio_old"][1:]), r["id"]))

with open(os.path.join(HERE, "81-prev-sept-b.json"), "w", encoding="utf-8", newline="\n") as f:
    json.dump(rows, f, ensure_ascii=False, indent=2)

inscope = [r for r in rows if r["area"] in IN_SCOPE]
sib = [r for r in rows if r["area"] in SIBLING]
st = Counter(r["status"] for r in inscope)
vd = Counter(r["verdict"] for r in inscope)
pn = Counter(r["prio_new"] for r in inscope)
by_area = Counter(r["area"] for r in inscope)
print("in scope:", len(inscope), dict(by_area), "siblings:", len(sib))
print("status:", dict(st))
print("verdict:", dict(vd))
print("prio_new:", dict(pn))

with open(os.path.join(HERE, "81-prev-sept-b-md-body.md"), encoding="utf-8") as f:
    body = f.read()

def short(r):
    if r["status"] == "OUT-OF-SCOPE":
        return d.SIB[r["id"]].split(" (vault")[0]
    return d.NOTE[r["id"]]

assert set(d.NOTE) == set(d.E), sorted(set(d.NOTE) ^ set(d.E))

def mark(r):
    return r["status"] + ("†" if r["id"] in d.HOST_UNVERIFIED else "")

table = ["| # | ID | Area | Prio (09-22) | Status | Verdict | Prio now | Note |",
         "|---|---|---|---|---|---|---|---|"]
for i, r in enumerate(rows, 1):
    note = short(r).replace("|", "/")
    table.append(f"| {i} | {r['id']} | {r['area']} | {r['prio_old']} | {mark(r)} | {r['verdict']} | {r['prio_new']} | {note} |")

counts = []
counts.append("| Status | Count |")
counts.append("|---|---|")
for k in ("DONE", "PARTIAL", "OPEN", "SUPERSEDED", "REGRESSED", "NOT-VERIFIABLE"):
    counts.append(f"| {k} | {st.get(k, 0)} |")
counts.append("")
counts.append("| Verdict | Count |")
counts.append("|---|---|")
for k in ("CONFIRMED", "ADJUSTED", "SUPERSEDED-BY-MODULARISATION", "REPRIORITISED", "DROPPED"):
    counts.append(f"| {k} | {vd.get(k, 0)} |")
counts.append("")
counts.append("| Remaining priority | Count |")
counts.append("|---|---|")
for k in ("P0", "P1", "P2", "P3", "closed"):
    counts.append(f"| {k} | {pn.get(k, 0)} |")
counts.append("")
counts.append("| Area | In scope | DONE | PARTIAL |")
counts.append("|---|---|---|---|")
for a in IN_SCOPE:
    rs = [r for r in inscope if r["area"] == a]
    counts.append(f"| {a} | {len(rs)} | {sum(1 for r in rs if r['status']=='DONE')} | {sum(1 for r in rs if r['status']=='PARTIAL')} |")
sib_c = Counter(r["area"] for r in sib)
counts.append("")
counts.append(f"Sibling findings listed OUT-OF-SCOPE: {len(sib)} (Android {sib_c['Android']}, Extractor {sib_c['Extractor']}, P4K Reader {sib_c['P4K Reader']}); per the vault 19 are done and 1 (SIB-SEC-01) is decided as won't-do.")

md = body.replace("{{COUNTS}}", "\n".join(counts)).replace("{{TABLE}}", "\n".join(table))
md = md.replace("{{HOST}}", ", ".join(d.HOST_UNVERIFIED))
with open(os.path.join(HERE, "81-prev-sept-b.md"), "w", encoding="utf-8", newline="\n") as f:
    f.write(md)
print("written", len(rows), "rows")
