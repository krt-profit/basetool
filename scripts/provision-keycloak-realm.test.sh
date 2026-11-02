#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROVISIONER="${SCRIPT_DIR}/provision-keycloak-realm.py"

if [[ ! -f "$PROVISIONER" ]]; then
  echo "FATAL: provisioner not found at ${PROVISIONER}" >&2
  exit 1
fi

PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || { echo "FATAL: ${PYTHON} not found" >&2; exit 1; }

tests_run=0
tests_failed=0

ORIGIN="https://testing.example"
SECRET_MARKER="STUB-GENERATED-SECRET-must-never-be-printed"

make_stub() {
  local state="$1" flavour="$2"
  mkdir -p "$state"
  STUB_FLAVOUR="$flavour" STUB_SECRET="$SECRET_MARKER" STUB_STATE="$state" "$PYTHON" - <<'SEED'
import json
import os
import pathlib

state = pathlib.Path(os.environ["STUB_STATE"])
flavour = os.environ["STUB_FLAVOUR"]

builtin_scopes = ["acr", "address", "basic", "email", "microprofile-jwt", "offline_access",
                  "organization", "phone", "profile", "roles", "service_account", "web-origins"]
scopes = [{"id": f"s-{n}", "name": n, "protocol": "openid-connect", "attributes": {}}
          for n in builtin_scopes]
roles = [{"id": "r-default", "name": "default-roles-basetool"},
         {"id": "r-krt", "name": "KRT Member"},
         {"id": "r-adm", "name": "Admin"}, {"id": "r-bem", "name": "Bank Employee"},
         {"id": "r-offline", "name": "offline_access"},
         {"id": "r-uma", "name": "uma_authorization"}]
realm_management = {"id": "c-rm", "clientId": "realm-management", "attributes": {}}
account = {"id": "c-account", "clientId": "account", "attributes": {}}

realm = {
    "realm": "basetool", "revokeRefreshToken": True, "refreshTokenMaxReuse": 0,
    "accessTokenLifespan": 300, "ssoSessionIdleTimeout": 2592000,
    "ssoSessionMaxLifespan": 15552000, "offlineSessionMaxLifespanEnabled": False,
    "offlineSessionMaxLifespan": 5184000, "clientSessionIdleTimeout": 0,
    "clientSessionMaxLifespan": 0, "clientOfflineSessionIdleTimeout": 0,
    "clientOfflineSessionMaxLifespan": 0, "oauth2DeviceCodeLifespan": 600,
    "defaultRole": {"id": "r-default", "name": "default-roles-basetool"},
}
data = {
    "realm": realm,
    "roles": roles,
    "role_composites": {"r-default": ["r-krt", "r-uma"]},
    "scope_role_mappings": {},
    "clients": [realm_management, account],
    "client_roles": {"c-rm": [{"id": f"rm-{n}", "name": n} for n in
                              ("manage-users", "query-users", "view-realm", "view-users", "manage-clients")]},
    "client_mappers": {},
    "client_default_scopes": {},
    "client_optional_scopes": {},
    "client_scope_mappings": {},
    "scopes": scopes,
    "scope_mappers": {},
    "users": {},
    "realm_default_scopes": ["s-acr", "s-basic", "s-email", "s-profile", "s-roles",
                             "s-web-origins"],
    "realm_optional_scopes": ["s-address", "s-microprofile-jwt", "s-offline_access",
                              "s-organization", "s-phone"],
    "profiles": {"profiles": [{"name": "someone-elses-profile", "executors": []}]},
    "policies": {"policies": [{"name": "someone-elses-policy", "enabled": True,
                               "conditions": [], "profiles": ["someone-elses-profile"]}]},
    "forbid_user_reads": False,
    "counter": 0,
}

if flavour == "testing":
    roles.append({"id": "r-bmg", "name": "Bank Management"})
    data["realm"]["revokeRefreshToken"] = False
    data["realm"]["refreshTokenMaxReuse"] = 5
    frontend = {
        "id": "c-frontend", "clientId": "basetool-frontend", "enabled": True,
        "protocol": "openid-connect", "publicClient": True, "bearerOnly": False,
        "standardFlowEnabled": True, "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False, "serviceAccountsEnabled": False,
        "consentRequired": False, "fullScopeAllowed": True, "frontchannelLogout": False,
        "redirectUris": [f"https://testing.example/*",
                         "https://testing.example/login/oauth2/code/keycloak",
                         "https://testing-only.example/*"],
        "webOrigins": ["https://testing.example"],
        "attributes": {"pkce.code.challenge.method": "S256",
                       "backchannel.logout.session.required": "true"},
    }
    backend = {
        "id": "c-backend", "clientId": "backend-service", "enabled": True,
        "protocol": "openid-connect", "publicClient": False, "bearerOnly": False,
        "standardFlowEnabled": False, "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": True, "serviceAccountsEnabled": True,
        "consentRequired": False, "fullScopeAllowed": True, "frontchannelLogout": False,
        "clientAuthenticatorType": "client-secret", "secret": os.environ["STUB_SECRET"],
        "redirectUris": [], "webOrigins": [], "attributes": {},
    }
    stray = {"id": "c-stray", "clientId": "testing-only-client", "attributes": {},
             "redirectUris": [], "webOrigins": []}
    data["clients"] += [frontend, backend, stray]
    data["client_default_scopes"] = {
        "c-frontend": ["s-acr", "s-basic", "s-email", "s-profile", "s-roles", "s-web-origins"],
        "c-backend": ["s-acr", "s-basic", "s-email", "s-profile", "s-roles", "s-web-origins",
                      "s-service_account"],
    }
    data["client_optional_scopes"] = {
        "c-frontend": ["s-address", "s-microprofile-jwt", "s-offline_access", "s-organization",
                       "s-phone"],
        "c-backend": ["s-address", "s-microprofile-jwt", "s-offline_access", "s-organization",
                      "s-phone"],
    }
    data["client_mappers"] = {"c-frontend": [{
        "id": "m-stray", "name": "testing-only-mapper", "protocol": "openid-connect",
        "protocolMapper": "oidc-hardcoded-claim-mapper", "config": {"claim.name": "x"}}]}
    data["users"] = {"u-backend": {"id": "u-backend", "client": "c-backend",
                                   "realm_roles": ["r-default"], "client_roles": {}}}

(state / "state.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
SEED

  cat >"${state}/kcadm_stub.py" <<'STUB'
"""A small, stateful kcadm impersonator.

Handles exactly the argument shapes the provisioner emits and fails loudly on anything else, so a
new call shape cannot slip through as a silent pass.
"""
import json
import os
import pathlib
import sys

state_dir = pathlib.Path(os.environ["KCADM_STUB_STATE"])
path_file = state_dir / "state.json"
data = json.loads(path_file.read_text(encoding="utf-8"))
argv = sys.argv[1:]
verb, path = argv[0], argv[1]
query = {}
for i, arg in enumerate(argv):
    if arg == "-q":
        key, _, value = argv[i + 1].partition("=")
        query[key] = value
body = json.loads(sys.stdin.read() or "null") if "-f" in argv else None

with (state_dir / "calls.log").open("a", encoding="utf-8") as log:
    log.write(f"{verb} {path}\n")
if body is not None:
    with (state_dir / "bodies.log").open("a", encoding="utf-8") as sink:
        sink.write(f"{verb} {path} {json.dumps(body, sort_keys=True)}\n")

def save():
    path_file.write_text(json.dumps(data, indent=2), encoding="utf-8")

def new_id(prefix):
    data["counter"] += 1
    return f"{prefix}-{data['counter']}"

def out(value):
    sys.stdout.write(json.dumps(value))
    sys.exit(0)

def fail(message):
    sys.stderr.write(message + "\n")
    sys.exit(1)

def client(uuid):
    return next(c for c in data["clients"] if c["id"] == uuid)

def scope_by_id(sid):
    return next(s for s in data["scopes"] if s["id"] == sid)

def policy_freezes(uuid):
    marker = any(r["name"] == "dpop-refresh-only" for r in data["client_roles"].get(uuid, []))
    attached = any(p.get("name") == "krt-mobile-dpop-policy"
                   for p in data["policies"]["policies"])
    return marker and attached

parts = path.split("/")

if verb == "get":
    if parts[0] == "realms":
        out(data["realm"])
    if path == "clients":
        found = data["clients"]
        if "clientId" in query:
            found = [c for c in found if c["clientId"] == query["clientId"]]
        out(found)
    if path == "client-scopes":
        out(data["scopes"])
    if path == "roles":
        out(data["roles"])
    if path in ("default-default-client-scopes", "default-optional-client-scopes"):
        key = "realm_default_scopes" if "default-default" in path else "realm_optional_scopes"
        out([{"id": s, "name": scope_by_id(s)["name"]} for s in data[key]])
    if path == "client-policies/profiles":
        out(data["profiles"])
    if path == "client-policies/policies":
        out(data["policies"])
    if parts[0] == "clients" and len(parts) == 3 and parts[2] == "roles":
        out(data["client_roles"].get(parts[1], []))
    if parts[0] == "clients" and parts[2:] == ["protocol-mappers", "models"]:
        out(data["client_mappers"].get(parts[1], []))
    if parts[0] == "client-scopes" and parts[2:] == ["protocol-mappers", "models"]:
        out(data["scope_mappers"].get(parts[1], []))
    if parts[0] == "clients" and parts[2] in ("default-client-scopes", "optional-client-scopes"):
        key = "client_default_scopes" if parts[2].startswith("default") else "client_optional_scopes"
        out([{"id": s, "name": scope_by_id(s)["name"]} for s in data[key].get(parts[1], [])])
    if parts[0] == "clients" and parts[2:] == ["scope-mappings", "realm"]:
        out(data["client_scope_mappings"].get(parts[1], []))
    if parts[0] == "roles" and parts[2:] == ["composites", "realm"]:
        owner = next((r for r in data["roles"] if r["name"] == parts[1]), None)
        if owner is None:
            fail(f"Could not find role {parts[1]}")
        held = data["role_composites"].get(owner["id"], [])
        out([r for r in data["roles"] if r["id"] in held])
    if parts[0] == "client-scopes" and parts[2:] == ["scope-mappings", "realm"]:
        out(data["scope_role_mappings"].get(parts[1], []))
    if parts[0] == "clients" and parts[2] == "service-account-user":
        user = next((u for u in data["users"].values() if u["client"] == parts[1]), None)
        out({"id": user["id"], "username": "service-account"} if user else None)
    if parts[0] == "users":
        if data["forbid_user_reads"]:
            fail("HTTP 403 Forbidden")
        user = data["users"][parts[1]]
        if parts[3] == "realm":
            out([next(r for r in data["roles"] if r["id"] == rid) for rid in user["realm_roles"]])
        held = user["client_roles"].get(parts[4], [])
        out([next(r for r in data["client_roles"][parts[4]] if r["id"] == rid) for rid in held])
    fail(f"stub: unexpected get {path}")

if verb == "create":
    if path == "clients":
        uuid = new_id("c")
        rep = {k: v for k, v in body.items()
               if k not in ("defaultClientScopes", "optionalClientScopes")}
        rep["id"] = uuid
        if "backchannel.logout.session.required" in (rep.get("attributes") or {}):
            rep["attributes"] = {**rep["attributes"], "backchannel.logout.session.required": "true"}
        if rep.get("publicClient") is False:
            rep["secret"] = os.environ.get("STUB_SECRET", "STUB-GENERATED-SECRET-must-never-be-printed")
        data["clients"].append(rep)
        data["client_default_scopes"][uuid] = list(data["realm_default_scopes"])
        data["client_optional_scopes"][uuid] = list(data["realm_optional_scopes"])
        if rep.get("serviceAccountsEnabled"):
            uid = new_id("u")
            data["users"][uid] = {"id": uid, "client": uuid, "realm_roles": ["r-default"],
                                  "client_roles": {}}
            data["client_default_scopes"][uuid].append("s-service_account")
        save()
        sys.exit(0)
    if path == "client-scopes":
        data["scopes"].append({**body, "id": new_id("s")})
        save()
        sys.exit(0)
    if path == "roles":
        data["roles"].append({**body, "id": new_id("r")})
        save()
        sys.exit(0)
    if parts[0] in ("clients", "client-scopes") and parts[2:] == ["protocol-mappers", "models"]:
        key = "client_mappers" if parts[0] == "clients" else "scope_mappers"
        data[key].setdefault(parts[1], []).append({**body, "id": new_id("m")})
        save()
        sys.exit(0)
    if parts[0] == "clients" and len(parts) == 3 and parts[2] == "roles":
        data["client_roles"].setdefault(parts[1], []).append({**body, "id": new_id("cr")})
        save()
        sys.exit(0)
    if parts[0] == "clients" and parts[2:] == ["scope-mappings", "realm"]:
        data["client_scope_mappings"].setdefault(parts[1], []).extend(body)
        save()
        sys.exit(0)
    if parts[0] == "roles" and parts[2:] == ["composites"]:
        owner = next((r for r in data["roles"] if r["name"] == parts[1]), None)
        if owner is None or any(r.get("id") not in {x["id"] for x in data["roles"]} for r in body):
            fail("stub: a composite names an unknown role or role id")
        held = data["role_composites"].setdefault(owner["id"], [])
        held += [r["id"] for r in body if r["id"] not in held]
        save()
        sys.exit(0)
    if parts[0] == "client-scopes" and parts[2:] == ["scope-mappings", "realm"]:
        if any(r.get("id") not in {x["id"] for x in data["roles"]} for r in body):
            fail("stub: a scope mapping names an unknown role id")
        mapped = data["scope_role_mappings"].setdefault(parts[1], [])
        mapped += [r for r in body if r["id"] not in {m["id"] for m in mapped}]
        save()
        sys.exit(0)
    if parts[0] == "users":
        user = data["users"][parts[1]]
        if parts[3] == "realm":
            user["realm_roles"] += [r["id"] for r in body]
        else:
            user["client_roles"].setdefault(parts[4], []).extend(r["id"] for r in body)
        save()
        sys.exit(0)
    fail(f"stub: unexpected create {path}")

if verb == "update":
    if parts[0] == "realms":
        if "-r" in argv:
            fail("stub: the realm is updated without -r")
        data["realm"].update(body)
        save()
        sys.exit(0)
    if path in ("client-policies/profiles", "client-policies/policies"):
        if path.endswith("profiles"):
            kept = {p["name"] for p in body["profiles"]}
            if any(name not in kept for p in data["policies"]["policies"]
                   for name in p.get("profiles", [])):
                fail("Invalid client profiles: a policy still references a removed profile")
        data[parts[1]] = body
        save()
        sys.exit(0)
    if parts[0] == "roles" and len(parts) == 2:
        victim = next((r for r in data["roles"] if r["name"] == parts[1]), None)
        if victim is None:
            fail(f"stub: no realm role {parts[1]}")
        data["roles"] = [r for r in data["roles"] if r["id"] != victim["id"]]
        for user in data["users"].values():
            user["realm_roles"] = [rid for rid in user["realm_roles"] if rid != victim["id"]]
        save()
        sys.exit(0)
    if parts[0] == "clients" and len(parts) == 2:
        if policy_freezes(parts[1]):
            fail("Invalid client metadata: DPoP token is disabled [invalid_client_metadata]")
        if "secret" in body:
            (state_dir / "SECRET_SENT_BACK").write_text("yes", encoding="utf-8")
        current = client(parts[1])
        secret = current.get("secret")
        current.clear()
        current.update(body)
        if secret is not None:
            current["secret"] = secret
        save()
        sys.exit(0)
    if parts[0] == "client-scopes" and len(parts) == 2:
        scope = scope_by_id(parts[1])
        scope.clear()
        scope.update(body)
        save()
        sys.exit(0)
    if parts[0] in ("clients", "client-scopes") and parts[2:4] == ["protocol-mappers", "models"]:
        key = "client_mappers" if parts[0] == "clients" else "scope_mappers"
        mappers = data[key][parts[1]]
        index = next(i for i, m in enumerate(mappers) if m["id"] == parts[4])
        mappers[index] = body
        save()
        sys.exit(0)
    if parts[0] == "clients" and parts[2] in ("default-client-scopes", "optional-client-scopes"):
        if "-n" not in argv:
            fail("stub: a scope link answers no GET; kcadm needs -n")
        key = "client_default_scopes" if parts[2].startswith("default") else "client_optional_scopes"
        linked = data[key].setdefault(parts[1], [])
        if parts[3] not in linked:
            linked.append(parts[3])
        save()
        sys.exit(0)
    fail(f"stub: unexpected update {path}")

if verb == "delete":
    if parts[0] in ("default-default-client-scopes", "default-optional-client-scopes") and len(parts) == 2:
        key = "realm_default_scopes" if parts[0].startswith("default-default") else "realm_optional_scopes"
        if parts[1] not in data[key]:
            fail(f"stub: scope {parts[1]} is not a realm {key}")
        data[key] = [s for s in data[key] if s != parts[1]]
        save()
        sys.exit(0)
    if parts[0] == "roles" and len(parts) == 2:
        victim = next((r for r in data["roles"] if r["name"] == parts[1]), None)
        if victim is None:
            fail(f"stub: no realm role {parts[1]}")
        data["roles"] = [r for r in data["roles"] if r["id"] != victim["id"]]
        for user in data["users"].values():
            user["realm_roles"] = [rid for rid in user["realm_roles"] if rid != victim["id"]]
        save()
        sys.exit(0)
    if parts[0] == "clients" and len(parts) == 2:
        if policy_freezes(parts[1]):
            fail("stub: the DPoP policy still matches this client; detach it before the delete")
        data["clients"] = [c for c in data["clients"] if c["id"] != parts[1]]
        for key in ("client_roles", "client_mappers", "client_default_scopes",
                    "client_optional_scopes", "client_scope_mappings"):
            data[key].pop(parts[1], None)
        save()
        sys.exit(0)
    if parts[0] in ("clients", "client-scopes") and parts[2:4] == ["protocol-mappers", "models"]:
        key = "client_mappers" if parts[0] == "clients" else "scope_mappers"
        before = data[key].get(parts[1], [])
        if not any(m["id"] == parts[4] for m in before):
            fail(f"stub: no mapper {parts[4]} on {parts[1]}")
        data[key][parts[1]] = [m for m in before if m["id"] != parts[4]]
        save()
        sys.exit(0)
    if parts[0] == "clients" and parts[2] in ("default-client-scopes", "optional-client-scopes"):
        key = "client_default_scopes" if parts[2].startswith("default") else "client_optional_scopes"
        data[key][parts[1]] = [s for s in data[key].get(parts[1], []) if s != parts[3]]
        save()
        sys.exit(0)
    if parts[0] == "clients" and parts[2:] == ["scope-mappings", "realm"]:
        removed = {r["name"] for r in body}
        data["client_scope_mappings"][parts[1]] = [
            r for r in data["client_scope_mappings"].get(parts[1], []) if r["name"] not in removed]
        save()
        sys.exit(0)
    fail(f"stub: unexpected delete {path}")

fail(f"stub: unexpected verb {verb}")
STUB
}

to_child_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

run_provisioner() {
  local state="$1"; shift
  local rc=0
  : >"${state}/calls.log"
  KCADM_STUB_STATE="$state" "$PYTHON" "$PROVISIONER" --realm basetool --public-origin "$ORIGIN" \
    --kcadm-command "${PYTHON} $(to_child_path "${state}/kcadm_stub.py")" "$@" 2>&1 || rc=$?
  echo "$rc" >"${state}/rc"
}

query() {
  local state="$1" expr="$2"
  STUB_STATE="$state" EXPR="$expr" "$PYTHON" -c '
import json, os, pathlib
d = json.loads((pathlib.Path(os.environ["STUB_STATE"]) / "state.json").read_text(encoding="utf-8"))
def client(cid):
    return next((c for c in d["clients"] if c["clientId"] == cid), None)
def scope_names(kind, cid):
    key = "client_default_scopes" if kind == "default" else "client_optional_scopes"
    ids = d[key].get(client(cid)["id"], [])
    return sorted(s["name"] for s in d["scopes"] if s["id"] in ids)
def mappers(cid):
    return sorted(m["name"] for m in d["client_mappers"].get(client(cid)["id"], []))
print(eval(os.environ["EXPR"]))
'
}

pass() { tests_run=$((tests_run + 1)); printf '  ok   %s\n' "$1"; }
fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  printf '  FAIL %s\n' "$1"
  [[ $# -gt 1 ]] && printf '       %s\n' "$2"
  return 0
}

assert_eq() {
  local actual="$1" expected="$2" label="$3"
  if [[ "$actual" == "$expected" ]]; then pass "$label"; else
    fail "$label" "expected [${expected}], got [${actual}]"; fi
}

assert_contains() {
  local haystack="$1" needle="$2" label="$3"
  if [[ "$haystack" == *"$needle"* ]]; then pass "$label"; else fail "$label" "missing: ${needle}"; fi
}

assert_not_contains() {
  local haystack="$1" needle="$2" label="$3"
  if [[ "$haystack" != *"$needle"* ]]; then pass "$label"; else fail "$label" "unexpected: ${needle}"; fi
}

writes_in() { grep -cE '^(create|update|delete) ' "$1/calls.log" || true; }

echo "1. a dry run writes nothing and says what it would do"
state="$(mktemp -d)"
make_stub "$state" empty
before="$(cat "${state}/state.json")"
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "2" "a dry run with pending changes exits 2"
assert_eq "$(writes_in "$state")" "0" "no create/update/delete reaches kcadm"
assert_eq "$(cat "${state}/state.json")" "$before" "the realm is byte-for-byte unchanged"
assert_contains "$output" "+ create client 'basetool-ingest-gateway'" "the plan names the missing gateway"
assert_contains "$output" "+ create client scope 'extractor-ingest-only'" "the plan names the missing scope"
assert_contains "$output" "~ revokeRefreshToken: true -> false" "the plan names the token-setting drift"
assert_contains "$output" "BASETOOL_INGEST_SERVICE_ACCOUNT_CLIENT_SECRET" "the plan says which .env value needs the new secret"
rm -rf "$state"

echo "2. from an empty realm, --apply builds the production shape"
state="$(mktemp -d)"
make_stub "$state" empty
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
for cid in basetool-frontend backend-service basetool-ingest-gateway basetool-sc-extractor; do
  assert_eq "$(query "$state" "client('${cid}') is not None")" "True" "client ${cid} exists"
done
assert_eq "$(query "$state" "client('basetool-android') is None")" "True" "the retired app client is not created"
assert_eq "$(query "$state" "client('grafana') is None")" "True" "grafana is left alone without --grafana-origin"
assert_eq "$(query "$state" "sorted(set(m['config']['included.custom.audience'] for s in d['scopes'] for m in d['scope_mappers'].get(s['id'], [])))")" \
  "['basetool-backend', 'basetool-ingest']" "both audiences have their mappers"
assert_eq "$(query "$state" "next(s for s in d['scopes'] if s['name']=='extractor-ingest-only')['attributes']['include.in.token.scope']")" \
  "true" "extractor-ingest-only puts its name in the scope claim"
assert_eq "$(query "$state" "next(s for s in d['scopes'] if s['name']=='extractor-ingest')['attributes']['include.in.token.scope']")" \
  "false" "extractor-ingest does not"
assert_eq "$(query "$state" "scope_names('default', 'basetool-frontend')")" \
  "['email', 'extractor-ingest', 'profile', 'roles', 'web-origins']" \
  "the frontend carries extractor-ingest and NOT extractor-ingest-only (REQ-INGEST-011)"
assert_eq "$(query "$state" "scope_names('default', 'basetool-sc-extractor')")" \
  "['basic']" "the extractor carries only basic by default: no ingest scope, no backend audience (H1)"
assert_eq "$(query "$state" "[client('basetool-sc-extractor')['consentRequired'], client('basetool-sc-extractor')['attributes']['dpop.bound.access.tokens']]")" \
  "[True, 'true']" "the extractor asks for consent and binds every token to DPoP (H1)"
assert_eq "$(query "$state" "sorted(client('basetool-frontend')['redirectUris'])")" \
  "['https://testing.example/*', 'https://testing.example/login/oauth2/code/keycloak']" \
  "the frontend's redirect URIs come from --public-origin"
assert_eq "$(query "$state" "client('basetool-frontend')['attributes']['post.logout.redirect.uris']")" \
  "https://testing.example/*##https://testing.example" "the post-logout list comes from --public-origin"
assert_eq "$(query "$state" "client('basetool-frontend')['baseUrl']")" \
  "https://testing.example/" "the frontend's baseUrl comes from --public-origin (REQ-SEC-071)"
assert_not_contains "$(cat "${state}/state.json")" "profit-base.online" "no production hostname is written into another realm"
assert_eq "$(query "$state" "'Bank Management' in [r['name'] for r in d['roles']]")" "True" "a missing application role is created"
assert_eq "$(query "$state" "mappers('basetool-frontend')")" \
  "['description', 'discord_guild_nickname', 'discord_user_id', 'rank', 'sub']" "the frontend's claim mappers exist"
assert_eq "$(query "$state" "[p['name'] for p in d['policies']['policies']]")" \
  "['someone-elses-policy']" "no client policy is written and a foreign policy survives"
assert_eq "$(query "$state" "[p['name'] for p in d['profiles']['profiles']]")" \
  "['someone-elses-profile']" "no client profile is written and a foreign profile survives"
assert_eq "$(grep -cE '^(create|update|delete) client-policies' "${state}/calls.log" || true)" "0" \
  "the apply never writes the client-policy lists"
assert_eq "$(query "$state" "sorted(r['name'] for u in d['users'].values() if u['client']==client('backend-service')['id'] for rid in u['client_roles'].get('c-rm', []) for r in d['client_roles']['c-rm'] if r['id']==rid)")" \
  "['manage-users', 'view-realm', 'view-users']" "backend-service's service account holds its realm-management roles"
assert_eq "$(query "$state" "d['realm']['revokeRefreshToken'], d['realm']['offlineSessionMaxLifespan']")" \
  "(False, 7776000)" "the realm token settings match production"
assert_not_contains "$output" "$SECRET_MARKER" "no generated client secret is printed"
assert_contains "$output" "-> Credentials" "the operator is told where to read the secret"
rm -rf "$state"

echo "3. a second --apply changes nothing"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
first="$(cat "${state}/state.json")"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the second apply exits 0"
assert_contains "$output" "No changes" "the second apply reports no changes"
assert_eq "$(writes_in "$state")" "0" "the second apply sends no write at all"
assert_eq "$(cat "${state}/state.json")" "$first" "the realm is unchanged by the second apply"
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "0" "a dry run against the shaped realm exits 0"
rm -rf "$state"

echo "4. the retired basetool-android client, its DPoP policy and its profile are deleted (ADR-0268)"
RETIRED_REASON="retired with the Android app, ADR-0268"
seed_android() {
  STUB_STATE="$1" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
d["clients"].append({"id": "c-android", "clientId": "basetool-android", "publicClient": True,
                     "redirectUris": ["https://testing.example/app/callback"], "webOrigins": [],
                     "attributes": {"pkce.code.challenge.method": "S256"}})
d["client_roles"]["c-android"] = [{"id": "cr-marker", "name": "dpop-refresh-only"}]
d["client_scope_mappings"]["c-android"] = [{"id": "r-krt", "name": "KRT Member"}]
d["profiles"]["profiles"].append({"name": "krt-mobile-dpop", "executors": []})
d["policies"]["policies"].append({"name": "krt-mobile-dpop-policy", "enabled": True,
                                  "conditions": [], "profiles": ["krt-mobile-dpop"]})
d["policies"]["policies"].append({"name": "a-later-policy", "enabled": True, "conditions": [],
                                  "profiles": []})
p.write_text(json.dumps(d), encoding="utf-8")
'
}
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
seed_android "$state"
before="$(cat "${state}/state.json")"
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "2" "a realm still holding the app client plans changes"
planned="$(printf '%s\n' "$output" | sed '/^\[only on this realm/,$d' | grep -E '^  [-+~=] ')"
expected="$(printf '%s\n' \
  "  - policy 'krt-mobile-dpop-policy' detached (${RETIRED_REASON}; 2 other policy(ies) carried forward)" \
  "  - profile 'krt-mobile-dpop' removed (${RETIRED_REASON}; 1 other profile(s) carried forward)" \
  "  - client 'basetool-android' deleted with its roles, mappers and scope mappings (${RETIRED_REASON})")"
assert_eq "$planned" "$expected" "the plan deletes exactly the policy, the profile and the client, in that order"
assert_not_contains "$output" "client 'basetool-android' is not in the production shape" "the retired client is deleted, not merely reported"
assert_eq "$(cat "${state}/state.json")" "$before" "the dry run writes nothing"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_eq "$(query "$state" "client('basetool-android') is None")" "True" "the app client is gone"
assert_eq "$(query "$state" "'c-android' in d['client_roles'] or 'c-android' in d['client_scope_mappings']")" "False" \
  "and its marker role and realm-role scope with it"
assert_eq "$(query "$state" "[p['name'] for p in d['policies']['policies']]")" \
  "['someone-elses-policy', 'a-later-policy']" "the DPoP policy is gone and every foreign policy survives in order"
assert_eq "$(query "$state" "[p['name'] for p in d['profiles']['profiles']]")" \
  "['someone-elses-profile']" "the DPoP profile is gone and the foreign profile survives"
policy_line="$(grep -n '^update client-policies/policies$' "${state}/calls.log" | head -1 | cut -d: -f1)"
profile_line="$(grep -n '^update client-policies/profiles$' "${state}/calls.log" | head -1 | cut -d: -f1)"
delete_line="$(grep -n '^delete clients/c-android$' "${state}/calls.log" | head -1 | cut -d: -f1)"
if [[ -n "$policy_line" && -n "$profile_line" && -n "$delete_line" \
      && "$policy_line" -lt "$profile_line" && "$profile_line" -lt "$delete_line" ]]; then
  pass "policy detach < profile removal < client delete"
else
  fail "policy detach < profile removal < client delete" \
    "policy ${policy_line:-none}, profile ${profile_line:-none}, delete ${delete_line:-none}"
fi
output="$(run_provisioner "$state" --apply)"
assert_contains "$output" "No changes" "a second apply is empty"
assert_eq "$(writes_in "$state")" "0" "and sends no write"
rm -rf "$state"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
d["clients"].append({"id": "c-android", "clientId": "basetool-android", "attributes": {}})
p.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "a client whose policy is already gone is deleted on its own"
assert_eq "$(grep -cE '^update client-policies' "${state}/calls.log" || true)" "0" "and the policy lists are left alone"
assert_eq "$(query "$state" "client('basetool-android') is None")" "True" "the client is gone"
rm -rf "$state"

echo "4b. the retired Officer realm role is deleted (ADR-0300)"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
d["roles"].append({"id": "r-off", "name": "Officer"})
p.write_text(json.dumps(d), encoding="utf-8")
'
before="$(cat "${state}/state.json")"
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "2" "a realm still holding the Officer role plans a change"
planned="$(printf '%s\n' "$output" | sed '/^\[only on this realm/,$d' | grep -E '^  [-+~=] ')"
assert_eq "$planned" "  - realm role 'Officer' deleted with its user mappings (retired with the OFFICER role, ADR-0300)" \
  "the plan deletes exactly the Officer role"
assert_eq "$(cat "${state}/state.json")" "$before" "the dry run writes nothing"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_eq "$(query "$state" "'Officer' in [r['name'] for r in d['roles']]")" "False" "the Officer role is gone"
assert_eq "$(query "$state" "sorted(r['name'] for r in d['roles'] if r['name'] in ('KRT Member', 'Admin'))")" \
  "['Admin', 'KRT Member']" "and the application roles survive"
output="$(run_provisioner "$state" --apply)"
assert_contains "$output" "No changes" "a second apply is empty"
assert_eq "$(writes_in "$state")" "0" "and sends no write"
rm -rf "$state"

echo "5. objects only the target realm has are reported, never deleted"
state="$(mktemp -d)"
make_stub "$state" testing
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the testing-shaped realm is brought into shape"
assert_eq "$(query "$state" "client('testing-only-client') is not None")" "True" "a testing-only client is kept"
assert_contains "$output" "client 'testing-only-client' is not in the production shape" "and reported"
assert_eq "$(query "$state" "'testing-only-mapper' in mappers('basetool-frontend')")" "True" "a testing-only mapper is kept"
assert_contains "$output" "mapper 'testing-only-mapper' is not in the production shape" "and reported"
assert_eq "$(query "$state" "'https://testing-only.example/*' in client('basetool-frontend')['redirectUris']")" "True" "a testing-only redirect URI is kept"
assert_contains "$output" "redirect URI 'https://testing-only.example/*' is not in the production shape" "and reported"
assert_eq "$(query "$state" "[n for n in ('acr', 'basic') if n in scope_names('default', 'basetool-frontend')]")" \
  "['acr', 'basic']" "scopes production's frontend lacks stay assigned"
assert_contains "$output" "basetool-frontend: default scope 'basic' is not in the production shape" "and are reported"
assert_eq "$(query "$state" "client('backend-service')['directAccessGrantsEnabled']")" "False" \
  "backend-service's direct grants are turned off, as in production"
assert_eq "$(query "$state" "client('backend-service')['frontchannelLogout']")" "True" "a managed flag converges"
assert_contains "$output" "~ baseUrl: <absent> -> https://testing.example/" \
  "a frontend without baseUrl is planned to get one (REQ-SEC-071)"
assert_eq "$(query "$state" "client('basetool-frontend')['baseUrl']")" "https://testing.example/" \
  "and gets it, so Keycloak's error pages link back to the app"
assert_eq "$(query "$state" "client('backend-service')['secret']")" "$SECRET_MARKER" "the existing secret is untouched"
if [[ -e "${state}/SECRET_SENT_BACK" ]]; then fail "no client secret is sent back in an update"; else
  pass "no client secret is sent back in an update"; fi
assert_not_contains "$output" "$SECRET_MARKER" "the existing secret is never printed"
assert_not_contains "$(cat "${state}/bodies.log")" "$SECRET_MARKER" "the existing secret is in no payload"
rm -rf "$state"

echo "6. service-account roles an identity cannot read are handed to the operator"
state="$(mktemp -d)"
make_stub "$state" testing
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
d["forbid_user_reads"] = True
p.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "3" "the apply exits 3: applied except the manual grants"
assert_contains "$output" "client 'realm-management' role 'manage-users'" "the manual step names the role"
assert_contains "$output" "Service account roles -> Assign role" "and where to grant it"
assert_eq "$(query "$state" "client('basetool-sc-extractor') is not None")" "True" "everything else was still applied"
rm -rf "$state"

echo "7. --grafana-origin manages the grafana client; a malformed origin is refused"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply --grafana-origin https://grafana.testing.example >/dev/null
assert_eq "$(query "$state" "client('grafana')['redirectUris']")" \
  "['https://grafana.testing.example/login/generic_oauth']" "grafana's redirect comes from --grafana-origin"
output="$(KCADM_STUB_STATE="$state" "$PYTHON" "$PROVISIONER" --public-origin "https://testing.example/" \
  --kcadm-command "false" 2>&1 || true)"
assert_contains "$output" "no path and no trailing slash" "a trailing slash is refused before anything is read"
rm -rf "$state"

echo "8. the 2026-09-22 retirements of the extractor and frontend entries converge away from a realm in the old production shape"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
by_id = {c["clientId"]: c for c in d["clients"]}
ids = {s["name"]: s["id"] for s in d["scopes"]}
ex = by_id["basetool-sc-extractor"]
ex["standardFlowEnabled"] = True
ex["redirectUris"] = ["http://127.0.0.1/*", "http://localhost/*"]
fe = by_id["basetool-frontend"]
fe["redirectUris"] = ["http://frontend:18081/*"] + fe["redirectUris"]
fe["webOrigins"] = ["http://frontend:18081"] + fe["webOrigins"]
p.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state")"
planned="$(printf '%s\n' "$output" | sed '/^\[only on this realm/,$d' | grep -E '^  [-+~=] ' | sort)"
expected="$(printf '%s\n' \
  "  - redirect URI http://127.0.0.1/* withheld (exchange-only since the extractor's 2.10.0 (H1, #2088); unused code flow retired 2026-09-22)" \
  "  - redirect URI http://frontend:18081/* withheld (compose-internal origin retired 2026-09-22)" \
  "  - redirect URI http://localhost/* withheld (exchange-only since the extractor's 2.10.0 (H1, #2088); unused code flow retired 2026-09-22)" \
  "  - web origin http://frontend:18081 withheld (compose-internal origin retired 2026-09-22)" \
  "  ~ standardFlowEnabled: true -> false" | sort)"
