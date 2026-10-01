"""Count modern-Java idioms versus their older forms across every module and source set.

Every regex runs over lexed code in which comments and literal contents are blanked
(60-modern-java-lexer.py), so nothing inside a string or a comment is counted.
Writes 60-modern-java-data.json and prints a summary.
"""

import collections
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("mjlex", os.path.join(HERE, "60-modern-java-lexer.py"))
L = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(L)

FILES = []
for m, s, p, rel in L.iter_java():
    src = L.read(p)
    code, strings, comments = L.lex(src)
    assert len(code) == len(src), rel
    assert code.count("\n") == src.count("\n"), rel
    imports = set(re.findall(r"(?m)^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;", code))
    pkg = re.search(r"(?m)^\s*package\s+([\w.]+)\s*;", code)
    FILES.append({
        "module": m, "sset": s, "rel": rel, "src": src, "code": code, "strings": strings,
        "comments": comments, "li": L.LineIndex(code), "imports": imports,
        "pkg": pkg.group(1) if pkg else "",
    })

GROUPS = [(m, s) for m in L.MODULES for s in L.SETS if any(f["module"] == m and f["sset"] == s for f in FILES)]

def grp(f):
    return f"{f['module']}/{f['sset']}"

def loc(f, off):
    return f"{f['rel']}:{f['li'].line(off)}"

def snippet(code, a, b, limit=140):
    t = re.sub(r"\s+", " ", code[a:b]).strip()
    return t[:limit]

TYPE_RE = re.compile(
    r"(?<![\w$.@])(?:(class|interface|enum)\s+([A-Z_$][\w$]*)|record\s+([A-Z_$][\w$]*)\s*(?=[(<]))")

TYPES = []

def parse_members(code, bstart, bend, is_enum):
    i = bstart + 1
    consts_text = None
    if is_enum:
        depth = 0
        j = i
        while j < bend:
            ch = code[j]
            if ch in "([{":
                depth += 1
            elif ch in ")]}":
                depth -= 1
            elif ch == ";" and depth == 0:
                break
            j += 1
        consts_text = (i, j)
        i = j + 1
    members = []
    cur = i
    j = i
    while j < bend:
        ch = code[j]
        if ch == ";":
            members.append(("decl", cur, j, None))
            j += 1
            cur = j
            continue
        if ch in "([":
            j = L.match_close(code, j) + 1
            continue
        if ch == "{":
            close = L.match_close(code, j)
            pre = L.strip_annotations(code[cur:j])
            if re.search(r"(?<![=!<>+\-*/&|^%])=(?!=)", pre):
                j = close + 1
                continue
            members.append(("block", cur, j, close))
            j = close + 1
            cur = j
            continue
        j += 1
    return members, consts_text

FIELD_ANNOT_RE = re.compile(r"@\s*([\w$.]+)")

def analyse_type(f, mt):
    code = f["code"]
    if mt.group(1):
        kind, name = mt.group(1), mt.group(2)
    else:
        kind, name = "record", mt.group(3)
    start = mt.start()
    hdr_start = mt.end()
    comps = None
    if kind == "record":
        j = hdr_start
        j = L.skip_ws(code, j)
        if code[j] == "<":
            depth = 0
            while j < len(code):
                if code[j] == "<":
                    depth += 1
                elif code[j] == ">":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            j = L.skip_ws(code, j + 1)
        if code[j] != "(":
            return None
        pc = L.match_close(code, j)
        comps_text = code[j + 1:pc]
        comps = []
        for part in L.split_top(comps_text):
            raw = part
            part = L.strip_annotations(part).strip()
            if not part:
                continue
            nm = re.search(r"([\w$]+)\s*$", part)
            typ = part[: nm.start()].strip() if nm else part
            annots = FIELD_ANNOT_RE.findall(raw)
            comps.append({"name": nm.group(1) if nm else part, "type": typ, "annots": annots})
        hdr_start = pc + 1
    b = code.find("{", hdr_start)
    if b < 0:
        return None
    header = code[hdr_start:b]
    if ";" in header:
        return None
    be = L.match_close(code, b)
    ext = re.search(r"\bextends\s+([\w$.]+(?:\s*<[^{]*?>)?(?:\s*,\s*[\w$.]+(?:\s*<[^{]*?>)?)*)", header)
    imp = re.search(r"\bimplements\s+(.+?)(?:\bpermits\b|$)", header, re.S)
    per = re.search(r"\bpermits\s+(.+)$", header, re.S)
    mstart, mods, annots = L.scan_modifiers_back(code, start)

    def names(txt):
        if not txt:
            return []
        out = []
        for p in L.split_top(txt):
            p = re.sub(r"<.*", "", p.strip(), flags=re.S).strip()
            if p:
                out.append(p.split(".")[-1])
        return out

    t = {
        "file": f, "kind": kind, "name": name, "start": start, "body": (b, be), "mods": mods,
        "annots": [a[0] for a in annots], "annot_args": annots,
        "extends": names(ext.group(1)) if ext else [], "implements": names(imp.group(1)) if imp else [],
        "permits": names(per.group(1)) if per else [], "components": comps,
        "line": f["li"].line(start),
    }
    members, consts = parse_members(code, b, be, kind == "enum")
    t["fields"] = []
    t["methods"] = []
    t["ctors"] = []
    t["compact_ctor"] = None
    for mk, a, c, close in members:
        text = code[a:c]
        plain = L.strip_annotations(text).strip()
        fannots = FIELD_ANNOT_RE.findall(text)
        if mk == "block":
            if re.search(r"(?<![\w$.@])(class|interface|enum|@interface)\s+[A-Z_$]", plain) or re.search(r"(?<![\w$.@])record\s+[A-Z_$][\w$]*\s*[(<]", plain):
                continue
            body = (c, close)
            words = plain.split()
            if "(" in plain:
                head = plain[: plain.index("(")].strip()
                mname = head.split()[-1] if head.split() else ""
                if mname == name:
                    t["ctors"].append({"start": a, "body": body, "text": plain})
                else:
                    t["methods"].append({"name": mname, "head": head, "start": a, "body": body, "params": plain[plain.index("("):]})
            elif words and words[-1] == name:
                t["compact_ctor"] = {"start": a, "body": body}
            continue
        if not plain:
            continue
        if "(" in plain.split("=")[0]:
            head = plain[: plain.index("(")].strip()
            mname = head.split()[-1] if head.split() else ""
            t["methods"].append({"name": mname, "head": head, "start": a, "body": None, "params": plain[plain.index("("):]})
            continue
        lhs = plain.split("=")[0].strip()
        toks = lhs.split()
        fmods = set()
        while toks and toks[0] in L.MODIFIERS:
            fmods.add(toks.pop(0))
        if len(toks) < 2:
            continue
        fname = re.sub(r"\[\]", "", toks[-1])
        ftype = " ".join(toks[:-1])
        t["fields"].append({"name": fname, "type": ftype, "mods": fmods, "annots": fannots,
                            "init": "=" in plain, "start": a})
    if kind == "enum" and consts:
        ctext = code[consts[0]:consts[1]]
        cl = []
        for part in L.split_top(L.strip_annotations(ctext)):
            p = part.strip()
            mm = re.match(r"([A-Za-z_$][\w$]*)", p)
            if mm:
                cl.append({"name": mm.group(1), "body": "{" in p})
        t["constants"] = cl
    return t

