#!/usr/bin/env bash
#
# Regression tests for scripts/check-adr-numbering.sh.
#
# Builds throwaway git repositories that reproduce the ADR-numbering scenarios
# the checker is meant to (and meant NOT to) flag, then asserts its exit status
# for each. No network, no Gradle -- pure git + bash, runs in a couple of
# seconds. Modelled on scripts/check-flyway-migrations.test.sh.
#
# Usage:
#   scripts/check-adr-numbering.test.sh
#
# Two scenarios reproduce collisions that actually happened: 0154 named two
# accepted decisions on main at once, and 0165 was held by a merged ADR and an
# open PR's ADR simultaneously. A third pins the behaviour that separates this
# checker from the Flyway one -- an ADR filling a gap BELOW the base tip is
# legitimate and must pass, because an open PR routinely holds a number main
# has already passed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKER="${SCRIPT_DIR}/check-adr-numbering.sh"
ADR_SUBDIR="docs/adr"

if [[ ! -f "$CHECKER" ]]; then
  echo "FATAL: checker not found at ${CHECKER}" >&2
  exit 1
fi

tests_run=0
tests_failed=0

# Creates a throwaway temp directory and prints its absolute path. Each
# scenario gets its own so they cannot interfere with one another.
mktmp() {
  mktemp -d "${TMPDIR:-/tmp}/adr-check-test.XXXXXX"
}

# Initialises a fresh git repo at $1 with a deterministic identity, gpg signing
# off, and `main` as the initial branch -- independent of the host's git config.
init_repo() {
  local repo="$1"
  git -C "$repo" init -q -b main
  git -C "$repo" config user.email "test@example.com"
  git -C "$repo" config user.name "ADR Test"
  git -C "$repo" config commit.gpgsign false
  # Keep line endings verbatim so a Windows host's autocrlf does not spew
  # "LF will be replaced by CRLF" warnings on every `git add` in these repos.
  git -C "$repo" config core.autocrlf false
}

# Writes a trivial ADR file $2 (basename) into the ADR dir of repo $1,
# creating the directory on first use.
write_adr() {
  local repo="$1" name="$2"
  mkdir -p "${repo}/${ADR_SUBDIR}"
  printf -- '# ADR-%s -- test\n' "${name:0:4}" >"${repo}/${ADR_SUBDIR}/${name}"
}

# Stages everything and commits with message $2 in repo $1.
commit_all() {
  local repo="$1" msg="$2"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m "$msg"
}

# Runs the checker inside repo $1 with base ref $2. Echoes nothing; returns the
# checker's exit code. Captured output is stashed in the global LAST_OUTPUT.
run_checker() {
  local repo="$1" base_ref="$2" rc=0
  LAST_OUTPUT="$(
    cd "$repo" &&
      ADR_DIR="$ADR_SUBDIR" ADR_BASE_REF="$base_ref" \
        bash "$CHECKER" 2>&1
  )" || rc=$?
  return "$rc"
}

# Records a passed/failed assertion with a description; dumps LAST_OUTPUT on
# failure so a red test is self-diagnosing.
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

# assert_exit <expected-rc> <actual-rc> <description>.
assert_exit() {
  local expected="$1" actual="$2" desc="$3"
  if [[ "$actual" -eq "$expected" ]]; then
    record 1 "${desc} (exit ${expected})"
  else
    record 0 "${desc} (expected exit ${expected}, got ${actual})"
  fi
}

# assert_contains <substring> <description> -- fails unless LAST_OUTPUT contains
# the substring. Distinguishes a correct verdict from an incidental exit code
# (a set -e/git/parse error also exits 1), so a test cannot pass red-for-the-
# wrong-reason.
assert_contains() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" == *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output missing: '${needle}')"
  fi
}

# assert_excludes <substring> <description> -- fails if LAST_OUTPUT contains it.
assert_excludes() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" != *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output unexpectedly contained: '${needle}')"
  fi
}

# Base repo shared by the base-ref scenarios: main holds 0163 and 0165, with
# 0164 deliberately free -- the exact shape main had on 2026-09-13.
seed_base() {
  local repo="$1"
  write_adr "$repo" "0163-the-container-runtime.md"
  write_adr "$repo" "0165-the-frontend-layout-model.md"
  commit_all "$repo" "base: 0163 and 0165, 0164 free"
}

