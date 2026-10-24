#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENAMER="${SCRIPT_DIR}/rename-keycloak-realm.py"

if [[ ! -f "$RENAMER" ]]; then
  echo "FATAL: ${RENAMER} not found" >&2
  exit 1
fi

PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" > /dev/null 2>&1 || { echo "FATAL: ${PYTHON} not found" >&2; exit 1; }

tests_run=0
tests_failed=0
SECRET_MARKER="STUB-CLIENT-SECRET-must-never-be-printed"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rename-realm-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

to_child_path() {
  if command -v cygpath > /dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

pass() { tests_run=$((tests_run + 1)); printf '  ok   %s\n' "$1"; }
fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  printf '  FAIL %s\n' "$1"
  if [[ $# -gt 1 ]]; then printf '       %s\n' "$2"; fi
  return 0
}

assert_eq() {
  if [[ "$1" == "$2" ]]; then pass "$3"; else fail "$3" "expected [$2], got [$1]"; fi
}
assert_contains() {
  if [[ "$1" == *"$2"* ]]; then pass "$3"; else fail "$3" "[$2] not found in: $1"; fi
}
assert_not_contains() {
  if [[ "$1" != *"$2"* ]]; then pass "$3"; else fail "$3" "[$2] found in: $1"; fi
}

cat > "${WORK}/kcadm_stub.py" <<'STUB'
"""A small, stateful kcadm impersonator for the realm rename.

Realms, roles and clients hang off the realm's internal id, as in Keycloak, so a rename moves only
the name. It fails loudly on every call shape the script does not use.
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
realm_arg = argv[argv.index("-r") + 1] if "-r" in argv else None
query = {}
for i, arg in enumerate(argv):
    if arg == "-q":
        key, _, value = argv[i + 1].partition("=")
        query[key] = value
body = json.loads(sys.stdin.read() or "null") if "-f" in argv else None

with (state_dir / "calls.log").open("a", encoding="utf-8", newline="") as log:
    log.write(f"{verb} {path} -r {realm_arg}\n")
if body is not None:
    with (state_dir / "bodies.log").open("a", encoding="utf-8", newline="") as sink:
        sink.write(f"{verb} {path} -r {realm_arg} {json.dumps(body, sort_keys=True)}\n")


def save():
    path_file.write_text(json.dumps(data, indent=2), encoding="utf-8")


def fail(message):
    sys.stderr.write(message + "\n")
    sys.exit(1)


def out(value):
    sys.stdout.write(json.dumps(value))
    sys.exit(0)


if os.environ.get("KCADM_STUB_RELOGIN"):
    data["session"]["valid"] = True
    save()
if not data["session"]["valid"]:
    fail("HTTP 401 Unauthorized: invalid token issuer")

realms = data["realms"]


def realm_id(name):
    if name not in realms:
        fail(f"Realm not found: {name}")
    return realms[name]["id"]


def role_by_id(rid):
    for roles in data["roles"].values():
        for role in roles:
            if role["id"] == rid:
                return role
    fail(f"Could not find role {rid}")


def realm_rep(name):
    entry = realms[name]
    default = role_by_id(entry["defaultRoleId"])
    return {"id": entry["id"], "realm": name, "enabled": True,
            "defaultRole": {"id": default["id"], "name": default["name"]}}


parts = path.split("/")
if verb == "get":
    if parts[0] == "realms" and len(parts) == 2:
        if parts[1] not in realms:
            fail(f"Resource not found for url: https://stub/admin/realms/{parts[1]}")
        out(realm_rep(parts[1]))
    if path == "roles":
        out(list(data["roles"][realm_id(realm_arg)]))
    if parts[0] == "roles-by-id":
        realm_id(realm_arg)
        out(role_by_id(parts[1]))
    if parts[0] == "roles" and len(parts) == 2:
        for role in data["roles"][realm_id(realm_arg)]:
            if role["name"] == parts[1]:
                out(role)
        fail(f"Resource not found for url: https://stub/admin/realms/{realm_arg}/roles/{parts[1]}")
    if path == "clients":
        found = list(data["clients"][realm_id(realm_arg)])
        if "clientId" in query:
            found = [c for c in found if c["clientId"] == query["clientId"]]
        out(found)
    fail(f"stub: unexpected get {path}")

if verb == "update":
    if parts[0] == "realms" and len(parts) == 2:
        if parts[1] not in realms:
            fail(f"Resource not found for url: https://stub/admin/realms/{parts[1]}")
        if data["flags"].get("conflict_on_rename"):
            fail("HTTP 409 Conflict")
        new = body.get("realm", parts[1])
        if new != parts[1]:
            if new in realms:
                fail("HTTP 409 Conflict: realm exists")
            realms[new] = realms.pop(parts[1])
            if data["session"]["stale_after_rename"] and data["session"]["realm"] == parts[1]:
                data["session"]["valid"] = False
        save()
        sys.exit(0)
    if parts[0] == "roles-by-id":
        realm_id(realm_arg)
        role = role_by_id(parts[1])
        if data["flags"].get("reject_role_rename"):
            fail("HTTP 400 Bad Request")
        if not data["flags"].get("ignore_role_rename"):
            role.clear()
            role.update({"id": parts[1], **body})
        if data["flags"].get("swap_default_role"):
            realms[realm_arg]["defaultRoleId"] = "r-other"
        save()
        sys.exit(0)
    if parts[0] == "clients":
        realm_id(realm_arg)
        client = next((c for c in data["clients"][realms[realm_arg]["id"]] if c["id"] == parts[1]), None)
        if client is None:
            fail(f"Could not find client {parts[1]}")
        client.update(body)
        save()
        sys.exit(0)
    fail(f"stub: unexpected update {path}")

fail(f"stub: unexpected verb {verb}")
STUB

seed() {
  local state="$1" flavour="$2"
  mkdir -p "$state"
  cp "${WORK}/kcadm_stub.py" "${state}/kcadm_stub.py"
  : > "${state}/calls.log"
  : > "${state}/bodies.log"
  STUB_FLAVOUR="$flavour" STUB_SECRET="$SECRET_MARKER" STUB_STATE="$state" "$PYTHON" - <<'SEED'
import json
import os
import pathlib

state = pathlib.Path(os.environ["STUB_STATE"])
flavour = os.environ["STUB_FLAVOUR"]
secret = os.environ["STUB_SECRET"]

roles = [
    {"id": "r-default", "name": "default-roles-iri", "description": "role_default-roles"},
    {"id": "r-other", "name": "something-else"},
    {"id": "r-krt", "name": "KRT Member"},
]
clients = [
    {"id": "c-fe", "clientId": "basetool-frontend", "name": "IRIDIUM Basetool Frontend",
     "description": "Frontend Application for IRIDIUM Basetool", "secret": secret,
     "redirectUris": ["https://h/*"]},
    {"id": "c-be", "clientId": "backend-service", "name": "IRIDIUM Basetool Backend",
     "description": "Backend", "secret": secret},
]
data = {
    "realms": {"iri": {"id": "realm-1", "defaultRoleId": "r-default"}},
    "roles": {"realm-1": roles},
    "clients": {"realm-1": clients},
    "session": {"realm": "iri", "valid": True, "stale_after_rename": False},
    "flags": {},
}
if flavour == "renamed-realm-only":
    data["realms"] = {"basetool": data["realms"]["iri"]}
    data["session"]["realm"] = "basetool"
if flavour == "renamed-realm-and-role":
    data["realms"] = {"basetool": data["realms"]["iri"]}
    data["session"]["realm"] = "basetool"
    roles[0]["name"] = "default-roles-basetool"
if flavour == "done":
    data["realms"] = {"basetool": data["realms"]["iri"]}
    data["session"]["realm"] = "basetool"
    roles[0]["name"] = "default-roles-basetool"
    clients[0]["name"] = "Profit Basetool Frontend"
    clients[0]["description"] = "Frontend Application for Profit Basetool"
if flavour == "both-realms":
    data["realms"]["basetool"] = {"id": "realm-2", "defaultRoleId": "r-default"}
    data["roles"]["realm-2"] = []
    data["clients"]["realm-2"] = []
if flavour == "no-realm":
    data["realms"] = {}
if flavour == "role-name-taken":
    roles.append({"id": "r-taken", "name": "default-roles-basetool"})
if flavour == "odd-default-role":
    roles[0]["name"] = "default-roles-something"
if flavour == "no-client":
    clients.pop(0)
if flavour == "stale-session":
    data["session"]["stale_after_rename"] = True
if flavour == "ignored-role-rename":
    data["flags"]["ignore_role_rename"] = True
if flavour == "swapped-default-role":
    data["flags"]["swap_default_role"] = True
if flavour == "role-rename-rejected":
    data["realms"] = {"basetool": data["realms"]["iri"]}
    data["session"]["realm"] = "basetool"
    data["flags"]["reject_role_rename"] = True
if flavour == "conflict":
    data["flags"]["conflict_on_rename"] = True
(state / "state.json").write_text(json.dumps(data, indent=2), encoding="utf-8")
SEED
}

run_renamer() {
  local state="$1"
  shift
  local rc=0
  : > "${state}/calls.log"
  : > "${state}/bodies.log"
  KCADM_STUB_RELOGIN="${RELOGIN:-}" KCADM_STUB_STATE="$state" "$PYTHON" "$RENAMER" \
    --kcadm-command "${PYTHON} $(to_child_path "${state}/kcadm_stub.py")" "$@" > "${state}/out" 2>&1 || rc=$?
  echo "$rc" > "${state}/rc"
}

query() {
  local state="$1" expr="$2"
  STUB_STATE="$state" EXPR="$expr" "$PYTHON" -c '
import json, os, pathlib
d = json.loads((pathlib.Path(os.environ["STUB_STATE"]) / "state.json").read_text(encoding="utf-8"))
def client(cid, realm="basetool"):
    rid = d["realms"][realm]["id"]
    return next(c for c in d["clients"][rid] if c["clientId"] == cid)
def role(rid):
    return next(r for roles in d["roles"].values() for r in roles if r["id"] == rid)
print(eval(os.environ["EXPR"]))
'
}

writes_in() {
  grep -cE '^update ' "$1/calls.log" || true
}

rc_of() { cat "$1/rc"; }
out_of() { cat "$1/out"; }

echo "== check mode =="

state="${WORK}/s1"
seed "$state" prod
before="$(cat "${state}/state.json")"
run_renamer "$state"
assert_eq "$(rc_of "$state")" "2" "a check with pending changes exits 2"
assert_eq "$(writes_in "$state")" "0" "no update reaches kcadm"
assert_eq "$(cat "${state}/state.json")" "$before" "the realm is byte-for-byte unchanged"
assert_contains "$(out_of "$state")" "~ rename realm 'iri' -> 'basetool'" "the plan names the realm rename"
assert_contains "$(out_of "$state")" "~ rename default role 'default-roles-iri' -> 'default-roles-basetool' (id r-default)" "the plan names the role rename and its id"
assert_contains "$(out_of "$state")" "client 'basetool-frontend' name -> 'Profit Basetool Frontend'" "the plan names the client name"
assert_contains "$(out_of "$state")" "client 'basetool-frontend' description -> 'Frontend Application for Profit Basetool'" "the plan names the client description"
assert_not_contains "$(out_of "$state")" "backend-service" "the plan does not touch another client"
assert_not_contains "$(out_of "$state")" "$SECRET_MARKER" "no client secret is printed"
assert_eq "$([[ -s "${state}/calls.log" ]] && echo yes || echo no)" "yes" "the stub saw the read calls"
run_renamer "$state" --check
assert_eq "$(rc_of "$state")" "2" "--check is the same as the default"
assert_eq "$(writes_in "$state")" "0" "--check writes nothing either"
run_renamer "$state" --check --apply
assert_eq "$(rc_of "$state")" "2" "--check together with --apply is a usage error (argparse exit 2)"
assert_eq "$(writes_in "$state")" "0" "and writes nothing"
assert_contains "$(out_of "$state")" "not allowed with argument" "argparse says why"

echo "== apply =="

state="${WORK}/s2"
seed "$state" prod
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "0" "the apply succeeds and verifies"
assert_eq "$(query "$state" "sorted(d['realms'])")" "['basetool']" "the realm exists under the new name only"
assert_eq "$(query "$state" "d['realms']['basetool']['id']")" "realm-1" "the realm keeps its internal id"
assert_eq "$(query "$state" "role('r-default')['name']")" "default-roles-basetool" "the default role is renamed"
assert_eq "$(query "$state" "d['realms']['basetool']['defaultRoleId']")" "r-default" "and is still the realm's default role"
assert_eq "$(query "$state" "role('r-default')['description']")" "role_default-roles" "the role keeps its description"
assert_eq "$(query "$state" "client('basetool-frontend')['name']")" "Profit Basetool Frontend" "the client name is relabelled"
assert_eq "$(query "$state" "client('basetool-frontend')['description']")" "Frontend Application for Profit Basetool" "the client description is relabelled"
assert_eq "$(query "$state" "client('basetool-frontend')['secret']")" "$SECRET_MARKER" "the client secret is untouched"
assert_eq "$(query "$state" "client('backend-service')['name']")" "IRIDIUM Basetool Backend" "another client is untouched"
assert_eq "$(grep -E '^update ' "${state}/calls.log" | tr '\n' ';')" "update realms/iri -r None;update roles-by-id/r-default -r basetool;update clients/c-fe -r basetool;" "three writes, in this order, the later ones under the new realm name"
assert_eq "$(cat "${state}/bodies.log")" "update realms/iri -r None {\"realm\": \"basetool\"}
update roles-by-id/r-default -r basetool {\"description\": \"role_default-roles\", \"name\": \"default-roles-basetool\"}
update clients/c-fe -r basetool {\"description\": \"Frontend Application for Profit Basetool\", \"name\": \"Profit Basetool Frontend\"}" "the realm and client bodies carry only the changed fields, the role body carries its current description too"
assert_eq "$(grep -cE ' -r iri$' "${state}/calls.log" || true)" "2" "the old realm name is used only before the rename (the role and client reads of the plan)"
assert_eq "$(awk '/^update realms\/iri/ { seen = 1; next } seen && / -r iri$/ { n++ } END { print n + 0 }' "${state}/calls.log")" "0" "no call names the old realm after the rename"
assert_contains "$(out_of "$state")" "[verify]" "the apply verifies"
assert_contains "$(out_of "$state")" "the default role is 'default-roles-basetool' and is still the realm's default role" "the verification says what it read"
assert_not_contains "$(out_of "$state")" "$SECRET_MARKER" "no client secret is printed"

run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "0" "a second apply succeeds"
assert_eq "$(writes_in "$state")" "0" "and writes nothing"
assert_contains "$(out_of "$state")" "Nothing to do" "and says so"
run_renamer "$state"
assert_eq "$(rc_of "$state")" "0" "a check on the renamed realm exits 0"
assert_eq "$(writes_in "$state")" "0" "and writes nothing"

echo "== resuming =="

state="${WORK}/s3"
seed "$state" renamed-realm-only
run_renamer "$state"
assert_eq "$(rc_of "$state")" "2" "a check on a half-renamed realm still has changes"
assert_not_contains "$(out_of "$state")" "rename realm" "and does not plan the realm rename again"
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "0" "the apply finishes what is open"
assert_eq "$(grep -E '^update ' "${state}/calls.log" | tr '\n' ';')" "update roles-by-id/r-default -r basetool;update clients/c-fe -r basetool;" "without a second realm update"
assert_eq "$(query "$state" "role('r-default')['name']")" "default-roles-basetool" "the role is renamed"

state="${WORK}/s4"
seed "$state" renamed-realm-and-role
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "0" "with realm and role done only the client is written"
assert_eq "$(grep -E '^update ' "${state}/calls.log" | tr '\n' ';')" "update clients/c-fe -r basetool;" "exactly that write"

state="${WORK}/s5"
seed "$state" "done"
before="$(cat "${state}/state.json")"
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "0" "a finished realm applies as a no-op"
assert_eq "$(cat "${state}/state.json")" "$before" "and is byte-for-byte unchanged"

echo "== refusals: nothing is written =="

refusal() {
  local flavour="$1" pattern="$2" what="$3" state="${WORK}/r-$1" before
  seed "$state" "$flavour"
  before="$(cat "${state}/state.json")"
  run_renamer "$state" --apply
  assert_eq "$(rc_of "$state")" "1" "${what}: exit 1"
  assert_eq "$(writes_in "$state")" "0" "${what}: no update reaches kcadm"
  assert_eq "$(cat "${state}/state.json")" "$before" "${what}: the realm is unchanged"
  assert_contains "$(out_of "$state")" "$pattern" "${what}: the reason is named"
}
refusal both-realms "both realm 'iri' and realm 'basetool' exist" "both realms exist"
refusal no-realm "neither realm 'iri' nor realm 'basetool' exists" "no realm exists"
refusal role-name-taken "a role named 'default-roles-basetool' exists already" "the new role name is taken"
refusal odd-default-role "the default role is named 'default-roles-something'" "the default role has another name"
refusal no-client "client 'basetool-frontend' does not exist" "the frontend client is missing, so the realm is not renamed either"

state="${WORK}/r-conflict"
seed "$state" conflict
before="$(cat "${state}/state.json")"
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "1" "a rename Keycloak refuses exits 1"
assert_contains "$(out_of "$state")" "FAILED" "and says so"
assert_not_contains "$(out_of "$state")" "config credentials" "without blaming the session"
assert_eq "$(cat "${state}/state.json")" "$before" "and the realm is unchanged"
assert_eq "$(grep -c . "${state}/bodies.log")" "1" "after a single write attempt"

echo "== a kcadm session that dies with the old realm =="

state="${WORK}/s6"
seed "$state" stale-session
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "2" "the apply stops with exit 2 (pending) when the session is no longer valid"
assert_eq "$(query "$state" "sorted(d['realms'])")" "['basetool']" "the realm was renamed before that"
assert_eq "$(query "$state" "role('r-default')['name']")" "default-roles-iri" "the role was not renamed yet"
assert_contains "$(out_of "$state")" "config credentials --server https://localhost:18443/auth --realm basetool --client basetool-provisioner" "the message names the exact re-login, for the new realm"
assert_contains "$(out_of "$state")" "re-run this script" "and says to re-run"
assert_not_contains "$(out_of "$state")" "FAILED" "and does not call it a failure"
assert_not_contains "$(out_of "$state")" "$SECRET_MARKER" "and prints no secret"
run_renamer "$state"
assert_eq "$(rc_of "$state")" "2" "without a new session even a check is pending, with the re-login named"
assert_contains "$(out_of "$state")" "config credentials" "and prints it"
RELOGIN=1
run_renamer "$state" --apply
RELOGIN=
assert_eq "$(rc_of "$state")" "0" "after signing in again the same command finishes"
assert_eq "$(grep -E '^update ' "${state}/calls.log" | tr '\n' ';')" "update roles-by-id/r-default -r basetool;update clients/c-fe -r basetool;" "with the two writes that were open, and no second rename"
assert_eq "$(query "$state" "client('basetool-frontend')['name']")" "Profit Basetool Frontend" "the client is relabelled"

state="${WORK}/s7"
seed "$state" role-rename-rejected
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "1" "a rejected role rename on an already renamed realm exits 1"
assert_not_contains "$(out_of "$state")" "config credentials" "and is not blamed on the session"
assert_not_contains "$(out_of "$state")" "PENDING" "nor reported as pending"
assert_contains "$(out_of "$state")" "HTTP 400" "Keycloak's own message is shown"

echo "== the verification has teeth =="

state="${WORK}/v1"
seed "$state" ignored-role-rename
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "1" "a role rename that Keycloak accepts but does not carry out fails the run"
assert_contains "$(out_of "$state")" "the realm's default role is named 'default-roles-iri', not 'default-roles-basetool'" "naming the default role"
assert_contains "$(out_of "$state")" "FAILED: the realm is not in shape after applying." "and the run"
state="${WORK}/v2"
seed "$state" swapped-default-role
run_renamer "$state" --apply
assert_eq "$(rc_of "$state")" "1" "a default role that is no longer the renamed role fails the run"
assert_contains "$(out_of "$state")" "no longer the role that was renamed" "naming the cause"

echo "== usage =="

"$PYTHON" "$RENAMER" --help > "${WORK}/help.out" 2>&1
assert_eq "$?" "0" "--help exits 0"
assert_contains "$(cat "${WORK}/help.out")" "--kcadm-command" "--help names the kcadm option"
assert_contains "$(cat "${WORK}/help.out")" "--apply" "--help names --apply"

echo
if [[ "$tests_failed" -eq 0 ]]; then
  echo "OK: ${tests_run} assertions"
  exit 0
fi
echo "FAILED: ${tests_failed} of ${tests_run} assertions"
exit 1