for f in FILES:
    for mt in TYPE_RE.finditer(f["code"]):
        t = analyse_type(f, mt)
        if t:
            TYPES.append(t)

for f in FILES:
    f["types"] = [t for t in TYPES if t["file"] is f]

def enclosing_type(f, off):
    best = None
    for t in f["types"]:
        b, e = t["body"]
        if b <= off <= e and (best is None or b > best["body"][0]):
            best = t
    return best

ENUMS = collections.defaultdict(list)
for t in TYPES:
    if t["kind"] == "enum":
        ENUMS[t["name"]].append(t)

CONST_TO_ENUMS = collections.defaultdict(set)
for nm, ts in ENUMS.items():
    for t in ts:
        for c in t.get("constants", []):
            CONST_TO_ENUMS[c["name"]].add(nm)

SECURITY_ENUM_RE = re.compile(
    r"(Role|Permission|Capability|Authority|Scope|Access|Grant|Approv|Approver|Status|State|"
    r"Audit|Kind|Visibility|Level|Gate|Decision|Outcome|Verdict|Mode|Action)", re.I)

COUNTS = collections.defaultdict(lambda: collections.Counter())
TOP = collections.defaultdict(lambda: collections.Counter())
EXAMPLES = collections.defaultdict(list)

def hit(key, f, off=None, n=1, example=None):
    COUNTS[key][grp(f)] += n
    TOP[key][f["rel"]] += n
    if off is not None and len(EXAMPLES[key]) < 400:
        EXAMPLES[key].append({"loc": loc(f, off), "text": example or snippet(f["code"], off, off + 120)})

