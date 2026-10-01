"""Measure gradle/verification-metadata.xml: components, artifacts, checksums, trust rules, groups."""
import collections
import os
import xml.etree.ElementTree as ET

REPO = r"$REPO"
path = os.path.join(REPO, "gradle", "verification-metadata.xml")
ns = {"v": "https://schema.gradle.org/dependency-verification"}
tree = ET.parse(path)
root = tree.getroot()
tag = root.tag
prefix = tag[: tag.index("}") + 1] if tag.startswith("{") else ""

cfg = root.find(prefix + "configuration")
print("file bytes:", os.path.getsize(path))
with open(path, encoding="utf-8") as fh:
    print("file lines:", sum(1 for _ in fh))
if cfg is not None:
    for child in cfg:
        name = child.tag.replace(prefix, "")
        if name == "trusted-artifacts":
            print("trusted-artifacts rules:", [dict(t.attrib) for t in child])
        elif name == "trusted-keys":
            print("trusted-keys:", len(list(child)))
        else:
            print(f"config {name}: {child.text}")
components = root.find(prefix + "components")
comps = list(components) if components is not None else []
artifacts = 0
sha256 = 0
also = 0
groups = collections.Counter()
exts = collections.Counter()
for c in comps:
    groups[c.attrib.get("group", "?")] += 1
    for a in c.findall(prefix + "artifact"):
        artifacts += 1
        nm = a.attrib.get("name", "")
        exts[nm.rsplit(".", 1)[-1] if "." in nm else "?"] += 1
        for s in a.findall(prefix + "sha256"):
            sha256 += 1
            also += len(s.findall(prefix + "also-trust"))
print("components:", len(comps))
print("artifacts:", artifacts)
print("sha256 entries:", sha256, "also-trust:", also)
print("artifact extensions:", dict(exts.most_common()))
print("distinct groups:", len(groups))
print("top groups:", groups.most_common(15))
kc = sum(v for g, v in groups.items() if g.startswith("org.keycloak"))
print("org.keycloak* components:", kc)
