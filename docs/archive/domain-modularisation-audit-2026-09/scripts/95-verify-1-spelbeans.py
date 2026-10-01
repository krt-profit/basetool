import os
import re
from collections import Counter, defaultdict

ROOT = "$REPO/backend/src/main/java"

ANNOT = re.compile(r"@(PreAuthorize|PostAuthorize|PreFilter|PostFilter)\s*\(")
BEAN_REF = re.compile(r"@([a-zA-Z_][a-zA-Z0-9_]*)\s*\.")
STRING_LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')
CONST_DEF = re.compile(r'static\s+final\s+String\s+([A-Z_][A-Z0-9_]*)\s*=\s*((?:[^;])+);', re.S)

def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()

def annotation_bodies(text):
    for m in ANNOT.finditer(text):
        i = m.end()
        depth = 1
        in_str = False
        esc = False
        while i < len(text) and depth:
            ch = text[i]
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
            else:
                if ch == '"':
                    in_str = True
                elif ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
            i += 1
        yield m.group(1), text[m.end():i - 1], text.count("\n", 0, m.start()) + 1

files = []
for d, _, fs in os.walk(ROOT):
    for f in fs:
        if f.endswith(".java"):
            files.append(os.path.join(d, f))

constants = {}
for p in files:
    t = read(p)
    for m in CONST_DEF.finditer(t):
        constants[m.group(1)] = " ".join(STRING_LIT.findall(m.group(2)))

refs = Counter()
ref_sites = defaultdict(list)
annotation_count = 0
for p in files:
    t = read(p)
    for kind, body, line in annotation_bodies(t):
        annotation_count += 1
        literal = " ".join(STRING_LIT.findall(body))
        for ident in re.findall(r"\b(?:[A-Z][A-Za-z0-9_]*\.)?([A-Z][A-Z0-9_]{2,})\b", body):
            if ident in constants:
                literal += " " + constants[ident]
        for b in BEAN_REF.findall(literal):
            refs[b] += 1
            ref_sites[b].append(os.path.relpath(p, ROOT).replace(os.sep, "/") + ":" + str(line))

print("method-security annotations scanned:", annotation_count)
print("distinct SpEL bean references:", len(refs))

decl_named = re.compile(r'@(Service|Component|Repository|Controller|RestController|Configuration)\s*\(\s*(?:value\s*=\s*)?"([^"]+)"')
bean_method_named = re.compile(r'@Bean\s*\(\s*(?:name|value)?\s*=?\s*\{?\s*"([^"]+)"')

explicit = {}
for p in files:
    t = read(p)
    for m in decl_named.finditer(t):
        explicit[m.group(2)] = os.path.relpath(p, ROOT).replace(os.sep, "/") + " (@" + m.group(1) + ")"
    for m in bean_method_named.finditer(t):
        explicit[m.group(1)] = os.path.relpath(p, ROOT).replace(os.sep, "/") + " (@Bean name)"

simple_names = {}
for p in files:
    base = os.path.basename(p)[:-5]
    simple_names[base[0].lower() + base[1:]] = os.path.relpath(p, ROOT).replace(os.sep, "/")

for b, n in refs.most_common():
    if b in explicit:
        how = "EXPLICIT " + explicit[b]
    elif b in simple_names:
        how = "DEFAULT (class " + simple_names[b] + ")"
    else:
        how = "UNRESOLVED BY SCAN"
    print(f"{b:34s} refs={n:4d}  {how}")
