import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")
root = r"$REPO\frontend\src\main\resources\static\js"
skip = {"krt-fetch.js", "krt-client-error.js"}
call = re.compile(r"(?<![.\w])(?:window\.)?fetch\(")
method = re.compile(r"method\s*:\s*['\"]?([A-Za-z]+)")
total = 0
writes = []
nonliteral = []
for name in sorted(os.listdir(root)):
    if not name.endswith(".js") or name.endswith(".min.js") or name in skip:
        continue
    path = os.path.join(root, name)
    if os.path.isdir(path):
        continue
    with open(path, encoding="utf-8") as f:
        lines = f.read().split("\n")
    for i, line in enumerate(lines):
        if call.search(line):
            total += 1
            window = "\n".join(lines[i:i + 14])
            m = method.search(window)
            if m and m.group(1).upper() != "GET":
                writes.append((name, i + 1, m.group(1)))
            elif not m:
                after = line[call.search(line).end():]
                if re.search(r",\s*[a-zA-Z_][\w.]*\s*\)?\s*$", after) or re.search(r",\s*[a-zA-Z_][\w.]*\(\)\s*\)", after):
                    nonliteral.append((name, i + 1, line.strip()))
print("raw fetch call sites (excluding krt-fetch.js, krt-client-error.js):", total)
print("with a non-GET method within 14 lines:", len(writes))
for w in writes:
    print("  WRITE?", w)
print("with a non-literal init argument on the same line:", len(nonliteral))
for n in nonliteral:
    print("  NONLIT", n)