assert_eq "$planned" "$expected" "the plan removes exactly the retired entries"
assert_not_contains "$output" "is not in the production shape" "a retired entry is removed, not merely reported"
run_provisioner "$state" --apply >/dev/null
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_eq "$(query "$state" "client('basetool-sc-extractor')['standardFlowEnabled'], client('basetool-sc-extractor')['redirectUris']")" \
  "(False, [])" "the extractor has no code flow and no redirect URI"
assert_eq "$(query "$state" "[u for u in client('basetool-frontend')['redirectUris'] + client('basetool-frontend')['webOrigins'] if 'frontend:18081' in u]")" \
  "[]" "the frontend's compose-internal origin is gone"
assert_eq "$(query "$state" "scope_names('default', 'basetool-sc-extractor')")" \
  "['basic']" \
  "the extractor keeps only basic"
output="$(run_provisioner "$state" --apply)"
assert_contains "$output" "No changes" "a second apply is empty"
assert_eq "$(writes_in "$state")" "0" "and sends no write"
rm -rf "$state"

echo "9. without --frontend-client the frontend's client type is never touched (ADR-0001)"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
assert_eq "$(query "$state" "client('basetool-frontend')['publicClient']")" "True" "a new frontend client is created public, production's pre-rollout shape"
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
fe = next(c for c in d["clients"] if c["clientId"] == "basetool-frontend")
fe["publicClient"] = False
fe["clientAuthenticatorType"] = "client-secret"
fe["secret"] = "ROLLED-OUT-SECRET-must-never-be-printed"
p.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "0" "a confidential frontend is 'in shape' without the flag"
assert_not_contains "$output" "publicClient" "no plan line touches the client type"
assert_not_contains "$output" "ROLLED-OUT-SECRET" "the stored secret is never printed"
rm -rf "$state"

