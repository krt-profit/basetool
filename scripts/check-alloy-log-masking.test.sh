#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKER="${SCRIPT_DIR}/check-alloy-log-masking.py"

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

indent() {
  while IFS= read -r line; do
    echo "      ${line}"
  done <<<"$1"
}

fixture() {
  local dir
  dir="$(mktemp -d)"
  cat >"${dir}/config.alloy"
  printf '%s' "${dir}/config.alloy"
}

run_checker() {
  set +e
  LAST_OUTPUT="$("$PYTHON" "$CHECKER" --config "$1" 2>&1)"
  LAST_STATUS=$?
  set -e
}

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

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	stage.replace {
		expression = "username=(\"[^\"]*\"|\\S+)"
		replace    = "***"
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "a well-formed mask passes" 0 "Alloy log masking OK"

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	stage.replace {
		expression = "username=(\\S+)"
		replace    = "${1}***"
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "a \${1} back-reference is caught" 1 "expands no back-references"

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	stage.replace {
		expression = "(username=)(\\S+)"
		replace    = "***"
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "a second capture group is caught on its own" 1 "has 2 capturing groups"

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	stage.replace {
		expression = "(ipAddress=)(\\S+)"
		replace    = "${1}***"
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "the pattern that shipped to production is caught" 1 "2 problem(s)"

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	stage.replace {
		expression = "(?i)(?:bearer\\s+|token\\s*[:=]?\\s*)([a-zA-Z0-9\\-_.+/=]+)"
		replace    = "***"
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "(?:...) and the (?i) flag are not counted as groups" 0 "Alloy log masking OK"

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	stage.replace {
		expression = "pid \\(([0-9]+)[)]"
		replace    = "***"
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "an escaped or bracketed parenthesis is not a group" 0 "Alloy log masking OK"

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	stage.replace {
		expression = "(?P<field>username=)(?P<value>\\S+)"
		replace    = "***"
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "a named capture group is counted" 1 "has 2 capturing groups"

FIX="$(fixture <<'ALLOY'
loki.process "container_mask" {
	stage.match {
		selector = "{app=\"mon-grafana\"}"

		stage.replace {
			expression = "(uname=)(\\S+)"
			replace    = "${1}***"
		}
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "a stage nested inside stage.match is checked" 1 "uname="

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	stage.replace {
		expression = "(eyJ[a-zA-Z0-9_-]{5,}\\.eyJ[a-zA-Z0-9_-]{5,})"
		replace    = "JWT_***"
	}

	stage.replace {
		expression = "(uname=)(\\S+)"
		replace    = "${1}***"
	}
}
ALLOY
)"
run_checker "$FIX"
assert_run "a regex quantifier brace does not hide the next stage" 1 "uname="

FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	forward_to = [loki.write.default.receiver]
}
ALLOY
)"
run_checker "$FIX"
assert_run "a config with no stage.replace fails loudly" 2 "contains no stage.replace"

run_checker "/nonexistent/config.alloy"
assert_run "a missing config fails loudly" 2 "does not exist"

tests_run=$((tests_run + 1))
set +e
LAST_OUTPUT="$(cd "$REPO_ROOT" && "$PYTHON" "$CHECKER" 2>&1)"
LAST_STATUS=$?
set -e
if [[ "$LAST_STATUS" == 0 && "$LAST_OUTPUT" == *"Alloy log masking OK"* ]]; then
  echo "ok: the repository's own Alloy config passes"
else
  tests_failed=$((tests_failed + 1))
  echo "FAIL: the repository's own Alloy config does not pass"
  indent "${LAST_OUTPUT}"
fi

echo
if [[ "$tests_failed" -gt 0 ]]; then
  echo "${tests_failed} of ${tests_run} tests FAILED"
  exit 1
fi
echo "all ${tests_run} tests passed"
