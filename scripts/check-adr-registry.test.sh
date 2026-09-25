#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKER="${SCRIPT_DIR}/check-adr-registry.sh"

if [[ ! -f "$CHECKER" ]]; then
  echo "FATAL: checker not found at ${CHECKER}" >&2
  exit 1
fi

tests_run=0
tests_failed=0
LAST_OUTPUT=""
LAST_STATUS=0

make_repo() {
  local dir rel body
  dir="$(mktemp -d)"
  while [ "$#" -ge 2 ]; do
    rel="$1"
    body="$2"
    shift 2
    mkdir -p "${dir}/$(dirname "$rel")"
    printf '%s\n' "$body" >"${dir}/${rel}"
  done
  git -C "$dir" init --quiet
  printf '%s' "$dir"
}

run_checker() {
  local dir="$1"
  set +e
  LAST_OUTPUT="$(cd "$dir" && bash "$CHECKER" 2>&1)"
  LAST_STATUS=$?
  set -e
}

expect() {
  local name="$1" want_status="$2" dir="$3" want_text="${4:-}"
  tests_run=$((tests_run + 1))
  run_checker "$dir"
  if [ "$LAST_STATUS" -ne "$want_status" ]; then
    printf 'FAIL %s: expected exit %s, got %s\n' "$name" "$want_status" "$LAST_STATUS" >&2
    printf '%s\n' "$LAST_OUTPUT" | sed 's/^/     | /' >&2
    tests_failed=$((tests_failed + 1))
    rm -rf "$dir"
    return
  fi
  if [ -n "$want_text" ] && ! printf '%s' "$LAST_OUTPUT" | grep -qF "$want_text"; then
    printf 'FAIL %s: report did not mention %s\n' "$name" "$want_text" >&2
    printf '%s\n' "$LAST_OUTPUT" | sed 's/^/     | /' >&2
    tests_failed=$((tests_failed + 1))
    rm -rf "$dir"
    return
  fi
  printf 'ok   %s\n' "$name"
  rm -rf "$dir"
}

ADR_ONE='# ADR-0001

Status: Accepted'
ADR_TWO='# ADR-0002

Status: Accepted'
TEMPLATE='# ADR-NNNN - title

Status: Proposed'

index() {
  printf '%s\n' \
    '# Architecture Decision Records (ADRs)' \
    '' \
    '## Index' \
    '' \
    '| ADR | Decision | Status |' \
    '| --- | --- | --- |' \
    "$@"
}

CLEAN_INDEX="$(index \
  '| [0001](0001-first.md) | The first decision. | Accepted |' \
  '| [0002](0002-second.md) | The second decision. | Accepted |')"

# shellcheck disable=SC2016
ESCAPED_PIPE_INDEX="$(index \
  '| [0001](0001-first.md) | Matches `catalog=MATERIAL\|ITEM` and nothing else. | Accepted |' \
  '| [0002](0002-second.md) | The second decision. | Accepted |')"

# shellcheck disable=SC2016
HALF_ESCAPED_INDEX="$(index \
  '| [0001](0001-first.md) | Narrower than `(heads/main|tags/v.+)`, unlike `heads/main\|tags/v.+`. | Accepted |' \
  '| [0002](0002-second.md) | The second decision. | Accepted |')"

# shellcheck disable=SC2016
DOUBLE_PIPE_INDEX="$(index \
  '| [0001](0001-first.md) | Now `(limit == null || amount > limit)` decides it. | Accepted |' \
  '| [0002](0002-second.md) | The second decision. | Accepted |')"

MISSING_ROW_INDEX="$(index \
  '| [0001](0001-first.md) | The first decision. | Accepted |')"

DANGLING_INDEX="$(index \
  '| [0001](0001-first.md) | The first decision. | Accepted |' \
  '| [0002](0002-renamed-since.md) | The second decision. | Accepted |')"

MISLABELLED_INDEX="$(index \
  '| [0001](0001-first.md) | The first decision. | Accepted |' \
  '| [0002](0001-first.md) | The second decision. | Accepted |')"

DUPLICATE_ROW_INDEX="$(index \
  '| [0001](0001-first.md) | The first decision. | Accepted |' \
  '| [0002](0002-second.md) | The second decision. | Accepted |' \
  '| [0002](0002-second.md) | The second decision, again. | Accepted |')"

NO_ROWS_INDEX='# Architecture Decision Records (ADRs)

## Index

Nothing here yet.'

expect "a complete, well-formed index passes" 0 \
  "$(make_repo docs/adr/README.md "$CLEAN_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "ADR registry OK"

expect "an ESCAPED pipe in the prose is NOT flagged" 0 \
  "$(make_repo docs/adr/README.md "$ESCAPED_PIPE_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "ADR registry OK"

expect "the unindexed template is NOT flagged" 0 \
  "$(make_repo docs/adr/README.md "$CLEAN_INDEX" \
    docs/adr/0000-template.md "$TEMPLATE" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "ADR registry OK"

expect "a row half-escaped like ADR-0137 is flagged" 1 \
  "$(make_repo docs/adr/README.md "$HALF_ESCAPED_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "splits into 4 cells"

expect "a '||' operator like ADR-0123 is flagged, and as two cells too many" 1 \
  "$(make_repo docs/adr/README.md "$DOUBLE_PIPE_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "splits into 5 cells"

expect "the report quotes the offending prose" 1 \
  "$(make_repo docs/adr/README.md "$HALF_ESCAPED_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" 'heads/main|tags/v.+'

expect "an ADR with no index row is flagged" 1 \
  "$(make_repo docs/adr/README.md "$MISSING_ROW_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "'0002-second.md' has no row"

expect "a row linking to a file that does not exist is flagged" 1 \
  "$(make_repo docs/adr/README.md "$DANGLING_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "does not exist"

expect "a row whose link points at another ADR is flagged" 1 \
  "$(make_repo docs/adr/README.md "$MISLABELLED_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "links to '0001-first.md'"

expect "two rows for one number are flagged" 1 \
  "$(make_repo docs/adr/README.md "$DUPLICATE_ROW_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "already has a row"

expect "an index with no rows at all says so" 1 \
  "$(make_repo docs/adr/README.md "$NO_ROWS_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE")" "No index rows found"

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[ "$tests_failed" -eq 0 ]
