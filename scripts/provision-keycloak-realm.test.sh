#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Regression tests for scripts/provision-keycloak-realm.py.
#
# Drives the provisioner against a stub that impersonates kcadm and keeps a small Keycloak realm in
# a JSON file, so the suite runs in seconds with no Docker, no Keycloak and no network. The stub is
# stateful on purpose: every write is reflected in what the next read returns, so "a second run
# changes nothing" is a real assertion rather than a frozen fixture agreeing with itself.
#
# Three behaviours of the real server are modelled because the script's correctness depends on them:
#   * creating a client attaches the realm's DEFAULT client scopes, whatever the payload says;
#   * while `krt-mobile-dpop-policy` is attached, `update clients/<id>` of a client holding the
#     marker role is refused with `invalid_client_metadata` (ADR-0131, experiment E1);
#   * a confidential client gets a generated secret, which the stub makes recognisable so the
#     suite can assert it is never printed and never sent back.
#
# Usage:
#   scripts/provision-keycloak-realm.test.sh

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

# Writes the stub and an initial realm into state dir $1. $2 selects the starting realm:
#   empty    - built-ins only, one foreign client policy and profile, one app role missing
#   testing  - the shape the testing realm had on 2026-09-22: frontend and backend-service only,
#              no audience scopes, plus objects production does not have
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
roles = [{"id": "r-default", "name": "default-roles-iri"},
         {"id": "r-krt", "name": "KRT Member"}, {"id": "r-off", "name": "Officer"},
         {"id": "r-adm", "name": "Admin"}, {"id": "r-bem", "name": "Bank Employee"}]
realm_management = {"id": "c-rm", "clientId": "realm-management", "attributes": {}}
account = {"id": "c-account", "clientId": "account", "attributes": {}}

