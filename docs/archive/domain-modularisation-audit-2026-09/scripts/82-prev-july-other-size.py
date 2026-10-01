"""Count main-source Java files over a LOC threshold per module (physical lines, like the July audit's 'over 600 LOC')."""
import sys
from pathlib import Path

ROOT = Path(r"$REPO")
THRESHOLD = int(sys.argv[1]) if len(sys.argv) > 1 else 600
rows = []
for mod in ("backend", "frontend", "ingest", "keycloak-spi", "logging-support"):
    base = ROOT / mod / "src" / "main" / "java"
    if not base.exists():
        continue
    for p in base.rglob("*.java"):
        loc = sum(1 for _ in p.open(encoding="utf-8", errors="replace"))
        if loc > THRESHOLD:
            rows.append((loc, mod, p.relative_to(ROOT)))
rows.sort(reverse=True)
for loc, mod, rel in rows:
    print(f"{loc}\t{mod}\t{rel}")
by_mod = {}
for loc, mod, _ in rows:
    by_mod[mod] = by_mod.get(mod, 0) + 1
print(f"TOTAL over {THRESHOLD}: {len(rows)} {by_mod}")
