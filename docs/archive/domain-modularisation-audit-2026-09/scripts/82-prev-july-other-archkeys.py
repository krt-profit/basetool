"""List every @Test / @ArchTest rule in an ArchitectureTest with the package, name and class-literal keys it uses."""
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
lines = path.read_text(encoding="utf-8").splitlines()
starts = []
for i, line in enumerate(lines):
    m = re.match(r"\s+void\s+(\w+)\(\)\s*\{", line)
    if m:
        starts.append((i, m.group(1)))
starts.append((len(lines), "END"))
pkg_re = re.compile(r'"(\.\.[\w.]*\.\.|[\w.]*\.\.)"')
name_re = re.compile(r'(haveSimpleName(?:StartingWith|EndingWith|Containing)?|haveNameMatching|haveSimpleNameNotEndingWith|getSimpleName\(\)\.(?:startsWith|endsWith|contains|equals)|nameMatching)\(\s*"([^"]+)"')
literal_re = re.compile(r'"([A-Z][A-Za-z0-9]+(?:Service|Controller|Repository|Mapper|Redactor|Helper|Filter|Config))"')
cls_re = re.compile(r'([A-Z][A-Za-z0-9]+)\.class')
for (s, name), (e, _) in zip(starts, starts[1:]):
    body = "\n".join(lines[s:e])
    pkgs = sorted(set(pkg_re.findall(body)))
    names = sorted(set(f"{a}:{b}" for a, b in name_re.findall(body)))
    lits = sorted(set(literal_re.findall(body)))
    classes = sorted(set(cls_re.findall(body)) - {"String", "Test", "Override", "Object"})
    print(f"{s + 1}\t{name}\tpkgs={pkgs}\tnames={names}\tliterals={lits[:12]}{'...' if len(lits) > 12 else ''}\tclasses={classes[:10]}{'...' if len(classes) > 10 else ''}")
