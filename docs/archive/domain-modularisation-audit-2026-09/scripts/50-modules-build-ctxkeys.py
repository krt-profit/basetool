"""Static approximation of distinct Spring test-context keys per module.

For each test class annotated @SpringBootTest, builds a key from the class-level test annotations
(normalised text of @SpringBootTest, @AutoConfigureMockMvc, @ActiveProfiles, @Import,
@TestPropertySource, @ContextConfiguration, @AutoConfigure*, @Testcontainers, @DynamicPropertySource
presence) plus the sorted set of @MockitoBean / @MockitoSpyBean field types, and counts distinct keys.
Inheritance is followed one level for a superclass declared in the same module's test tree.
It is a proxy for Spring's MergedContextConfiguration key, not a measurement.
"""
import os
import re
import sys
import collections

REPO = r"$REPO"
MODULES = sys.argv[1:] or ["backend", "frontend", "ingest"]

ANN_RE = re.compile(r"^\s*@(SpringBootTest|AutoConfigureMockMvc|ActiveProfiles|Import|TestPropertySource|"
                    r"ContextConfiguration|AutoConfigure\w+|Testcontainers|EnableConfigurationProperties|"
                    r"MockitoBean|MockitoSpyBean|WithMockUser)\b(\([^)]*\))?", re.M)
CLASS_RE = re.compile(r"^(?:public\s+|abstract\s+|final\s+)*class\s+(\w+)(?:<[^>]*>)?(?:\s+extends\s+(\w+))?", re.M)
FIELD_MOCK_RE = re.compile(r"@(MockitoBean|MockitoSpyBean)(\([^)]*\))?\s+(?:private\s+|protected\s+)?(?:final\s+)?([\w.<>, ?]+?)\s+\w+\s*;", re.S)

def normalise(s):
    return re.sub(r"\s+", " ", s or "").strip()

def parse(path):
    with open(path, encoding="utf-8") as fh:
        src = fh.read()
    m = CLASS_RE.search(src)
    if not m:
        return None
    head = src[: m.start()]
    last_import = max(head.rfind("\nimport "), 0)
    head = head[last_import:]
    head = head[head.find("\n", 1):] if last_import else head
    anns = []
    for am in ANN_RE.finditer(head):
        name, args = am.group(1), normalise(am.group(2))
        anns.append(f"@{name}{args}")
    body = src[m.end():]
    mocks = sorted({normalise(fm.group(1) + ":" + fm.group(3)) for fm in FIELD_MOCK_RE.finditer(body)})
    dyn = "@DynamicPropertySource" in body
    return {
        "name": m.group(1),
        "extends": m.group(2),
        "anns": sorted(anns),
        "mocks": mocks,
        "dyn": dyn,
        "sbt": any(a.startswith("@SpringBootTest") for a in anns),
    }

for module in MODULES:
    root = os.path.join(REPO, module, "src", "test", "java")
    classes = {}
    for dp, _, fn in os.walk(root):
        for f in fn:
            if f.endswith(".java"):
                info = parse(os.path.join(dp, f))
                if info:
                    classes[info["name"]] = info
    keys = collections.Counter()
    n = 0
    for name, info in classes.items():
        anns, mocks, dyn, sbt = list(info["anns"]), list(info["mocks"]), info["dyn"], info["sbt"]
        parent = classes.get(info["extends"]) if info["extends"] else None
        depth = 0
        while parent is not None and depth < 3:
            anns += parent["anns"]
            mocks += parent["mocks"]
            dyn = dyn or parent["dyn"]
            sbt = sbt or parent["sbt"]
            parent = classes.get(parent["extends"]) if parent["extends"] else None
            depth += 1
        if not sbt or "abstract" in name.lower():
            continue
        n += 1
        key = (tuple(sorted(set(a for a in anns if not a.startswith(("@MockitoBean", "@MockitoSpyBean", "@WithMockUser"))))),
               tuple(sorted(set(mocks + [a for a in anns if a.startswith(("@MockitoBean", "@MockitoSpyBean"))]))),
               dyn)
        keys[key] += 1
    singles = sum(1 for k, v in keys.items() if v == 1)
    print(f"{module}: @SpringBootTest classes {n}, distinct approx. context keys {len(keys)}, "
          f"keys used by exactly one class {singles}")
    top = keys.most_common(5)
    for k, v in top:
        print(f"   {v:4d} classes share key: anns={list(k[0])[:4]} mocks={len(k[1])} dyn={k[2]}")
