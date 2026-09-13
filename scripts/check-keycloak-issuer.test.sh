#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Regression tests for scripts/check-keycloak-issuer.py.
#
# Builds throwaway copies of the compose files and the Spring configs, breaks each in exactly one
# way, and asserts the gate says so. A gate nobody has watched fail is a gate nobody knows works --
# and this one is easy to break into silence, because every rule it applies is a comparison that
# passes trivially when one side goes missing.
#
# THE CASES THAT MATTER MOST
#   * ADR-0166's measured broken row -- an origin-only KC_HOSTNAME beside KC_HTTP_RELATIVE_PATH=/auth.
#     That is the combination a first draft of the ADR specified, and it is silent everywhere except
#     the apps' start-up log.
#   * The derivation removed. Restore KEYCLOAK_ISSUER_URI to a second independent literal and
#     `prod-defaults` still passes -- the two literals agree today. Only `prod-hostname-override`
#     catches it, which is the whole reason that scenario exists.
#   * The vacuity guard. Delete the issuer from the stack entirely and the gate must complain that
#     it has nothing to check, rather than reporting success over an empty list.
#
# The suite runs the UNMUTATED fixture first and requires it to be clean. Without that, every
# assertion below would still pass on a checker that failed unconditionally.
#
# Usage:
#   scripts/check-keycloak-issuer.test.sh
#
# Needs docker (the gate renders through `docker compose config`) and python3. No network.
#
# SC2016 is disabled for the whole file, and this is the one place the rule is exactly inverted:
# every sed expression below matches compose interpolation syntax, so the `${...}` inside them are
# the TEXT BEING SEARCHED FOR. Letting the shell expand one would substitute an empty string and the
# mutation would then match nothing -- which rewrite_or_die exists to catch, but satisfying the
# linter by breaking every fixture is not a trade worth making.
# shellcheck disable=SC2016

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKER="${SCRIPT_DIR}/check-keycloak-issuer.py"

if [[ ! -f "$CHECKER" ]]; then
  echo "FATAL: checker not found at ${CHECKER}" >&2
  exit 1
fi

COMPOSE_FILES=(
  docker-compose.yml
  docker-compose.test.yml
  docker-compose.build.yml
  docker-compose.e2e.yml
  docker-compose.android.yml
  docker-compose.localtest.yml
)

SPRING_DIRS=(
  backend/src/main/resources
  frontend/src/main/resources
  ingest/src/main/resources
)

tests_run=0
tests_failed=0
LAST_OUTPUT=""
LAST_STATUS=0

# Creates a throwaway fixture: the compose files and the Spring configs, and nothing else. The gate
# reads only those, so a partial copy is a faithful stand-in for the repository and costs a
# fraction of copying a worktree.
#
# Prints the fixture's absolute path.
new_fixture() {
  local dir
  dir="$(mktemp -d "${TMPDIR:-/tmp}/keycloak-issuer-test.XXXXXX")"
  local f
  for f in "${COMPOSE_FILES[@]}"; do
    cp "${REPO_ROOT}/${f}" "${dir}/${f}"
  done
  local d
  for d in "${SPRING_DIRS[@]}"; do
    mkdir -p "${dir}/${d}"
    cp "${REPO_ROOT}/${d}"/application*.yml "${dir}/${d}/"
  done
  echo "$dir"
}

# In-place substitution that does not rely on `sed -i`. Writes beside the file and renames, so a
# failed edit leaves the original intact rather than truncating it.
#
# Args: $1 file, $2 sed expression.
rewrite() {
  local file="$1" expr="$2"
  sed "$expr" "$file" >"${file}.new"
  mv "${file}.new" "$file"
}

# Asserts a substitution actually changed something. A sed expression that matches nothing is the
# classic way a regression suite goes green while testing the unmodified file.
#
# Args: $1 file, $2 sed expression, $3 description.
rewrite_or_die() {
  local file="$1" expr="$2" what="$3"
  local before after
  before="$(cksum <"$file")"
  rewrite "$file" "$expr"
  after="$(cksum <"$file")"
  if [[ "$before" == "$after" ]]; then
    echo "FATAL: fixture mutation '${what}' matched nothing in ${file} -- the file moved under the" \
      "suite and these tests would pass vacuously" >&2
    exit 1
  fi
}

