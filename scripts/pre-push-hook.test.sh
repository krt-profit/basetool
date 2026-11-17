#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOK="${SCRIPT_DIR}/../.githooks/pre-push"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

ZERO_SHA1='0000000000000000000000000000000000000000'
ZERO_SHA256='0000000000000000000000000000000000000000000000000000000000000000'
COMMIT='1111111111111111111111111111111111111111'
CHECK_CALL='spotlessCheck --quiet'

cat >"${WORK}/gradlew" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >>"${STUB_LOG}"
exit "${STUB_EXIT}"
STUB
chmod +x "${WORK}/gradlew"

tests_run=0
tests_failed=0

# Runs the hook as git does, from the checkout root with the pushed refs on stdin.
expect() {
  local label="$1" stub_exit="$2" want_rc="$3" want_calls="$4" refs="$5"
  local rc=0 calls
  tests_run=$((tests_run + 1))
  : >"${WORK}/calls"
  (
    cd "${WORK}"
    printf '%s' "${refs}" \
      | STUB_LOG="${WORK}/calls" STUB_EXIT="${stub_exit}" sh "${HOOK}" origin https://example.invalid/r.git
  ) >"${WORK}/output" 2>&1 || rc=$?
  calls="$(cat "${WORK}/calls")"
  if [[ ${rc} -eq ${want_rc} && ${calls} == "${want_calls}" ]]; then
    printf '  ok    %s\n' "${label}"
  else
    tests_failed=$((tests_failed + 1))
    printf '  FAIL  %s: rc=%s (want %s), gradlew calls [%s] (want [%s]); output: %s\n' \
      "${label}" "${rc}" "${want_rc}" "${calls}" "${want_calls}" "$(cat "${WORK}/output")"
  fi
}

printf 'pre-push hook self-test\n'

expect 'a push of commits passes while spotlessCheck passes' \
  0 0 "${CHECK_CALL}" \
  "refs/heads/topic ${COMMIT} refs/heads/topic ${ZERO_SHA1}"$'\n'

expect 'a push of commits is refused while spotlessCheck fails' \
  1 1 "${CHECK_CALL}" \
  "refs/heads/topic ${COMMIT} refs/heads/topic ${ZERO_SHA1}"$'\n'

expect 'a branch deletion skips the check' \
  1 0 '' \
  "(delete) ${ZERO_SHA1} refs/heads/topic ${COMMIT}"$'\n'

expect 'a branch deletion in a SHA-256 repository skips the check' \
  1 0 '' \
  "(delete) ${ZERO_SHA256} refs/heads/topic ${COMMIT}"$'\n'

expect 'a deletion pushed with commits still runs the check' \
  1 1 "${CHECK_CALL}" \
  "(delete) ${ZERO_SHA1} refs/heads/old ${COMMIT}"$'\n'"refs/heads/topic ${COMMIT} refs/heads/topic ${ZERO_SHA1}"$'\n'

expect 'a push with no refs skips the check' \
  1 0 '' \
  ''

printf '%d run, %d failed\n' "${tests_run}" "${tests_failed}"
[[ ${tests_failed} -eq 0 ]]
