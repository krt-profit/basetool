#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# image-pin-gate: ignore-file — the fixtures below pin WRONG tags on purpose. "Repairing" them would
# make this suite pass vacuously, the one outcome a regression suite must never have.
#
# Regression tests for scripts/check-monitoring-image-pins.sh.
#
# Builds throwaway git repositories that reproduce the drift scenarios the gate is meant to (and
# meant NOT to) flag, then asserts its exit status, its report, and — for --fix — what it wrote.
# No network, no Gradle — pure git + bash, runs in a couple of seconds.
#
# Usage:
#   scripts/check-monitoring-image-pins.test.sh
#
# The headline cases are the three the code review caught:
#   * an ADR is a historical record, so a Tempo bump must not turn the gate red and must never let
#     --fix rewrite an accepted decision to a version that decision never made;
#   * `grep -H` — with a single file in the list, grep omits the filename and the report parses the
#     line number as the filename;
#   * the --fix `sed` interpolations — 'ghcr.io/google/cadvisor' contains dots today, and an
#     unescaped dot rewrites neighbouring text that merely looks alike.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKER="${SCRIPT_DIR}/check-monitoring-image-pins.sh"
COMPOSE_FILE="docker-compose.monitoring.yml"

if [[ ! -f "$CHECKER" ]]; then
  echo "FATAL: checker not found at ${CHECKER}" >&2
  exit 1
fi

tests_run=0
tests_failed=0
LAST_OUTPUT=""

# Creates a throwaway temp directory and prints its absolute path. Each scenario gets its own so
# they cannot interfere with one another.
mktmp() {
  mktemp -d "${TMPDIR:-/tmp}/image-pin-check-test.XXXXXX"
}

# Initialises a fresh git repo at $1 with a deterministic identity, gpg signing off and `main` as
# the initial branch — independent of the host's git config. autocrlf stays off so a Windows host
# does not rewrite the fixtures under the checker's feet.
init_repo() {
  local repo="$1"
  git -C "$repo" init -q -b main
  git -C "$repo" config user.email "test@example.com"
  git -C "$repo" config user.name "Image Pin Test"
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config core.autocrlf false
}

# Writes the compose fixture of repo $1 from the remaining arguments, each a full `repository:tag`
# reference, in the same shape the real docker-compose.monitoring.yml has.
write_compose() {
  local repo="$1" ref
  shift
  {
    printf 'services:\n'
    for ref in "$@"; do
      printf '  svc_%s:\n    image: %s\n' "$RANDOM" "$ref"
    done
  } >"${repo}/${COMPOSE_FILE}"
}

# Writes file $2 (repo-relative, directories created on demand) of repo $1 from stdin.
write_doc() {
  local repo="$1" path="$2"
  mkdir -p "$(dirname "${repo}/${path}")"
  cat >"${repo}/${path}"
}

# Stages everything and commits in repo $1. `git ls-files` reads the index, so this is what puts the
# fixtures in the checker's field of view.
commit_all() {
  local repo="$1"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m "fixture"
}

# Runs the checker inside repo $1, passing the remaining arguments through (e.g. --fix). Returns the
# checker's exit code; its merged output lands in the global LAST_OUTPUT.
run_checker() {
  local repo="$1" rc=0
  shift
  LAST_OUTPUT="$(cd "$repo" && bash "$CHECKER" "$@" 2>&1)" || rc=$?
  return "$rc"
}

# Records a passed/failed assertion with a description; dumps LAST_OUTPUT on failure so a red test
# is self-diagnosing.
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

# assert_contains <substring> <description> — fails unless LAST_OUTPUT contains the substring.
# Distinguishes a correct verdict from an incidental exit code (a set -e or parse error also exits
# non-zero), so a test cannot pass red-for-the-wrong-reason.
assert_contains() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" == *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output missing: '${needle}')"
  fi
}

# assert_excludes <substring> <description> — fails if LAST_OUTPUT contains it.
assert_excludes() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" != *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output unexpectedly contained: '${needle}')"
  fi
}

# assert_file_contains <file> <substring> <description>.
assert_file_contains() {
  local file="$1" needle="$2" desc="$3"
  if grep -qF -- "$needle" "$file"; then
    record 1 "$desc"
  else
    record 0 "$desc (file missing: '${needle}')"
  fi
}

# assert_file_excludes <file> <substring> <description>.
assert_file_excludes() {
  local file="$1" needle="$2" desc="$3"
  if grep -qF -- "$needle" "$file"; then
    record 0 "$desc (file unexpectedly contained: '${needle}')"
  else
    record 1 "$desc"
  fi
}

