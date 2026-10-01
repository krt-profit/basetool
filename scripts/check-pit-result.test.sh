#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKER="${SCRIPT_DIR}/check-pit-result.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

tests_run=0
tests_failed=0
LAST_OUTPUT=""

complete_report() {
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="false">\n<mutation detected="true"/>\n<mutation detected="false"/>\n</mutations>\n' > "$1"
}

partial_report() {
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="true">\n<mutation detected="true"/>\n' > "$1"
}

expect() {
  local label="$1" want_rc="$2" want_text="$3" outcome="$4" report="$5" log="$6" rc=0
  tests_run=$((tests_run + 1))
  LAST_OUTPUT="$(bash "$CHECKER" backend "$log" "$report" "$outcome" 2>&1)" || rc=$?
  if [[ "$rc" -eq "$want_rc" && "$LAST_OUTPUT" == *"$want_text"* ]]; then
    echo "  ok    ${label}"
  else
    tests_failed=$((tests_failed + 1))
    echo "  FAIL  ${label}: rc=${rc} (want ${want_rc}); output: ${LAST_OUTPUT}"
  fi
}

: > "${WORK}/clean.log"
printf 'PIT >> SEVERE : PitHelpError: tests are not green\n' > "${WORK}/help.log"
complete_report "${WORK}/complete.xml"
partial_report "${WORK}/partial.xml"
: > "${WORK}/empty.xml"
printf '<mutations partial="false">\n</mutations>\n' > "${WORK}/none.xml"

expect "a finished run with mutations passes" 0 "2 mutations" success "${WORK}/complete.xml" "${WORK}/clean.log"
expect "a step cancelled at the job timeout fails even with a plausible report" 1 "ended 'cancelled'" cancelled "${WORK}/partial.xml" "${WORK}/clean.log"
expect "...and a cancelled step fails even when the report is complete" 1 "ended 'cancelled'" cancelled "${WORK}/complete.xml" "${WORK}/clean.log"
expect "a failed step fails" 1 "ended 'failure'" failure "${WORK}/complete.xml" "${WORK}/clean.log"
expect "an empty outcome fails" 1 "not 'success'" "" "${WORK}/complete.xml" "${WORK}/clean.log"
expect "a report cut off before </mutations> fails although the step reported success" 1 "cut off" success "${WORK}/partial.xml" "${WORK}/clean.log"
expect "PitHelpError fails" 1 "PitHelpError" success "${WORK}/complete.xml" "${WORK}/help.log"
expect "a missing report fails" 1 "did not produce a result" success "${WORK}/absent.xml" "${WORK}/clean.log"
expect "an empty report fails" 1 "did not produce a result" success "${WORK}/empty.xml" "${WORK}/clean.log"
expect "a report with zero mutations fails" 1 "lists no mutations" success "${WORK}/none.xml" "${WORK}/clean.log"

tests_run=$((tests_run + 1))
if grep -q 'check-pit-result.sh' "${SCRIPT_DIR}/../.github/workflows/pitest.yml" \
   && grep -q 'steps.pit.outcome' "${SCRIPT_DIR}/../.github/workflows/pitest.yml"; then
  echo "  ok    pitest.yml gates on this script and on the PIT step's own outcome"
else
  tests_failed=$((tests_failed + 1))
  echo "  FAIL  pitest.yml no longer calls check-pit-result.sh with steps.pit.outcome"
fi

echo "${tests_run} run, ${tests_failed} failed"
[[ "$tests_failed" -eq 0 ]]
