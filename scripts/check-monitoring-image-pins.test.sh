#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
# image-pin-gate: ignore-file

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

mktmp() {
  mktemp -d "${TMPDIR:-/tmp}/image-pin-check-test.XXXXXX"
}

init_repo() {
  local repo="$1"
  git -C "$repo" init -q -b main
  git -C "$repo" config user.email "test@example.com"
  git -C "$repo" config user.name "Image Pin Test"
  git -C "$repo" config commit.gpgsign false
  git -C "$repo" config core.autocrlf false
}

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

write_doc() {
  local repo="$1" path="$2"
  mkdir -p "$(dirname "${repo}/${path}")"
  cat >"${repo}/${path}"
}

commit_all() {
  local repo="$1"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m "fixture"
}

run_checker() {
  local repo="$1" rc=0
  shift
  LAST_OUTPUT="$(cd "$repo" && bash "$CHECKER" "$@" 2>&1)" || rc=$?
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

assert_file_contains() {
  local file="$1" needle="$2" desc="$3"
  if grep -qF -- "$needle" "$file"; then
    record 1 "$desc"
  else
    record 0 "$desc (file missing: '${needle}')"
  fi
}

assert_file_excludes() {
  local file="$1" needle="$2" desc="$3"
  if grep -qF -- "$needle" "$file"; then
    record 0 "$desc (file unexpectedly contained: '${needle}')"
  else
    record 1 "$desc"
  fi
}

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

scenario_single_file_list_keeps_filename() {
  echo "Scenario: single non-excluded doc (report must still name the file)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/loki:3.7.4"
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
  assert_excludes "${COMPOSE_FILE}:1" "the compose file is absent from the drift report"
  rm -rf "$repo"
}

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

scenario_late_ignore_marker_does_not_exempt() {
  echo "Scenario: ignore marker quoted below the header (must FAIL)"
  local repo rc=0
  repo="$(mktmp)"
  init_repo "$repo"
  write_compose "$repo" "grafana/alloy:v1.18.0"
  {
    printf '# Contributing\n'
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