# ---------------------------------------------------------------------------
# Scenario 1: a stale pin in an ordinary runbook is flagged, and --fix repairs it. This is the gate
# doing its job; every exclusion below is only defensible while this still holds.
# ---------------------------------------------------------------------------
scenario_ordinary_doc_is_gated() {
  echo "Scenario: stale pin in an ordinary doc (must FAIL, then be fixable)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/alloy:v1.18.0"
  write_doc "$repo" "docs/MONITORING_ROLLOUT_RUNBOOK.md" <<'DOC'
# Rollout runbook

    docker run --rm grafana/alloy:v1.17.1 fmt /cfg/config.alloy
DOC
  write_doc "$repo" "README.md" <<'DOC'
# Readme
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 1 "$rc" "the stale runbook pin is flagged"
  assert_contains "docs/MONITORING_ROLLOUT_RUNBOOK.md:3" "the report names the file and its line"
  assert_contains "grafana/alloy:v1.17.1  ->  v1.18.0" "the report names found and wanted tag"

  rc=0
  run_checker "$repo" --fix || rc=$?
  assert_exit 0 "$rc" "--fix succeeds"
  assert_file_contains "${repo}/docs/MONITORING_ROLLOUT_RUNBOOK.md" "grafana/alloy:v1.18.0" \
    "--fix rewrote the pin to the compose tag"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 2 (the review finding): an ADR quotes the tag its decision pinned. The next Dependabot
# bump must not turn the gate red — and --fix must not rewrite an accepted decision record to a
# version that decision never made.
# ---------------------------------------------------------------------------
scenario_adr_is_a_historical_record() {
  echo "Scenario: ADR quoting the tag it decided on (must PASS, must stay verbatim)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/tempo:3.4.0"
  write_doc "$repo" "docs/adr/0076-tempo-3x-monolithic-no-kafka.md" <<'DOC'
# ADR-0076: Tempo 3.x monolithic, no Kafka

We will upgrade the trace store to the current Tempo 3.x line (pinned to `grafana/tempo:3.0.2`).
DOC
  write_doc "$repo" "README.md" <<'DOC'
# Readme
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 0 "$rc" "a superseded tag inside an ADR is not drift"
  assert_excludes "docs/adr/" "no ADR appears in the report at all"

  rc=0
  run_checker "$repo" --fix || rc=$?
  assert_exit 0 "$rc" "--fix has nothing to do"
  assert_file_contains "${repo}/docs/adr/0076-tempo-3x-monolithic-no-kafka.md" \
    "grafana/tempo:3.0.2" "--fix left the decision record verbatim"
  assert_file_excludes "${repo}/docs/adr/0076-tempo-3x-monolithic-no-kafka.md" \
    "grafana/tempo:3.4.0" "--fix did not backdate today's tag into the ADR"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 3: the changelog exclusion still holds — the case the gate shipped with.
# ---------------------------------------------------------------------------
scenario_changelog_is_excluded() {
  echo "Scenario: release note naming the shipped version (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/grafana-oss:13.0.2"
  write_doc "$repo" "CHANGELOG.md" <<'DOC'
# Changelog

- Pinned back to `grafana/grafana-oss:12.4.1` after the bad tag.
DOC
  write_doc "$repo" "CHANGELOG-ARCHIVE.md" <<'DOC'
# Archive

- Shipped with `grafana/grafana-oss:11.0.0`.
DOC
  write_doc "$repo" "README.md" <<'DOC'
# Readme
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 0 "$rc" "changelog and archive stay out of scope"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 4: the inline escape hatch — a frozen document outside docs/adr/ opts out through the
# repository's existing "Doc type: ... Historical ..." front matter.
# ---------------------------------------------------------------------------
scenario_frozen_front_matter_exempts() {
  echo "Scenario: frozen plan with historical front matter (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "prom/prometheus:v3.13.1"
  write_doc "$repo" "docs/BANK_PLAN.md" <<'DOC'
> **Doc type:** Historical plan — frozen. All five phases shipped.

Validated against `prom/prometheus:v2.55.0` at the time.
DOC
  write_doc "$repo" "README.md" <<'DOC'
# Readme
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 0 "$rc" "the frozen plan is exempt"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 5: the exemption is front matter, not a magic word anywhere in the file. docs/specs/
# INDEX.md quotes the very same header deep in its body as an authoring example, and that file is a
# living index that must stay gated.
# ---------------------------------------------------------------------------
scenario_late_front_matter_does_not_exempt() {
  echo "Scenario: 'Doc type: Historical' quoted below the header (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "prom/prometheus:v3.13.1"
  write_doc "$repo" "docs/specs/INDEX.md" <<'DOC'
> **Doc type:** Living spec — kept in sync with `main`.

# Spec registry

Filler line 4.
Filler line 5.
Filler line 6.
Filler line 7.
Filler line 8.
Filler line 9.
Filler line 10.
Filler line 11.
Frozen specs open with:

> **Doc type:** Historical plan — frozen after implementation.

Validate with `prom/prometheus:v2.55.0`.
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 1 "$rc" "a quoted header deep in the body does not exempt the file"
  assert_contains "docs/specs/INDEX.md" "the living index is still reported"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 6 (the review nit): with exactly ONE file in the list, `grep` without -H omits the
# filename, and the report's field split reads the line number as the filename. Asserting the
# rendered "<file>:<line>" catches that; asserting only the exit code would not.
# ---------------------------------------------------------------------------
scenario_single_file_list_keeps_filename() {
  echo "Scenario: single non-excluded doc (report must still name the file)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/loki:3.7.4"
  # The only tracked Markdown besides the excluded trees, so doc_files holds exactly one entry.
  write_doc "$repo" "CHANGELOG.md" <<'DOC'
# Changelog
DOC
  write_doc "$repo" "docs/adr/0001-x.md" <<'DOC'
# ADR-0001
DOC
  write_doc "$repo" "monitoring/README.md" <<'DOC'
# Monitoring

    docker run --rm grafana/loki:3.7.0 -version
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 1 "$rc" "the lone file is still scanned"
  assert_contains "across 1 tracked file(s)" "the list really did collapse to one file"
  assert_contains "monitoring/README.md:3" "the report names the file, not the line number"

  rc=0
  run_checker "$repo" --fix || rc=$?
  assert_exit 0 "$rc" "--fix succeeds against the single-file list"
  assert_file_contains "${repo}/monitoring/README.md" "grafana/loki:3.7.4" \
    "--fix edited the doc, not a path built from the line number"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 7 (the review nit): the --fix search half is a BRE. 'ghcr.io/google/cadvisor' contains
# dots today, so an unescaped '.' matches any character and rewrites text that merely looks alike.
# ---------------------------------------------------------------------------
scenario_fix_does_not_leak_through_regex_metacharacters() {
  echo "Scenario: --fix on a dotted repository (must not touch look-alike text)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "ghcr.io/google/cadvisor:v0.60.5"
  write_doc "$repo" "monitoring/README.md" <<'DOC'
# Monitoring

    docker run --rm ghcr.io/google/cadvisor:v0.52.0 --version

A look-alike that is NOT the pinned repository: ghcrXio/google/cadvisor:v0.52.0
DOC
  write_doc "$repo" "README.md" <<'DOC'
# Readme
DOC
  commit_all "$repo"

  run_checker "$repo" --fix || rc=$?
  assert_exit 0 "$rc" "--fix succeeds"
  assert_file_contains "${repo}/monitoring/README.md" "ghcr.io/google/cadvisor:v0.60.5" \
    "the real pin was updated"
  assert_file_contains "${repo}/monitoring/README.md" "ghcrXio/google/cadvisor:v0.52.0" \
    "the look-alike survived: the dot was escaped, not treated as 'any character'"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 8: a repository whose every documented pin is already correct passes, and the run reports
# that it actually looked at something. Guards the "green because it could not look" failure mode
# that the exclusions make easier to reach.
# ---------------------------------------------------------------------------
scenario_all_docs_excluded_is_an_error() {
  echo "Scenario: nothing left to scan (must ERROR, not pass)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "prom/blackbox-exporter:v0.28.0"
  write_doc "$repo" "docs/adr/0001-x.md" <<'DOC'
# ADR-0001
DOC
  write_doc "$repo" "CHANGELOG.md" <<'DOC'
# Changelog
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 2 "$rc" "an empty scan set is an error, not a green gate"
  assert_contains "error:" "it says why"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 9 (the 2026-08-16 widening): the same copy-pasteable command in a NON-Markdown file. Nine
# of the ten promtool test files under monitoring/prometheus/tests/ had drifted while the gate only
# looked at *.md, so this is the headline case for the widened scan — a pinned tag is a pinned tag
# whatever the file extension.
# ---------------------------------------------------------------------------
scenario_non_markdown_file_is_gated() {
  echo "Scenario: stale pin in a .yml header comment (must FAIL, then be fixable)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "prom/prometheus:v3.13.2"
  write_doc "$repo" "monitoring/prometheus/tests/some_alerts_test.yml" <<'DOC'
# Run locally:
#   docker run --rm --entrypoint promtool prom/prometheus:v3.13.0 test rules tests/x.yml
rule_files:
  - ../alerts/business.yml
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 1 "$rc" "the stale pin in a .yml is flagged"
  assert_contains "monitoring/prometheus/tests/some_alerts_test.yml:2" "the report names the file and its line"
  assert_contains "prom/prometheus:v3.13.0  ->  v3.13.2" "the report names found and wanted tag"

  rc=0
  run_checker "$repo" --fix || rc=$?
  assert_exit 0 "$rc" "--fix succeeds"
  assert_file_contains "${repo}/monitoring/prometheus/tests/some_alerts_test.yml" \
    "prom/prometheus:v3.13.2" "--fix rewrote the pin in the .yml"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 10: the compose file is the AUTHORITY, not a document about it. Two services pinning the
# same repository at different tags is legal there (a canary, a staged bump); with the widened scan
# the gate would otherwise compare the source of truth against itself and report the loser.
# ---------------------------------------------------------------------------
scenario_compose_authority_is_never_flagged() {
  echo "Scenario: compose pins the same repository twice (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/loki:3.7.4" "grafana/loki:3.7.0"
  write_doc "$repo" "monitoring/README.md" <<'DOC'
# Monitoring
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 0 "$rc" "the authority is not scanned against itself"
  # The success line names the compose file, so match the report's `file:line` shape instead.
  assert_excludes "${COMPOSE_FILE}:1" "the compose file is absent from the drift report"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 11: the inline opt-out for files whose tags are deliberately not pins — this suite's own
# fixtures are the case it exists for. --fix must leave them byte-identical, or a red gate would
# "repair" a regression suite into passing vacuously.
# ---------------------------------------------------------------------------
scenario_ignore_marker_exempts() {
  echo "Scenario: 'image-pin-gate: ignore-file' in the header (must PASS and stay unwritten)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/tempo:3.4.0"
  write_doc "$repo" "scripts/fixtures.sh" <<'DOC'
#!/usr/bin/env bash
#
# image-pin-gate: ignore-file — the tag below is a fixture, not a pin.
write_fixture 'grafana/tempo:3.0.2'
DOC
  write_doc "$repo" "monitoring/README.md" <<'DOC'
# Monitoring
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 0 "$rc" "the marked file is exempt"
  assert_excludes "scripts/fixtures.sh" "it is absent from the report"

  rc=0
  run_checker "$repo" --fix || rc=$?
  assert_exit 0 "$rc" "--fix succeeds"
  assert_file_contains "${repo}/scripts/fixtures.sh" "grafana/tempo:3.0.2" \
    "--fix left the fixture tag alone"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 12: the ignore marker is header matter, not a magic word anywhere in the file — the same
# guard scenario 5 puts on the "Doc type:" convention. A document that DOCUMENTS the marker (this
# suite, the gate's own header, a future contributing guide) must not exempt itself by talking.
# ---------------------------------------------------------------------------
scenario_late_ignore_marker_does_not_exempt() {
  echo "Scenario: ignore marker quoted below the header (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/alloy:v1.18.0"
  {
    printf '# Contributing\n'
    # Push the marker past EXEMPTION_HEADER_LINES, exactly as a prose document would.
    for _ in $(seq 1 20); do printf '\n'; done
    printf 'A file opts out with "image-pin-gate: ignore-file" in its header.\n'
    printf '\n    docker run --rm grafana/alloy:v1.17.1 fmt /cfg/config.alloy\n'
  } >"${repo}/CONTRIBUTING.md"
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 1 "$rc" "a quoted marker deep in the body does not exempt the file"
  assert_contains "CONTRIBUTING.md" "the file is still reported"
  rm -rf "$repo"
}

# ---------------------------------------------------------------------------
# Scenario 13: binary files are skipped rather than scanned. `git ls-files` lists the keystore, the
# fonts and every image in the repository; grep -I is what keeps them out of both the exemption pass
# and the scan, and a NUL byte next to a plausible tag is the cheapest way to prove it.
# ---------------------------------------------------------------------------
scenario_binary_files_are_skipped() {
  echo "Scenario: a binary file carrying a tag-like byte sequence (must PASS)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "prom/blackbox-exporter:v0.28.0"
  printf 'header\000\001\002 prom/blackbox-exporter:v0.24.0 \000trailer\n' \
    >"${repo}/frontend-asset.bin"
  write_doc "$repo" "monitoring/README.md" <<'DOC'
# Monitoring
DOC
  commit_all "$repo"

  run_checker "$repo" || rc=$?
  assert_exit 0 "$rc" "the binary is not scanned"
  assert_excludes "frontend-asset.bin" "it is absent from the report"
  rm -rf "$repo"
}

scenario_ordinary_doc_is_gated
scenario_adr_is_a_historical_record
scenario_changelog_is_excluded
scenario_frozen_front_matter_exempts
scenario_late_front_matter_does_not_exempt
scenario_single_file_list_keeps_filename
scenario_fix_does_not_leak_through_regex_metacharacters
scenario_all_docs_excluded_is_an_error
scenario_non_markdown_file_is_gated
scenario_compose_authority_is_never_flagged
scenario_ignore_marker_exempts
scenario_late_ignore_marker_does_not_exempt
scenario_binary_files_are_skipped

echo
if [[ "$tests_failed" -eq 0 ]]; then
  echo "All ${tests_run} image-pin-gate tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} image-pin-gate test(s) failed."
exit 1
