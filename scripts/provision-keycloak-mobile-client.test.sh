#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Regression tests for scripts/provision-keycloak-mobile-client.py.
#
# Drives the provisioner against a stub that impersonates kcadm, so the whole suite runs in a
# second with no Docker, no Keycloak and no network. What it pins down is exactly what a live
# smoke test would not catch cheaply: the two realm-global lists are REPLACED on write, so a merge
# bug silently deletes somebody else's client policy, and the write order is load-bearing because
# Keycloak refuses to edit a client while the DPoP policy is attached to it.
#
# The provisioner was additionally run end-to-end against a throwaway Keycloak 26.7 on 2026-08-17;
# these tests are the part of that run that can be repeated in CI.
#
# Usage:
#   scripts/provision-keycloak-mobile-client.test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROVISIONER="${SCRIPT_DIR}/provision-keycloak-mobile-client.py"

if [[ ! -f "$PROVISIONER" ]]; then
  echo "FATAL: provisioner not found at ${PROVISIONER}" >&2
  exit 1
fi

PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || { echo "FATAL: ${PYTHON} not found" >&2; exit 1; }

tests_run=0
tests_failed=0

CLIENT_UUID="11111111-2222-3333-4444-555555555555"

# Builds a throwaway state directory holding the JSON the stub serves, and writes the stub itself.
# The stub is stateful for the two client-policy lists: an `update` overwrites the file a later
# `get` reads back, which is what makes the idempotency assertions meaningful.
make_stub() {
  local state="$1"
  mkdir -p "$state"

  cat >"${state}/clients.json" <<JSON
[{"id":"${CLIENT_UUID}","clientId":"basetool-android","attributes":{"existing.untouched":"keep"}}]
JSON
  cat >"${state}/realm.json" <<'JSON'
{"realm":"iri","ssoSessionIdleTimeout":2592000,"ssoSessionMaxLifespan":15552000}
JSON
  cat >"${state}/profiles.json" <<'JSON'
{"profiles":[{"name":"someone-elses-profile","executors":[]}]}
JSON
  cat >"${state}/policies.json" <<'JSON'
{"policies":[{"name":"someone-elses-policy","enabled":true,"conditions":[],"profiles":[]}]}
JSON
  echo '[]' >"${state}/roles.json"
  echo '[]' >"${state}/mappers.json"
  echo '[]' >"${state}/optional-scopes.json"
  # The realm's own roles, as `kcadm get roles` would serve them, and what the client's scope
  # already holds. The pre-existing Guest mapping is the interesting part: it is a real realm role
  # that MEMBER_REALM_ROLES does not name, so the provisioner has to take it back, or a hand-edit
  # in the Admin Console survives every later run. It was `Admin` here until 2026-09-01, when Admin
  # became a granted role (REQ-SEC-035 reversed) and could no longer serve as the probe.
  cat >"${state}/realm-roles.json" <<'JSON'
[{"id":"r-krt","name":"KRT Member"},{"id":"r-off","name":"Officer"},
 {"id":"r-adm","name":"Admin"},{"id":"r-gue","name":"Guest"},
 {"id":"r-bem","name":"Bank Employee"},{"id":"r-bmg","name":"Bank Management"}]
JSON
  cat >"${state}/scope-mappings.json" <<'JSON'
[{"id":"r-gue","name":"Guest"}]
JSON

  # The stub is Python rather than a shell script on purpose: the provisioner spawns it through
  # subprocess, and a shebanged shell script is not directly executable on a Windows developer
  # machine. Invoking it as `python3 kcadm_stub.py` keeps this suite runnable everywhere.
  cat >"${state}/kcadm_stub.py" <<'STUB'
"""Minimal kcadm impersonator: serves reads, applies writes, logs every call.

It reflects writes back into the state it serves, so the provisioner's own closing verification is
exercised for real rather than against a frozen fixture. Only the argument shapes the provisioner
actually emits are handled; anything else is a loud failure rather than a silent pass.
"""
import json
import os
import pathlib
import sys

state = pathlib.Path(os.environ["KCADM_STUB_STATE"])
verb, path = sys.argv[1], sys.argv[2]
STUB_UUID = "11111111-2222-3333-4444-555555555555"

with (state / "calls.log").open("a", encoding="utf-8") as log:
    log.write(f"{verb} {path}\n")


def load(name):
    return json.loads((state / name).read_text(encoding="utf-8"))


def save(name, value):
    (state / name).write_text(json.dumps(value, indent=2), encoding="utf-8")


def read_target(request_path):
    if request_path.startswith("realms/"):
        return "realm.json"
    if request_path == "roles":
        # The REALM's roles, not a client's — the two paths differ only by prefix.
        return "realm-roles.json"
    if request_path.endswith("/scope-mappings/realm"):
        return "scope-mappings.json"
    if request_path.endswith("/roles"):
        return "roles.json"
    if request_path.endswith("/protocol-mappers/models"):
        return "mappers.json"
    if request_path.endswith("/optional-client-scopes"):
        return "optional-scopes.json"
    return {
        "clients": "clients.json",
        "client-policies/profiles": "profiles.json",
        "client-policies/policies": "policies.json",
    }.get(request_path)


if verb == "get":
    name = read_target(path)
    if name is None:
        sys.exit(f"stub: unexpected get {path}")
    sys.stdout.write((state / name).read_text(encoding="utf-8"))

elif verb in ("create", "update"):
    body = json.loads(sys.stdin.read())
    if path in ("client-policies/profiles", "client-policies/policies"):
        save(path.rsplit("/", 1)[1] + ".json", body)
    elif path == "clients" or (path.startswith("clients/") and path.count("/") == 1):
        # Record every client payload for assertions, and reflect it as the live client.
        with (state / "client-writes.json").open("a", encoding="utf-8") as sink:
            sink.write(json.dumps(body, indent=2, sort_keys=True))
        save("clients.json", [{**body, "id": body.get("id", STUB_UUID)}])
    elif path.endswith("/scope-mappings/realm"):
        save("scope-mappings.json", load("scope-mappings.json") + body)
    elif path.endswith("/roles"):
        save("roles.json", load("roles.json") + [body])
    elif path.endswith("/protocol-mappers/models"):
        save("mappers.json", load("mappers.json") + [{**body, "id": STUB_UUID}])
    else:
        sys.exit(f"stub: unexpected {verb} {path}")

elif verb == "delete":
    if path.endswith("/scope-mappings/realm"):
        removed = {role["name"] for role in json.loads(sys.stdin.read())}
        save("scope-mappings.json",
             [role for role in load("scope-mappings.json") if role["name"] not in removed])
    elif path.rsplit("/", 2)[-2] == "optional-client-scopes":
        save("optional-scopes.json", [])
else:
    sys.exit(f"stub: unexpected verb {verb}")
STUB
}