SIMPLE = {
    "text.string_format": r"\bString\.format\s*\(",
    "text.formatted": r"\.formatted\s*\(",
    "text.message_format": r"\bMessageFormat\.format\s*\(",
    "text.repeat": r"\.repeat\s*\(",
    "text.strip": r"\.strip(?:Leading|Trailing|Indent)?\s*\(\s*\)",
    "text.trim": r"\.trim\s*\(\s*\)",
    "text.isBlank": r"\.isBlank\s*\(\s*\)",
    "text.trim_isEmpty": r"\.trim\s*\(\s*\)\s*\.(?:isEmpty\s*\(\s*\)|length\s*\(\s*\)\s*==\s*0)",
    "text.StringUtils_blank": r"\bStringUtils\.(?:isBlank|isNotBlank|isEmpty|isNotEmpty|hasText|hasLength)\s*\(",
    "text.lines": r"\.lines\s*\(\s*\)",
    "text.string_join": r"\bString\.join\s*\(",
    "var.decl": r"(?<![\w$.])var\s+[A-Za-z_$][\w$]*\s*(?:=|:)",
    "seq.get0": r"\.get\s*\(\s*0\s*\)",
    "seq.get_size_minus_1": r"\.get\s*\(\s*[\w$.()]+\.size\s*\(\s*\)\s*-\s*1\s*\)",
    "seq.iterator_next": r"\.iterator\s*\(\s*\)\s*\.next\s*\(\s*\)",
    "seq.collections_reverse": r"\bCollections\.reverse\s*\(",
    "seq.modern_first_last": r"\.(?:getFirst|getLast|removeFirst|removeLast|addFirst|addLast)\s*\(",
    "seq.modern_map": r"\.(?:firstEntry|lastEntry|pollFirstEntry|pollLastEntry|sequencedKeySet|sequencedValues|sequencedEntrySet|putFirst|putLast)\s*\(",
    "seq.map_view_iterator_next": r"\.(?:entrySet|keySet|values)\s*\(\s*\)\s*\.iterator\s*\(\s*\)\s*\.next\s*\(",
    "seq.stream_findFirst": r"\.stream\s*\(\s*\)\s*\.findFirst\s*\(\s*\)",
    "stream.toList": r"(?<!Collectors)\.toList\s*\(\s*\)",
    "stream.collectors_toList": r"\bCollectors\.toList\s*\(\s*\)",
    "stream.collectors_toUnmodifiableList": r"\bCollectors\.toUnmodifiableList\s*\(",
    "stream.collectors_toSet": r"\bCollectors\.toSet\s*\(\s*\)",
    "stream.collectors_toUnmodifiableSet": r"\bCollectors\.toUnmodifiableSet\s*\(",
    "stream.collectors_toMap": r"\bCollectors\.toMap\s*\(",
    "stream.collectors_toUnmodifiableMap": r"\bCollectors\.toUnmodifiableMap\s*\(",
    "stream.mapMulti": r"\.mapMulti\s*\(",
    "stream.gatherers": r"\bGatherers?\b|\.gather\s*\(",
    "stream.teeing": r"\bCollectors\.teeing\s*\(",
    "stream.parallel": r"\.parallelStream\s*\(|\.parallel\s*\(\s*\)",
    "stream.optional_stream": r"Optional::stream|\.stream\s*\(\s*\)\s*\)",
    "coll.list_of": r"\bList\.of\s*\(",
    "coll.set_of": r"\bSet\.of\s*\(",
    "coll.map_of": r"\bMap\.(?:of|ofEntries|entry)\s*\(",
    "coll.copyOf": r"\b(?:List|Set|Map)\.copyOf\s*\(",
    "coll.arrays_asList": r"\bArrays\.asList\s*\(",
    "coll.unmodifiable_view": r"\bCollections\.unmodifiable\w*\s*\(",
    "coll.collections_empty": r"\bCollections\.empty\w*\s*\(",
    "coll.collections_singleton": r"\bCollections\.singleton\w*\s*\(",
    "coll.guava_immutable": r"\bImmutable(?:List|Set|Map|SortedMap|SortedSet)\.",
    "coll.enumset": r"\bEnumSet\.\w+\s*\(",
    "coll.enummap": r"\bnew\s+EnumMap\s*<",
    "opt.isPresent": r"\.isPresent\s*\(\s*\)",
    "opt.not_isPresent": r"!\s*[\w$.()]+\.isPresent\s*\(\s*\)",
    "opt.get_after_find": r"\.(?:findFirst|findAny|max|min|reduce|findById|findOne)\s*\([^;{}]*?\)\s*\.get\s*\(\s*\)",
    "opt.orElseThrow_noarg": r"\.orElseThrow\s*\(\s*\)",
    "opt.orElseThrow_supplier": r"\.orElseThrow\s*\(\s*[^)\s]",
    "opt.orElse_null": r"\.orElse\s*\(\s*null\s*\)",
    "opt.orElseGet": r"\.orElseGet\s*\(",
    "opt.requireNonNullElse": r"\bObjects\.requireNonNullElse(?:Get)?\s*\(",
    "opt.ifPresentOrElse": r"\.ifPresentOrElse\s*\(",
    "opt.or": r"\.or\s*\(\s*\(\s*\)\s*->",
    "math.clamp": r"\bMath\.clamp\s*\(",
    "hex.hexformat": r"\bHexFormat\b",
    "hex.toHexString": r"\b(?:Integer|Long)\.toHexString\s*\(",
    "hex.forDigit": r"\bCharacter\.forDigit\s*\(",
    "hex.lib_hex": r"\bHex\.encode\w*\s*\(|\bencodeHex\w*\s*\(|\bDatatypeConverter\.\w+\s*\(|\bHexUtils\.",
    "hex.biginteger_radix16": r"\.toString\s*\(\s*16\s*\)",
    "conc.threadlocal_new": r"\bnew\s+(?:Inheritable|Named|NamedInheritable)?ThreadLocal\s*<|\bThreadLocal\.withInitial\s*\(",
    "conc.executors": r"\bExecutors\.\w+\s*\(",
    "conc.virtual_api": r"\bThread\.ofVirtual\s*\(|\bThread\.startVirtualThread\s*\(|newVirtualThreadPerTaskExecutor",
    "conc.new_thread": r"\bnew\s+Thread\s*\(",
    "conc.async_annotation": r"@Async\b",
    "conc.scheduled_annotation": r"@Scheduled\b",
    "conc.synchronized_block": r"\bsynchronized\s*\(",
    "conc.synchronized_method": r"\bsynchronized\b(?!\s*\()",
    "conc.explicit_lock": r"\bnew\s+ReentrantLock\s*\(|\bReentrantReadWriteLock\b|\bStampedLock\b",
    "conc.thread_sleep": r"\bThread\.sleep\s*\(|\bTimeUnit\.\w+\.sleep\s*\(",
    "conc.volatile": r"\bvolatile\b",
    "conc.atomic": r"\bAtomic(?:Integer|Long|Boolean|Reference)\b",
    "conc.collections_synchronized": r"\bCollections\.synchronized\w*\s*\(",
    "conc.completable_future": r"\bCompletableFuture\b",
    "conc.executor_twr": r"try\s*\(\s*(?:var|ExecutorService)\s+\w+\s*=\s*Executors\.",
    "conc.awaitility": r"\bawait\s*\(\s*\)\s*\.|\bAwaitility\.",
    "legacy.new_date": r"\bnew\s+Date\s*\(",
    "legacy.calendar": r"\b(?:Gregorian)?Calendar\b",
    "legacy.simpledateformat": r"\bSimpleDateFormat\b|\bDateFormat\b",
    "legacy.timezone": r"\bTimeZone\b",
    "legacy.vector": r"\bVector\s*<|\bnew\s+Vector\b",
    "legacy.hashtable": r"\bHashtable\b",
    "legacy.stack": r"\bStack\s*<",
    "legacy.stringbuffer": r"\bStringBuffer\b",
    "legacy.enumeration": r"\bEnumeration\s*<",
    "legacy.serialVersionUID": r"\bserialVersionUID\b",
    "legacy.serial_annotation": r"@Serial\b",
    "legacy.finalize": r"\bvoid\s+finalize\s*\(\s*\)",
    "legacy.setAccessible": r"\.setAccessible\s*\(\s*true\s*\)",
    "legacy.reflection_setField": r"\bReflectionTestUtils\.setField\s*\(",
    "legacy.reflection_getField": r"\bReflectionTestUtils\.getField\s*\(",
    "legacy.getDeclaredField": r"\.getDeclaredField\s*\(",
    "legacy.field_set": r"\b\w*[Ff]ield\w*\.set(?:Int|Long|Boolean)?\s*\(",
    "legacy.new_url": r"\bnew\s+URL\s*\(",
    "legacy.new_locale": r"\bnew\s+Locale\s*\(",
    "legacy.boxing_ctor": r"\bnew\s+(?:Integer|Long|Double|Float|Short|Byte|Boolean|Character)\s*\(",
    "legacy.math_random": r"\bMath\.random\s*\(|\bnew\s+Random\s*\(",
    "legacy.urlcodec_string_charset": r"\bURL(?:En|De)coder\.(?:encode|decode)\s*\([^;]*?,\s*\"",
    "legacy.getBytes_nocharset": r"\.getBytes\s*\(\s*\)",
    "legacy.new_string_bytes_nocharset": r"\bnew\s+String\s*\(\s*[\w$.()]+\s*\)",
    "legacy.thread_getId": r"\bThread\.currentThread\s*\(\s*\)\s*\.getId\s*\(",
    "legacy.unsafe": r"\bsun\.misc\.Unsafe\b|\bUnsafe\b\.",
    "legacy.clone_override": r"\bObject\s+clone\s*\(\s*\)|\bclone\s*\(\s*\)\s*throws",
    "misc.predicate_not": r"\bPredicate\.not\s*\(",
    "misc.files_readString": r"\bFiles\.(?:readString|writeString)\s*\(",
    "misc.transferTo": r"\.transferTo\s*\(|\.readAllBytes\s*\(",
    "misc.objects_checkIndex": r"\bObjects\.check(?:Index|FromToIndex|FromIndexSize)\s*\(",
    "misc.random_generator": r"\bRandomGenerator\b|\bThreadLocalRandom\.current\s*\(",
    "misc.secure_random": r"\bnew\s+SecureRandom\s*\(|\bSecureRandom\.getInstance",
    "misc.list_sort_null": r"\bCollections\.sort\s*\(",
    "misc.removeIf": r"\.removeIf\s*\(",
    "misc.iterator_remove": r"\b\w+\.remove\s*\(\s*\)\s*;",
    "misc.instanceof_pattern_record": r"\binstanceof\s+[\w$.]+\s*\(",
    "misc.yield": r"(?<![\w$.])yield\s+[^;=]",
    "misc.underscore_ident": r"(?<![\w$])_(?![\w$])",
    "sealed.sealed": r"(?<![\w$-])sealed\b(?=[\w\s@$.<>,]*\b(?:class|interface)\b)",
    "sealed.non_sealed": r"\bnon-sealed\b",
    "sealed.permits": r"\bpermits\b",
    "switch.case_null": r"\bcase\s+null\b",
    "switch.when_guard": r"\bcase\b[^;{}]*?\bwhen\b",
    "ann.override": r"@Override\b",
    "ann.functional_interface": r"@FunctionalInterface\b",
    "ann.deprecated": r"@Deprecated\b",
    "ann.suppress_warnings": r"@SuppressWarnings\b",
    "ann.suppress_fb": r"@SuppressFBWarnings\b",
}
SIMPLE_RE = {k: re.compile(v) for k, v in SIMPLE.items()}

for f in FILES:
    code = f["code"]
    for k, rx in SIMPLE_RE.items():
        for mm in rx.finditer(code):
            hit(k, f, mm.start())

for f in FILES:
    if "java.util.stream.Collectors.toList" in f["imports"] or "java.util.stream.Collectors.*" in f["imports"]:
        for mm in re.finditer(r"(?<![\w$.])toList\s*\(\s*\)", f["code"]):
            hit("stream.collectors_toList_static", f, mm.start())

IMPORT_KEYS = {
    "legacy.import_java_util_Date": r"^java\.util\.Date$",
    "legacy.import_java_sql_time": r"^java\.sql\.(?:Timestamp|Date|Time)$",
    "legacy.import_Serializable": r"^java\.io\.Serializable$",
    "null.jetbrains": r"^org\.jetbrains\.annotations\.",
    "null.spring_lang": r"^org\.springframework\.lang\.(?:Nullable|NonNull|NonNullApi|NonNullFields)$",
    "null.jspecify": r"^org\.jspecify\.",
    "null.jakarta_validation_NotNull": r"^jakarta\.validation\.constraints\.NotNull$",
    "null.lombok_NonNull": r"^lombok\.NonNull$",
    "null.jakarta_annotation": r"^jakarta\.annotation\.(?:Nullable|Nonnull)$",
    "null.javax_annotation": r"^javax\.annotation\.",
    "null.findbugs": r"^edu\.umd\.cs\.findbugs\.annotations\.",
}
for f in FILES:
    for k, rx in IMPORT_KEYS.items():
        if any(re.search(rx, i) for i in f["imports"]):
            COUNTS[k + "(files)"][grp(f)] += 1
            TOP[k + "(files)"][f["rel"]] += 1