echo "10. --frontend-client confidential without the secret in the environment refuses"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
before="$(cat "${state}/state.json")"
unset KEYCLOAK_FRONTEND_CLIENT_SECRET
output="$(run_provisioner "$state" --frontend-client confidential --apply)"
assert_eq "$(cat "${state}/rc")" "1" "the apply is refused"
assert_contains "$output" "KEYCLOAK_FRONTEND_CLIENT_SECRET" "the refusal names the variable"
assert_eq "$(cat "${state}/state.json")" "$before" "nothing is written"
rm -rf "$state"

echo "11. --frontend-client confidential switches the client with the operator's secret, once"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
rm -f "${state}/SECRET_SENT_BACK"
output="$(KEYCLOAK_FRONTEND_CLIENT_SECRET="OPERATOR-SECRET-must-never-be-printed" run_provisioner "$state" --frontend-client confidential)"
assert_eq "$(cat "${state}/rc")" "2" "the dry run plans the switch"
assert_contains "$output" "~ publicClient: true -> false" "the plan names the switch"
assert_contains "$output" "~ secret: set from \$KEYCLOAK_FRONTEND_CLIENT_SECRET" "the plan says where the secret comes from"
assert_not_contains "$output" "OPERATOR-SECRET" "the dry run never prints the secret"
output="$(KEYCLOAK_FRONTEND_CLIENT_SECRET="OPERATOR-SECRET-must-never-be-printed" run_provisioner "$state" --frontend-client confidential --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_not_contains "$output" "OPERATOR-SECRET" "the apply never prints the secret"
assert_eq "$(query "$state" "client('basetool-frontend')['publicClient'], client('basetool-frontend')['clientAuthenticatorType'], client('basetool-frontend')['secret']")" \
  "(False, 'client-secret', 'OPERATOR-SECRET-must-never-be-printed')" "Keycloak holds exactly the operator's secret"
