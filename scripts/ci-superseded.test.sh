#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKER="${SCRIPT_DIR}/ci-superseded.sh"

TIP='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
HEAD_SHA='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'

tests_run=0
tests_failed=0

expect() {
  local label="$1" want_skip="$2" want_text="$3"
  shift 3
  local output rc=0
  tests_run=$((tests_run + 1))
  output="$(bash "$CHECKER" --decide "$@" 2>&1)" || rc=$?
  if [[ ${rc} -eq 0 && ${output} == *"skip=${want_skip}"* && ${output} == *"${want_text}"* ]]; then
    printf '  ok    %s\n' "${label}"
  else
    tests_failed=$((tests_failed + 1))
    printf '  FAIL  %s: rc=%s; output: %s\n' "${label}" "${rc}" "${output}"
  fi
}

expect_usage() {
  local label="$1"
  shift
  local output rc=0
  tests_run=$((tests_run + 1))
  output="$(bash "$CHECKER" "$@" 2>&1)" || rc=$?
  if [[ ${rc} -eq 2 && ${output} == *usage* ]]; then
    printf '  ok    %s\n' "${label}"
  else
    tests_failed=$((tests_failed + 1))
    printf '  FAIL  %s: rc=%s (want 2); output: %s\n' "${label}" "${rc}" "${output}"
  fi
}

printf 'ci-superseded self-test\n'

expect 'a superseded main push skips' \
  true 'superseded: main'"'"'s tip is already aaaaaaaaaaaa' \
  push refs/heads/main "${HEAD_SHA}" "${TIP}" ahead

expect 'the tip of main builds' \
  false 'this commit is the tip of main' \
  push refs/heads/main "${TIP}" "${TIP}" identical

expect 'a pull request never skips' \
  false 'not a main push' \
  pull_request refs/pull/42/merge "${HEAD_SHA}" "${TIP}" ahead

expect 'a merge group never skips' \
  false 'not a main push' \
  merge_group refs/heads/gh-readonly-queue/main/pr-42-abc "${HEAD_SHA}" "${TIP}" ahead

expect 'a workflow_dispatch never skips' \
  false 'not a main push' \
  workflow_dispatch refs/heads/main "${HEAD_SHA}" "${TIP}" ahead

expect 'a push to another branch never skips' \
  false 'not a main push' \
  push refs/heads/topic "${HEAD_SHA}" "${TIP}" ahead

expect 'an unreadable tip builds' \
  false "main's tip could not be read" \
  push refs/heads/main "${HEAD_SHA}" '' ahead

expect 'a malformed tip builds' \
  false "main's tip could not be read" \
  push refs/heads/main "${HEAD_SHA}" 'not-a-sha' ahead

expect 'a diverged commit builds' \
  false "not an ancestor of main's tip aaaaaaaaaaaa (compare says 'diverged'" \
  push refs/heads/main "${HEAD_SHA}" "${TIP}" diverged

expect 'a commit ahead of the tip builds' \
  false "not an ancestor of main's tip aaaaaaaaaaaa (compare says 'behind'" \
  push refs/heads/main "${HEAD_SHA}" "${TIP}" behind

expect 'an unreadable comparison builds' \
  false "not an ancestor of main's tip aaaaaaaaaaaa (compare says 'unknown'" \
  push refs/heads/main "${HEAD_SHA}" "${TIP}" ''

expect 'an identical comparison builds' \
  false "not an ancestor of main's tip aaaaaaaaaaaa (compare says 'identical'" \
  push refs/heads/main "${HEAD_SHA}" "${TIP}" identical

expect 'an error body never reaches the reason' \
  false "compare says 'unknown'" \
  push refs/heads/main "${HEAD_SHA}" "${TIP}" '{"message":"Not Found","status":"404"}'

expect_usage 'no mode is a usage error'
expect_usage 'a short --decide is a usage error' --decide push refs/heads/main "${HEAD_SHA}"
expect_usage 'a short --check is a usage error' --check push refs/heads/main

printf '\n%d test(s), %d failure(s)\n' "${tests_run}" "${tests_failed}"
[[ ${tests_failed} -eq 0 ]]
