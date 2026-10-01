"""List the September 2026 audit findings that touch the REST API contract."""
import json
import os
import re
import sys

OUT = r"$SCRATCHPAD"
KEYS = re.compile(r"openapi|OpenAPI|contract|Kontrakt|REQ-API|DTO|Dto|endpoint|Endpoint|problem|Problem|@Valid|@PreAuthorize|ApiDeprecation|Pageable|PageResponse|/api/v1|springdoc|allow-list|Allowlist|allowlist|vhost|Tag\b|slim", re.I)

def main():
    with open(os.path.join(OUT, "sept_audit_findings.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    items = data if isinstance(data, list) else data.get("findings", data)
    hits = []
    for f in items:
        blob = " ".join(str(f.get(k, "")) for k in ("id", "area", "title", "where", "recommendation", "note", "dims"))
        if KEYS.search(blob) and (re.search(r"API|CI|BE-|FE-|SEC", str(f.get("id", ""))) or KEYS.search(str(f.get("title", "")))):
            hits.append(f)
    print("total findings:", len(items), "API-related candidates:", len(hits))
    for f in hits:
        print("-", f.get("id"), "|", f.get("prio"), "|", f.get("area"), "|", (f.get("title") or "")[:150])
        print("    where:", (str(f.get("where")) or "")[:160])
        print("    note:", (str(f.get("note")) or "")[:200])

if __name__ == "__main__":
    sys.exit(main())
