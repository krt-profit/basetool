import json
import sys

ROOT = "$REPO/"
FILES = [
    "docs/keycloak/realm-config.reference.json",
    "frontend/src/e2e/resources/realm-export.e2e.json",
    "scripts/keycloak/test-realm-base.json",
    "docker/sandbox/keycloak/realm-iri.json",
]

for rel in FILES:
    try:
        with open(ROOT + rel, encoding="utf-8") as f:
            data = json.load(f)
    except Exception as e:
        print("==", rel, "unreadable:", e)
        continue
    print("==", rel)
    realm_roles = (data.get("roles") or {}).get("realm") or []
    for r in realm_roles:
        comp = r.get("composite")
        comps = r.get("composites")
        print("  realm role:", repr(r.get("name")), "composite=", comp, "composites=", json.dumps(comps) if comps else None)
    drr = data.get("defaultRole")
    if drr:
        print("  defaultRole:", json.dumps(drr))
    for c in data.get("clients") or []:
        cid = c.get("clientId")
        fsa = c.get("fullScopeAllowed")
        if fsa is False or cid in ("basetool-android", "basetool-frontend", "basetool-backend"):
            print("  client:", cid, "fullScopeAllowed=", fsa)
    scope_mappings = data.get("scopeMappings") or []
    for sm in scope_mappings:
        print("  scopeMapping:", json.dumps(sm))
    client_scope_mappings = data.get("clientScopeMappings") or {}
    if client_scope_mappings:
        print("  clientScopeMappings keys:", list(client_scope_mappings.keys()))
    users = data.get("users") or []
    for u in users:
        print("  user:", u.get("username"), "realmRoles=", u.get("realmRoles"))