# ---------------------------------------------------------------------------
# Scenario 1: the 0154 shape -- two files claiming one number in the tree.
# Independent of the base ref, and the only check that catches a duplicate that
# has ALREADY landed (as 0154 did, in a single release commit).
# ---------------------------------------------------------------------------
scenario_duplicate_in_tree() {
  echo "Scenario: two ADRs claiming one number in the tree (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"

  write_adr "$repo" "0154-a-container-written-final-session-value.md"
  write_adr "$repo" "0154-self-enrolment-carries-the-answers.md"
  commit_all "$repo" "two files claiming 0154"

  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "two files claiming ADR-0154 are flagged"
  assert_contains "Duplicate ADR number" "it fails for the duplicate reason"
  assert_contains "ADR-0154 is claimed by both" "the message names the number"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 2: the 0165 shape -- the branch adds an ADR whose number a DIFFERENT
# file already holds on the base. This is the collision proper, and the branch
# is green in isolation: only the comparison against the base tip sees it.
# ---------------------------------------------------------------------------
scenario_number_claimed_on_base() {
  echo "Scenario: new ADR takes a number the base already holds (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  seed_base "$repo"

  git -C "$repo" checkout -q -b feature
  write_adr "$repo" "0165-the-phone-class-gets-its-own-layout-contract.md"
  commit_all "$repo" "feat: add 0165 phone class"

  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "a number already held on the base is flagged"
  assert_contains "ADR number already claimed" "it fails for the base-collision reason"
  assert_contains "0165-the-frontend-layout-model.md" "the message names the file already holding it"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 3: the decisive difference from the Flyway checker. The branch adds
# 0164 while the base is already at 0165 -- a gap an open PR legitimately holds.
# A monotonic "must sort after the tip" rule would reject this; ADRs have no
# such requirement and it must PASS.
# ---------------------------------------------------------------------------
scenario_gap_below_base_tip_is_fine() {
  echo "Scenario: new ADR fills a gap below the base tip (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  seed_base "$repo"

  git -C "$repo" checkout -q -b feature
  write_adr "$repo" "0164-an-installable-web-app.md"
  commit_all "$repo" "feat: add 0164"

  run_checker "$repo" "main" || rc=$?
  assert_exit 0 "$rc" "a free number below the base tip is accepted"
  assert_contains "ok: 0164-an-installable-web-app.md" "the new file was evaluated, not skipped"
  assert_excludes "::error" "no error annotation is emitted"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 4: squash-merge in flight. The branch's own ADR is already on the
# base under the SAME filename, because the PR was squash-merged while this run
# was in flight. Comparing it against itself would report a collision with its
# own number; it must be skipped and the run must PASS.
# ---------------------------------------------------------------------------
scenario_squash_merge_race() {
  echo "Scenario: squash-merge in flight (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  seed_base "$repo"

  git -C "$repo" checkout -q -b feature
  write_adr "$repo" "0166-identity-moves-onto-the-app-origin.md"
  commit_all "$repo" "feat: add 0166"

  # Squash-merge equivalent: a brand-new commit on main carrying the same file,
  # WITHOUT merging the feature branch (so feature head stays a non-ancestor).
  git -C "$repo" checkout -q main
  write_adr "$repo" "0166-identity-moves-onto-the-app-origin.md"
  commit_all "$repo" "feat(frontend): ... (#1870)"

  git -C "$repo" checkout -q feature
  run_checker "$repo" "main" || rc=$?
  assert_exit 0 "$rc" "the branch's own ADR already on the base is not a self-collision"
  assert_contains "is already present on 'main'" "the skip is reported as a notice"
  assert_excludes "::error" "no error annotation is emitted"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 5: merged file + genuinely colliding file. The per-file skip must
# drop only the already-merged one and STILL flag the collision. A naive
# "blanket skip the moment any addition is already on base" regression would
# pass every other scenario but let this through.
# ---------------------------------------------------------------------------
scenario_partial_skip_still_flags() {
  echo "Scenario: merged ADR + colliding ADR (must FAIL on the colliding one)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  seed_base "$repo"

  git -C "$repo" checkout -q -b feature
  write_adr "$repo" "0166-identity-moves-onto-the-app-origin.md"
  write_adr "$repo" "0165-the-phone-class-gets-its-own-layout-contract.md"
  commit_all "$repo" "feat: add 0166 and 0165"

  # Squash-merge equivalent of only the 0166 onto main.
  git -C "$repo" checkout -q main
  write_adr "$repo" "0166-identity-moves-onto-the-app-origin.md"
  commit_all "$repo" "feat(frontend): ... (#1870)"

  git -C "$repo" checkout -q feature
  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "the genuinely colliding file is still flagged"
  assert_contains "0165-the-phone-class-gets-its-own-layout-contract.md" "the flagged file is the 0165, not the 0166"
  assert_contains "is already present on 'main'" "the 0166 was still recognised as merged-on-base"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 6: a duplicate the branch INHERITED from the base still fails, even
# though the branch added nothing. This is deliberate and worth pinning: while
# a duplicate sits on main, every PR is red until it rebases onto the fix. The
# alternative -- only flagging additions -- would let a landed duplicate sit
# unreported on main indefinitely, which is exactly how 0154 survived 11 days.
# ---------------------------------------------------------------------------
scenario_inherited_duplicate_still_fails() {
  echo "Scenario: duplicate inherited from the base, branch adds nothing (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_adr "$repo" "0154-a-container-written-final-session-value.md"
  write_adr "$repo" "0154-self-enrolment-carries-the-answers.md"
  commit_all "$repo" "base already carries the duplicate"

  git -C "$repo" checkout -q -b feature
  printf 'unrelated\n' >"${repo}/README.md"
  commit_all "$repo" "docs: unrelated change"

  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "an inherited duplicate is reported on the branch too"
  assert_contains "Duplicate ADR number" "it fails for the duplicate reason"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 7: a plain new ADR above the base tip (must PASS) -- the ordinary
# case, asserted so the checker cannot pass everything by refusing to evaluate.
# ---------------------------------------------------------------------------
scenario_ordinary_new_adr() {
  echo "Scenario: ordinary new ADR above the base tip (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  seed_base "$repo"

  git -C "$repo" checkout -q -b feature
  write_adr "$repo" "0171-an-adr-number-is-claimed-against-the-base-branch.md"
  commit_all "$repo" "docs: add 0170"

  run_checker "$repo" "main" || rc=$?
  assert_exit 0 "$rc" "a free number above the base tip is accepted"
  assert_contains "ok: 0171-an-adr-number-is-claimed-against-the-base-branch.md" "the new file was evaluated"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 8: a RENUMBER into a number the base already holds. Git reports this
# as a rename, not an addition, so a `--diff-filter=A` checker would never look
# at it -- and renumbering is exactly what this repository does when it fixes a
# collision. The destination number is a claim like any other and must FAIL.
# ---------------------------------------------------------------------------
scenario_renumber_into_occupied() {
  echo "Scenario: renumber into a number the base holds (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  seed_base "$repo"
  write_adr "$repo" "0100-something-old.md"
  commit_all "$repo" "base also holds 0100"

  git -C "$repo" checkout -q -b feature
  git -C "$repo" mv "${ADR_SUBDIR}/0100-something-old.md"     "${ADR_SUBDIR}/0165-something-old.md"
  commit_all "$repo" "docs: renumber 0100 -> 0165"

  run_checker "$repo" "main" || rc=$?
  assert_exit 1 "$rc" "a rename onto an occupied number is flagged"
  assert_contains "ADR number already claimed" "it fails for the base-collision reason"
  assert_contains "0165-something-old.md" "the renamed destination is what is reported"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 9: a renumber into a FREE number must pass and be evaluated -- the
# shape of this very change (0154 -> 0170).
# ---------------------------------------------------------------------------
scenario_renumber_into_free() {
  echo "Scenario: renumber into a free number (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  seed_base "$repo"
  write_adr "$repo" "0100-something-old.md"
  commit_all "$repo" "base also holds 0100"

  git -C "$repo" checkout -q -b feature
  git -C "$repo" mv "${ADR_SUBDIR}/0100-something-old.md"     "${ADR_SUBDIR}/0170-something-old.md"
  commit_all "$repo" "docs: renumber 0100 -> 0170"

  run_checker "$repo" "main" || rc=$?
  assert_exit 0 "$rc" "a rename onto a free number is accepted"
  assert_contains "ok: 0170-something-old.md" "the renamed destination was evaluated"
  rm -rf "$repo"
}

scenario_duplicate_in_tree
scenario_number_claimed_on_base
scenario_gap_below_base_tip_is_fine
scenario_squash_merge_race
scenario_partial_skip_still_flags
scenario_inherited_duplicate_still_fails
scenario_ordinary_new_adr
scenario_renumber_into_occupied
scenario_renumber_into_free

echo
if [[ "$tests_failed" -eq 0 ]]; then
  echo "All ${tests_run} ADR-checker tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} ADR-checker test(s) failed."
exit 1