for f in FILES:
    code = f["code"]
    if any(i.startswith("org.jetbrains.annotations.") for i in f["imports"]):
        for a in ("NotNull", "Nullable", "Contract", "Unmodifiable", "UnmodifiableView", "UnknownNullability", "VisibleForTesting"):
            n = len(re.findall(r"@%s\b" % a, code))
            if n:
                COUNTS["null.jb_use." + a][grp(f)] += n

DIRECTIVE_RE = re.compile(r"^//\s*(?:CHECKSTYLE\.|NOSONAR|noinspection|@formatter|language=|spotless:|eslint|nolint)")
for f in FILES:
    for idx, (line, kind, text, off) in enumerate(f["comments"]):
        if kind == "block" and idx == 0 and "SPDX-License-Identifier" in text:
            COUNTS["doc.license_header"][grp(f)] += 1
            continue
        if kind == "javadoc":
            COUNTS["doc.javadoc_blocks"][grp(f)] += 1
        elif kind == "mddoc":
            COUNTS["doc.markdown_doc_lines"][grp(f)] += 1
            hit("doc.markdown_doc_lines_ex", f, off, n=0)
        elif kind == "line":
            if DIRECTIVE_RE.match(text):
                hit("doc.line_directive", f, off, example=text[:100])
            else:
                hit("doc.line_comment", f, off, example=text[:100])
        else:
            hit("doc.block_comment", f, off, example=text[:100].replace("\n", " "))

CONCAT_RUN = re.compile(r'"\s*"(?:\s*\+\s*"\s*")+', re.S)
for f in FILES:
    code = f["code"]
    for (line, kind, text, off) in f["strings"]:
        if kind == "textblock":
            hit("text.text_block", f, off, example=text[:80].replace("\n", "\\n"))
    for mm in re.finditer(r'"[^"\n]*"(?:\s*\+\s*"[^"\n]*")+', code):
        seg = mm.group(0)
        nlit = seg.count('"') // 2
        nlines = seg.count("\n") + 1
        if nlines >= 2 and nlit >= 3:
            hit("text.concat_multiline_literal_run", f, mm.start(), example=f"{nlit} literals over {nlines} lines")
    for mm in re.finditer(r"@(?:Query|NativeQuery)\s*\(", code):
        pc = L.match_close(code, code.index("(", mm.start()))
        args = code[mm.end():pc]
        if '"""' in args:
            hit("text.query_textblock", f, mm.start())
        elif re.search(r'"\s*\+\s*"', args):
            hit("text.query_concat", f, mm.start())
        else:
            hit("text.query_single_literal", f, mm.start())
    for (line, kind, text, off) in f["strings"]:
        if kind == "string" and text.count('\\"') >= 4 and ("{" in text or "[" in text):
            hit("text.escaped_json_literal", f, off, example=text[:80])

LOCAL_DECL = re.compile(
    r"(?m)^[ \t]+(?:final\s+)?(?:[A-Z][\w$]*(?:\.[A-Z][\w$]*)*(?:\s*<[^;=(){}]*>)?(?:\[\])*|int|long|double|boolean|char|byte|short|float)\s+[a-z][\w$]*\s*=(?!=)")
for f in FILES:
    for mm in LOCAL_DECL.finditer(f["code"]):
        e = enclosing_type(f, mm.start())
        if e is None:
            continue
        depth_ok = True
        hit("var.explicit_local_decl", f, mm.start(), n=1)
        if re.search(r"=\s*new\s+", f["code"][mm.end() - 1: mm.end() + 60].split(";")[0] if mm.end() else ""):
            hit("var.explicit_local_decl_new", f, mm.start())

SWITCHES = []
SWITCH_ARROWS = collections.defaultdict(set)
EXPR_PREV = {"return", "yield", "=", "(", ",", "->", "?", "+", "&&", "||", "!", "[", "==", "!=", "+=", "-=", "throw"}

def classify_label(lbl):
    s = lbl.strip()
    if s == "null":
        return "null"
    if s == "default":
        return "default"
    if re.fullmatch(r'"\s*"', s):
        return "string"
    if re.fullmatch(r"'\s*'", s):
        return "char"
    if re.fullmatch(r"-?\s*(?:0x[\da-fA-F_]+|\d[\d_]*)[lL]?", s):
        return "int"
    if re.search(r"\bwhen\b", s):
        return "pattern"
    if re.fullmatch(r"[\w$.]+(?:<[^>]*>)?\s*\(.*\)(?:\s+[\w$]+)?", s, re.S):
        return "recordpattern"
    if re.fullmatch(r"(?:final\s+)?[\w$.]+(?:<[^>]*>)?(?:\[\])*\s+[\w$]+", s):
        return "pattern"
    if re.fullmatch(r"[A-Z][A-Z0-9_$]*", s):
        return "const"
    if re.fullmatch(r"[\w$]+(?:\.[\w$]+)+", s):
        return "qualified"
    if re.fullmatch(r"[a-z_$][\w$]*", s):
        return "ident"
    return "other"

def find_label_delim(code, j, end):
    depth = 0
    k = j
    while k < end:
        ch = code[k]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0:
            if code.startswith("->", k):
                return k, "arrow"
            if ch == ":" and not code.startswith("::", k) and code[k - 1] != ":":
                return k, "colon"
        k += 1
    return end, "none"

def statement_end(code, k, end):
    depth = 0
    while k < end:
        ch = code[k]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth < 0:
                return k
        elif ch == ";" and depth == 0:
            return k
        k += 1
    return end

def default_kind(body):
    b = body.strip()
    for pat, name in [
        (r"^\{?\s*(?:return\s+)?false\b", "false"), (r"^\{?\s*(?:return\s+)?true\b", "true"),
        (r"^\{?\s*(?:return\s+)?null\b", "null"), (r"^\{?\s*throw\b", "throw"),
        (r"^\{?\s*break\b", "break"), (r"^\{\s*\}", "empty"), (r"^\{?\s*(?:return\s+)?(?:List|Set|Map|Optional)\.(?:of|empty)\s*\(", "empty-collection"),
        (r"^\{?\s*log\.", "log"), (r"^\{?\s*(?:return\s+)?\"", "string"), (r"^\{?\s*(?:return\s+)?-?\d", "number"),
        (r"^\{?\s*(?:return\s+)?[A-Z][\w$]*\.[A-Z_][A-Z0-9_]*\b", "constant"),
        (r"^\{?\s*continue\b", "continue"), (r"^\{?\s*return\s*;", "return-void"),
    ]:
        if re.search(pat, b):
            return name
    return "other"

