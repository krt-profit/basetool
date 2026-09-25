#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKER="${SCRIPT_DIR}/check-flyway-migrations.sh"
MIG_SUBDIR="db/migration"

if [[ ! -f "$CHECKER" ]]; then
  echo "FATAL: checker not found at ${CHECKER}" >&2
  exit 1
fi

tests_run=0
tests_failed=0

mktmp() {
  mktemp -d "${TMPDIR:-/tmp}/flyway-check-test.XXXXXX"
}

init_repo() {
  local repo="$1"
  git -C "$repo" init -q -b main
  git -C "$repo" config user.email "test@example.com"
  git -C "$repo" config user.name "Flyway Test"
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config core.autocrlf false
}

write_migration() {
  local repo="$1" name="$2"
  mkdir -p "${repo}/${MIG_SUBDIR}"
  printf -- '-- test migration %s\n' "$name" >"${repo}/${MIG_SUBDIR}/${name}"
}

commit_all() {
  local repo="$1" msg="$2"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m "$msg"
}

run_checker() {
  local repo="$1" base_ref="$2" rc=0
  LAST_OUTPUT="$(
    cd "$repo" &&
      FLYWAY_MIGRATION_DIR="$MIG_SUBDIR" FLYWAY_BASE_REF="$base_ref" \
        bash "$CHECKER" 2>&1
  )" || rc=$?
  return "$rc"
}

record() {
  local ok="$1" desc="$2"
  tests_run=$((tests_run + 1))
  if [[ "$ok" -eq 1 ]]; then
    echo "  ok   - ${desc}"
  else
    tests_failed=$((tests_failed + 1))
    echo "  FAIL - ${desc}"
    echo "----- checker output -----"
    echo "${LAST_OUTPUT}"
    echo "--------------------------"
  fi
}

assert_exit() {
  local expected="$1" actual="$2" desc="$3"
  if [[ "$actual" -eq "$expected" ]]; then
    record 1 "${desc} (exit ${expected})"
  else
    record 0 "${desc} (expected exit ${expected}, got ${actual})"
  fi
}

assert_contains() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" == *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output missing: '${needle}')"
  fi
}

assert_excludes() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" != *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output unexpectedly contained: '${needle}')"
  fi
}

scenario_squash_merge_race() {
  echo "Scenario: squash-merge in flight (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"

  write_migration "$repo" "V100__m100.sql"
  write_migration "$repo" "V193__m193.sql"
  commit_all "$repo" "base up to V193"

  git -C "$repo" checkout -q -b feature
  write_migration "$repo" "V194__seed_bank_notifications.sql"
  commit_all "$repo" "feat: add V194 seed"

  git -C "$repo" checkout -q main
  write_migration "$repo" "V194__seed_bank_notifications.sql"
  commit_all "$repo" "feat(bank): ... (#854)"

  git -C "$repo" checkout -q feature
  run_checker "$repo" "main" || rc=$?
  assert_exit 0 "$rc" "branch migration already merged to base is not a self-collision"
  assert_contains "is already present on 'main'" "the skip is reported as a notice"
  assert_excludes "::error" "no error annotation is emitted"
  rm -rf "$repo"
}

scenario_genuine_collision() {
  echo "Scenario: genuine same-number collision (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"

  write_migration "$repo" "V193__m193.sql"
  commit_all "$repo" "base up to V193"

  git -C "$repo" checkout -q -b feature
  write_migration "$repo" "V194__feature_thing.sql"
  commit_all "$repo" "feat: add V194 feature_thing"

  git -C "$repo" checkout -q main
  write_migration "$repo" "V194__other_thing.sql"
  commit_all "$repo" "feat: add V194 other_thing"

  git -C "$repo" checkout -q feature
  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "different file sharing the number is still flagged"
  assert_contains "Out-of-order Flyway migration" "it fails for the ordering reason"
  rm -rf "$repo"
}

scenario_in_order() {
  echo "Scenario: normal in-order addition (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"

  write_migration "$repo" "V193__m193.sql"
  commit_all "$repo" "base up to V193"

  git -C "$repo" checkout -q -b feature
  write_migration "$repo" "V194__new_thing.sql"
  commit_all "$repo" "feat: add V194 new_thing"

  run_checker "$repo" "main" || rc=$?
  assert_exit 0 "$rc" "V194 sorts after base V193"
  assert_contains "ok: V194__new_thing.sql" "the new file was actually evaluated and accepted"
  rm -rf "$repo"
}

scenario_out_of_order() {
  echo "Scenario: new migration numbered below base tip (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"

  write_migration "$repo" "V193__m193.sql"
  commit_all "$repo" "base up to V193"

  git -C "$repo" checkout -q -b feature
  write_migration "$repo" "V150__late_low_number.sql"
  commit_all "$repo" "feat: add V150 late_low_number"

  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "new file at/below the base tip is flagged"
  assert_contains "Out-of-order Flyway migration" "it fails for the ordering reason"
  rm -rf "$repo"
}

scenario_duplicate_in_tree() {
  echo "Scenario: duplicate version in the tree (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"

  write_migration "$repo" "V100__first.sql"
  write_migration "$repo" "V100__second.sql"
  commit_all "$repo" "two files claiming V100"

  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "two files claiming the same number are flagged"
  assert_contains "Duplicate Flyway version" "it fails for the duplicate reason"
  rm -rf "$repo"
}

scenario_partial_skip_still_flags() {
  echo "Scenario: merged file + new below-tip file (must FAIL on the new one)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"

  write_migration "$repo" "V193__m193.sql"
  commit_all "$repo" "base up to V193"

  git -C "$repo" checkout -q -b feature
  write_migration "$repo" "V194__seed.sql"
  write_migration "$repo" "V150__late_low_number.sql"
  commit_all "$repo" "feat: add V194 seed and V150 late"

  git -C "$repo" checkout -q main
  write_migration "$repo" "V194__seed.sql"
  commit_all "$repo" "feat(bank): ... (#854)"

  git -C "$repo" checkout -q feature
  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "the genuinely-new below-tip file is still flagged"
  assert_contains "V150__late_low_number.sql" "the flagged file is V150, not V194"
  assert_contains "is already present on 'main'" "V194 was still recognised as merged-on-base"
  rm -rf "$repo"
}

scenario_partial_skip_evaluates_new() {
  echo "Scenario: merged file + new in-order file (must PASS, new one evaluated)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"

  write_migration "$repo" "V193__m193.sql"
  commit_all "$repo" "base up to V193"

  git -C "$repo" checkout -q -b feature
  write_migration "$repo" "V194__seed.sql"
  write_migration "$repo" "V195__next_thing.sql"
  commit_all "$repo" "feat: add V194 seed and V195 next"

  git -C "$repo" checkout -q main
  write_migration "$repo" "V194__seed.sql"
  commit_all "$repo" "feat(bank): ... (#854)"

  git -C "$repo" checkout -q feature
  run_checker "$repo" "main" || rc=$?
  assert_exit 0 "$rc" "merged V194 skipped, in-order V195 accepted"
  assert_contains "ok: V195__next_thing.sql" "the new file was evaluated, not skipped wholesale"
  assert_excludes "::error" "no error annotation is emitted"
  rm -rf "$repo"
}

scenario_squash_merge_race
scenario_genuine_collision
scenario_in_order
scenario_out_of_order
scenario_duplicate_in_tree
scenario_partial_skip_still_flags
scenario_partial_skip_evaluates_new

echo
if [[ "$tests_failed" -eq 0 ]]; then
  echo "All ${tests_run} Flyway-checker tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} Flyway-checker test(s) failed."
exit 1