assert_eq "$(query "$state" "client('basetool-frontend')['attributes']['pkce.code.challenge.method']")" "S256" "PKCE S256 stays required"
output="$(KEYCLOAK_FRONTEND_CLIENT_SECRET="A-DIFFERENT-VALUE" run_provisioner "$state" --frontend-client confidential --apply)"
assert_contains "$output" "No changes" "once confidential, a later run changes nothing"
assert_eq "$(query "$state" "client('basetool-frontend')['secret']")" "OPERATOR-SECRET-must-never-be-printed" "and never rewrites the secret"
rm -rf "$state"

echo "12. --frontend-client public is the rollback, and sends no secret"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
KEYCLOAK_FRONTEND_CLIENT_SECRET="OPERATOR-SECRET-must-never-be-printed" run_provisioner "$state" --frontend-client confidential --apply >/dev/null
rm -f "${state}/SECRET_SENT_BACK"
output="$(run_provisioner "$state" --frontend-client public --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the rollback applies and verifies clean"
assert_eq "$(query "$state" "client('basetool-frontend')['publicClient']")" "True" "the frontend is public again"
assert_eq "$([[ -f "${state}/SECRET_SENT_BACK" ]] && echo sent || echo none)" "none" "no secret travels on the rollback"
rm -rf "$state"