for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"(?<![\w$.])switch\s*\(", code):
        p = code.index("(", mm.start())
        pc = L.match_close(code, p)
        b = L.skip_ws(code, pc + 1)
        if b >= len(code) or code[b] != "{":
            continue
        be = L.match_close(code, b)
        prev = L.prev_token(code, mm.start())
        is_expr = prev in EXPR_PREV
        labels = []
        default_info = None
        k = b + 1
        depth = 0
        forms = set()
        while k < be:
            ch = code[k]
            if ch in "([{":
                depth += 1
                k += 1
                continue
            if ch in ")]}":
                depth -= 1
                k += 1
                continue
            if depth == 0 and (code.startswith("case", k) or code.startswith("default", k)) and not L.IDENT_CH.match(code[k - 1]):
                kw = "case" if code.startswith("case", k) else "default"
                after = k + len(kw)
                if after < be and L.IDENT_CH.match(code[after]):
                    k += 1
                    continue
                d, form = find_label_delim(code, after, be)
                if form == "none":
                    k = after
                    continue
                forms.add(form)
                if form == "arrow":
                    SWITCH_ARROWS[f["rel"]].add(d)
                body_start = d + (2 if form == "arrow" else 1)
                if kw == "default":
                    parts = ["default"]
                else:
                    parts = L.split_top(code[after:d])
                kinds = [classify_label(x) for x in parts]
                for x, kd in zip(parts, kinds):
                    labels.append((x.strip(), kd))
                if "default" in kinds:
                    bs = L.skip_ws(code, body_start)
                    if form == "arrow" and code[bs] == "{":
                        dbody = code[bs:L.match_close(code, bs) + 1]
                    else:
                        dbody = code[bs:statement_end(code, bs, be) + 1]
                    default_info = {"kind": default_kind(dbody), "text": snippet(dbody, 0, len(dbody), 100)}
                if form == "arrow":
                    bs = L.skip_ws(code, body_start)
                    if code[bs] == "{":
                        k = L.match_close(code, bs) + 1
                    else:
                        k = statement_end(code, bs, be) + 1
                else:
                    k = body_start
                continue
            k += 1
        label_kinds = collections.Counter(kd for _x, kd in labels)
        consts = [x for x, kd in labels if kd == "const"]
        cands = None
        if consts and set(label_kinds) <= {"const", "default", "null"}:
            sets = [CONST_TO_ENUMS.get(c, set()) for c in consts]
            cands = set.intersection(*sets) if sets else set()
        sw = {
            "file": f, "rel": f["rel"], "group": grp(f), "line": f["li"].line(mm.start()),
            "selector": snippet(code, p + 1, pc, 80), "expr": is_expr, "prev": prev,
            "forms": sorted(forms), "labels": labels, "label_kinds": dict(label_kinds),
            "has_default": default_info is not None, "default": default_info,
            "enum_candidates": sorted(cands) if cands is not None else None,
            "end_line": f["li"].line(pc),
        }
        SWITCHES.append(sw)

BYTECODE = {}
bc_path = os.path.join(HERE, "60-modern-java-bytecode.json")
if os.path.exists(bc_path):
    with open(bc_path, encoding="utf-8") as fh:
        for ev in json.load(fh)["events"]:
            BYTECODE.setdefault(ev["source"], []).append(ev)

for sw in SWITCHES:
    evs = BYTECODE.get(sw["rel"], [])
    sw["bytecode"] = [e for e in evs if e["line"] is not None and sw["line"] <= e["line"] <= sw["end_line"]]
    enum_types = sorted({e["detail"] for e in sw["bytecode"] if e["kind"] in ("switchmap", "enumswitch")})
    sw["enum_type"] = enum_types[0] if len(enum_types) == 1 else (None if not enum_types else "|".join(enum_types))
    if sw["enum_type"] is None and sw["enum_candidates"]:
        if len(sw["enum_candidates"]) == 1:
            sw["enum_type_guess"] = sw["enum_candidates"][0]

INST = re.compile(r"(?<![\w$])instanceof\s+(final\s+)?([\w$.]+)(\s*<[^>]*>)?((?:\s*\[\s*\])*)")
INSTANCEOF = []
for f in FILES:
    code = f["code"]
    for mm in INST.finditer(code):
        after = L.skip_ws(code, mm.end())
        typ = mm.group(2)
        kind = "plain"
        binding = None
        if after < len(code) and code[after] == "(":
            kind = "record-pattern"
        else:
            nm = re.match(r"([A-Za-z_$][\w$]*)", code[after:after + 60])
            if nm and nm.group(1) not in ("instanceof",):
                kind = "pattern"
                binding = nm.group(1)
        pre = code[max(0, mm.start() - 120):mm.start()]
        subj = re.search(r"([\w$]+(?:\s*\.\s*[\w$]+(?:\(\s*\))?)*)\s*$", pre)
        subject = re.sub(r"\s+", "", subj.group(1)) if subj else ""
        cast = False
        if kind == "plain" and subject:
            simple = typ.split(".")[-1]
            window = code[mm.end(): mm.end() + 700]
            if re.search(r"\(\s*(?:[\w$]+\.)*%s(?:\s*<[^>]*>)?\s*\)\s*\(?\s*%s\b" % (re.escape(simple), re.escape(subject.split("(")[0])), window):
                cast = True
        INSTANCEOF.append({"f": f, "off": mm.start(), "kind": kind, "type": typ, "subject": subject, "cast": cast, "binding": binding})
        key = "instanceof." + kind + ("+cast" if cast else "")
        hit(key, f, mm.start())

CHAINS = []
for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"(?<![\w$.])if\s*\(", code):
        if L.prev_token(code, mm.start()) == "else":
            continue
        conds = []
        k = mm.start()
        while True:
            p = code.index("(", k)
            pc = L.match_close(code, p)
            conds.append(code[p + 1:pc])
            s = L.skip_ws(code, pc + 1)
            if s < len(code) and code[s] == "{":
                e = L.match_close(code, s) + 1
            else:
                e = statement_end(code, s, len(code)) + 1
            n = L.skip_ws(code, e)
            if code.startswith("else", n) and not L.IDENT_CH.match(code[n + 4:n + 5] or " "):
                n2 = L.skip_ws(code, n + 4)
                if code.startswith("if", n2) and re.match(r"if\s*\(", code[n2:n2 + 10]):
                    k = n2
                    continue
            break
        if len(conds) < 2:
            continue
        subs = []
        for c in conds:
            ms = re.search(r"([\w$.()]+?)\s+instanceof\s+([\w$.]+)", c)
            subs.append(ms.group(1) if ms else None)
        best = collections.Counter(s for s in subs if s).most_common(1)
        if best and best[0][1] >= 2:
            CHAINS.append({"f": f, "off": mm.start(), "kind": "instanceof", "len": best[0][1], "subject": best[0][0]})
            hit("chain.instanceof_ifelse", f, mm.start(), example=f"{best[0][1]} arms on {best[0][0]}")
        esubs = []
        for c in conds:
            ms = re.search(r"([\w$.()]+?)\s*==\s*([A-Z][\w$]*)\.([A-Z][A-Z0-9_]*)\b", c) or re.search(r"\b([A-Z][\w$]*)\.([A-Z][A-Z0-9_]*)\s*==\s*([\w$.()]+)", c)
            esubs.append(ms.group(1) if ms else None)
        best = collections.Counter(s for s in esubs if s).most_common(1)
        if best and best[0][1] >= 2:
            CHAINS.append({"f": f, "off": mm.start(), "kind": "enum-eq", "len": best[0][1], "subject": best[0][0]})
            hit("chain.enum_eq_ifelse", f, mm.start(), example=f"{best[0][1]} arms on {best[0][0]}")
        ssubs = []
        for c in conds:
            ms = re.search(r'"\s*"\s*\.equals(?:IgnoreCase)?\s*\(\s*([\w$.()]+)\s*\)', c)
            ssubs.append(ms.group(1) if ms else None)
        best = collections.Counter(s for s in ssubs if s).most_common(1)
        if best and best[0][1] >= 3:
            hit("chain.string_equals_ifelse", f, mm.start(), example=f"{best[0][1]} arms on {best[0][0]}")

