"""Render the idiom counts of 60-modern-java-data.json as Markdown tables for the report."""

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
D = json.load(open(os.path.join(HERE, "60-modern-java-data.json"), encoding="utf-8"))
C = D["counts"]
COLS = [("backend", "main"), ("frontend", "main"), ("ingest", "main"), ("keycloak-spi", "main"),
        ("logging-support", "main"), ("test-support", "main")]

ROWS = [
    ("Records declared", None),
    ("switch: arrow form / colon form", None),
    ("instanceof type pattern", "instanceof.pattern"),
    ("instanceof + cast (old)", "instanceof.plain+cast"),
    ("instanceof without binding (legit)", "instanceof.plain"),
    ("if/else instanceof chain (>=2 arms)", "chain.instanceof_ifelse"),
    ("if/else enum == chain (>=2 arms)", "chain.enum_eq_ifelse"),
    ("unnamed `_` used", "misc.underscore_ident"),
    ("lambda params unused", "lambda.unused_param"),
    ("catch param unused (non-empty body)", "catch.unused"),
    ("catch block empty", "catch.empty"),
    ("text blocks", "text.text_block"),
    ("multi-line literal concatenation (>=3 literals)", "text.concat_multiline_literal_run"),
    ("@Query with `+` concatenation", "text.query_concat"),
    ("@Query as text block", "text.query_textblock"),
    ("String.format(", "text.string_format"),
    (".formatted(", "text.formatted"),
    (".trim()", "text.trim"),
    (".strip*()", "text.strip"),
    (".isBlank()", "text.isBlank"),
    ("var declarations", "var.decl"),
    ("explicitly typed local decl. (approx.)", "var.explicit_local_decl"),
    (".get(0)", "seq.get0"),
    ("getFirst/getLast/removeFirst/...", "seq.modern_first_last"),
    (".iterator().next()", "seq.iterator_next"),
    ("Stream.toList()", "stream.toList"),
    ("Collectors.toList()", "stream.collectors_toList"),
    ("Collectors.toSet()", "stream.collectors_toSet"),
    ("List.of(", "coll.list_of"),
    ("Arrays.asList(", "coll.arrays_asList"),
    ("Collections.empty*(", "coll.collections_empty"),
    ("Collections.unmodifiable*( (views)", "coll.unmodifiable_view"),
    ("Optional isPresent() then get()", "opt.isPresent_then_get"),
    ("Optional.orElseThrow()", "opt.orElseThrow_noarg"),
    ("Optional parameter", "opt.param"),
    ("mapMulti", "stream.mapMulti"),
    ("flatMap(... Stream.of/empty ...)", "stream.flatMap_stream_of"),
    ("Math.clamp", "math.clamp"),
    ("Math.max(Math.min(..)) clamp candidates", "math.clamp_candidate"),
    ("HexFormat", "hex.hexformat"),
    ("Integer/Long.toHexString", "hex.toHexString"),
    ("new ThreadLocal / withInitial", "conc.threadlocal_new"),
    ("synchronized block", "conc.synchronized_block"),
    ("synchronized method", "conc.synchronized_method"),
    ("Thread.sleep", "conc.thread_sleep"),
    ("Executors.*(", "conc.executors"),
    ("serialVersionUID", "legacy.serialVersionUID"),
    ("@Serial", "legacy.serial_annotation"),
    ("ReflectionTestUtils.setField", "legacy.reflection_setField"),
    ("hand-written equals(Object)", "eq.equals_override"),
    ("Javadoc blocks /** */", "doc.javadoc_blocks"),
    ("Markdown doc lines ///", "doc.markdown_doc_lines"),
    ("JetBrains-annotated files", "null.jetbrains(files)"),
    ("sealed type declarations", "sealed.sealed"),
    ("explicit super(args) as first ctor stmt", "jep513.explicit_ctor_call_super_args"),
]

def val(key, m, s):
    return C.get(key, {}).get(f"{m}/{s}", 0)

def tests(key, m):
    return val(key, m, "test") + val(key, m, "e2e")

recs = {}
for r in D["records"]:
    recs[r["group"]] = recs.get(r["group"], 0) + 1
forms = {}
for sw in D["switches"]:
    for fm in sw["forms"]:
        forms[(sw["group"], fm)] = forms.get((sw["group"], fm), 0) + 1

hdr = "| Idiom | " + " | ".join(f"{m} main" for m, _s in COLS) + " | all tests+e2e |"
print(hdr)
print("|" + "---|" * (len(COLS) + 2))
for label, key in ROWS:
    if label == "Records declared":
        cells = [str(recs.get(f"{m}/{s}", 0)) for m, s in COLS]
        t = sum(v for g, v in recs.items() if not g.endswith("/main"))
    elif label.startswith("switch:"):
        cells = [f"{forms.get((f'{m}/{s}', 'arrow'), 0)} / {forms.get((f'{m}/{s}', 'colon'), 0)}" for m, s in COLS]
        t = f"{sum(v for (g, f), v in forms.items() if f == 'arrow' and not g.endswith('/main'))} / {sum(v for (g, f), v in forms.items() if f == 'colon' and not g.endswith('/main'))}"
    else:
        cells = [str(val(key, m, s)) for m, s in COLS]
        t = sum(tests(key, m) for m, _s in COLS)
    print(f"| {label} | " + " | ".join(cells) + f" | {t} |")
print()
print("files per group:", D["files_per_group"])