realm = {
    "realm": "iri", "revokeRefreshToken": True, "refreshTokenMaxReuse": 0,
    "accessTokenLifespan": 300, "ssoSessionIdleTimeout": 2592000,
    "ssoSessionMaxLifespan": 15552000, "offlineSessionMaxLifespanEnabled": False,
    "offlineSessionMaxLifespan": 5184000, "clientSessionIdleTimeout": 0,
    "clientSessionMaxLifespan": 0, "clientOfflineSessionIdleTimeout": 0,
    "clientOfflineSessionMaxLifespan": 0, "oauth2DeviceCodeLifespan": 600,
}
data = {
    "realm": realm,
    "roles": roles,
    "clients": [realm_management, account],
    "client_roles": {"c-rm": [{"id": f"rm-{n}", "name": n} for n in
                              ("manage-users", "view-realm", "view-users", "manage-clients")]},
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

  # The stub is Python rather than a shell script: the provisioner spawns it through subprocess,
  # and a shebanged shell script is not directly executable on a Windows developer machine.
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
    if path == "default-default-client-scopes":
        out([{"id": s, "name": scope_by_id(s)["name"]} for s in data["realm_default_scopes"]])
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
        if rep.get("publicClient") is False:
            rep["secret"] = os.environ.get("STUB_SECRET", "STUB-GENERATED-SECRET-must-never-be-printed")
        data["clients"].append(rep)
        # Like Keycloak: the realm defaults, not the payload's lists.
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
        data[parts[1]] = body
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

# The stub's path goes to a CHILD python through --kcadm-command, so it has to be a path that child
# can open. On Windows the interpreter is native while this shell is MSYS, and the two disagree on
# `/tmp`; `cygpath -m` yields C:/Users/... which both accept. On Linux and in CI there is no
# cygpath and the path passes through untouched (the same helper as the mobile provisioner's tests).
to_child_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# Runs the provisioner against the stub in state dir $1; extra arguments pass through. Echoes the
# combined output; the exit code is written to ${state}/rc so callers can keep `set -e`.
run_provisioner() {
  local state="$1"; shift
  local rc=0
  : >"${state}/calls.log"
  KCADM_STUB_STATE="$state" "$PYTHON" "$PROVISIONER" --realm iri --public-origin "$ORIGIN" \
    --kcadm-command "${PYTHON} $(to_child_path "${state}/kcadm_stub.py")" "$@" 2>&1 || rc=$?
  echo "$rc" >"${state}/rc"
}

# Evaluates a Python expression against the stub's state; `d` is the realm, `client(id)` finds a
# client by clientId, `scope_names(kind, id)` lists a client's scopes. Prints the result.
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

# ---------------------------------------------------------------------------
echo "1. a dry run writes nothing and says what it would do"
# ---------------------------------------------------------------------------
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
assert_contains "$output" "IRI_INGEST_SERVICE_ACCOUNT_CLIENT_SECRET" "the plan says which .env value needs the new secret"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "2. from an empty realm, --apply builds the production shape"
# ---------------------------------------------------------------------------
state="$(mktemp -d)"
make_stub "$state" empty
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
for cid in basetool-frontend backend-service basetool-ingest-gateway basetool-sc-extractor basetool-android; do
  assert_eq "$(query "$state" "client('${cid}') is not None")" "True" "client ${cid} exists"
done
assert_eq "$(query "$state" "client('grafana') is None")" "True" "grafana is left alone without --grafana-origin"
assert_eq "$(query "$state" "sorted(m['config']['included.custom.audience'] for s in d['scopes'] for m in d['scope_mappers'].get(s['id'], []))")" \
  "['basetool-backend', 'basetool-ingest']" "both audience mappers exist"
assert_eq "$(query "$state" "next(s for s in d['scopes'] if s['name']=='extractor-ingest-only')['attributes']['include.in.token.scope']")" \
  "true" "extractor-ingest-only puts its name in the scope claim"
assert_eq "$(query "$state" "next(s for s in d['scopes'] if s['name']=='extractor-ingest')['attributes']['include.in.token.scope']")" \
  "false" "extractor-ingest does not"
assert_eq "$(query "$state" "scope_names('default', 'basetool-frontend')")" \
  "['email', 'extractor-ingest', 'profile', 'roles', 'web-origins']" \
  "the frontend carries extractor-ingest and NOT extractor-ingest-only (REQ-INGEST-011)"
assert_eq "$(query "$state" "'extractor-ingest-only' in scope_names('default', 'basetool-sc-extractor')")" \
  "True" "the extractor carries the exclusive scope"
assert_eq "$(query "$state" "scope_names('optional', 'basetool-android')")" \
  "['address', 'microprofile-jwt', 'organization', 'phone']" "offline_access is withheld from the app"
assert_eq "$(query "$state" "sorted(client('basetool-frontend')['redirectUris'])")" \
  "['https://testing.example/*', 'https://testing.example/login/oauth2/code/keycloak']" \
  "the frontend's redirect URIs come from --public-origin"
assert_eq "$(query "$state" "client('basetool-android')['redirectUris']")" \
  "['https://testing.example/app/callback']" "the app's callback comes from --public-origin"
assert_eq "$(query "$state" "client('basetool-frontend')['attributes']['post.logout.redirect.uris']")" \
  "https://testing.example/*##https://testing.example" "the post-logout list comes from --public-origin"
assert_not_contains "$(cat "${state}/state.json")" "profit-base.online" "no production hostname is written into another realm"
assert_eq "$(query "$state" "sorted(r['name'] for r in d['client_scope_mappings'][client('basetool-android')['id']])")" \
  "['Admin', 'Bank Employee', 'Bank Management', 'KRT Member', 'Officer']" "the app's realm-role scope is exactly the member list"
assert_eq "$(query "$state" "'Bank Management' in [r['name'] for r in d['roles']]")" "True" "a missing application role is created"
assert_eq "$(query "$state" "mappers('basetool-frontend')")" \
  "['description', 'discord_guild_nickname', 'discord_user_id', 'rank', 'sub']" "the frontend's claim mappers exist"
assert_eq "$(query "$state" "[p['name'] for p in d['policies']['policies']]")" \
  "['someone-elses-policy', 'krt-mobile-dpop-policy']" "the DPoP policy is attached and a foreign policy survives"
assert_eq "$(query "$state" "[p['name'] for p in d['profiles']['profiles']]")" \
  "['someone-elses-profile', 'krt-mobile-dpop']" "the DPoP profile is merged and a foreign profile survives"
assert_eq "$(query "$state" "sorted(r['name'] for u in d['users'].values() if u['client']==client('backend-service')['id'] for rid in u['client_roles'].get('c-rm', []) for r in d['client_roles']['c-rm'] if r['id']==rid)")" \
  "['manage-users', 'view-realm', 'view-users']" "backend-service's service account holds its realm-management roles"
assert_eq "$(query "$state" "d['realm']['revokeRefreshToken'], d['realm']['offlineSessionMaxLifespan']")" \
  "(False, 7776000)" "the realm token settings match production"
assert_not_contains "$output" "$SECRET_MARKER" "no generated client secret is printed"
assert_contains "$output" "-> Credentials" "the operator is told where to read the secret"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "3. a second --apply changes nothing"
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
echo "4. an Android edit detaches the DPoP policy first and re-attaches it last"
# ---------------------------------------------------------------------------
# Keycloak refuses every update of the client while the policy is attached; the stub does too, so a
# wrong order fails the apply outright rather than only an ordering assertion.
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply >/dev/null
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
c = next(c for c in d["clients"] if c["clientId"] == "basetool-android")
c["attributes"]["pkce.code.challenge.method"] = ""
d["policies"]["policies"].append({"name": "a-later-policy", "enabled": True, "conditions": [], "profiles": []})
p.write_text(json.dumps(d), encoding="utf-8")
'
android_id="$(query "$state" "client('basetool-android')['id']")"
output="$(run_provisioner "$state" --apply)"
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds against a policy-frozen client"
detach_line="$(grep -n '^update client-policies/policies' "${state}/calls.log" | head -1 | cut -d: -f1)"
client_line="$(grep -n "^update clients/${android_id}\$" "${state}/calls.log" | head -1 | cut -d: -f1)"
attach_line="$(grep -n '^update client-policies/policies' "${state}/calls.log" | tail -1 | cut -d: -f1)"
if [[ -n "$detach_line" && -n "$client_line" && -n "$attach_line" \
      && "$detach_line" -lt "$client_line" && "$client_line" -lt "$attach_line" ]]; then
  pass "detach < client update < re-attach"
else
  fail "detach < client update < re-attach" \
    "detach ${detach_line:-none}, client ${client_line:-none}, attach ${attach_line:-none}"
fi
assert_eq "$(query "$state" "sorted(p['name'] for p in d['policies']['policies'])")" \
  "['a-later-policy', 'krt-mobile-dpop-policy', 'someone-elses-policy']" "every foreign policy survives the detach/re-attach"
assert_eq "$(query "$state" "client('basetool-android')['attributes']['pkce.code.challenge.method']")" "S256" "the drift is corrected"
# A change that does NOT touch the frozen client must not detach anything.
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
next(c for c in d["clients"] if c["clientId"] == "basetool-frontend")["directAccessGrantsEnabled"] = True
p.write_text(json.dumps(d), encoding="utf-8")
'
run_provisioner "$state" --apply >/dev/null
assert_eq "$(grep -c '^update client-policies/policies' "${state}/calls.log" || true)" "0" "an unrelated client edit leaves the policy attached"
# A realm role added to the app's scope by hand is taken back (REQ-SEC-035) — the one scope this
# script converges in both directions — while the policy stays attached around it.
STUB_STATE="$state" "$PYTHON" -c '
import json, os, pathlib
p = pathlib.Path(os.environ["STUB_STATE"]) / "state.json"
d = json.loads(p.read_text(encoding="utf-8"))
d["roles"].append({"id": "r-brl", "name": "Bereichsleitung"})
uuid = next(c for c in d["clients"] if c["clientId"] == "basetool-android")["id"]
d["client_scope_mappings"][uuid].append({"id": "r-brl", "name": "Bereichsleitung"})
p.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state" --apply)"
assert_contains "$output" "take back Bereichsleitung" "the plan names the hand-added role"
assert_eq "$(query "$state" "sorted(r['name'] for r in d['client_scope_mappings'][client('basetool-android')['id']])")" \
  "['Admin', 'Bank Employee', 'Bank Management', 'KRT Member', 'Officer']" "a hand-added realm role is taken back off the app's scope"
assert_eq "$(query "$state" "'krt-mobile-dpop-policy' in [p['name'] for p in d['policies']['policies']]")" "True" "and the policy is attached afterwards"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "5. objects only the target realm has are reported, never deleted"
# ---------------------------------------------------------------------------
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
assert_eq "$(query "$state" "client('backend-service')['secret']")" "$SECRET_MARKER" "the existing secret is untouched"
if [[ -e "${state}/SECRET_SENT_BACK" ]]; then fail "no client secret is sent back in an update"; else
  pass "no client secret is sent back in an update"; fi
assert_not_contains "$output" "$SECRET_MARKER" "the existing secret is never printed"
assert_not_contains "$(cat "${state}/bodies.log")" "$SECRET_MARKER" "the existing secret is in no payload"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "6. service-account roles an identity cannot read are handed to the operator"
# ---------------------------------------------------------------------------
# The provisioning client deliberately lacks manage-users/view-users, so role mappings answer 403.
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

# ---------------------------------------------------------------------------
echo "7. --grafana-origin manages the grafana client; a malformed origin is refused"
# ---------------------------------------------------------------------------
state="$(mktemp -d)"
make_stub "$state" empty
run_provisioner "$state" --apply --grafana-origin https://grafana.testing.example >/dev/null
assert_eq "$(query "$state" "client('grafana')['redirectUris']")" \
  "['https://grafana.testing.example/login/generic_oauth']" "grafana's redirect comes from --grafana-origin"
output="$(KCADM_STUB_STATE="$state" "$PYTHON" "$PROVISIONER" --public-origin "https://testing.example/" \
  --kcadm-command "false" 2>&1 || true)"