# Runs the gate against a fixture. Captures stdout+stderr in LAST_OUTPUT and the exit code in
# LAST_STATUS; never aborts the suite itself.
#
# Args: $1 fixture dir, $2.. extra checker arguments.
run_checker() {
  local dir="$1"
  shift
  set +e
  LAST_OUTPUT="$(python3 "$CHECKER" --repo-root "$dir" "$@" 2>&1)"
  LAST_STATUS=$?
  set -e
}

# Args: $1 test name, $2 expected substring of the report.
expect_failure() {
  local name="$1" needle="$2"
  tests_run=$((tests_run + 1))
  if [[ $LAST_STATUS -eq 0 ]]; then
    tests_failed=$((tests_failed + 1))
    printf 'FAIL  %s\n      expected a non-zero exit, got 0. Output:\n%s\n' "$name" "$LAST_OUTPUT"
    return
  fi
  if [[ "$LAST_OUTPUT" != *"$needle"* ]]; then
    tests_failed=$((tests_failed + 1))
    printf 'FAIL  %s\n      report did not mention %q. Output:\n%s\n' "$name" "$needle" "$LAST_OUTPUT"
    return
  fi
  printf 'ok    %s\n' "$name"
}

# Args: $1 test name.
expect_success() {
  local name="$1"
  tests_run=$((tests_run + 1))
  if [[ $LAST_STATUS -ne 0 ]]; then
    tests_failed=$((tests_failed + 1))
    printf 'FAIL  %s\n      expected exit 0, got %d. Output:\n%s\n' "$name" "$LAST_STATUS" "$LAST_OUTPUT"
    return
  fi
  printf 'ok    %s\n' "$name"
}

echo "Running scripts/check-keycloak-issuer.py regression suite..."
echo

# ---------------------------------------------------------------------------------------------
# 0. The fixture itself is clean. Everything below is worthless without this.
# ---------------------------------------------------------------------------------------------
FIXTURE="$(new_fixture)"
run_checker "$FIXTURE"
expect_success "an unmodified checkout passes every stack"
rm -rf "$FIXTURE"

# ---------------------------------------------------------------------------------------------
# 1. ADR-0166's broken row: KC_HOSTNAME loses its path while Keycloak still serves under /auth.
#    Keycloak reports healthy and advertises root issuer links; the apps die at start-up.
# ---------------------------------------------------------------------------------------------
FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.yml" \
  's|KC_HOSTNAME: ${IRI_KEYCLOAK_HOSTNAME:-https://profit-base.online/auth}|KC_HOSTNAME: ${IRI_KEYCLOAK_HOSTNAME:-https://profit-base.online}|' \
  "origin-only KC_HOSTNAME"
run_checker "$FIXTURE" --only prod-defaults
expect_failure "an origin-only KC_HOSTNAME beside /auth is rejected" "would SERVE"
rm -rf "$FIXTURE"

# ---------------------------------------------------------------------------------------------
# 2. The same disagreement from the other side: Keycloak moves to a different mount point and the
#    hostname is left behind. Same outage, and the hostname alone looks perfectly reasonable.
# ---------------------------------------------------------------------------------------------
FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.yml" \
  's|^    KC_HTTP_RELATIVE_PATH: /auth$|    KC_HTTP_RELATIVE_PATH: /identity|' \
  "relative path moved to /identity"
run_checker "$FIXTURE" --only prod-defaults
expect_failure "a relative path that the hostname does not carry is rejected" "KC_HTTP_RELATIVE_PATH"
rm -rf "$FIXTURE"

