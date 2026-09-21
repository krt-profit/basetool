#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Regression tests for scripts/check-alloy-log-masking.py.
#
# Builds throwaway Alloy configs that reproduce each way a masking stage can be wrong, then asserts
# the checker's exit status and its report. No network, no Gradle, no Alloy -- pure python3 + bash.
#
# Usage:
#   scripts/check-alloy-log-masking.test.sh
#
# The fixtures below are WRONG ON PURPOSE. "Repairing" them would make this suite pass vacuously,
# which is the one outcome a regression suite must never have -- and it is the whole reason this
# gate exists at all: the production patterns were also syntactically fine, also accepted by
# `alloy fmt`, and also wrong for as long as they had existed.
#
# Both failure modes are asserted, because they are independent. A stage can overwrite a field name
# without ever mentioning ${1} (two groups, literal replacement), and a stage can emit ${1} verbatim
# with only one group. The real patterns happened to have both, which is exactly why checking for
# one of them would have looked sufficient.
#
# The last case runs the checker against the repository's own config, so a checker reduced to
# "return 0" cannot pass this suite either.

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

# Prints $1 with every line indented, so a failing checker's report stays visually attached to the
# assertion that produced it.
indent() {
  while IFS= read -r line; do
    echo "      ${line}"
  done <<<"$1"
}

# Reads an Alloy config from stdin into a throwaway directory and prints the file's absolute path.
# Each scenario gets its own directory so they cannot interfere with one another.
fixture() {
  local dir
  dir="$(mktemp -d)"
  cat >"${dir}/config.alloy"
  printf '%s' "${dir}/config.alloy"
}

# Runs the checker against the config at $1, capturing its status and combined output.
run_checker() {
  set +e
  LAST_OUTPUT="$("$PYTHON" "$CHECKER" --config "$1" 2>&1)"
  LAST_STATUS=$?
  set -e
}

# $1 human name, $2 expected exit status, $3 substring the report must contain ('' to skip).
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

# --- the happy path, so a later failure is attributable to the fixture and not the checker --------
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

# --- FAILURE MODE 1: a back-reference in the replacement is emitted as literal text ---------------
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

# --- FAILURE MODE 2: a second capture group is overwritten along with the value -------------------
# Note the LITERAL replacement: this stage never mentions ${1}, so the back-reference rule alone
# would pass it while the field name is still destroyed.
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

# --- and the shape that actually shipped: both defects in one stage -------------------------------
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

# --- a non-capturing group does not capture, so an alternation may be grouped with (?:...) --------
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

# --- a parenthesis that is escaped or inside a character class is not a group ---------------------
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

# --- a named group DOES capture, and RE2 overwrites it exactly like a numbered one ----------------
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

# --- a stage nested inside stage.match must be found: most of the real ones are ------------------
# A scanner that only looked at top level would check the Keycloak FILE mask and silently miss every
# container mask -- including the keycloak-stdout copy of the very same patterns.
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

# --- a brace inside a regex quantifier must not end the block early ------------------------------
# "{5,}" appears in the real JWT pattern; naive brace matching walks out of the block on it and the
# stage's own attributes are never seen.
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

# --- a config with no masking at all is a setup error, not a pass --------------------------------
FIX="$(fixture <<'ALLOY'
loki.process "mask" {
	forward_to = [loki.write.default.receiver]
}
ALLOY
)"
run_checker "$FIX"
assert_run "a config with no stage.replace fails loudly" 2 "contains no stage.replace"

# --- a missing file is a setup error too ---------------------------------------------------------
run_checker "/nonexistent/config.alloy"
assert_run "a missing config fails loudly" 2 "does not exist"

# --- and the repository's own Alloy config must be clean ------------------------------------------
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
