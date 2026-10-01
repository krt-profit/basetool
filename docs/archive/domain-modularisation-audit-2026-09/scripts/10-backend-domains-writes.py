"""Cross-domain write paths: calls from a class of domain X that write state owned by domain Y.

Detects (1) repository writes on a foreign repository (save*/delete*/derived delete/@Modifying),
(2) pessimistic/advisory lock acquisitions on a foreign repository, (3) calls into a foreign service
method that is write-capable (@Transactional without readOnly, MANDATORY, REQUIRES_NEW, or a body that
writes), and (4) setter / collection mutations on local variables typed with a foreign entity.
Writes 10-backend-domains-writes.csv and prints a summary grouped by caller domain -> target domain.
"""
import csv
import importlib.util
import os
import re
from collections import Counter, defaultdict

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("common", os.path.join(here, "10-backend-domains-common.py"))
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)

OUT = os.path.join(here, "10-backend-domains-writes.csv")
STD_WRITES = {"save", "saveAll", "saveAndFlush", "saveAllAndFlush", "delete", "deleteAll", "deleteById",
              "deleteAllById", "deleteAllInBatch", "deleteAllByIdInBatch", "deleteInBatch", "flush"}
NON_DOMAIN = {"shared-kernel", "infrastructure"}

def strip(text):
    text = re.sub(r'"""(?:.|\n)*?"""', lambda m: '"' + re.sub(r"[^\n]", " ", m.group(0)[3:-3]) + '"', text)
    text = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    return text

def read(row):
    with open(os.path.join(common.REPO, row["path"].replace("/", os.sep)), encoding="utf-8") as fh:
        return fh.read()

def repo_methods(raw):
    """Return {method: set(tags)} for a repository interface; tags: modifying, lock:<mode>, native-lock."""
    text = strip(raw)
    raw_nc = re.sub(r"/\*.*?\*/", lambda m: re.sub(r"[^\n]", " ", m.group(0)), raw, flags=re.S)
    out = defaultdict(set)
    body = text[text.find("{") + 1:]
    raw_body = raw_nc[raw_nc.find("{") + 1:]
    chunks = body.split(";")
    raw_chunks = raw_body.split(";")
    for i, ch in enumerate(chunks):
        tags = set()
        if "@Modifying" in ch:
            tags.add("modifying")
        lm = re.search(r"@Lock\(\s*LockModeType\.(\w+)", ch)
        if lm:
            tags.add("lock:" + lm.group(1))
        rc = raw_chunks[i] if i < len(raw_chunks) else ""
        if "pg_advisory_xact_lock" in rc:
            tags.add("lock:ADVISORY")
        if re.search(r"FOR\s+UPDATE", rc, re.I):
            tags.add("lock:FOR_UPDATE")
        s = ch
        prev = None
        while prev != s:
            prev = s
            s = re.sub(r"@[\w.]+\s*\([^()]*\)", " ", s)
        s = re.sub(r"@[\w.]+", " ", s)
        m = re.search(r"(\w+)\s*\(", s)
        if m:
            out[m.group(1)] |= tags
    return out

HEADER = re.compile(r"^  (?=[\w<@])(?!return\b|throw\b|if\b|for\b|while\b|switch\b|else\b|try\b|catch\b|new\b)(?:[\w<>\[\],?.@ ]+?\s+)?(\w+)\s*\(")

def methods_of(raw):
    """Split a class into (name, annotations, start_line, body) using the 2-space member indentation."""
    text = strip(raw)
    lines = text.split("\n")
    res = []
    ann = []
    cur = None
    depth_ann = 0
    for i, ln in enumerate(lines):
        if depth_ann > 0:
            ann.append(ln)
            depth_ann += ln.count("(") - ln.count(")")
            continue
        if re.match(r"^  @\w", ln):
            ann.append(ln)
            depth_ann = ln.count("(") - ln.count(")")
            continue
        m = HEADER.match(ln)
        if m and not ln.rstrip().endswith(";") and not re.match(r"^  (?:private|protected|public)?\s*(?:static\s+)?(?:final\s+)?[\w<>\[\],?. ]+\s+\w+\s*=", ln):
            if cur:
                res.append(cur)
            cur = {"name": m.group(1), "ann": " ".join(ann), "line": i + 1, "body": [], "private": ln.lstrip().startswith("private ")}
            ann = []
            continue
        if re.match(r"^  \S", ln):
            ann = []
        if cur is not None:
            cur["body"].append(ln)
    if cur:
        res.append(cur)
    for r in res:
        r["body"] = "\n".join(r["body"])
    return res

def class_tx(raw):
    text = strip(raw)
    m = re.search(r"((?:@[\w.]+(?:\([^)]*\))?\s*)+)(?:public\s+|final\s+|abstract\s+)*(?:class|interface|record)\s", text)
    anns = m.group(1) if m else ""
    t = re.search(r"@Transactional(\([^)]*\))?", anns)
    if not t:
        return ""
    return "readOnly" if t.group(1) and "readOnly = true" in t.group(1) else "rw"

def tx_of(ann, cls_default):
    t = re.search(r"@Transactional(\([^)]*\))?", ann)
    if not t:
        return cls_default or "none"
    a = t.group(1) or ""
    if "MANDATORY" in a:
        return "MANDATORY"
    if "REQUIRES_NEW" in a:
        return "REQUIRES_NEW"
    if "readOnly = true" in a:
        return "readOnly"
    return "rw"

