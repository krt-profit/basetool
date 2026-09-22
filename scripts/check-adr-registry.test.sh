#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Regression tests for scripts/check-adr-registry.sh.
#
# Builds throwaway git repositories carrying the exact shapes the gate is meant to flag and -- just
# as importantly -- the shapes it must NOT flag, then asserts its exit status and its report. No
# network, no Gradle: pure git + bash, runs in well under a second.
#
# The escaped-pipe case is the one that earns the suite. The gate exists because `\|` and `|` are
# indistinguishable to a reader of a padded 4000-character table row, and a checker that cannot
# tell them apart either would flag all 195 rows that legitimately contain `\|` on the day it
# merged -- the `catalog=MATERIAL\|ITEM` row among them. Both directions are asserted here.
#
# Usage:
#   scripts/check-adr-registry.test.sh
#
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

# A throwaway git repo containing the given files, passed as alternating path/body arguments.
# `git init` matters: the checker anchors itself with `git rev-parse --show-toplevel`, so without
# a repo of its own a temp directory nested under a real checkout would send it to the wrong tree.
# Nothing is staged -- the checker reads the working tree with `find`, not `git ls-files`.
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

# The false-positive guard: prose that legitimately contains a pipe, escaped exactly as the real
# registry escapes it. This is the shape of the `catalog=MATERIAL\|ITEM` row.
#
# The backticks in these three fixtures are load-bearing, not decoration: a `|` inside a Markdown
# CODE SPAN still splits the cell, which is the whole reason ADR-0137 had to escape one that was
# already inside backticks. Keeping them here documents that a code span is not a shelter.
# shellcheck disable=SC2016  # Markdown fixture: the backticks must reach the file literally
ESCAPED_PIPE_INDEX="$(index \
  '| [0001](0001-first.md) | Matches `catalog=MATERIAL\|ITEM` and nothing else. | Accepted |' \
  '| [0002](0002-second.md) | The second decision. | Accepted |')"

# ADR-0137's shape: one pipe escaped correctly, the other not, in the SAME row.
# shellcheck disable=SC2016  # Markdown fixture: the backticks must reach the file literally
HALF_ESCAPED_INDEX="$(index \
  '| [0001](0001-first.md) | Narrower than `(heads/main|tags/v.+)`, unlike `heads/main\|tags/v.+`. | Accepted |' \
  '| [0002](0002-second.md) | The second decision. | Accepted |')"

# ADR-0123's shape: a `||` operator, which splits the row twice.
# shellcheck disable=SC2016  # Markdown fixture: the backticks must reach the file literally
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

# --- the green case -------------------------------------------------------------------------
expect "a complete, well-formed index passes" 0 \
  "$(make_repo docs/adr/README.md "$CLEAN_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "ADR registry OK"

# --- the false-positive guards, which are the point -------------------------------------------
# A gate that cannot tell `\|` from `|` would flag every row that legitimately contains a pipe.
expect "an ESCAPED pipe in the prose is NOT flagged" 0 \
  "$(make_repo docs/adr/README.md "$ESCAPED_PIPE_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "ADR registry OK"

# 0000-template.md is the one numbered file that is expected to carry no row.
expect "the unindexed template is NOT flagged" 0 \
  "$(make_repo docs/adr/README.md "$CLEAN_INDEX" \
    docs/adr/0000-template.md "$TEMPLATE" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "ADR registry OK"

# --- the red cases --------------------------------------------------------------------------
expect "a row half-escaped like ADR-0137 is flagged" 1 \
  "$(make_repo docs/adr/README.md "$HALF_ESCAPED_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "splits into 4 cells"

expect "a '||' operator like ADR-0123 is flagged, and as two cells too many" 1 \
  "$(make_repo docs/adr/README.md "$DOUBLE_PIPE_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" "splits into 5 cells"

# The report has to quote the offending prose: the real rows are ~4000 characters wide and padded,
# so "row 187 is wrong" alone leaves the author hunting for the pipe by eye.
expect "the report quotes the offending prose" 1 \
  "$(make_repo docs/adr/README.md "$HALF_ESCAPED_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE" \
    docs/adr/0002-second.md "$ADR_TWO")" 'heads/main|tags/v.+'

# This is the 0192/0193/0194 case: the file is on disk, numbered correctly, and nothing indexes it.
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

# --- an index that parsed to nothing must not be a silent pass ---------------------------------
# Without this, a heading rename or a table rewritten in another syntax would make the gate green
# by finding nothing to check -- the exact failure it was built to prevent.
expect "an index with no rows at all says so" 1 \
  "$(make_repo docs/adr/README.md "$NO_ROWS_INDEX" \
    docs/adr/0001-first.md "$ADR_ONE")" "No index rows found"

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[ "$tests_failed" -eq 0 ]
