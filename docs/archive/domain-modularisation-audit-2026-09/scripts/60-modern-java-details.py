"""Print the concrete evidence lists behind the counts in 60-modern-java-data.json."""

import collections
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
D = json.load(open(os.path.join(HERE, "60-modern-java-data.json"), encoding="utf-8"))
SECTION = sys.argv[1] if len(sys.argv) > 1 else "all"

def sec(name):
    return SECTION in ("all", name)

def short(rel):
    return rel.split("/java/")[-1].replace("de/greluc/krt/profit/basetool/", "")

def ex(key, only_main=False, limit=60, text=True):
    for e in D["examples"].get(key, [])[:limit]:
        if only_main and "/src/main/" not in e["loc"]:
            continue
        print("  ", short(e["loc"]), "|", e["text"] if text else "")

ENUM_SIZE = {}
for e in D["enums"]:
    ENUM_SIZE.setdefault(e["name"], []).append((e["rel"], e["n"]))

if sec("enumcov"):
    print("== enum switches: coverage of constants (main+test) ==")
    for sw in D["switches"]:
        et = sw.get("enum_type") or sw.get("enum_type_guess")
        if not et and sw["enum_candidates"]:
            et = "|".join(sw["enum_candidates"])
        if not et:
            continue
        simple = et.split("|")[0].split(".")[-1]
        sizes = ENUM_SIZE.get(simple)
        handled = [x for x, k in sw["labels"] if k == "const"]
        size = None
        if sizes:
            if len(sizes) == 1:
                size = sizes[0][1]
            else:
                segs = et.split("|")[0].split(".")
                hint = segs[-2] if len(segs) >= 2 else ""
                cand = [n for rel, n in sizes if hint and hint in rel]
                size = cand[0] if cand else sizes[0][1]
        full = size is not None and len(set(handled)) >= size
        tag = "FULL" if full else f"{len(set(handled))}/{size}"
        d = (sw["default"]["kind"] + ": " + sw["default"]["text"][:60]) if sw["has_default"] else "-"
        print(f"{'EXPR' if sw['expr'] else 'STMT'} {tag:7s} {simple:34s} default={d:70s} {short(sw['rel'])}:{sw['line']}")

if sec("sealed"):
    print("\n== sealed declarations ==")
    for c in D["sealed_candidates"]:
        if c["sealed"]:
            print("  SEALED", c["name"], c["kind"], short(c["rel"]), c["line"], c["subs"], "same_pkg=", c["same_package"])
    print("\n== sealed candidates (not sealed, >=2 subtypes in same module) ==")
    for c in D["sealed_candidates"]:
        if c["sealed"]:
            continue
        print(f"  {c['n_subs']:3d} {c['kind']:9s} {c['name']:40s} {c['group']:16s} same_pkg={str(c['same_package']):5s} sub_pkgs={c['sub_packages']} test_subs={c['test_subs']} anon={c['anonymous_impls']} kinds={c['sub_kinds']} {short(c['rel'])}:{c['line']}")

if sec("exceptions"):
    print("\n== exception classes by base ==")
    by = collections.defaultdict(list)
    for e in D["exceptions"]:
        by[(e["group"], e["extends"])].append((e["name"], e["pkg"].split(".")[-1]))
    for k, v in sorted(by.items(), key=lambda kv: -len(kv[1])):
        print(" ", k, len(v), sorted(v)[:30])

if sec("strategy"):
    print("\n== enums with constant-specific bodies ==")
    for e in D["strategy_enums"]:
        print("  ", e["name"], short(e["rel"]), e["line"], f"{e['consts_with_body']}/{e['consts']}")

if sec("records"):
    print("\n== records ==")
    R = D["records"]
    by = collections.Counter(r["group"] for r in R)
    print("  per group:", dict(by))
    for g in sorted(by):
        rs = [r for r in R if r["group"] == g]
        print(f"  {g}: n={len(rs)} compact_ctor={sum(r['compact_ctor'] for r in rs)} cc_validates={sum(r['cc_validates'] for r in rs)} explicit_ctors={sum(1 for r in rs if r['explicit_ctors'])} toString={sum(r['toString'] for r in rs)} equals={sum(r['equals'] for r in rs)} with_coll_comp={sum(1 for r in rs if r['coll_components'])} coll_comps={sum(len(r['coll_components']) for r in rs)} coll_copied={sum(len(r['coll_copied']) for r in rs)} builder={sum(1 for r in rs if 'Builder' in r['annots'])} nested={sum(r['nested'] for r in rs)} implements={sum(1 for r in rs if r['implements'])}")
    print("  records overriding toString:")
    for r in R:
        if r["toString"]:
            print("    ", short(r["rel"]), r["line"], r["name"])
    print("  records overriding equals:")
    for r in R:
        if r["equals"]:
            print("    ", short(r["rel"]), r["line"], r["name"])
    print("  records whose compact ctor copies a collection:")
    for r in R:
        if r["coll_copied"]:
            print("    ", short(r["rel"]), r["line"], r["name"], r["coll_copied"], "of", r["coll_components"])
    impl = collections.Counter(i for r in R for i in r["implements"])
    print("  implemented interfaces:", impl.most_common(20))

