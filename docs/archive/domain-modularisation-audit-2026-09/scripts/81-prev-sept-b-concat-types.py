import os
import re
import sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8")
root = r"$REPO\frontend\src\main\java"
call = re.compile(r"\bbackendApiClient\s*\.\s*(get|post|put|delete|patch)\s*\(")
ident = re.compile(r"\+\s*([A-Za-z_][A-Za-z0-9_]*)(?:\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\))?")
encoders = ("encode(", "UriUtils", "URLEncoder", "UriComponentsBuilder", "encodePath")
stats = Counter()
string_sites = []
for dp, dn, fn in os.walk(root):
    for name in fn:
        if not name.endswith(".java"):
            continue
        with open(os.path.join(dp, name), encoding="utf-8") as f:
            src = f.read()
        for m in call.finditer(src):
            depth = 0
            i = m.end()
            arg = []
            while i < len(src):
                c = src[i]
                if c == "(":
                    depth += 1
                elif c == ")":
                    if depth == 0:
                        break
                    depth -= 1
                elif c == "," and depth == 0:
                    break
                arg.append(c)
                i += 1
            first = "".join(arg)
            if "+" not in first:
                continue
            stats["concat_sites"] += 1
            if any(e in first for e in encoders):
                stats["concat_with_encoder"] += 1
                continue
            kinds = set()
            for im in ident.finditer(first):
                var, meth = im.group(1), im.group(2)
                if meth:
                    kinds.add("call")
                    continue
                decl = re.search(r"\b(UUID|String|Long|long|Integer|int|boolean|Boolean|Instant|LocalDate|BigDecimal|[A-Z][A-Za-z0-9]*)\s+" + re.escape(var) + r"\b", src)
                kinds.add(decl.group(1) if decl else "?")
            if "String" in kinds:
                stats["concat_with_String_var"] += 1
                line = src.count("\n", 0, m.start()) + 1
                string_sites.append(f"{name}:{line}")
            elif kinds <= {"UUID", "Long", "long", "Integer", "int", "boolean", "Boolean"}:
                stats["concat_only_typed_ids"] += 1
            else:
                stats["concat_other"] += 1
print(dict(stats))
print("String-typed concatenations (first 40):")
for s in string_sites[:40]:
    print("  ", s)
print("total String sites:", len(string_sites))