def expr_end(code, k):
    depth = 0
    n = len(code)
    while k < n:
        ch = code[k]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                return k
            depth -= 1
        elif depth == 0 and ch in ",;":
            return k
        k += 1
    return n

def used(name, text):
    return re.search(r"(?<![\w$.])%s(?![\w$])" % re.escape(name), text) is not None

LAMBDAS = []
for f in FILES:
    code = f["code"]
    arrows = SWITCH_ARROWS.get(f["rel"], set())
    for mm in re.finditer(r"->", code):
        a = mm.start()
        if a in arrows:
            continue
        j = L.skip_ws_back(code, a - 1)
        if j < 0:
            continue
        params = []
        if code[j] == ")":
            o = L.match_open_back(code, j)
            ptxt = code[o + 1:j]
            for pp in L.split_top(ptxt):
                pp = L.strip_annotations(pp).strip()
                if not pp:
                    continue
                nm = re.search(r"([\w$]+)\s*$", pp)
                if nm:
                    params.append(nm.group(1))
            pstart = o
        elif L.IDENT_CH.match(code[j]):
            k = j
            while k >= 0 and L.IDENT_CH.match(code[k]):
                k -= 1
            params = [code[k + 1:j + 1]]
            pstart = k + 1
            if params[0] in ("case", "default"):
                continue
        else:
            continue
        s = L.skip_ws(code, a + 2)
        if s < len(code) and code[s] == "{":
            body = code[s:L.match_close(code, s) + 1]
        else:
            body = code[s:expr_end(code, s)]
        unused = [p for p in params if p != "_" and not used(p, body)]
        LAMBDAS.append({"f": f, "off": pstart, "n": len(params), "unused": unused})
        hit("lambda.total", f, None)
        if params and not unused:
            pass
        if unused:
            hit("lambda.unused_param", f, pstart, n=len(unused), example=f"unused {unused} of {params}")
        if "_" in params:
            hit("lambda.underscore_param", f, pstart)

CATCHES = []
for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"(?<![\w$.])catch\s*\(", code):
        p = code.index("(", mm.start())
        pc = L.match_close(code, p)
        ptxt = L.strip_annotations(code[p + 1:pc]).strip()
        nm = re.search(r"([\w$]+)\s*$", ptxt)
        name = nm.group(1) if nm else "?"
        b = L.skip_ws(code, pc + 1)
        if code[b] != "{":
            continue
        be = L.match_close(code, b)
        body = code[b + 1:be]
        empty = body.strip() == ""
        orig_body = f["src"][b + 1:be]
        has_comment = orig_body.strip() != "" and empty
        u = used(name, body)
        CATCHES.append({"f": f, "off": mm.start(), "name": name, "empty": empty, "used": u, "comment": has_comment})
        cat = "empty" if empty else ("used" if u else "unused")
        hit(f"catch.{cat}", f, mm.start(), example=f"catch ({ptxt}) {'{}' if empty else ''}")
        COUNTS["catch.name." + (name if name in ("ignored", "expected", "e", "ex", "_") else "other") + "." + cat][grp(f)] += 1

FOREACH = []
for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"(?<![\w$.])for\s*\(", code):
        p = code.index("(", mm.start())
        pc = L.match_close(code, p)
        head = code[p + 1:pc]
        parts = L.split_top(head, ":")
        if len(parts) != 2 or ";" in head:
            continue
        decl = L.strip_annotations(parts[0]).strip()
        nm = re.search(r"([\w$]+)\s*$", decl)
        if not nm:
            continue
        name = nm.group(1)
        s = L.skip_ws(code, pc + 1)
        if code[s] == "{":
            body = code[s:L.match_close(code, s) + 1]
        else:
            body = code[s:statement_end(code, s, len(code)) + 1]
        hit("foreach.total", f, None)
        if name != "_" and not used(name, body):
            hit("foreach.unused_var", f, mm.start(), example=snippet(code, mm.start(), pc + 1))

for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"(?<![\w$.])try\s*\(", code):
        p = code.index("(", mm.start())
        pc = L.match_close(code, p)
        res = code[p + 1:pc]
        b = L.skip_ws(code, pc + 1)
        if code[b] != "{":
            continue
        body = code[b:L.match_close(code, b) + 1]
        for r in L.split_top(res, ";"):
            r = L.strip_annotations(r).strip()
            mr = re.match(r"(?:final\s+)?[\w$.<>, ?\[\]]+?\s+([\w$]+)\s*=", r)
            if not mr:
                continue
            hit("twr.resource_decl", f, None)
            name = mr.group(1)
            if name != "_" and not used(name, body):
                hit("twr.unused_resource", f, mm.start(), example=snippet(r, 0, len(r)))

for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"(?<![\w$.])if\s*\(\s*!?\s*([\w$.]+(?:\(\s*\))?)\.isPresent\s*\(\s*\)\s*\)", code):
        var = mm.group(1)
        window = code[mm.end(): mm.end() + 600]
        if re.search(re.escape(var) + r"\s*\.get\s*\(\s*\)", window):
            hit("opt.isPresent_then_get", f, mm.start())
    for mm in re.finditer(r"\bOptional\.ofNullable\s*\(", code):
        pc = L.match_close(code, code.index("(", mm.start()))
        nxt = code[pc + 1: pc + 20]
        if re.match(r"\s*\.orElse(?:Get)?\s*\(", nxt):
            hit("opt.ofNullable_orElse", f, mm.start())
    for mm in re.finditer(r"\.orElse\s*\(", code):
        pc = L.match_close(code, code.index("(", mm.start()))
        arg = code[mm.end():pc].strip()
        if re.match(r"(?:new\s+)?[\w$.]+(?:<[^>]*>)?\s*\(", arg) and not re.match(r"(?:List|Set|Map|Optional|Collections|BigDecimal|String|Integer|Long|UUID|Boolean|Stream)\.(?:of|empty\w*|valueOf|copyOf|ZERO)\b", arg):
            hit("opt.orElse_eager_call", f, mm.start(), example=snippet(code, mm.start(), pc + 1))
    for t in f["types"]:
        for fl in t["fields"]:
            if re.match(r"Optional\s*<", fl["type"]) and "static" not in fl["mods"]:
                hit("opt.field", f, fl["start"], example=f"{t['name']}.{fl['name']}")
        for md in t["methods"]:
            if re.search(r"[(,]\s*(?:final\s+)?Optional\s*<[^()]*?>\s+[\w$]+\s*(?=[,)])", md["params"]):
                hit("opt.param", f, md["start"], example=f"{t['name']}.{md['name']}")
            if re.search(r"(?:^|\s)Optional\s*<", md["head"]) and md["body"]:
                b0, b1 = md["body"]
                if re.search(r"(?<![\w$.])return\s+null\s*;", code[b0:b1]):
                    hit("opt.method_returns_null", f, md["start"], example=f"{t['name']}.{md['name']}")

