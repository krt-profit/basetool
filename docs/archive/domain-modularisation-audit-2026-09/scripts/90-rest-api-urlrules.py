"""Find mappings whose only role gate is a SecurityConfig URL rule.

Re-creates the ordered authorizeHttpRequests matchers of SecurityConfig.java:362-440 (first match
wins), evaluates every documented mapping against them, and lists mappings whose annotation-level
expression is exactly isAuthenticated() while the matching URL rule demands a role or authority.
Such mappings lose their role gate if a re-cut moves them out of the URL rule's pattern.
"""
import csv
import os
import re
import sys
from collections import Counter

OUT = r"$SCRATCHPAD"
UUID = "00000000-0000-4000-8000-000000000000"

RULES = [
    (None, ["/error"], "permitAll"),
    (None, ["/v3/api-docs*", "/v3/api-docs/**"], "ADMIN"),
    (None, ["/internal/**"], "permitAll"),
    ("GET", ["/api/v1/terms/document"], "permitAll"),
    ("GET", ["/api/v1/app/version-policy"], "permitAll"),
    (None, ["/api/v1/users/search", "/api/v1/users/search/references"], "ADMIN|OFFICER|KRT_MEMBER"),
    (None, ["/api/v1/users/search-bank", "/api/v1/users/search-bank/references"], "ADMIN|OFFICER|KRT_MEMBER|BANK_MANAGEMENT|BANK_EMPLOYEE"),
    (None, ["/api/v1/users/lookup"], "ADMIN|OFFICER|KRT_MEMBER|BANK_MANAGEMENT|BANK_EMPLOYEE"),
    (None, ["/api/v1/users/me", "/api/v1/users/me/**"], "authenticated"),
    ("GET", ["/api/v1/users"], "ADMIN|OFFICER|KRT_MEMBER"),
    ("GET", ["/api/v1/users/*"], "ADMIN|OFFICER|KRT_MEMBER"),
    ("PUT", ["/api/v1/users/*/attributes"], "ADMIN"),
    ("GET", ["/api/v1/users/*/memberships"], "ADMIN|OFFICER|KRT_MEMBER|BANK_EMPLOYEE"),
    (None, ["/api/v1/users/**"], "ADMIN"),
    ("GET", ["/api/v1/hangar/my-ships"], "authenticated"),
    ("POST", ["/api/v1/hangar/ships"], "authenticated"),
    ("PUT", ["/api/v1/hangar/ships/*"], "authenticated"),
    ("DELETE", ["/api/v1/hangar/ships/*"], "authenticated"),
    ("POST", ["/api/v1/hangar/import/ships"], "authenticated"),
    ("POST", ["/api/v1/hangar/import/fleetview"], "authenticated"),
    (None, ["/api/v1/hangar/**"], "HANGAR_READ|HANGAR_WRITE|ROLE_ADMIN"),
    (None, ["/api/v1/inventory/my-inventory", "/api/v1/inventory/my-inventory/**"], "authenticated"),
    (None, ["/api/v1/inventory", "/api/v1/inventory/**"], "ADMIN|OFFICER|LOGISTICIAN|KRT_MEMBER"),
    (None, ["/api/v1/personal-inventory", "/api/v1/personal-inventory/**"], "authenticated"),
    (None, ["/api/v1/uex/locations/**"], "authenticated"),
    (None, ["/api/v1/admin/**"], "ADMIN"),
    (None, ["/api/v1/bank/admin/**"], "ADMIN"),
    (None, ["/api/v1/audit/**"], "ADMIN"),
    (None, ["/**"], "authenticated"),
]

def rx(p):
    s = re.escape(p).replace(r"/\*\*", "(/.*)?").replace(r"\*", "[^/]*")
    return re.compile("^" + s + "$")

COMPILED = [(v, [rx(p) for p in ps], g, ps) for v, ps, g in RULES]

def rule_for(verb, path):
    u = re.sub(r"\{[^}]+\}", UUID, path)
    for v, rxs, g, ps in COMPILED:
        if v and v != verb:
            continue
        if any(r.match(u) for r in rxs):
            return g, ps
    return None, None

def main():
    with open(os.path.join(OUT, "90-rest-api-mappings.csv"), encoding="utf-8") as fh:
        rows = [r for r in csv.DictReader(fh) if r["path"].startswith("/api/")]
    only_url = []
    stricter = Counter()
    for r in rows:
        g, ps = rule_for(r["verb"], r["path"])
        if g not in ("authenticated", "permitAll") and r["effective_preauth"].strip() == "isAuthenticated()":
            only_url.append((r["verb"], r["path"], g, ps[0], r["class"]))
            stricter[ps[0]] += 1
    print("mappings whose only role gate is a URL rule:", len(only_url))
    for k, v in stricter.most_common():
        print(f"   {v:3d}  URL rule {k}")
    for o in only_url:
        print("   ", o[0], o[1].replace("/api/v1", ""), "| URL:", o[2], "|", o[4])

if __name__ == "__main__":
    sys.exit(main())