assert_contains "$output" "no path and no trailing slash" "a trailing slash is refused before anything is read"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "8. the three retirements of 2026-09-22 converge away from a realm in the old production shape"
# ---------------------------------------------------------------------------
# Owner decisions (ADR-0202 amendment): the extractor's code flow and loopback redirects, the two
# ingest scopes on the app, and the frontend's compose-internal origin. They are the only entries
# besides REQ-SEC-035 / ADR-0131 that converge in BOTH directions, so the plan against a realm
# that still has them must remove exactly these and nothing else.
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
app = by_id["basetool-android"]["id"]
d["client_default_scopes"][app] += [ids["extractor-ingest"], ids["extractor-ingest-only"]]
p.write_text(json.dumps(d), encoding="utf-8")
'
output="$(run_provisioner "$state")"
# Only the plan: the report section that follows it uses the same `  - ` bullet.
planned="$(printf '%s\n' "$output" | sed '/^\[only on this realm/,$d' | grep -E '^  [-+~=] ' | sort)"
expected="$(printf '%s\n' \
  "  + attach policy 'krt-mobile-dpop-policy' (merged by name; every other policy carried forward)" \
  "  - default scope 'extractor-ingest' withheld (ADR-0131 / ingest scopes retired 2026-09-22)" \
  "  - default scope 'extractor-ingest-only' withheld (ADR-0131 / ingest scopes retired 2026-09-22)" \
  "  - detach 'krt-mobile-dpop-policy' (1 other policy(ies) carried forward)" \
  "  - redirect URI http://127.0.0.1/* withheld (unused authorization-code flow retired 2026-09-22)" \
  "  - redirect URI http://frontend:18081/* withheld (compose-internal origin retired 2026-09-22)" \
  "  - redirect URI http://localhost/* withheld (unused authorization-code flow retired 2026-09-22)" \
  "  - web origin http://frontend:18081 withheld (compose-internal origin retired 2026-09-22)" \
  "  ~ standardFlowEnabled: true -> false" | sort)"
