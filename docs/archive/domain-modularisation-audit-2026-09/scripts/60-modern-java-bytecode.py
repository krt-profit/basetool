"""Map compiled switch constructs of every module's main classes back to source lines.

Reads */build/classes/java/main with javap -c -p -l and records, per source file and line:
  switchmap  - a classic enum switch (javac's $SwitchMap$ ordinal table), with the enum type
  enumswitch - an invokedynamic SwitchBootstraps.enumSwitch, with the selector enum type
  typeswitch - an invokedynamic SwitchBootstraps.typeSwitch (pattern switch)
  matchexc   - a compiler-inserted `new MatchException` (exhaustive switch without default)
Writes 60-modern-java-bytecode.json next to this script.
"""

import json
import os
import re
import subprocess

ROOT = r"$REPO"
JAVAP = r"C:\Program Files\Zulu\zulu-25\bin\javap.exe"
HERE = os.path.dirname(os.path.abspath(__file__))
MODULES = ["backend", "frontend", "ingest", "keycloak-spi", "logging-support", "test-support"]

NEEDLES = (b"$SwitchMap$", b"SwitchBootstraps", b"java/lang/MatchException")

def enum_name(mangled):
    parts = mangled.split("$")
    pkg = []
    cls = []
    for p in parts:
        if not cls and p and p[0].islower():
            pkg.append(p)
        else:
            cls.append(p)
    return ".".join(pkg) + ("." if pkg else "") + ".".join(cls)

def run():
    results = []
    stats = {}
    for m in MODULES:
        base = os.path.join(ROOT, m, "build", "classes", "java", "main")
        if not os.path.isdir(base):
            continue
        wanted = []
        total = 0
        for dp, _d, fs in os.walk(base):
            for f in fs:
                if not f.endswith(".class"):
                    continue
                total += 1
                p = os.path.join(dp, f)
                with open(p, "rb") as fh:
                    data = fh.read()
                if any(n in data for n in NEEDLES):
                    rel = os.path.relpath(p, base)[:-6].replace("\\", ".").replace("/", ".")
                    wanted.append(rel)
        stats[m] = {"classes": total, "with_switch_constructs": len(wanted)}
        for k in range(0, len(wanted), 60):
            batch = wanted[k:k + 60]
            out = subprocess.run(
                [JAVAP, "-c", "-p", "-l", "-cp", base] + batch,
                capture_output=True, text=True, encoding="utf-8", errors="replace",
            ).stdout
            results.extend(parse(out, m))
    return results, stats

CLASS_HDR = re.compile(r"^(?:[\w\s]*?)(?:class|interface|enum|record)\s+([\w.$]+)")
INSN = re.compile(r"^\s+(\d+): (\w+)\s*(.*)$")
LNT = re.compile(r"^\s+line (\d+): (\d+)$")

def parse(out, module):
    res = []
    source = None
    cls = None
    method = None
    events = []
    table = []
    in_lnt = False

    def flush():
        if not events:
            return
        tbl = sorted(table, key=lambda t: t[1])
        synthetic_holder = bool(method and method.startswith("static {}") and re.search(r"\$\d+$", cls or ""))
        for off, kind, detail in events:
            if synthetic_holder and kind == "switchmap":
                continue
            line = None
            for ln, pc in tbl:
                if pc <= off:
                    line = ln
                else:
                    break
            pkg_path = cls.rsplit(".", 1)[0].replace(".", "/") if "." in cls else ""
            res.append({
                "module": module,
                "source": f"{module}/src/main/java/{pkg_path}/{source}",
                "class": cls,
                "method": method,
                "line": line,
                "kind": kind,
                "detail": detail,
            })

    for raw in out.splitlines():
        if raw.startswith("Compiled from "):
            flush()
            events, table, in_lnt = [], [], False
            source = raw.split('"')[1]
            cls = None
            continue
        if cls is None and source is not None:
            m = CLASS_HDR.match(raw)
            if m:
                cls = m.group(1)
            continue
        if raw.startswith("  ") and not raw.startswith("   ") and raw.rstrip().endswith(";"):
            flush()
            events, table, in_lnt = [], [], False
            method = raw.strip()
            continue
        if raw.strip() == "LineNumberTable:":
            in_lnt = True
            continue
        if in_lnt:
            m = LNT.match(raw)
            if m:
                table.append((int(m.group(1)), int(m.group(2))))
                continue
            in_lnt = False
        m = INSN.match(raw)
        if m:
            off = int(m.group(1))
            op = m.group(2)
            rest = m.group(3)
            if op == "getstatic" and "$SwitchMap$" in rest:
                mangled = rest.split("$SwitchMap$", 1)[1].split(":", 1)[0]
                events.append((off, "switchmap", enum_name(mangled)))
            elif op == "invokedynamic" and ("typeSwitch" in rest or "enumSwitch" in rest):
                kind = "enumswitch" if "enumSwitch" in rest else "typeswitch"
                desc = rest.split(":(", 1)[1] if ":(" in rest else ""
                sel = desc.split(";", 1)[0].lstrip("L").replace("/", ".") if desc else ""
                events.append((off, kind, sel))
            elif op == "new" and "java/lang/MatchException" in rest:
                events.append((off, "matchexc", ""))
    flush()
    return res

if __name__ == "__main__":
    results, stats = run()
    with open(os.path.join(HERE, "60-modern-java-bytecode.json"), "w", encoding="utf-8") as fh:
        json.dump({"stats": stats, "events": results}, fh, indent=1)
    from collections import Counter
    print("stats", json.dumps(stats))
    print("events by kind", Counter(e["kind"] for e in results))
    print("events by module/kind", Counter((e["module"], e["kind"]) for e in results))
    enums = Counter(e["detail"] for e in results if e["kind"] in ("switchmap", "enumswitch"))
    for k, v in enums.most_common(80):
        print(f"{v:4d} {k}")
