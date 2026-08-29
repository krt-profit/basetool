#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Regression tests for scripts/check-grafana-dashboards.py.
#
# Builds throwaway dashboard trees that reproduce each silent-failure mode the gate exists for, then
# asserts its exit status and its report. No network, no Gradle -- pure python3 + bash.
#
# Usage:
#   scripts/check-grafana-dashboards.test.sh
#
# A gate nobody has watched fail is worth nothing -- that is the #1715 lesson, applied to the gate
# this suite guards. So every case below asserts the FAILURE as well as the pass, and the last one
# asserts the repository's own dashboards are clean, which is what keeps the suite from passing
# vacuously if the checker were ever reduced to "return 0".

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKER="${SCRIPT_DIR}/check-grafana-dashboards.py"

if [[ ! -f "$CHECKER" ]]; then
  echo "FATAL: checker not found at ${CHECKER}" >&2
  exit 1
fi

PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || PYTHON=python

tests_run=0
tests_failed=0
LAST_OUTPUT=""
LAST_STATUS=0

# Prints $1 with every line indented, so a failing checker's report stays visually attached to the
# assertion that produced it.
indent() {
  while IFS= read -r line; do
    echo "      ${line}"
  done <<<"$1"
}

# Builds a throwaway dashboard tree and prints its absolute path. Each scenario gets its own so they
# cannot interfere with one another.
mkfixture() {
  local dir
  dir="$(mktemp -d)"
  mkdir -p "${dir}/dashboards" "${dir}/datasources"
  cat >"${dir}/datasources/datasources.yaml" <<'YAML'
apiVersion: 1
datasources:
  - name: Prometheus
    uid: prometheus
  - name: Loki
    uid: loki
YAML
  printf '%s' "$dir"
}

# Writes one dashboard file. $1 fixture dir, $2 basename, $3 the JSON body.
dashboard() {
  printf '%s\n' "$3" >"${1}/dashboards/${2}"
}

# Runs the checker against a fixture, capturing status and combined output.
run_checker() {
  set +e
  LAST_OUTPUT="$("$PYTHON" "$CHECKER" --dashboards "${1}/dashboards" --datasources "${1}/datasources/datasources.yaml" 2>&1)"
  LAST_STATUS=$?
  set -e
}

# $1 human name, $2 expected exit status, $3 substring the report must contain ('' to skip).
assert_run() {
  tests_run=$((tests_run + 1))
  local name="$1" want="$2" needle="${3:-}"
  if [[ "$LAST_STATUS" != "$want" ]]; then
    tests_failed=$((tests_failed + 1))
    echo "FAIL: ${name} -- expected exit ${want}, got ${LAST_STATUS}"
    indent "${LAST_OUTPUT}"
    return
  fi
  if [[ -n "$needle" && "$LAST_OUTPUT" != *"$needle"* ]]; then
    tests_failed=$((tests_failed + 1))
    echo "FAIL: ${name} -- report did not mention '${needle}'"
    indent "${LAST_OUTPUT}"
    return
  fi
  echo "ok: ${name}"
}

VALID='{
  "uid": "one",
  "title": "One",
  "panels": [
    { "id": 1, "type": "timeseries", "title": "A", "datasource": { "type": "prometheus", "uid": "prometheus" } }
  ]
}'

# --- the happy path, so a later failure is attributable to the fixture and not the checker --------
FIX="$(mkfixture)"; dashboard "$FIX" 01.json "$VALID"
run_checker "$FIX"
assert_run "a well-formed dashboard passes" 0 "Grafana dashboards OK"

# --- invalid JSON: Grafana logs a provisioning error and serves the stack without the dashboard ---
FIX="$(mkfixture)"; dashboard "$FIX" 01.json "$VALID"; dashboard "$FIX" 02.json '{ "uid": "two", '
run_checker "$FIX"
assert_run "invalid JSON is caught" 1 "not valid UTF-8 JSON"

# --- duplicate uid: provisioning is last-writer-wins, one dashboard silently replaces the other ---
FIX="$(mkfixture)"; dashboard "$FIX" 01.json "$VALID"; dashboard "$FIX" 02.json "${VALID/\"One\"/\"Two\"}"
run_checker "$FIX"
assert_run "a duplicate uid is caught" 1 "already used by"

# --- an unprovisioned datasource: every panel on it reads 'Datasource not found' ------------------
FIX="$(mkfixture)"
dashboard "$FIX" 01.json '{ "uid": "one", "title": "One", "panels": [
  { "id": 1, "type": "timeseries", "title": "A", "datasource": { "type": "tempo", "uid": "tempo" } } ] }'
run_checker "$FIX"
assert_run "an unprovisioned datasource uid is caught" 1 "does not provision"

# --- a ${var} datasource is resolved at view time, not provisioning, so it must NOT fail ----------
FIX="$(mkfixture)"
# shellcheck disable=SC2016  # ${ds} is a GRAFANA template variable and must reach the fixture literally
dashboard "$FIX" 01.json '{ "uid": "one", "title": "One", "panels": [
  { "id": 1, "type": "timeseries", "title": "A", "datasource": { "uid": "${ds}" } } ] }'
run_checker "$FIX"
assert_run "a template-variable datasource is accepted" 0 "Grafana dashboards OK"

# --- duplicate panel id: panel deep links resolve to whichever Grafana finds first ----------------
FIX="$(mkfixture)"
dashboard "$FIX" 01.json '{ "uid": "one", "title": "One", "panels": [
  { "id": 1, "type": "timeseries", "title": "A", "datasource": { "uid": "prometheus" } },
  { "id": 1, "type": "timeseries", "title": "B", "datasource": { "uid": "prometheus" } } ] }'
run_checker "$FIX"
assert_run "a duplicate panel id is caught" 1 "used twice"

# --- a panel inside a COLLAPSED row is walked too: the one nobody opens is the one that rots ------
FIX="$(mkfixture)"
dashboard "$FIX" 01.json '{ "uid": "one", "title": "One", "panels": [
  { "id": 1, "type": "row", "title": "Row", "collapsed": true, "panels": [
    { "id": 2, "type": "timeseries", "title": "", "datasource": { "uid": "prometheus" } } ] } ] }'
run_checker "$FIX"
assert_run "an untitled panel inside a collapsed row is caught" 1 "has no title"

# --- an empty dashboard directory is a setup error, not a pass ------------------------------------
FIX="$(mkfixture)"
run_checker "$FIX"
assert_run "an empty dashboard directory fails loudly" 2 "contains no dashboards"

# --- and the repository's own dashboards must be clean --------------------------------------------
tests_run=$((tests_run + 1))
set +e
LAST_OUTPUT="$(cd "$REPO_ROOT" && "$PYTHON" "$CHECKER" 2>&1)"
LAST_STATUS=$?
set -e
if [[ "$LAST_STATUS" == 0 && "$LAST_OUTPUT" == *"Grafana dashboards OK"* ]]; then
  echo "ok: the repository's own dashboards pass"
else
  tests_failed=$((tests_failed + 1))
  echo "FAIL: the repository's own dashboards do not pass"
  indent "${LAST_OUTPUT}"
fi

echo
if [[ "$tests_failed" -gt 0 ]]; then
  echo "${tests_failed} of ${tests_run} tests FAILED"
  exit 1
fi
echo "all ${tests_run} tests passed"