for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"\.flatMap\s*\(", code):
        pc = L.match_close(code, code.index("(", mm.start()))
        arg = code[mm.end():pc]
        if re.search(r"\bStream\.(?:of|empty|ofNullable)\s*\(", arg):
            hit("stream.flatMap_stream_of", f, mm.start(), example=snippet(code, mm.start(), pc + 1))

for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"\bMath\.(max|min)\s*\(", code):
        outer = mm.group(1)
        p = code.index("(", mm.start())
        pc = L.match_close(code, p)
        args = [a.strip() for a in L.split_top(code[p + 1:pc])]
        inner = "min" if outer == "max" else "max"
        if len(args) == 2 and any(re.match(r"Math\.%s\s*\(" % inner, a) for a in args):
            hit("math.clamp_candidate", f, mm.start(), example=snippet(code, mm.start(), pc + 1))

for f in FILES:
    for (line, kind, text, off) in f["strings"]:
        if re.search(r"%0?2[xX]", text):
            hit("hex.format_02x", f, off, example=text[:40])
        if text.lower() == "0123456789abcdef":
            hit("hex.alphabet_literal", f, off)

for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"\bCollectors\.toList\s*\(\s*\)", code):
        st = code.rfind(";", 0, mm.start())
        st2 = code.rfind("{", 0, mm.start())
        st = max(st, st2)
        stmt = code[st + 1:mm.start()]
        assign = re.search(r"(?:^|\s)([\w$]+)\s*=\s*[^=]", stmt)
        kind = "other"
        if re.match(r"\s*return\b", stmt):
            kind = "returned"
        elif assign:
            var = assign.group(1)
            window = code[mm.end(): mm.end() + 2500]
            if re.search(r"(?<![\w$.])%s\s*\.\s*(?:add|addAll|remove|removeIf|removeAll|retainAll|set|sort|clear|replaceAll|addFirst|addLast)\s*\(" % re.escape(var), window) or re.search(r"Collections\.(?:sort|shuffle|reverse|swap)\s*\(\s*%s\b" % re.escape(var), window):
                kind = "mutated"
            elif re.search(r"(?<![\w$.])return\s+%s\s*;" % re.escape(var), window):
                kind = "returned-var"
            else:
                kind = "local-unmutated"
        hit("stream.collectors_toList_" + kind, f, mm.start())

for t in TYPES:
    f = t["file"]
    code = f["code"]
    for c in t["ctors"]:
        b0, b1 = c["body"]
        body = code[b0 + 1:b1]
        ms = re.match(r"\s*(super|this)\s*\(", body)
        if not ms:
            continue
        p = b0 + 1 + body.index("(", ms.start())
        pc = L.match_close(code, p)
        args = code[p + 1:pc].strip()
        hit("jep513.explicit_ctor_call_" + ms.group(1) + ("_args" if args else "_noargs"), f, c["start"])
        if re.search(r"(?<!new)\s[\w$.]*[a-z][\w$]*\s*\(", " " + args) and not re.fullmatch(r"[\w$.,\s]*", args):
            hit("jep513.args_computed_by_call", f, c["start"], example=f"{t['name']}: {ms.group(1)}({snippet(args, 0, len(args), 90)})")
        rest = code[pc + 1:b1]
        if re.match(r"\s*;\s*(?:if\s*\([^;]*\)\s*\{?\s*throw\b|Objects\.requireNonNull\s*\(|Assert\.)", rest):
            hit("jep513.validation_after_super", f, c["start"], example=f"{t['name']}")

for f in FILES:
    code = f["code"]
    for mm in re.finditer(r"\bboolean\s+equals\s*\(\s*(?:final\s+)?(?:@[\w$.]+\s+)*Object\s+[\w$]+\s*\)", code):
        t = enclosing_type(f, mm.start())
        hit("eq.equals_override", f, mm.start(), example=(t["kind"] + " " + t["name"]) if t else "?")
    for mm in re.finditer(r"\bint\s+hashCode\s*\(\s*\)", code):
        t = enclosing_type(f, mm.start())
        hit("eq.hashCode_override", f, mm.start(), example=(t["kind"] + " " + t["name"]) if t else "?")

JPA = {"Entity", "Embeddable", "MappedSuperclass", "Table"}
BEANS = {"Component", "Service", "Repository", "Controller", "RestController", "Configuration",
         "ControllerAdvice", "RestControllerAdvice", "ConfigurationProperties", "Aspect", "Endpoint",
         "WebEndpoint", "SpringBootApplication", "Named", "ApplicationScoped", "JBossLog", "Slf4j"}

CRED = re.compile(r"(?i)(secret|passw|passphrase|pwd$|token|apikey|api_key|privatekey|private_key|credential|bearer|jwt|cookie|sessionid|session_id|signature|hmac|otp$|clientsecret|authorization|devicecode|usercode|verifier|nonce|salt$|keystore)")
PERSONAL = re.compile(r"(?i)(email|e_mail|mailaddress|discord|username|displayname|firstname|lastname|fullname|realname|nickname|handle$|phone|ipaddress|clientip|remoteaddr|^ip$|birth|iban|address$|avatar)")
KEYISH = re.compile(r"(?i)(key|keys)$")
KEY_BENIGN = re.compile(r"(?i)^(message|label|i18n|title|sort|cache|lock|participant|group|idempotency|name|type|i18nlabel|description|status|text|bucket|lot|row|partition|resource|field|property|attribute|entry|map|route|metric|tag|event|param|header|dedupe|dedup|natural|business|composite|lookup|join|mode|kind|scope|column|translation)Key(s)?$|^key(s)?$")

RECORDS = []
CARRIERS = []
SENSITIVE = []

def classify_name(nm):
    if CRED.search(nm):
        return "credential"
    if PERSONAL.search(nm):
        return "personal"
    if KEYISH.search(nm) and not KEY_BENIGN.search(nm):
        return "key-like"
    return None