if sec("carriers"):
    print("\n== record candidates: non-JPA, non-bean final carriers ==")
    C = D["carriers"]
    print("  per group:", dict(collections.Counter(c["group"] for c in C)))
    for c in sorted(C, key=lambda c: (c["group"], c["rel"])):
        print(f"  {c['group']:18s} {c['name']:44s} fields={c['fields']:2d} annots={c['annots']} impl={c['implements']} nested={c['nested']} methods={c['methods'][:8]} {short(c['rel'])}:{c['line']}")

if sec("sensitive"):
    print("\n== sensitive-looking components in records / Lombok toString classes ==")
    for s in sorted(D["sensitive"], key=lambda s: (s["group"], s["rel"])):
        fl = [tuple(f[:2]) + ((("EXCLUDED" if f[3] else "PRINTED"),) if len(f) > 3 else ()) for f in s["fields"]]
        print(f"  {s['group']:18s} {s['kind']:6s} {s['name']:44s} toString_overridden={s['toString_overridden']!s:5s} {fl} {short(s['rel'])}:{s['line']}")

if sec("lambda"):
    print("\n== unused lambda parameters (main) ==")
    ex("lambda.unused_param", only_main=True, limit=400)

if sec("catch"):
    print("\n== catch parameters unused in a non-empty body (main) ==")
    ex("catch.unused", only_main=True, limit=400)
    print("\n== empty catch blocks ==")
    ex("catch.empty", limit=60)

if sec("foreach"):
    print("\n== unused enhanced-for variables ==")
    ex("foreach.unused_var", limit=60)
    print("\n== unused try-with-resources variables ==")
    ex("twr.unused_resource", limit=60)

if sec("jep513"):
    print("\n== JEP 513: super/this args computed by a call ==")
    ex("jep513.args_computed_by_call", limit=60)
    print("\n== JEP 513: validation right after super/this ==")
    ex("jep513.validation_after_super", limit=60)

if sec("conc"):
    for k in ["conc.threadlocal_new", "conc.thread_sleep", "conc.executors", "conc.virtual_api", "conc.new_thread", "conc.synchronized_block", "conc.synchronized_method", "conc.explicit_lock", "conc.async_annotation", "conc.collections_synchronized", "conc.volatile"]:
        print(f"\n== {k} ==")
        ex(k, limit=40)

if sec("reflect"):
    for k in ["legacy.reflection_setField", "legacy.setAccessible", "legacy.getDeclaredField", "legacy.field_set", "legacy.reflection_getField"]:
        print(f"\n== {k} ==")
        ex(k, limit=60)

if sec("misc"):
    for k in ["math.clamp_candidate", "math.clamp", "opt.isPresent_then_get", "opt.orElse_eager_call", "opt.param", "opt.field", "opt.ofNullable_orElse",
              "instanceof.plain+cast", "chain.instanceof_ifelse", "chain.enum_eq_ifelse", "chain.string_equals_ifelse", "coll.unmodifiable_view", "coll.arrays_asList",
              "seq.iterator_next", "seq.get_size_minus_1", "seq.map_view_iterator_next", "seq.collections_reverse", "eq.equals_override", "eq.hashCode_override",
              "legacy.serialVersionUID", "legacy.import_Serializable(files)", "hex.toHexString", "hex.format_02x", "hex.biginteger_radix16", "hex.hexformat",
              "text.trim_isEmpty", "stream.collectors_toList", "stream.flatMap_stream_of", "stream.collectors_toSet", "legacy.getBytes_nocharset", "legacy.new_string_bytes_nocharset",
              "legacy.enumeration", "misc.iterator_remove", "text.string_format", "legacy.boxing_ctor", "legacy.new_url", "legacy.new_locale", "legacy.calendar", "legacy.simpledateformat",
              "legacy.timezone", "legacy.new_date", "legacy.math_random", "misc.secure_random", "misc.random_generator", "seq.stream_findFirst"]:
        print(f"\n== {k} ==")
        ex(k, only_main=False, limit=60)

if sec("text"):
    for k in ["text.query_concat", "text.concat_multiline_literal_run"]:
        print(f"\n== {k} (main) ==")
        ex(k, only_main=True, limit=80)
    print("\n== text.StringUtils_blank (main) ==")
    ex("text.StringUtils_blank", only_main=True, limit=10)

if sec("top"):
    for k in sorted(D["top"]):
        print(k, D["top"][k][:6])