echo "13. the exchange scopes, the third-party template and the extractor's exchange scopes (REQ-XCH-005)"
state="$(mktemp -d)"
make_stub "$state" empty
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
EXCHANGE="['exchange.blueprints.read', 'exchange.blueprints.write', 'exchange.connect', 'exchange.demand.details', 'exchange.demand.read', 'exchange.drafts.blueprints', 'exchange.drafts.refinery', 'exchange.hangar.read', 'exchange.hangar.write', 'exchange.orgstock.details', 'exchange.orgstock.read', 'exchange.orgstock.write', 'exchange.orgunits.read', 'exchange.stock.read', 'exchange.stock.write']"
assert_eq "$(query "$state" "sorted(s['name'] for s in d['scopes'] if s['name'].startswith('exchange.'))")" \
  "$EXCHANGE" "all fifteen exchange scopes exist"
assert_eq "$(query "$state" "sorted({s['attributes']['include.in.token.scope'] for s in d['scopes'] if s['name'].startswith('exchange.')})")" \
  "['true']" "each puts its name in the scope claim"
assert_eq "$(query "$state" "sorted({m['config']['included.custom.audience'] for s in d['scopes'] if s['name'].startswith('exchange.') for m in d['scope_mappers'].get(s['id'], [])})")" \
  "['basetool-ingest']" "each carries the ingest audience and no other"