# Runs the provisioner against the stub in state dir $1; extra arguments are passed through.
# Echoes the provisioner's combined output and returns its exit code.
# The stub's path is handed to a CHILD python through --kcadm-command, so it has to be a path that
# child can open. On Windows the interpreter behind `python3` is a native one while this shell is
# MSYS, and the two do not agree on `/tmp`: MSYS means C:\Users\<user>\AppData\Local\Temp, native
# Python reads the same string as <current drive>:\tmp. The whole suite therefore died on its first
# assertion with `can't open file 'D:\tmp\...\kcadm_stub.py'`, before this change and after it.
# `cygpath -m` yields the mixed form (C:/Users/...) that both accept; on Linux and in CI there is no
# cygpath and the path is passed through untouched.
to_child_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

run_provisioner() {
  local state="$1"; shift
  KCADM_STUB_STATE="$state" "$PYTHON" "$PROVISIONER" \
    --realm iri --kcadm-command "${PYTHON} $(to_child_path "${state}/kcadm_stub.py")" "$@" 2>&1
}

pass() { tests_run=$((tests_run + 1)); printf '  ok   %s\n' "$1"; }
fail() {
  tests_run=$((tests_run + 1))
  tests_failed=$((tests_failed + 1))
  printf '  FAIL %s\n' "$1"
  [[ $# -gt 1 ]] && printf '       %s\n' "$2"
  return 0
}

assert_contains() {
  local haystack="$1" needle="$2" label="$3"
  if [[ "$haystack" == *"$needle"* ]]; then pass "$label"; else fail "$label" "missing: ${needle}"; fi
}

assert_not_contains() {
  local haystack="$1" needle="$2" label="$3"
  if [[ "$haystack" != *"$needle"* ]]; then pass "$label"; else fail "$label" "unexpected: ${needle}"; fi
}

# ---------------------------------------------------------------------------
echo "1. a foreign client policy survives the merge"
# ---------------------------------------------------------------------------
state="$(mktemp -d)"
make_stub "$state"
run_provisioner "$state" >/dev/null
profiles="$(cat "${state}/profiles.json")"
policies="$(cat "${state}/policies.json")"
assert_contains "$profiles" "someone-elses-profile" "the pre-existing profile is carried forward"
assert_contains "$profiles" "krt-mobile-dpop" "our profile is present"
assert_contains "$policies" "someone-elses-policy" "the pre-existing policy is carried forward"
assert_contains "$policies" "krt-mobile-dpop-policy" "our policy is attached"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "2. the policy is detached before the client is written"
# ---------------------------------------------------------------------------
# Keycloak refuses `invalid_client_metadata: DPoP token is disabled` for any client edit while the
# policy is attached, so a client write that happens first fails on a real server.
state="$(mktemp -d)"
make_stub "$state"
cat >"${state}/policies.json" <<'JSON'
{"policies":[{"name":"krt-mobile-dpop-policy","enabled":true,"conditions":[],"profiles":["krt-mobile-dpop"]}]}
JSON
run_provisioner "$state" >/dev/null
first_policy_write="$(grep -n 'update client-policies/policies' "${state}/calls.log" | head -1 | cut -d: -f1)"
first_client_write="$(grep -n 'update clients/' "${state}/calls.log" | head -1 | cut -d: -f1)"
if [[ -n "$first_policy_write" && -n "$first_client_write" && "$first_policy_write" -lt "$first_client_write" ]]; then
  pass "the detach write precedes the client write"
else
  fail "the detach write precedes the client write" \
    "policy write at line ${first_policy_write:-none}, client write at ${first_client_write:-none}"
fi
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "3. the client payload pins the values the posture depends on"
# ---------------------------------------------------------------------------
state="$(mktemp -d)"
make_stub "$state"
run_provisioner "$state" >/dev/null
written="$(cat "${state}/client-writes.json")"
assert_contains "$written" '"dpop.bound.access.tokens": "false"' \
  "the per-client DPoP switch stays off (it would re-bind the access token)"
assert_contains "$written" '"pkce.code.challenge.method": "S256"' "PKCE S256 is enforced"
assert_contains "$written" '"directAccessGrantsEnabled": false' "direct grants stay off"
assert_contains "$written" '"publicClient": true' "the client is public"
assert_contains "$written" '"existing.untouched": "keep"' \
  "attributes the script does not manage survive the update"
assert_not_contains "$written" '*' "no redirect URI carries a wildcard"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "4. session bounds are clamped to what the realm permits"
# ---------------------------------------------------------------------------
# Keycloak rejects a client whose session bounds exceed the realm's SSO values outright.
state="$(mktemp -d)"
make_stub "$state"
cat >"${state}/realm.json" <<'JSON'
{"realm":"iri","ssoSessionIdleTimeout":1800,"ssoSessionMaxLifespan":36000}
JSON
output="$(run_provisioner "$state")"
written="$(cat "${state}/client-writes.json")"
assert_contains "$output" "NOTE: realm SSO bounds" "the clamp is announced rather than silent"
assert_contains "$written" '"client.session.idle.timeout": "1800"' "the idle bound is clamped"
assert_contains "$written" '"client.session.max.lifespan": "36000"' "the max bound is clamped"
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "5. running twice converges"
# ---------------------------------------------------------------------------
state="$(mktemp -d)"
make_stub "$state"
run_provisioner "$state" >/dev/null
first_profiles="$(cat "${state}/profiles.json")"
first_policies="$(cat "${state}/policies.json")"
run_provisioner "$state" >/dev/null
if [[ "$first_profiles" == "$(cat "${state}/profiles.json")" ]]; then
  pass "the profile list is unchanged by a second run"
else
  fail "the profile list is unchanged by a second run"
fi
if [[ "$first_policies" == "$(cat "${state}/policies.json")" ]]; then
  pass "the policy list is unchanged by a second run"
else
  fail "the policy list is unchanged by a second run"
fi
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "6. --verify-only rejects a realm that would break the posture"
# ---------------------------------------------------------------------------
state="$(mktemp -d)"
make_stub "$state"
run_provisioner "$state" >/dev/null
# Someone flips the per-client switch in the console: both tokens bind again and the backend
# starts refusing every request. Verification has to catch that.
cat >"${state}/clients.json" <<JSON
[{"id":"${CLIENT_UUID}","clientId":"basetool-android","publicClient":true,
  "directAccessGrantsEnabled":false,"redirectUris":["https://profit-base.online/app/callback"],
  "attributes":{"dpop.bound.access.tokens":"true","pkce.code.challenge.method":"S256"}}]
JSON
echo '[{"name":"dpop-refresh-only"}]' >"${state}/roles.json"
rc=0
output="$(run_provisioner "$state" --verify-only)" || rc=$?
if [[ $rc -ne 0 ]]; then pass "a bound access token is reported as a failure"; else
  fail "a bound access token is reported as a failure" "exit was 0"; fi
assert_contains "$output" "dpop.bound.access.tokens is not false" "the message names the cause"

# And the healthy realm verifies clean.
cat >"${state}/clients.json" <<JSON
[{"id":"${CLIENT_UUID}","clientId":"basetool-android","publicClient":true,
  "directAccessGrantsEnabled":false,"redirectUris":["https://profit-base.online/app/callback"],
  "attributes":{"dpop.bound.access.tokens":"false","pkce.code.challenge.method":"S256"}}]
JSON
rc=0
run_provisioner "$state" --verify-only >/dev/null || rc=$?
if [[ $rc -eq 0 ]]; then pass "the intended state verifies clean"; else
  fail "the intended state verifies clean" "exit was ${rc}"; fi
rm -rf "$state"

# ---------------------------------------------------------------------------
echo "7. the client scope carries exactly the roles the list names, Admin included"
# ---------------------------------------------------------------------------
# The failure this pins is not "the app has fewer rights". `fullScopeAllowed` is off, so a client
# with no scope mappings sends a token with NO realm roles at all — and the backend replaces the
# local role set from that claim on every login, falling back to Guest. Measured on the test stack
# before this existed: an account holding Admin + Officer + KRT Member came out holding Guest.
#
# `Admin` moved from asserted-absent to granted on 2026-09-01 (owner decision, REQ-SEC-035
# reversed). What survives that reversal is the property this section really guards: the scope is
# converged to EXACTLY the list, in both directions, so it can neither shrink by accident nor grow
# by a hand-edit in the Admin Console.
state="$(mktemp -d)"
make_stub "$state"
run_provisioner "$state" >/dev/null
scope="$(cat "${state}/scope-mappings.json")"
assert_contains "$scope" '"KRT Member"' "KRT Member reaches the app"
assert_contains "$scope" '"Officer"' "Officer reaches the app"
assert_contains "$scope" '"Bank Employee"' "Bank Employee reaches the app"
assert_contains "$scope" '"Bank Management"' "Bank Management reaches the app"
# The reversal itself: without this the administrator's app has no org unit to pin and „Alle
# Org-Einheiten" resolves to their own empty reach rather than to everything.
assert_contains "$scope" '"Admin"' "Admin reaches the app"
# Converging downwards still has to work, or the list stops being the authority. The stub seeded
# Guest on the scope to model a hand-edit in the Admin Console.
assert_not_contains "$scope" '"Guest"' "a hand-added mapping outside the list is taken back"

# A second run must not re-add or re-remove anything.
before="$scope"
run_provisioner "$state" >/dev/null
if [[ "$before" == "$(cat "${state}/scope-mappings.json")" ]]; then
  pass "the client scope is unchanged by a second run"
else
  fail "the client scope is unchanged by a second run"
fi

# Verification has to catch a scope somebody emptied, and one somebody widened.
echo '[]' >"${state}/scope-mappings.json"
rc=0
output="$(run_provisioner "$state" --verify-only)" || rc=$?
if [[ $rc -ne 0 ]]; then pass "an empty client scope is reported as a failure"; else
  fail "an empty client scope is reported as a failure" "exit was 0"; fi
assert_contains "$output" "reconciled onto the Guest fallback" "the message names the consequence"

echo '[{"id":"r-krt","name":"KRT Member"},{"id":"r-off","name":"Officer"},
      {"id":"r-bem","name":"Bank Employee"},{"id":"r-bmg","name":"Bank Management"},
      {"id":"r-adm","name":"Admin"},{"id":"r-gue","name":"Guest"}]' >"${state}/scope-mappings.json"
rc=0
output="$(run_provisioner "$state" --verify-only)" || rc=$?
if [[ $rc -ne 0 ]]; then pass "a widened client scope is reported as a failure"; else
  fail "a widened client scope is reported as a failure" "exit was 0"; fi
assert_contains "$output" "without a decision" "the message names the failure mode"
assert_contains "$output" "'Guest'" "the message names the role that was added"

# The complement: the exact intended scope, Admin included, must verify clean. Without this the
# section above could pass while the granted list itself was wrong.
echo '[{"id":"r-krt","name":"KRT Member"},{"id":"r-off","name":"Officer"},
      {"id":"r-bem","name":"Bank Employee"},{"id":"r-bmg","name":"Bank Management"},
      {"id":"r-adm","name":"Admin"}]' >"${state}/scope-mappings.json"
rc=0
run_provisioner "$state" --verify-only >/dev/null || rc=$?
if [[ $rc -eq 0 ]]; then pass "the intended scope, with Admin, verifies clean"; else
  fail "the intended scope, with Admin, verifies clean" "exit was ${rc}"; fi
rm -rf "$state"

# ---------------------------------------------------------------------------
echo
if [[ $tests_failed -gt 0 ]]; then
  echo "FAILED: ${tests_failed} of ${tests_run} assertions"
  exit 1
fi
echo "OK: ${tests_run} assertions"
