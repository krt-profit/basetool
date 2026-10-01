"""Walk the compile/runtime dependency tree of Maven Central artifacts (non-optional, non-test).

Usage: python 70-research-pomtree.py <group:artifact:version> [...]
Prints each reachable artifact once with the path that reached it. Versions come only from the
declaring POM or its parent's dependencyManagement/properties, which is enough for Spring
Modulith's own POMs; anything unresolved is printed with '?'.
"""
import re
import sys
import urllib.request

CENTRAL = "https://repo1.maven.org/maven2"
cache: dict[str, str] = {}

def pom(g: str, a: str, v: str) -> str:
    key = f"{g}:{a}:{v}"
    if key not in cache:
        url = f"{CENTRAL}/{g.replace('.', '/')}/{a}/{v}/{a}-{v}.pom"
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                cache[key] = r.read().decode("utf-8", errors="replace")
        except Exception:
            cache[key] = ""
    return cache[key]

def deps(text: str) -> list[tuple[str, str, str, str, bool]]:
    body = re.sub(r"<dependencyManagement>.*?</dependencyManagement>", "", text, flags=re.S)
    out = []
    for m in re.finditer(r"<dependency>(.*?)</dependency>", body, flags=re.S):
        d = m.group(1)
        g = re.search(r"<groupId>([^<]+)", d)
        a = re.search(r"<artifactId>([^<]+)", d)
        v = re.search(r"<version>([^<]+)", d)
        s = re.search(r"<scope>([^<]+)", d)
        o = re.search(r"<optional>\s*true", d)
        out.append((g.group(1) if g else "?", a.group(1) if a else "?",
                    v.group(1) if v else "?", s.group(1) if s else "compile", bool(o)))
    return out

def main() -> None:
    seen: set[str] = set()
    stack = [(coord.split(":"), [coord]) for coord in sys.argv[1:]]
    while stack:
        (g, a, v), path = stack.pop()
        key = f"{g}:{a}"
        if key in seen:
            continue
        seen.add(key)
        print(f"{g}:{a}:{v}  <-  {' > '.join(path[:-1]) or '(root)'}")
        if not g.startswith("org.springframework.modulith"):
            continue
        for dg, da, dv, scope, optional in deps(pom(g, a, v)):
            if optional or scope in ("test", "provided", "import", "system"):
                continue
            if dv.startswith("${project.version}") or dv == "?":
                dv = v if dg.startswith("org.springframework.modulith") else dv
            stack.append(((dg, da, dv), path + [f"{dg}:{da}:{dv}"]))

if __name__ == "__main__":
    main()