assert_eq "$planned" "$expected" "the plan removes exactly the retired entries (and detaches for the app's scopes)"
assert_not_contains "$output" "is not in the production shape" "a retired entry is removed, not merely reported"
run_provisioner "$state" --apply >/dev/null
assert_eq "$(cat "${state}/rc")" "0" "the apply succeeds and verifies clean"
assert_eq "$(query "$state" "client('basetool-sc-extractor')['standardFlowEnabled'], client('basetool-sc-extractor')['redirectUris']")" \
  "(False, [])" "the extractor has no code flow and no redirect URI"
assert_eq "$(query "$state" "[n for n in ('extractor-ingest', 'extractor-ingest-only') if n in scope_names('default', 'basetool-android')]")" \
  "[]" "the app carries neither ingest scope"
assert_eq "$(query "$state" "[u for u in client('basetool-frontend')['redirectUris'] + client('basetool-frontend')['webOrigins'] if 'frontend:18081' in u]")" \
  "[]" "the frontend's compose-internal origin is gone"
assert_eq "$(query "$state" "scope_names('default', 'basetool-sc-extractor')")" \
  "['acr', 'basic', 'email', 'extractor-ingest', 'extractor-ingest-only', 'profile', 'roles', 'web-origins']" \
  "the extractor keeps both ingest scopes"
assert_eq "$(query "$state" "'krt-mobile-dpop-policy' in [p['name'] for p in d['policies']['policies']]")" "True" "the DPoP policy is attached again"
output="$(run_provisioner "$state" --apply)"
assert_contains "$output" "No changes" "a second apply is empty"
assert_eq "$(writes_in "$state")" "0" "and sends no write"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "9. without --frontend-client the frontend's client type is never touched (ADR-0001)"
# ---------------------------------------------------------------------------
# The rollout is the owner's. A run in between -- or after it -- must neither flip production's
# frontend to confidential nor flip it back to public.
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

# ---------------------------------------------------------------------------
echo "10. --frontend-client confidential without the secret in the environment refuses"
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
echo "11. --frontend-client confidential switches the client with the operator's secret, once"
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
echo "12. --frontend-client public is the rollback, and sends no secret"
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
echo
if [[ $tests_failed -gt 0 ]]; then
  echo "FAILED: ${tests_failed} of ${tests_run} assertions"
  exit 1
fi
echo "OK: ${tests_run} assertions"