def main():
    cls = common.load_classes()
    by_simple = {r["simple"]: r for r in cls.values()}
    repo_info = {}
    for f, r in cls.items():
        if r["layer"] == "repository" and r["kind"] == "interface":
            repo_info[r["simple"]] = repo_methods(read(r))
    svc_methods = {}
    svc_default = {}
    for f, r in cls.items():
        if r["layer"] in ("service", "support", "task") and r["kind"] == "class":
            raw = read(r)
            svc_default[r["simple"]] = class_tx(raw)
            svc_methods[r["simple"]] = methods_of(raw)

    def repo_writes(repo, meth):
        tags = repo_info.get(repo, {}).get(meth, set())
        if meth in STD_WRITES or re.match(r"^(delete|remove)[A-Z]", meth) or "modifying" in tags:
            return "write"
        if any(t.startswith("lock:") for t in tags):
            return "lock"
        return None

    def svc_method_kind(svc, meth):
        ms = [m for m in svc_methods.get(svc, []) if m["name"] == meth]
        if not ms:
            return None, ""
        kinds = set()
        for m in ms:
            kinds.add(tx_of(m["ann"], svc_default.get(svc, "")))
        writes_body = any(re.search(r"\.(save\w*|delete\w*|remove[A-Z]\w*)\(", m["body"]) for m in ms)
        if kinds & {"MANDATORY", "REQUIRES_NEW", "rw"} or writes_body:
            return "write", "/".join(sorted(kinds)) + ("+body-writes" if writes_body else "")
        return None, "/".join(sorted(kinds))

    rows = []
    for f, r in sorted(cls.items()):
        if r["layer"] not in ("service", "support", "task", "controller", "config") or r["kind"] != "class":
            continue
        raw = read(r)
        text = strip(raw)
        fields = dict((n, t) for t, n in re.findall(r"^\s+private\s+final\s+(?:@\w+\s+)*([\w.]+)(?:<[^>]*>)?\s+(\w+)\s*;", text, re.M))
        prov = dict((n, t) for t, n in re.findall(r"^\s+private\s+final\s+ObjectProvider<(\w+)>\s+(\w+)\s*;", text, re.M))
        default = class_tx(raw)
        model_imports = set(re.findall(r"^import\s+" + re.escape(common.BASE_PKG) + r"\.model(?:\.scwiki)?\.(\w+);", raw, re.M))
        for m in methods_of(raw):
            caller_tx = "private(caller-tx)" if m.get("private") and "@Transactional" not in m["ann"] else tx_of(m["ann"], default)
            body = m["body"]
            base_line = m["line"]
            for cm in re.finditer(r"\b(\w+)\.(\w+)\s*\(", body):
                var, meth = cm.group(1), cm.group(2)
                line = base_line + body.count("\n", 0, cm.start()) + 1
                ftype = fields.get(var)
                if ftype is None and var in prov:
                    continue
                if ftype is None or ftype not in by_simple:
                    continue
                t = by_simple[ftype]
                if t["domain"] == r["domain"] or t["domain"] in NON_DOMAIN or r["domain"] in NON_DOMAIN and False:
                    continue
                if t["layer"] == "repository":
                    k = repo_writes(ftype, meth)
                    if k:
                        tags = ",".join(sorted(repo_info.get(ftype, {}).get(meth, set())))
                        rows.append([r["simple"], r["domain"], m["name"], caller_tx, "%s:%d" % (r["path"], line), "repository-" + k, ftype, t["domain"], meth, tags])
                elif t["layer"] in ("service", "support", "task") and t["kind"] == "class":
                    k, how = svc_method_kind(ftype, meth)
                    if k:
                        rows.append([r["simple"], r["domain"], m["name"], caller_tx, "%s:%d" % (r["path"], line), "service-write", ftype, t["domain"], meth, how])
            local = re.findall(r"\b([A-Z]\w+)\s+(\w+)\s*(?:=|:|,|\))", body)
            for typ, var in local:
                t = by_simple.get(typ)
                if not t or t["domain"] == r["domain"] or t["domain"] in NON_DOMAIN:
                    continue
                if t["layer"] != "model" or "Entity" not in t["stereotypes"]:
                    continue
                if typ not in model_imports:
                    continue
                for mm in re.finditer(r"\b" + re.escape(var) + r"\.(set[A-Z]\w*|get\w+\(\)\.(?:add|remove|clear|addAll|removeIf)\w*)\s*\(", body):
                    line = base_line + body.count("\n", 0, mm.start()) + 1
                    rows.append([r["simple"], r["domain"], m["name"], caller_tx, "%s:%d" % (r["path"], line), "entity-mutation", typ, t["domain"], mm.group(1), ""])
    seen = set()
    uniq = []
    for row in rows:
        key = tuple(row)
        if key not in seen:
            seen.add(key)
            uniq.append(row)
    with open(OUT, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["caller", "caller_domain", "caller_method", "caller_tx", "where", "kind", "target", "target_domain", "target_method", "target_tags"])
        w.writerows(uniq)
    print("cross-domain write sites:", len(uniq))
    print("by kind:", dict(Counter(x[5] for x in uniq)))
    print("caller tx:", dict(Counter(x[3] for x in uniq)))
    grp = defaultdict(list)
    for x in uniq:
        grp[(x[1], x[7])].append(x)
    for k in sorted(grp, key=lambda k: (-len(grp[k]), k)):
        print("\n%s -> %s (%d)" % (k[0], k[1], len(grp[k])))
        for x in sorted(grp[k], key=lambda x: (x[0], x[4])):
            print("   %-34s %-34s tx=%-12s %-18s %s.%s %s  @%s" % (x[0], x[2], x[3], x[5], x[6], x[8], x[9], x[4].split("/")[-1]))

if __name__ == "__main__":
    main()
