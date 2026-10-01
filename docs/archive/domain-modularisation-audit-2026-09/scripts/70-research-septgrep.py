"""List September-audit findings whose text mentions a researched technology.

Usage: python 70-research-septgrep.py <sept_audit_findings.json>
"""
import json
import re
import sys

TERMS = {
    "modulith": r"modulith",
    "jmolecules": r"jmolecules",
    "openrewrite": r"openrewrite|rewrite",
    "errorprone/nullaway": r"error ?prone|nullaway|jspecify",
    "http-interface": r"httpexchange|http ?interface|http service|importhttpservices",
    "api-versioning": r"api.?version|deprecation|sunset",
    "retry": r"retryable|retrytemplate|concurrencylimit",
    "uuid": r"uuidv7|uuid ?v7|version_7",
    "trusted-types": r"trusted.?types",
    "view-transitions": r"view.?transition",
    "es2024+": r"groupby|withresolvers|set methods|iterator helper|toSorted|findLast",
    "css": r"@scope|:has\(|container quer|color-mix|nesting|popover",
    "hibernate": r"hibernate|softdelete|statelesssession|jakarta data",
    "gradle": r"convention.?plugin|build-logic|buildsrc|isolated.?projects|configuration.?cache",
    "jpms": r"jpms|module-info",
    "jdk": r"jep ?\d+|scoped ?value|virtual thread|gatherer|flexible constructor",
    "postgres18": r"postgres(ql)? ?18|returning (old|new)|without overlaps|skip scan|virtual generated",
    "archunit": r"archunit|freez",
}

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    data = json.load(open(sys.argv[1], encoding="utf-8"))
    items = data if isinstance(data, list) else data.get("findings", data)
    for label, pat in TERMS.items():
        hits = []
        for f in items:
            blob = " ".join(str(f.get(k, "")) for k in
                            ("title", "where", "recommendation", "guard", "risk", "note"))
            if re.search(pat, blob, flags=re.I):
                hits.append(f"{f.get('id')} [{f.get('prio')}] {str(f.get('title'))[:110]}")
        print(f"== {label}: {len(hits)}")
        for h in hits[:12]:
            print("   " + h)

if __name__ == "__main__":
    main()
