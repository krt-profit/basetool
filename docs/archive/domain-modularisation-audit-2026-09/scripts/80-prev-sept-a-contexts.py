"""Static approximation of distinct Spring test contexts per module.

For every top-level test class annotated with @SpringBootTest (directly), build a key from the
context-affecting declarations found in the file: the @SpringBootTest attribute text, @ActiveProfiles,
@TestPropertySource, @Import, @AutoConfigureMockMvc, @DirtiesContext, @ContextConfiguration,
@EnableConfigurationProperties, and the sorted set of @MockitoBean / @MockitoSpyBean / @MockBean field
types (plus types=... attributes). Superclasses are resolved by simple name inside the same module when
the class extends another test class that is itself annotated. Nested classes inherit the key.
Approximate by design: it does not evaluate meta-annotations or context customizers.

Usage: python 80-prev-sept-a-contexts.py <test-src-root> [...]
"""
import os
import re
import sys
from collections import Counter

ANN = re.compile(
    r"@(SpringBootTest|ActiveProfiles|TestPropertySource|Import|AutoConfigureMockMvc|DirtiesContext|"
    r"ContextConfiguration|EnableConfigurationProperties|AutoConfigureTestDatabase|Testcontainers)"
    r"(\((?:[^()]|\([^()]*\))*\))?"
)
MOCK_FIELD = re.compile(
    r"@(MockitoBean|MockitoSpyBean|MockBean|SpyBean)(\((?:[^()]|\([^()]*\))*\))?\s+"
    r"(?:(?:private|protected|public|final|static)\s+)*([\w.<>, ?]+?)\s+\w+\s*;"
)
MOCK_TYPES_ATTR = re.compile(r"@(MockitoBean|MockitoSpyBean)\(\s*types\s*=\s*([^)]*)\)")
CLASS_DECL = re.compile(r"^(?:public\s+|abstract\s+|final\s+)*class\s+(\w+)(?:<[^>]*>)?(?:\s+extends\s+(\w+))?", re.M)

def norm(s):
    return re.sub(r"\s+", " ", s or "").strip()

def scan(root):
    files = {}
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if fn.endswith(".java"):
                p = os.path.join(dp, fn)
                with open(p, encoding="utf-8", errors="replace") as f:
                    files[fn[:-5]] = f.read()
    info = {}
    for name, src in files.items():
        m = CLASS_DECL.search(src)
        if not m:
            continue
        head = src[: m.start()]
        anns = sorted(norm(a[0] + a[1]) for a in ANN.findall(head))
        mocks = sorted(norm(t[2]) for t in MOCK_FIELD.findall(src))
        mocks += sorted(norm(t[1]) for t in MOCK_TYPES_ATTR.findall(src))
        info[name] = {
            "sbt": any(a.startswith("SpringBootTest") for a in anns),
            "anns": anns,
            "mocks": sorted(mocks),
            "extends": m.group(2),
        }

    def key(name, depth=0):
        d = info.get(name)
        if d is None or depth > 5:
            return ((), ())
        parent = key(d["extends"], depth + 1) if d["extends"] in info else ((), ())
        anns = tuple(sorted(set(parent[0]) | set(d["anns"])))
        mocks = tuple(sorted(list(parent[1]) + d["mocks"]))
        return (anns, mocks)

    keys = Counter()
    sbt_classes = 0
    for name, d in info.items():
        chain_has_sbt = d["sbt"]
        p = d["extends"]
        hops = 0
        while not chain_has_sbt and p in info and hops < 5:
            chain_has_sbt = info[p]["sbt"]
            p = info[p]["extends"]
            hops += 1
        if not chain_has_sbt:
            continue
        if "abstract class" in files[name][: 4000] and name.startswith("Abstract"):
            continue
        sbt_classes += 1
        keys[key(name)] += 1
    return sbt_classes, keys

if __name__ == "__main__":
    for r in sys.argv[1:]:
        n, keys = scan(r)
        singles = sum(1 for k, v in keys.items() if v == 1)
        with_mocks = sum(1 for k in keys if k[1])
        print(f"{r}: {n} @SpringBootTest classes -> {len(keys)} distinct approximate context keys; "
              f"{singles} keys used by exactly one class; {with_mocks} keys include mock beans")
        for k, v in keys.most_common(6):
            print(f"   {v:4d} x anns={list(k[0])[:4]} mocks={len(k[1])}")
