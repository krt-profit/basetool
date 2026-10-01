"""Dump annotations of every compiled main class of backend, frontend and ingest via javap.

Writes 20-crosscutting-annotations.json: {module: {fqcn: {"class": [...], "members": {sig: [...]}}}}
Each annotation entry is {"type": fqcn, "text": decoded javap body}.
"""
import json
import os
import re
import subprocess
import sys

ROOT = r"$REPO"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "20-crosscutting-annotations.json")
MODULES = ["backend", "frontend", "ingest"]
BATCH = 120

def class_files(module):
    base = os.path.join(ROOT, module, "build", "classes", "java", "main")
    for dirpath, _, files in os.walk(base):
        for f in files:
            if f.endswith(".class"):
                yield os.path.join(dirpath, f)

HEADER_RE = re.compile(r"^(?:public |protected |private |abstract |final |static |sealed |non-sealed |strictfp )*(class|interface|enum|record|@interface) ([\w.$]+)")
ANN_TYPE_RE = re.compile(r"^\s+([a-zA-Z_][\w.$]*)(\(|$)")

def parse(text):
    """Parse a javap -v -p multi-class output into per-class annotation records."""
    result = {}
    current = None
    member = None
    in_members = False
    after_members = False
    lines = text.splitlines()
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        if line.startswith("Classfile "):
            current = None
            member = None
            in_members = False
            after_members = False
            i += 1
            continue
        if current is None:
            m = HEADER_RE.match(line)
            if m and not line.startswith(" "):
                current = m.group(2)
                result[current] = {"class": [], "members": {}, "header": line.strip()}
            i += 1
            continue
        if line == "{":
            in_members = True
            i += 1
            continue
        if line == "}" and in_members:
            in_members = False
            after_members = True
            member = None
            i += 1
            continue
        if in_members and line.startswith("  ") and not line.startswith("   ") and line.rstrip().endswith(";"):
            member = line.strip()
            result[current]["members"].setdefault(member, [])
            i += 1
            continue
        stripped = line.strip()
        if stripped in ("RuntimeVisibleAnnotations:", "RuntimeInvisibleAnnotations:",
                        "RuntimeVisibleParameterAnnotations:"):
            kind = stripped[:-1]
            indent = len(line) - len(line.lstrip())
            i += 1
            while i < n:
                l2 = lines[i]
                ind2 = len(l2) - len(l2.lstrip())
                if not l2.strip() or ind2 <= indent:
                    break
                m2 = re.match(r"^\s+\d+: #\d+", l2)
                if m2 or re.match(r"^\s+parameter \d+:", l2):
                    i += 1
                    continue
                tm = ANN_TYPE_RE.match(l2)
                if tm:
                    atype = tm.group(1)
                    body = [l2.strip()]
                    if tm.group(2) == "(":
                        depth = l2.count("(") - l2.count(")")
                        i += 1
                        while i < n and depth > 0:
                            body.append(lines[i].strip())
                            depth += lines[i].count("(") - lines[i].count(")")
                            i += 1
                    else:
                        i += 1
                    entry = {"type": atype, "text": " ".join(body), "kind": kind}
                    if after_members or member is None:
                        result[current]["class"].append(entry)
                    else:
                        result[current]["members"][member].append(entry)
                    continue
                i += 1
            continue
        i += 1
    return result

def main():
    data = {}
    for module in MODULES:
        files = sorted(class_files(module))
        mod = {}
        for start in range(0, len(files), BATCH):
            chunk = files[start:start + BATCH]
            proc = subprocess.run(["javap", "-v", "-p"] + chunk, capture_output=True, text=True,
                                  encoding="utf-8", errors="replace")
            if proc.returncode != 0:
                print("javap error", module, start, proc.stderr[:500], file=sys.stderr)
            mod.update(parse(proc.stdout))
        data[module] = mod
        print(module, len(files), "files", len(mod), "classes parsed")
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=1)
    print("written", OUT)

if __name__ == "__main__":
    main()
