"""Audit call-site inventory: every AuditService/BankAuditService.record(...) call, by class and domain.

Bytecode (javap -c) gives the exact call count per class; source parsing gives file:line and the
AuditEventType constants named at each call site.
"""
import collections
import importlib.util as U
import json
import os
import re
import subprocess

spec = U.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "20-crosscutting-common.py"))
common = U.module_from_spec(spec)
spec.loader.exec_module(common)

CLS = os.path.join(common.ROOT, "backend", "build", "classes", "java", "main")
AUDIT = "de/greluc/krt/profit/basetool/backend/service/AuditService.record"
BANK = "de/greluc/krt/profit/basetool/backend/service/BankAuditService.record"

def bytecode_counts():
    files = []
    for dp, _, fs in os.walk(CLS):
        for f in fs:
            if f.endswith(".class"):
                p = os.path.join(dp, f)
                with open(p, "rb") as fh:
                    b = fh.read()
                if b"AuditService" in b:
                    files.append(p)
    counts = collections.Counter()
    for i in range(0, len(files), 100):
        out = subprocess.run(["javap", "-c", "-p"] + files[i:i + 100], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
        cur = None
        for line in out.splitlines():
            m = re.match(r"^(?:public |final |abstract |sealed |non-sealed )*(?:class|interface|enum|record) ([\w.$]+)", line)
            if m:
                cur = m.group(1)
                continue
            if "invoke" in line and (AUDIT in line or BANK in line):
                counts[(cur, "bank" if BANK in line else "audit")] += 1
    return counts

def source_sites():
    sites = []
    call_re = re.compile(r"\b(\w*[aA]uditService)\s*\.\s*record\s*\(")
    for path in common.iter_java("backend"):
        text = common.read(path)
        for m in call_re.finditer(text):
            start = m.end()
            depth = 1
            i = start
            while i < len(text) and depth:
                if text[i] == "(":
                    depth += 1
                elif text[i] == ")":
                    depth -= 1
                i += 1
            args = text[start:i - 1]
            line = text.count("\n", 0, m.start()) + 1
            types = re.findall(r"(?:AuditEventType|BankAuditEventType)\.([A-Z_]+)", args)
            bare = re.match(r"\s*([A-Z][A-Z_]+)\s*,", args)
            if not types and bare:
                types = [bare.group(1)]
            sites.append({"file": common.rel(path), "line": line, "var": m.group(1), "types": types,
                          "dynamic": not types})
    return sites

def enum_domains():
    path = os.path.join(common.ROOT, "backend/src/main/java/de/greluc/krt/profit/basetool/backend/model/AuditEventType.java")
    text = common.read(path)
    return dict(re.findall(r"^\s{2}([A-Z_]+)\(AuditDomain\.([A-Z_]+)\)", text, re.M))

def main():
    bc = bytecode_counts()
    sites = source_sites()
    ed = enum_domains()
    by_class = collections.Counter()
    by_domain = collections.Counter()
    cross = collections.Counter()
    for s in sites:
        fq = s["file"].split("src/main/java/")[1][:-5].replace("/", ".")
        d = common.domain_of(fq)
        s["class"] = fq
        s["domain"] = d
        by_class[(fq, s["var"])] += 1
        by_domain[(d, "bank" if s["var"].lower().startswith("bank") else "audit")] += 1
        for t in s["types"]:
            ad = ed.get(t)
            if ad:
                cross[(d, ad)] += 1
    total_bc_audit = sum(v for (c, k), v in bc.items() if k == "audit")
    total_bc_bank = sum(v for (c, k), v in bc.items() if k == "bank")
    enum_by_domain = collections.Counter(ed.values())
    res = {
        "bytecode_calls": {"audit": total_bc_audit, "bank": total_bc_bank,
                           "classes_audit": len({c for (c, k) in bc if k == "audit"}),
                           "classes_bank": len({c for (c, k) in bc if k == "bank"})},
        "source_calls": len(sites),
        "by_domain": {"%s/%s" % k: v for k, v in sorted(by_domain.items())},
        "by_class": {"%s (%s)" % k: v for k, v in by_class.most_common()},
        "writer_domain_to_audit_domain": {"%s -> %s" % k: v for k, v in sorted(cross.items())},
        "event_types": len(ed),
        "event_types_by_audit_domain": dict(enum_by_domain.most_common()),
        "dynamic_sites": [s for s in sites if s["dynamic"]],
        "sites": sites,
    }
    with open(os.path.join(common.SCRATCH, "20-crosscutting-audit.json"), "w", encoding="utf-8") as fh:
        json.dump(res, fh, indent=1)
    print("bytecode:", res["bytecode_calls"], "source call expressions:", res["source_calls"])
    print("event types:", res["event_types"], res["event_types_by_audit_domain"])
    print("calls by writer domain:", res["by_domain"])
    print("writer domain -> audit domain (named constants):")
    for k, v in res["writer_domain_to_audit_domain"].items():
        print("   ", k, v)
    print("dynamic (event type passed as variable):", len(res["dynamic_sites"]))
    for s in res["dynamic_sites"]:
        print("   ", s["file"], s["line"])
    print("top classes:")
    for k, v in list(res["by_class"].items())[:60]:
        print("   ", v, k)

if __name__ == "__main__":
    main()