# ---------------------------------------------------------------------------------------------
# 3. THE HEADLINE CASE. The issuer goes back to being a second independent literal. Today's two
#    values agree, so prod-defaults still passes -- only the override scenario notices that the
#    issuer has stopped following the hostname. This is the regression the single-source change
#    exists to prevent, and it is invisible in any single line of the file.
# ---------------------------------------------------------------------------------------------
FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.yml" \
  's|KEYCLOAK_ISSUER_URI: ${IRI_KEYCLOAK_ISSUER_URI:-${IRI_KEYCLOAK_HOSTNAME:-https://profit-base.online/auth}/realms/iri}|KEYCLOAK_ISSUER_URI: ${IRI_KEYCLOAK_ISSUER_URI:-https://profit-base.online/auth/realms/iri}|g' \
  "issuer decoupled from the hostname"
run_checker "$FIXTURE" --only prod-defaults
expect_success "the decoupled issuer still passes on the defaults -- which is why case 3 exists"
run_checker "$FIXTURE" --only prod-hostname-override
expect_failure "a decoupled issuer no longer follows IRI_KEYCLOAK_HOSTNAME" \
  "this is what fails the moment the issuer becomes a second independent literal again"
rm -rf "$FIXTURE"

# ---------------------------------------------------------------------------------------------
# 4. One app left behind on a rename. Two of three services move, the third keeps the old issuer
#    and rejects every token it is handed.
# ---------------------------------------------------------------------------------------------
FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.test.yml" \
  '0,\|KEYCLOAK_ISSUER_URI: http://host.docker.internal:18080/auth/realms/iri|s||KEYCLOAK_ISSUER_URI: http://host.docker.internal:18080/auth/realms/stale|' \
  "one service left on a stale realm"
run_checker "$FIXTURE" --only test-stack
expect_failure "services that disagree on the issuer are rejected" "do not agree on the issuer"
rm -rf "$FIXTURE"

# ---------------------------------------------------------------------------------------------
# 5. The Spring fallback default left on the retired host. Nothing in the compose file is wrong;
#    an app started without KEYCLOAK_ISSUER_URI simply trusts an issuer that no longer exists.
# ---------------------------------------------------------------------------------------------
FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/backend/src/main/resources/application-prod.yml" \
  's|${KEYCLOAK_ISSUER_URI:https://profit-base.online/auth/realms/iri}|${KEYCLOAK_ISSUER_URI:https://keycloak.profit-base.online/realms/iri}|' \
  "Spring fallback left on the retired host"
run_checker "$FIXTURE" --only prod-defaults
expect_failure "a Spring fallback default that names the retired issuer is rejected" \
  "would validate the wrong issuer"
rm -rf "$FIXTURE"

# ---------------------------------------------------------------------------------------------
# 6. The vacuity guard. With no issuer configured anywhere, every comparison above has nothing to
#    compare -- the failure mode a gate like this dies of, quietly, years later.
# ---------------------------------------------------------------------------------------------
FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.yml" \
  's|^\(\s*\)KEYCLOAK_ISSUER_URI: ${IRI_KEYCLOAK_ISSUER_URI.*$|\1KEYCLOAK_ISSUER_URI_RENAMED: unused|' \
  "issuer variable renamed away"
run_checker "$FIXTURE" --only prod-defaults
expect_failure "a stack with no issuer at all is reported, not passed" "this scenario checked nothing"
rm -rf "$FIXTURE"

# ---------------------------------------------------------------------------------------------
# 7. The script's own constant drifting from the compose file. Move the domain consistently and
#    every rule still passes -- PROD_BASE is the only thing left that knows the old value, so it
#    has to say so rather than keep asserting a host nobody deploys.
# ---------------------------------------------------------------------------------------------
FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.yml" \
  's|https://profit-base.online/auth|https://basetool.example.test/auth|g' \
  "domain moved in the compose file"
run_checker "$FIXTURE" --only prod-defaults
expect_failure "the script's PROD_BASE cannot drift from docker-compose.yml" \
  "no longer defaults KC_HOSTNAME"
rm -rf "$FIXTURE"

echo
if [[ $tests_failed -gt 0 ]]; then
  echo "check-keycloak-issuer.test.sh: ${tests_failed} of ${tests_run} FAILED"
  exit 1
fi
echo "check-keycloak-issuer.test.sh: all ${tests_run} tests passed"