assert_eq "$(query "$state" "next(s for s in d['scopes'] if s['name']=='exchange.stock.write')['attributes']['consent.screen.text']")" \
  "\${xchConsentStockWrite}" "the consent text is a theme message key"
assert_eq "$(query "$state" "client('versekit') is not None")" "True" "the listed third-party client is created"
assert_eq "$(query "$state" "[client('versekit')[k] for k in ('publicClient', 'consentRequired', 'fullScopeAllowed', 'standardFlowEnabled', 'directAccessGrantsEnabled', 'implicitFlowEnabled', 'serviceAccountsEnabled')]")" \
  "[True, True, False, False, False, False, False]" "public, consent required, no full scope, no other flow"
assert_eq "$(query "$state" "[client('versekit')['attributes'][k] for k in ('oauth2.device.authorization.grant.enabled', 'dpop.bound.access.tokens', 'client.offline.session.idle.timeout', 'client.offline.session.max.lifespan', 'login_theme')]")" \
  "['true', 'true', '2592000', '7776000', 'krt-theme']" "device grant, DPoP-bound tokens, 30/90-day offline session, the Basetool theme"
assert_eq "$(query "$state" "[client('versekit')['attributes'].get(k) for k in ('client.session.idle.timeout', 'client.session.max.lifespan')]")" \
  "['2592000', '7776000']" "the online session is capped at 30/90 days too, never the realm's 180"
assert_eq "$(query "$state" "client('versekit')['name']")" "VerseKit" "the consent page names the product"
assert_eq "$(query "$state" "scope_names('default', 'versekit')")" "['basic']" "the only default scope is basic"
assert_eq "$(query "$state" "scope_names('optional', 'versekit')")" \
  "$(printf '%s' "$EXCHANGE" | sed "s/\]$/, 'offline_access']/")" "every exchange scope and offline_access are optional"
assert_eq "$(query "$state" "mappers('versekit')")" "[]" "no protocol mapper on the client"
assert_eq "$(query "$state" "sorted(set(scope_names('optional', 'basetool-sc-extractor')) & set(${EXCHANGE}))")" \
  "['exchange.blueprints.read', 'exchange.blueprints.write', 'exchange.connect', 'exchange.drafts.blueprints', 'exchange.drafts.refinery']" \
  "the extractor gets its exchange scopes as optional"
assert_eq "$(query "$state" "[client('basetool-sc-extractor')['attributes'][k] for k in ('client.offline.session.idle.timeout', 'client.offline.session.max.lifespan')]")" \
  "['2592000', '7776000']" "the extractor's offline session is 30/90 days, as the template's"
assert_eq "$(query "$state" "[client('basetool-sc-extractor')['attributes'].get(k) for k in ('client.session.idle.timeout', 'client.session.max.lifespan')]")" \
  "['2592000', '7776000']" "the extractor's online session is capped at 30/90 days too"
assert_eq "$(query "$state" "'offline_access' in scope_names('optional', 'basetool-sc-extractor')")" \
  "True" "the extractor may request offline_access"
assert_eq "$(query "$state" "scope_names('optional', 'basetool-sc-extractor')")" \
  "['exchange.blueprints.read', 'exchange.blueprints.write', 'exchange.connect', 'exchange.drafts.blueprints', 'exchange.drafts.refinery', 'offline_access']" \
  "the extractor may request nothing but its exchange scopes and offline_access"
assert_eq "$(query "$state" "[d['realm'].get(k) for k in ('oauth2DeviceCodeLifespan', 'oauth2DevicePollingInterval', 'loginTheme')]")" \
  "[600, 5, 'krt-theme']" "the device code lifespan, polling interval and login theme are pinned"
output="$(run_provisioner "$state" --apply)"
assert_contains "$output" "No changes" "a second run changes nothing"
rm -rf "$state"

echo "14. an existing third-party client loses what the template never offers"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
path = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(path.read_text(encoding="utf-8"))
vk = next(c for c in d["clients"] if c["clientId"] == "versekit")
ids = {s["name"]: s["id"] for s in d["scopes"]}
d["client_default_scopes"][vk["id"]] += [ids["profile"], ids["email"], ids["extractor-ingest"]]
vk["consentRequired"] = False
path.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state")"
assert_contains "$output" "- default scope 'profile' withheld" "the dry run plans to remove profile"
assert_contains "$output" "~ consentRequired: false -> true" "and to require consent again"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_eq "$(query "$state" "scope_names('default', 'versekit')")" "['basic']" "only basic is left as a default scope"
assert_eq "$(query "$state" "client('versekit')['consentRequired']")" "True" "consent is required again"
rm -rf "$state"

echo "16. the extractor client in its pre-migration production shape converges to exchange-only (H1)"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
path = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(path.read_text(encoding="utf-8"))
ex = next(c for c in d["clients"] if c["clientId"] == "basetool-sc-extractor")
ids = {s["name"]: s["id"] for s in d["scopes"]}
d["client_default_scopes"][ex["id"]] += [ids[n] for n in ("acr", "email", "profile", "roles", "web-origins", "extractor-ingest", "extractor-ingest-only")]
d["client_optional_scopes"][ex["id"]] += [ids[n] for n in ("address", "microprofile-jwt", "phone")]
ex["consentRequired"] = False
ex["attributes"]["dpop.bound.access.tokens"] = "false"
path.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state")"
assert_contains "$output" "- default scope 'extractor-ingest' withheld" "the dry run plans to drop the backend audience scope"
assert_contains "$output" "- default scope 'extractor-ingest-only' withheld" "and the legacy ingest scope"
assert_contains "$output" "- default scope 'profile' withheld" "and the profile claims"
assert_contains "$output" "- optional scope 'phone' withheld" "and every optional scope that is no exchange scope"
assert_contains "$output" "~ consentRequired: false -> true" "consent becomes required"
assert_contains "$output" "~ attribute dpop.bound.access.tokens: false -> 'true'" "tokens become DPoP-bound"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_eq "$(query "$state" "scope_names('default', 'basetool-sc-extractor')")" "['basic']" "only basic is left as a default scope"
assert_eq "$(query "$state" "[client('basetool-sc-extractor')['consentRequired'], client('basetool-sc-extractor')['attributes']['dpop.bound.access.tokens']]")" \
  "[True, 'true']" "consent and DPoP binding hold after the apply"
rm -rf "$state"

echo "15. a malformed third-party client list is refused before anything is read"
state="$(mktemp -d)"
make_stub "$state" empty
printf '[{"clientId": "basetool-frontend", "name": "Impostor"}]' >"${state}/bad.json"
output="$(run_provisioner "$state" --external-clients "$(to_child_path "${state}/bad.json")")"
assert_eq "$(cat "${state}/rc")" "1" "a reserved clientId exits 1"
assert_contains "$output" "reserved or listed twice" "and says why"
assert_eq "$(writes_in "$state")" "0" "nothing is written"
printf '[{"clientId": "somebody"}]' >"${state}/bad.json"
output="$(run_provisioner "$state" --external-clients "$(to_child_path "${state}/bad.json")")"
assert_contains "$output" "needs a name for the consent page" "a client without a name is refused"
rm -rf "$state"

