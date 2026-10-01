"""Authorization inventory: every method-security annotation, every SpEL bean reference, bean-name resolution."""
import collections
import importlib.util as C
import json
import os
import re

common = None

def _load():
    global common
    spec = C.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "20-crosscutting-common.py"))
    common = C.module_from_spec(spec)
    spec.loader.exec_module(common)

_load()

SEC_ANN = {
    "org.springframework.security.access.prepost.PreAuthorize": "PreAuthorize",
    "org.springframework.security.access.prepost.PostAuthorize": "PostAuthorize",
    "org.springframework.security.access.prepost.PreFilter": "PreFilter",
    "org.springframework.security.access.prepost.PostFilter": "PostFilter",
    "org.springframework.security.access.annotation.Secured": "Secured",
    "jakarta.annotation.security.RolesAllowed": "RolesAllowed",
}
STEREO = {
    "org.springframework.stereotype.Service",
    "org.springframework.stereotype.Component",
    "org.springframework.stereotype.Repository",
    "org.springframework.stereotype.Controller",
    "org.springframework.web.bind.annotation.RestController",
    "org.springframework.context.annotation.Configuration",
    "org.springframework.web.bind.annotation.ControllerAdvice",
    "org.springframework.web.bind.annotation.RestControllerAdvice",
}
VALUE_RE = re.compile(r'value="((?:[^"\\]|\\.)*)"')
BEAN_REF_RE = re.compile(r"@([A-Za-z_]\w*)\.([A-Za-z_]\w*)\s*\(")

def decap(name):
    if len(name) > 1 and name[0].isupper() and name[1].isupper():
        return name
    return name[0].lower() + name[1:]

def main():
    ann = common.load_annotations()
    out = {}
    for module in ("backend", "frontend", "ingest"):
        classes = ann[module]
        beans = {}
        for fqcn, rec in classes.items():
            for a in rec["class"]:
                if a["type"] in STEREO:
                    m = VALUE_RE.search(a["text"])
                    name = m.group(1) if m and m.group(1) else decap(common.simple_name(fqcn))
                    if "$" in fqcn:
                        name = m.group(1) if m and m.group(1) else decap(fqcn.rsplit(".", 1)[-1].replace("$", "."))
                    beans.setdefault(name, []).append({"class": fqcn, "explicit": bool(m and m.group(1)), "stereotype": a["type"].rsplit(".", 1)[-1]})
            for member, anns in rec["members"].items():
                for a in anns:
                    if a["type"] == "org.springframework.context.annotation.Bean":
                        mname = re.search(r"\s([\w$]+)\(", member)
                        nm = re.search(r'(?:name|value)=\["?([^"\]]+)', a["text"])
                        bn = nm.group(1) if nm else (mname.group(1) if mname else "?")
                        beans.setdefault(bn, []).append({"class": fqcn, "explicit": bool(nm), "stereotype": "@Bean " + member})
        sites = []
        for fqcn, rec in classes.items():
            for a in rec["class"]:
                if a["type"] in SEC_ANN:
                    m = VALUE_RE.search(a["text"])
                    sites.append({"class": fqcn, "member": "<class>", "ann": SEC_ANN[a["type"]], "value": m.group(1) if m else a["text"]})
            for member, anns in rec["members"].items():
                for a in anns:
                    if a["type"] in SEC_ANN:
                        m = VALUE_RE.search(a["text"])
                        sites.append({"class": fqcn, "member": member, "ann": SEC_ANN[a["type"]], "value": m.group(1) if m else a["text"]})
        by_ann = collections.Counter(s["ann"] for s in sites)
        bean_refs = collections.Counter()
        bean_methods = collections.defaultdict(collections.Counter)
        bean_ref_domains = collections.defaultdict(collections.Counter)
        per_domain = collections.Counter()
        per_class = collections.Counter()
        expr = collections.Counter()
        for s in sites:
            per_domain[common.domain_of(s["class"])] += 1
            per_class[s["class"]] += 1
            expr[s["value"]] += 1
            for bm in BEAN_REF_RE.finditer(s["value"]):
                bean_refs[bm.group(1)] += 1
                bean_methods[bm.group(1)][bm.group(2)] += 1
                bean_ref_domains[bm.group(1)][common.domain_of(s["class"])] += 1
        resolved = {}
        for b in bean_refs:
            resolved[b] = beans.get(b, [{"class": "UNRESOLVED"}])
        role_literals = collections.Counter()
        for s in sites:
            for rm in re.finditer(r"has(?:Any)?(?:Role|Authority)\(([^)]*)\)", s["value"]):
                for lit in re.findall(r"'([^']+)'", rm.group(1)):
                    role_literals[lit] += 1
        out[module] = {
            "total_sites": len(sites),
            "by_annotation": dict(by_ann),
            "classes_with_sites": len(per_class),
            "per_domain": dict(per_domain.most_common()),
            "bean_refs": dict(bean_refs.most_common()),
            "bean_methods": {b: dict(c.most_common()) for b, c in bean_methods.items()},
            "bean_ref_domains": {b: dict(c.most_common()) for b, c in bean_ref_domains.items()},
            "resolved_beans": resolved,
            "distinct_expressions": len(expr),
            "top_expressions": expr.most_common(40),
            "role_literals": dict(role_literals.most_common()),
            "explicit_named_beans": {k: v for k, v in beans.items() if any(x["explicit"] for x in v) and not any(x["stereotype"].startswith("@Bean") for x in v)},
            "sites": sites,
        }
    with open(os.path.join(common.SCRATCH, "20-crosscutting-authz.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=1)
    for module, d in out.items():
        print("==", module, "sites", d["total_sites"], d["by_annotation"], "classes", d["classes_with_sites"], "distinct expr", d["distinct_expressions"])
        print("  per_domain", d["per_domain"])
        print("  bean refs:")
        for b, n in d["bean_refs"].items():
            cls = ",".join("%s%s" % (x["class"].rsplit(".", 1)[-1], "(explicit)" if x.get("explicit") else "") for x in d["resolved_beans"][b])
            print("    @%s x%d -> %s | methods=%d | from domains %s" % (b, n, cls, len(d["bean_methods"][b]), d["bean_ref_domains"][b]))
        print("  role literals", d["role_literals"])
        print("  explicitly named stereotype beans:", {k: [x["class"].rsplit(".", 1)[-1] for x in v] for k, v in d["explicit_named_beans"].items()})

if __name__ == "__main__":
    main()
