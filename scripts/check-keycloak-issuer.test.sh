#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
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
  docker-compose.monitoring.yml
)

SPRING_DIRS=(
  backend/src/main/resources
  frontend/src/main/resources
  ingest/src/main/resources
)

NESTED_FILES=(
  monitoring/prometheus/prometheus.yml
)

tests_run=0
tests_failed=0
LAST_OUTPUT=""
LAST_STATUS=0

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
  local n
  for n in "${NESTED_FILES[@]}"; do
    mkdir -p "${dir}/$(dirname "$n")"
    cp "${REPO_ROOT}/${n}" "${dir}/${n}"
  done
  echo "$dir"
}

rewrite() {
  local file="$1" expr="$2"
  sed "$expr" "$file" >"${file}.new"
  mv "${file}.new" "$file"
}

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

run_checker() {
  local dir="$1"
  shift
  set +e
  LAST_OUTPUT="$(python3 "$CHECKER" --repo-root "$dir" "$@" 2>&1)"
  LAST_STATUS=$?
  set -e
}

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

FIXTURE="$(new_fixture)"
run_checker "$FIXTURE"
expect_success "an unmodified checkout passes every stack"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.yml" \
  's|KC_HOSTNAME: ${IRI_KEYCLOAK_HOSTNAME:-https://profit-base.online/auth}|KC_HOSTNAME: ${IRI_KEYCLOAK_HOSTNAME:-https://profit-base.online}|' \
  "origin-only KC_HOSTNAME"
run_checker "$FIXTURE" --only prod-defaults
expect_failure "an origin-only KC_HOSTNAME beside /auth is rejected" "would SERVE"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.yml" \
  's|^    KC_HTTP_RELATIVE_PATH: /auth$|    KC_HTTP_RELATIVE_PATH: /identity|' \
  "relative path moved to /identity"
run_checker "$FIXTURE" --only prod-defaults
expect_failure "a relative path that the hostname does not carry is rejected" "KC_HTTP_RELATIVE_PATH"
rm -rf "$FIXTURE"

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

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.test.yml" \
  '0,\|KEYCLOAK_ISSUER_URI: http://host.docker.internal:18080/auth/realms/iri|s||KEYCLOAK_ISSUER_URI: http://host.docker.internal:18080/auth/realms/stale|' \
  "one service left on a stale realm"
run_checker "$FIXTURE" --only test-stack
expect_failure "services that disagree on the issuer are rejected" "do not agree on the issuer"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/backend/src/main/resources/application-prod.yml" \
  's|${KEYCLOAK_ISSUER_URI:https://profit-base.online/auth/realms/iri}|${KEYCLOAK_ISSUER_URI:https://keycloak.profit-base.online/realms/iri}|' \
  "Spring fallback left on the retired host"
run_checker "$FIXTURE" --only spring-defaults
expect_failure "a Spring fallback default that names the retired issuer is rejected" \
  "would validate the wrong issuer"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.monitoring.yml" \
  's|${IRI_KEYCLOAK_HOSTNAME:-https://profit-base.online/auth}/realms/iri/protocol/openid-connect|https://profit-base.online/auth/realms/iri/protocol/openid-connect|g' \
  "Grafana endpoints decoupled from the hostname"
run_checker "$FIXTURE" --only monitoring-oidc
expect_failure "decoupled Grafana endpoints no longer follow IRI_KEYCLOAK_HOSTNAME" \
  "realm other than the one minting"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.monitoring.yml" \
  's|\(GF_AUTH_GENERIC_OAUTH_TOKEN_URL: .*\)/realms/iri/|\1/realms/stale/|' \
  "one Grafana endpoint on a stale realm"
run_checker "$FIXTURE" --only monitoring-oidc
expect_failure "a Grafana endpoint on the wrong realm is rejected" "GF_AUTH_GENERIC_OAUTH_TOKEN_URL"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/monitoring/prometheus/prometheus.yml" \
  's|https://profit-base.online/auth/health|https://keycloak.profit-base.online/auth/health|' \
  "identity probe left on the retired host"
run_checker "$FIXTURE" --only prometheus-targets
expect_failure "a Prometheus identity probe on the wrong base is rejected" "never reaches it on its own"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/monitoring/prometheus/prometheus.yml" \
  's|https://profit-base.online/auth/metrics|https://keycloak.profit-base.online/metrics|' \
  "identity probe relocated off the identity path"
run_checker "$FIXTURE" --only prometheus-targets
expect_failure "a probe moved off the identity base entirely is reported" "is not probed"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/monitoring/prometheus/prometheus.yml" \
  '\|/.well-known/openid-configuration|d' \
  "discovery probe removed"
run_checker "$FIXTURE" --only prometheus-targets
expect_failure "a dropped discovery probe is reported" "openid-configuration is not probed"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/monitoring/prometheus/prometheus.yml" \
  's|- https://profit-base.online/robots.txt|- https://profit-base.online/authors|' \
  "an /authors route added beside the identity targets"
run_checker "$FIXTURE" --only prometheus-targets
expect_success "a /authors route is not mistaken for an identity probe"
rm -rf "$FIXTURE"

FIXTURE="$(new_fixture)"
rewrite_or_die "${FIXTURE}/docker-compose.yml" \
  's|^\(\s*\)KEYCLOAK_ISSUER_URI: ${IRI_KEYCLOAK_ISSUER_URI.*$|\1KEYCLOAK_ISSUER_URI_RENAMED: unused|' \
  "issuer variable renamed away"
run_checker "$FIXTURE" --only prod-defaults
expect_failure "a stack with no issuer at all is reported, not passed" "this scenario checked nothing"
rm -rf "$FIXTURE"

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