echo "17. a stray client-level mapper on the extractor or a third-party client is removed, elsewhere reported"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
path = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(path.read_text(encoding="utf-8"))
by_id = {c["clientId"]: c["id"] for c in d["clients"]}
aud = {"included.custom.audience": "basetool-backend", "access.token.claim": "true"}
d["client_mappers"][by_id["basetool-sc-extractor"]] = [
    {"id": "m-ex-aud", "name": "backend-audience", "protocol": "openid-connect",
     "protocolMapper": "oidc-audience-mapper", "config": aud},
    {"id": "m-ex-email", "name": "email", "protocol": "openid-connect",
     "protocolMapper": "oidc-usermodel-property-mapper", "config": {"claim.name": "email"}}]
d["client_mappers"][by_id["versekit"]] = [
    {"id": "m-vk-roles", "name": "realm roles", "protocol": "openid-connect",
     "protocolMapper": "oidc-usermodel-realm-role-mapper", "config": {"claim.name": "roles"}}]
d["client_mappers"].setdefault(by_id["basetool-frontend"], []).append(
    {"id": "m-fe-extra", "name": "testing-only-mapper", "protocol": "openid-connect",
     "protocolMapper": "oidc-hardcoded-claim-mapper", "config": {"claim.name": "x"}})
path.write_text(json.dumps(d), encoding="utf-8")
'
before="$(cat "${state}/state.json")"
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "2" "the dry run plans changes and exits 2"
assert_contains "$output" "- basetool-sc-extractor: mapper 'backend-audience' (oidc-audience-mapper basetool-backend) removed (not in this client's spec)" \
  "the dry run plans to remove the extractor's backend audience mapper"
assert_contains "$output" "- basetool-sc-extractor: mapper 'email' (oidc-usermodel-property-mapper email) removed" \
  "and every other mapper on the extractor"
assert_contains "$output" "- versekit: mapper 'realm roles' (oidc-usermodel-realm-role-mapper roles) removed" \
  "and every mapper on a third-party client"
assert_not_contains "$output" "basetool-sc-extractor: mapper 'backend-audience' is not in the production shape" \
  "the extractor's mapper is a planned change, not a report"
assert_contains "$output" "basetool-frontend: mapper 'testing-only-mapper' is not in the production shape" \
  "a mapper on any other client is still only reported"
assert_eq "$(cat "${state}/state.json")" "$before" "the dry run writes nothing"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_contains "$output" "Applied. A second run reports no changes." "the verify pass finds nothing left"
assert_eq "$(query "$state" "mappers('basetool-sc-extractor'), mappers('versekit')")" "([], [])" \
  "neither the extractor nor the third-party client keeps a mapper"
assert_eq "$(query "$state" "'testing-only-mapper' in mappers('basetool-frontend')")" "True" \
  "the mapper on the frontend is kept"
output="$(run_provisioner "$state" --apply)"
assert_contains "$output" "No changes" "a second apply is empty"
assert_eq "$(writes_in "$state")" "0" "and sends no write"
rm -rf "$state"

echo "18. every member may hold an offline session: the default role and the client scope carry offline_access"
OFFLINE_TITLE="offline_access for every member"
COMPOSITE_LINE="+ default role 'default-roles-basetool': composite realm role 'offline_access' (every member may hold an offline session)"
MAPPING_LINE="+ client scope 'offline_access': realm role 'offline_access' mapped (a client with fullScopeAllowed off may then issue an offline token)"
section_first_line() { printf '%s\n' "$1" | grep -A1 -F "] ${OFFLINE_TITLE}" | tail -1; }
seed_offline() {
  STUB_STATE="$1" SEED="$2" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
if "composite" in os.environ["SEED"]:
    d["role_composites"]["r-default"].append("r-offline")
if "mapping" in os.environ["SEED"]:
    d["scope_role_mappings"]["s-offline_access"] = [{"id": "r-offline", "name": "offline_access"}]
if "no-realm-default" in os.environ["SEED"]:
    d["realm_optional_scopes"].remove("s-offline_access")
p.write_text(json.dumps(d), encoding="utf-8")
'
}
state="$(mktemp -d)"
make_stub "$state" empty
before="$(cat "${state}/state.json")"
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "2" "a realm without either item plans changes"
assert_contains "$output" "  ${COMPOSITE_LINE}" "the dry run plans offline_access as a composite of the default role"
assert_contains "$output" "  ${MAPPING_LINE}" "and the role mapped on the offline_access client scope"
assert_eq "$(cat "${state}/state.json")" "$before" "the dry run writes nothing"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_contains "$output" "Applied. A second run reports no changes." "the verify pass finds nothing left"
assert_eq "$(query "$state" "sorted(r['name'] for r in d['roles'] if r['id'] in d['role_composites']['r-default'])")" \
  "['KRT Member', 'offline_access', 'uma_authorization']" "the default role holds offline_access beside what it had"
assert_eq "$(query "$state" "[r['name'] for r in d['scope_role_mappings']['s-offline_access']]")" \
  "['offline_access']" "the offline_access client scope maps the offline_access role"
assert_contains "$(cat "${state}/bodies.log")" 'create roles/default-roles-basetool/composites [{"id": "r-offline", "name": "offline_access"}]' \
  "the composite is posted by the role's id"
assert_contains "$(cat "${state}/bodies.log")" 'create client-scopes/s-offline_access/scope-mappings/realm [{"id": "r-offline", "name": "offline_access"}]' \
  "and so is the scope mapping"
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "0" "a dry run afterwards finds the realm in shape"
assert_eq "$(section_first_line "$output")" "  in shape" "and the offline_access section in shape"
rm -rf "$state"
state="$(mktemp -d)"
make_stub "$state" empty
seed_offline "$state" mapping
output="$(run_provisioner "$state")"
assert_contains "$output" "  ${COMPOSITE_LINE}" "production before the fix, scope mapped and composite missing: the composite is planned"
assert_not_contains "$output" "$MAPPING_LINE" "and the existing scope mapping is not"
rm -rf "$state"
state="$(mktemp -d)"
make_stub "$state" empty
seed_offline "$state" composite+mapping+no-realm-default
output="$(run_provisioner "$state")"
assert_eq "$(section_first_line "$output")" "  in shape" "a realm with both and offline_access no realm default is in shape"
assert_not_contains "$output" "offline_access' (every member" "no composite line is planned"
run_provisioner "$state" --apply >/dev/null
assert_eq "$(grep -cE '^create (roles/[^/]+/composites|client-scopes/[^/]+/scope-mappings/realm)$' "${state}/calls.log" || true)" \
  "0" "and the apply sends neither write"
rm -rf "$state"

echo "19. only the exchange clients are offered offline_access; the first-party clients lose it"
WITHHELD_LINE="- optional scope 'offline_access' withheld (offline sessions are for the exchange clients only, ADR-0202 amendment 5)"
NO_OFFLINE_CLIENTS="basetool-frontend backend-service basetool-ingest-gateway grafana"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply --grafana-origin https://grafana.testing.example >/dev/null
assert_eq "$(cat "${state}/rc")" "0" "a fresh realm is built and verifies clean"
for cid in $NO_OFFLINE_CLIENTS; do
  assert_eq "$(query "$state" "'offline_access' in scope_names('optional', '${cid}') + scope_names('default', '${cid}')")" \
    "False" "a new ${cid} is not offered offline_access"
done
for cid in basetool-sc-extractor versekit; do
  assert_eq "$(query "$state" "'offline_access' in scope_names('optional', '${cid}')")" \
    "True" "${cid} keeps offline_access as an optional scope"
done
STUB_STATE="$state" CLIENTS="$NO_OFFLINE_CLIENTS" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
for cid in os.environ["CLIENTS"].split():
    uuid = next(c["id"] for c in d["clients"] if c["clientId"] == cid)
    d["client_optional_scopes"][uuid].append("s-offline_access")
