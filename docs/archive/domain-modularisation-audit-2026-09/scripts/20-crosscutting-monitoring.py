"""Find Java simple class names, logger names and code-derived tags referenced in monitoring/ and other non-Java files."""
import collections
import importlib.util as U
import json
import os
import re

spec = U.spec_from_file_location("common", os.path.join(os.path.dirname(os.path.abspath(__file__)), "20-crosscutting-common.py"))
common = U.module_from_spec(spec)
spec.loader.exec_module(common)

TARGET_DIRS = ["monitoring", "docker", "scripts", ".github", "deploy", "ops", "ansible"]
TEXT_EXT = {".yml", ".yaml", ".json", ".alloy", ".conf", ".sh", ".py", ".ps1", ".toml", ".txt", ".md", ".xml", ".properties", ".env", ".j2", ".tmpl", ".container", ".network", ".service", ".timer", ""}

def class_names():
    names = {}
    for module in ("backend", "frontend", "ingest", "keycloak-spi", "logging-support"):
        for p in common.iter_java(module):
            n = os.path.basename(p)[:-5]
            names.setdefault(n, []).append(module)
    return names

def main():
    names = class_names()
    ambiguous_english = {"Roles", "Permissions", "Mission", "Operation", "User", "Material", "Location", "Ship", "Terminal", "City", "Outpost", "Poi", "Notification", "Announcement", "Season", "Role", "Frequency", "Probe", "Main"}
    word_re = re.compile(r"\b([A-Z][A-Za-z0-9]{3,})\b")
    hits = collections.defaultdict(list)
    for d in TARGET_DIRS:
        base = os.path.join(common.ROOT, d)
        if not os.path.isdir(base):
            continue
        for dp, dns, fs in os.walk(base):
            if "node_modules" in dp or os.sep + "build" + os.sep in dp:
                continue
            for f in fs:
                ext = os.path.splitext(f)[1]
                if ext not in TEXT_EXT:
                    continue
                p = os.path.join(dp, f)
                try:
                    text = common.read(p)
                except OSError:
                    continue
                for i, line in enumerate(text.splitlines(), 1):
                    for m in word_re.finditer(line):
                        w = m.group(1)
                        if w in names and w not in ambiguous_english:
                            hits[w].append("%s:%d" % (common.rel(p), i))
    out = {w: {"modules": names[w], "count": len(v), "sites": v} for w, v in sorted(hits.items(), key=lambda kv: -len(kv[1]))}
    with open(os.path.join(common.SCRATCH, "20-crosscutting-monitoring.json"), "w", encoding="utf-8") as fh:
        json.dump(out, fh, indent=1)
    by_dir = collections.Counter()
    for w, v in out.items():
        for s in v["sites"]:
            by_dir[s.split("/")[0] + "/" + s.split("/")[1]] += 1
    print("distinct class names referenced:", len(out), "by top dir:", dict(by_dir.most_common(20)))
    for w, v in list(out.items()):
        mon = [s for s in v["sites"] if s.startswith("monitoring/")]
        if mon:
            print("%-45s %3d  %s" % (w, len(mon), "; ".join(mon[:4])))

if __name__ == "__main__":
    main()