for t in TYPES:
    f = t["file"]
    has_tostring = any(md["name"] == "toString" for md in t["methods"])
    if t["kind"] == "record":
        comps = t["components"] or []
        cc = t["compact_ctor"]
        cc_body = f["code"][cc["body"][0]:cc["body"][1]] if cc else ""
        coll = [c for c in comps if re.match(r"(?:java\.util\.)?(?:List|Set|Map|Collection|SortedSet|SortedMap|NavigableMap|Queue|Deque)\s*<", c["type"])]
        copied = [c for c in coll if re.search(r"\b(?:List|Set|Map)\.copyOf\s*\(\s*%s\b|%s\s*=\s*[^;]*copyOf" % (c["name"], c["name"]), cc_body)]
        validated = bool(re.search(r"\bthrow\b|requireNonNull|Assert\.|checkArgument|Objects\.check", cc_body))
        rec = {
            "rel": f["rel"], "group": grp(f), "name": t["name"], "line": t["line"], "n": len(comps),
            "compact_ctor": cc is not None, "cc_validates": validated, "explicit_ctors": len(t["ctors"]),
            "toString": has_tostring, "equals": any(md["name"] == "equals" for md in t["methods"]),
            "implements": t["implements"], "coll_components": [c["name"] for c in coll],
            "coll_copied": [c["name"] for c in copied], "annots": t["annots"],
            "nested": enclosing_type(f, t["start"] - 1) is not None,
        }
        RECORDS.append(rec)
        flagged = []
        for c in comps:
            cls = classify_name(c["name"])
            if cls:
                flagged.append((c["name"], cls, c["type"]))
        if flagged:
            SENSITIVE.append({"kind": "record", "rel": f["rel"], "group": grp(f), "name": t["name"], "line": t["line"],
                              "fields": flagged, "toString_overridden": has_tostring, "annots": t["annots"]})
        continue
    if t["kind"] != "class":
        continue
    annots = set(a.split(".")[-1] for a in t["annots"])
    lombok_value = "Value" in annots and ("lombok.Value" in f["imports"] or "lombok.*" in f["imports"])
    lombok_data = "Data" in annots
    lombok_tostring = "ToString" in annots or lombok_data or lombok_value
    inst_fields = [fl for fl in t["fields"] if "static" not in fl["mods"]]
    if lombok_tostring:
        tostring_args = " ".join(str(a[1]) for a in t["annot_args"] if a[0].split(".")[-1] == "ToString")
        only_explicit = "onlyExplicitlyIncluded" in tostring_args
        flagged = []
        for fl in inst_fields:
            cls = classify_name(fl["name"])
            if cls:
                excluded = any(a.endswith("ToString.Exclude") for a in fl["annots"]) or only_explicit
                flagged.append((fl["name"], cls, fl["type"], excluded))
        if flagged:
            SENSITIVE.append({"kind": "lombok", "rel": f["rel"], "group": grp(f), "name": t["name"], "line": t["line"],
                              "fields": flagged, "toString_overridden": has_tostring, "annots": t["annots"]})
    if annots & JPA or annots & BEANS:
        continue
    if "abstract" in t["mods"] or t["extends"]:
        continue
    if not inst_fields:
        continue
    all_final = all("final" in fl["mods"] for fl in inst_fields) or lombok_value
    lombokish = bool(annots & {"Value", "Data", "Getter", "AllArgsConstructor", "RequiredArgsConstructor", "Builder", "EqualsAndHashCode", "ToString"})
    if all_final and (lombokish or any(md["name"].startswith("get") for md in t["methods"]) or len(t["ctors"]) > 0):
        CARRIERS.append({
            "rel": f["rel"], "group": grp(f), "name": t["name"], "line": t["line"],
            "annots": sorted(annots), "fields": len(inst_fields), "lombok_value": lombok_value,
            "setter": "Setter" in annots, "implements": t["implements"],
            "methods": [md["name"] for md in t["methods"]][:12],
            "nested": enclosing_type(f, t["start"] - 1) is not None,
        })

SUBTYPES = collections.defaultdict(list)
for t in TYPES:
    for sup in t["extends"] + t["implements"]:
        SUBTYPES[sup].append(t)
DECLS = collections.defaultdict(list)
for t in TYPES:
    DECLS[t["name"]].append(t)
SEALED_CANDIDATES = []
for name, decls in DECLS.items():
    for d in decls:
        if d["kind"] not in ("interface", "class"):
            continue
        if d["kind"] == "class" and "abstract" not in d["mods"]:
            continue
        subs = [s for s in SUBTYPES.get(name, []) if s["file"]["module"] == d["file"]["module"]]
        if len(subs) < 2:
            continue
        same_pkg = all(s["file"]["pkg"] == d["file"]["pkg"] for s in subs)
        main_only = d["file"]["sset"] == "main"
        test_subs = [s for s in subs if s["file"]["sset"] != "main"]
        anon_impls = 0
        SEALED_CANDIDATES.append({
            "name": name, "kind": d["kind"], "rel": d["file"]["rel"], "line": d["line"],
            "group": grp(d["file"]), "sealed": "sealed" in d["mods"], "n_subs": len(subs),
            "subs": sorted({s["name"] for s in subs}), "same_package": same_pkg,
            "sub_packages": sorted({s["file"]["pkg"].split(".")[-1] for s in subs}),
            "test_subs": len(test_subs), "sub_kinds": dict(collections.Counter(s["kind"] for s in subs)),
        })

for c in SEALED_CANDIDATES:
    n_anon = 0
    for f in FILES:
        if f["module"] != c["rel"].split("/")[0]:
            continue
        n_anon += len(re.findall(r"\bnew\s+%s\s*(?:<[^>]*>)?\s*\(\s*\)\s*\{" % re.escape(c["name"]), f["code"]))
    c["anonymous_impls"] = n_anon

EXCEPTIONS = [t for t in TYPES if t["kind"] == "class" and t["extends"] and re.search(r"(Exception|Error|Throwable)$", t["extends"][0])]
STRATEGY_ENUMS = [t for t in TYPES if t["kind"] == "enum" and any(c["body"] for c in t.get("constants", []))]

def table(key):
    c = COUNTS.get(key, collections.Counter())
    return {g: c.get(f"{g[0]}/{g[1]}", 0) for g in GROUPS}

def fmt_counts(key):
    c = COUNTS.get(key, collections.Counter())
    tot = sum(c.values())
    parts = [f"{g}={n}" for g, n in sorted(c.items()) if n]
    return f"{key}: total={tot} | " + ", ".join(parts)

out = {
    "groups": [f"{m}/{s}" for m, s in GROUPS],
    "files_per_group": dict(collections.Counter(grp(f) for f in FILES)),
    "counts": {k: dict(v) for k, v in COUNTS.items()},
    "top": {k: v.most_common(12) for k, v in TOP.items()},
    "examples": {k: v[:400] for k, v in EXAMPLES.items()},
    "records": RECORDS,
    "carriers": CARRIERS,
    "sensitive": SENSITIVE,
    "sealed_candidates": sorted(SEALED_CANDIDATES, key=lambda c: -c["n_subs"]),
    "exceptions": [{"rel": t["file"]["rel"], "name": t["name"], "extends": t["extends"][0], "pkg": t["file"]["pkg"], "group": grp(t["file"])} for t in EXCEPTIONS],
    "strategy_enums": [{"rel": t["file"]["rel"], "name": t["name"], "line": t["line"], "consts_with_body": sum(1 for c in t["constants"] if c["body"]), "consts": len(t["constants"])} for t in STRATEGY_ENUMS],
    "enums": [{"name": t["name"], "rel": t["file"]["rel"], "group": grp(t["file"]), "n": len(t.get("constants", []))} for t in TYPES if t["kind"] == "enum"],
    "switches": [{k: v for k, v in sw.items() if k != "file"} for sw in SWITCHES],
    "chains": [{"loc": loc(c["f"], c["off"]), "kind": c["kind"], "len": c["len"], "subject": c["subject"]} for c in CHAINS],
    "catches": [{"loc": loc(c["f"], c["off"]), "name": c["name"], "empty": c["empty"], "used": c["used"], "comment": c["comment"]} for c in CATCHES],
    "type_kinds": {},
}
tk = collections.Counter()
for t in TYPES:
    tk[(grp(t["file"]), t["kind"])] += 1
out["type_kinds"] = {f"{g}|{k}": n for (g, k), n in tk.items()}

with open(os.path.join(HERE, "60-modern-java-data.json"), "w", encoding="utf-8") as fh:
    json.dump(out, fh, indent=1, default=list)

print("files:", len(FILES), dict(collections.Counter(grp(f) for f in FILES)))
print("types:", dict(tk))
for k in sorted(COUNTS):
    print(fmt_counts(k))