p.write_text(json.dumps(d), encoding="utf-8")
'
before="$(cat "${state}/state.json")"
output="$(run_provisioner "$state" --grafana-origin https://grafana.testing.example)"
assert_eq "$(cat "${state}/rc")" "2" "a realm in production's shape plans changes"
assert_eq "$(printf '%s\n' "$output" | grep -cxF "  ${WITHHELD_LINE}" || true)" "4" \
  "the dry run withholds offline_access from each of the four first-party clients"
assert_eq "$(printf '%s\n' "$output" | sed '/^\[only on this realm/,$d' | grep -cE '^  [-+~=] ' || true)" "4" \
  "and plans nothing else"
assert_eq "$(cat "${state}/state.json")" "$before" "the dry run writes nothing"
output="$(run_provisioner "$state" --apply --grafana-origin https://grafana.testing.example)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
for cid in $NO_OFFLINE_CLIENTS; do
  assert_eq "$(query "$state" "'offline_access' in scope_names('optional', '${cid}')")" \
    "False" "${cid} no longer offers offline_access"
done
assert_eq "$(query "$state" "'offline_access' in scope_names('optional', 'basetool-sc-extractor')")" \
  "True" "the extractor still does"
assert_eq "$(query "$state" "'r-offline' in d['role_composites']['r-default']")" "True" \
  "the default role keeps offline_access"
output="$(run_provisioner "$state" --apply --grafana-origin https://grafana.testing.example)"
assert_contains "$output" "No changes" "a second apply is empty"
assert_eq "$(writes_in "$state")" "0" "and sends no write"
rm -rf "$state"

echo "20. offline_access is no realm default client scope, so a client created by hand does not inherit it"
REALM_OPTIONAL_LINE="- realm optional client scope 'offline_access' removed (a client created by hand no longer inherits it; the exchange clients name it themselves)"
REALM_DEFAULT_LINE="- realm default client scope 'offline_access' removed (a client created by hand no longer inherits it; the exchange clients name it themselves)"
state="$(mktemp -d)"
make_stub "$state" empty
before="$(cat "${state}/state.json")"
output="$(run_provisioner "$state")"
assert_contains "$output" "  ${REALM_OPTIONAL_LINE}" "the dry run removes offline_access from the realm's optional client scopes"
assert_eq "$(cat "${state}/state.json")" "$before" "the dry run writes nothing"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_eq "$(query "$state" "'s-offline_access' in d['realm_default_scopes'] + d['realm_optional_scopes']")" \
  "False" "offline_access is no realm default client scope any more"
assert_eq "$(query "$state" "sorted(s['name'] for s in d['scopes'] if s['id'] in d['realm_optional_scopes'])")" \
  "['address', 'microprofile-jwt', 'organization', 'phone']" "the other realm optional scopes stay"
for cid in basetool-sc-extractor versekit; do
  assert_eq "$(query "$state" "'offline_access' in scope_names('optional', '${cid}')")" \
    "True" "${cid} still carries offline_access explicitly"
done
output="$(run_provisioner "$state")"
assert_eq "$(cat "${state}/rc")" "0" "a dry run afterwards finds the realm in shape"
assert_eq "$(section_first_line "$output")" "  in shape" "and the offline_access section in shape"
rm -rf "$state"
state="$(mktemp -d)"
make_stub "$state" empty
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
d["realm_optional_scopes"].remove("s-offline_access")
d["realm_default_scopes"].append("s-offline_access")
p.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state")"
assert_contains "$output" "  ${REALM_DEFAULT_LINE}" "as a realm DEFAULT client scope it is planned away too"
run_provisioner "$state" --apply >/dev/null
assert_eq "$(cat "${state}/rc")" "0" "and the apply verifies clean"
assert_eq "$(query "$state" "'s-offline_access' in d['realm_default_scopes']")" "False" "it is gone from the realm defaults"
rm -rf "$state"

echo "21. the testing-only seed client exists only with --seed-client and never for a production origin"
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
assert_eq "$(query "$state" "client('basetool-seed') is None")" "True" "without --seed-client no seed client is created"
output="$(run_provisioner "$state" --seed-client --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply with --seed-client verifies clean"
assert_eq "$(query "$state" "[client('basetool-seed')['directAccessGrantsEnabled'], client('basetool-seed')['serviceAccountsEnabled'], client('basetool-seed')['publicClient']]")" \
  "[True, True, False]" "the seed client is confidential with password grant and a service account"
assert_eq "$(query "$state" "client('basetool-frontend')['directAccessGrantsEnabled']")" "False" \
  "the frontend client keeps the password grant off"
assert_contains "$(query "$state" "scope_names('default', 'basetool-seed')")" "'extractor-ingest'" \
  "the seed client's tokens carry the backend audience"
assert_contains "$output" "SEED_CLIENT_SECRET" "the operator is told where the seed secret goes"
assert_not_contains "$output" "re-render env.d" "the seed secret goes into no host .env"
output="$(run_provisioner "$state")"
assert_contains "$output" "client 'basetool-seed' is not in the production shape" \
  "a later run without --seed-client reports the seed client and keeps it"
assert_eq "$(query "$state" "client('basetool-seed') is None")" "False" "the provisioner never deletes it"
: >"${state}/calls.log"
rc=0
KCADM_STUB_STATE="$state" "$PYTHON" "$PROVISIONER" --realm basetool --public-origin https://profit-base.online \
  --seed-client --kcadm-command "${PYTHON} $(to_child_path "${state}/kcadm_stub.py")" >/dev/null 2>&1 || rc=$?
assert_eq "$([[ $rc -ne 0 ]] && echo refused || echo accepted)" "refused" "--seed-client refuses a production origin"
assert_eq "$(wc -l <"${state}/calls.log" | tr -d ' ')" "0" "and refuses before kcadm is called"
rm -rf "$state"

echo "22. a browser-extension client gets its Chromium origins, * for Firefox, and 14 idle days (ADR-0303)"
state="$(mktemp -d)"
make_stub "$state" empty
printf '[{"clientId": "hangar-exporter", "name": "Hangar-Exporter", "extensionOrigins": ["chrome-extension://abcdefghijklmnopabcdefghijklmnop"], "firefoxExtension": true}, {"clientId": "desktop-only", "name": "Desktop Only"}]' >"${state}/clients.json"
output="$(run_provisioner "$state" --apply --external-clients "$(to_child_path "${state}/clients.json")")"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_eq "$(query "$state" "sorted(client('hangar-exporter')['webOrigins'])")" \
  "['*', 'chrome-extension://abcdefghijklmnopabcdefghijklmnop']" "the extension client allows its origins"
assert_eq "$(query "$state" "client('desktop-only')['webOrigins']")" "[]" "a desktop client keeps no Web Origin"
assert_eq "$(query "$state" "[client('hangar-exporter')['attributes'][k] for k in ('client.offline.session.idle.timeout', 'client.session.idle.timeout')]")" \
  "['1209600', '1209600']" "the extension client's sessions end after 14 idle days"
assert_eq "$(query "$state" "[client('desktop-only')['attributes'][k] for k in ('client.offline.session.idle.timeout', 'client.session.idle.timeout')]")" \
  "['2592000', '2592000']" "a desktop client keeps 30 idle days"
assert_eq "$(query "$state" "client('hangar-exporter')['attributes']['client.offline.session.max.lifespan']")" \
  "7776000" "the extension client keeps the 90-day maximum"
output="$(run_provisioner "$state" --apply --external-clients "$(to_child_path "${state}/clients.json")")"
assert_contains "$output" "No changes" "a second run changes nothing"
printf '[{"clientId": "hangar-exporter", "name": "Hangar-Exporter", "extensionOrigins": ["https://profit-base.online"]}]' >"${state}/bad.json"
output="$(run_provisioner "$state" --external-clients "$(to_child_path "${state}/bad.json")")"
assert_eq "$(cat "${state}/rc")" "1" "a web origin in extensionOrigins exits 1"
assert_contains "$output" "chrome-extension://<32 letters a-p>" "and says why"
printf '[{"clientId": "hangar-exporter", "name": "Hangar-Exporter", "firefoxExtension": "yes"}]' >"${state}/bad.json"
output="$(run_provisioner "$state" --external-clients "$(to_child_path "${state}/bad.json")")"
assert_contains "$output" "needs a true or false firefoxExtension" "a non-boolean firefoxExtension is refused"
rm -rf "$state"

echo
if [[ $tests_failed -gt 0 ]]; then
  echo "FAILED: ${tests_failed} of ${tests_run} assertions"
  exit 1
fi
echo "OK: ${tests_run} assertions"
