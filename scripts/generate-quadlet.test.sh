#!/usr/bin/env bash
#
# Regression tests for scripts/generate-quadlet.py's compose-to-Quadlet translation.
#
# Pure python against synthetic service mappings -- no host, no containers, no
# network, runs in under a second.
#
# Usage:
#   scripts/generate-quadlet.test.sh
#
# Why this file exists. The generator had a drift check from the start, and the
# drift check compares the generator against its own output -- so a translation
# that is uniformly wrong is uniformly consistent and reports clean. Four defects
# reached the tree behind that green check (PR #1933 review, 2026-09-18):
#
#   * an exec-form `CMD` healthcheck re-joined into an unquoted shell string, so
#     a password containing a space broke the probe forever and one containing
#     `;` ran whatever followed, inside the container, every five seconds;
#   * `Notify=healthy` with no `TimeoutStartSec=`, capping four services at
#     systemd's 90s default -- keycloak's own numbers allow 330s -- which with
#     Restart=always is a restart loop that never reports healthy;
#   * raw systemd `%` specifiers copied into `Exec=`, measured LIVE on the
#     testing host: both databases were running with log_line_prefix expanded to
#     the machine id, the unit name and the architecture;
#   * a prefix-unsafe substring replace in the health-variable rewrite.
#
# Every one of them is a property of the translation, not of the output, which is
# the level this file tests at.

# shellcheck disable=SC2016
# The single quotes are the subject of the tests, not an oversight: the fixtures
# feed the translator LITERAL ${...} and `%` text, and a double-quoted string
# would let bash expand it before the translator ever saw it.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GENERATOR="${SCRIPT_DIR}/generate-quadlet.py"
PY="${PYTHON:-python3}"

if [[ ! -f "$GENERATOR" ]]; then
  echo "FATAL: generator not found at ${GENERATOR}" >&2
  exit 1
fi

PASSED=0
FAILED=0
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

# Runs a python expression against the loaded generator module and prints the
# result. $1 is the python body; it may use `g` for the module.
run_py() {
  "$PY" - "$GENERATOR" <<'PYEOF' "$1"
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("genquadlet", sys.argv[1])
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)

try:
    exec(sys.argv[2], {"g": g, "print": print})
except g.Refusal as exc:
    print("REFUSAL: " + str(exc).replace("\n", " "))
PYEOF
}

# $1 label, $2 python body, $3 expected substring
expect() {
  local label="$1" body="$2" want="$3" got
  got="$(run_py "$body" 2>&1)"
  if [[ "$got" == *"$want"* ]]; then
    ok "$label"
  else
    bad "$label"
    printf '        want substring: %s\n' "$want"
    printf '        got           : %s\n' "$got"
  fi
}

# $1 label, $2 python body, $3 substring that must NOT appear
expect_not() {
  local label="$1" body="$2" unwanted="$3" got
  got="$(run_py "$body" 2>&1)"
  if [[ "$got" != *"$unwanted"* ]]; then
    ok "$label"
  else
    bad "$label"
    printf '        must not contain: %s\n' "$unwanted"
    printf '        got             : %s\n' "$got"
  fi
}

echo "== finding 3: an exec-form CMD keeps its argument boundaries =="

expect "a value with a space stays ONE argument" \
  'print(g._health({"test": ["CMD", "redis-cli", "-a", "pass word", "ping"]}, {}, "redis")[0][0])' \
  "HealthCmd=redis-cli -a 'pass word' ping"

expect "a value with a semicolon cannot start a second command" \
  'print(g._health({"test": ["CMD", "redis-cli", "-a", "x; touch /tmp/pwned", "ping"]}, {}, "redis")[0][0])' \
  "'x; touch /tmp/pwned'"

expect "a value with a command substitution is inert" \
  'print(g._health({"test": ["CMD", "sh", "-c", "echo $(id -u)"]}, {}, "x")[0][0])' \
  "'echo \$(id -u)'"

expect "an embedded single quote is closed and reopened, not terminated" \
  'print(g._sh_quote("it'"'"'s"))' \
  "'it'\\''s'"

expect "a DELIBERATE \${VAR} keeps double quotes, so it still expands" \
  'print(g._health({"test": ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD:?}", "ping"]}, {"REDIS_PASSWORD": "x"}, "redis")[0][0])' \
  'HealthCmd=redis-cli -a "${REDIS_PASSWORD:?}" ping'

expect "a CMD-SHELL test is left exactly as the author wrote it" \
  'print(g._health({"test": ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:?} -p 15432"]}, {"POSTGRES_USER": "x"}, "db")[0][0])' \
  'HealthCmd=pg_isready -U ${POSTGRES_USER:?} -p 15432'

expect "an unrecognised test: keyword is refused rather than guessed" \
  'print(g._health({"test": ["SHELL", "true"]}, {}, "x"))' \
  "REFUSAL: x: its healthcheck \`test:\` is a list starting with 'SHELL'"

echo
echo "== finding 2: Notify=healthy comes with a start budget =="

expect "keycloak's own numbers, not systemd's 90s default" \
  'print(g._health({"test": ["CMD", "true"], "interval": "10s", "timeout": "10s", "retries": 15, "start_period": "30s"}, {}, "keycloak")[1][0])' \
  "TimeoutStartSec=390"

expect "Notify=healthy is still emitted" \
  'print(g._health({"test": ["CMD", "true"]}, {}, "x")[0])' \
  "Notify=healthy"

expect "a healthcheck-less service gets neither" \
  'print(g._health({}, {}, "x"))' \
  "([], [])"

expect "podman's own defaults are used when compose states none" \
  'print(g._health({"test": ["CMD", "true"]}, {}, "x")[1][0])' \
  "TimeoutStartSec=240"

expect "a compound duration parses" \
  'print(g._seconds("1m30s", "x"))' \
  "90"

expect "a sub-second duration rounds UP, because this is a budget" \
  'print(g._seconds("1500ms", "x"))' \
  "2"

expect "an unreadable duration is refused, not silently zero" \
  'print(g._seconds("soon", "x.healthcheck.interval"))' \
  "REFUSAL: x.healthcheck.interval: 'soon' is not a duration"

echo
echo "== finding 4: systemd specifiers cannot reach the running process =="

expect "postgres keeps its log_line_prefix literal" \
  'print(g._exec("db", {"command": "postgres -c log_line_prefix=%m [%p] %q%u@%d/%a"})[0])' \
  "Exec=postgres -c log_line_prefix=%%m [%%p] %%q%%u@%%d/%%a"

expect "an entrypoint is escaped too" \
  'print(g._exec("x", {"entrypoint": "/bin/sh -c echo %H"})[0])' \
  "Entrypoint=/bin/sh -c echo %%H"

expect "a percent in a health command is escaped" \
  'print(g._health({"test": ["CMD-SHELL", "df --output=pcent | grep 90%"]}, {}, "x")[0][0])' \
  "grep 90%%"

expect "nothing already doubled is left half-escaped" \
  'print(g._escape_percent("%"))' \
  "%%"

echo
echo "== finding 13: the health-variable rewrite respects name boundaries =="

expect "a longer name that starts with a shorter one survives" \
  'print(g._health({"test": ["CMD-SHELL", "check -U ${POSTGRES_USER} -x ${POSTGRES_USER_EXTRA}"]}, {"PGUSER": "${POSTGRES_USER}", "EXTRA": "${POSTGRES_USER_EXTRA}"}, "db")[0][0])' \
  'HealthCmd=check -U ${PGUSER} -x ${EXTRA}'

expect_not "and is NOT corrupted into a name the container never receives" \
  'print(g._health({"test": ["CMD-SHELL", "check -U ${POSTGRES_USER} -x ${POSTGRES_USER_EXTRA}"]}, {"PGUSER": "${POSTGRES_USER}", "EXTRA": "${POSTGRES_USER_EXTRA}"}, "db")[0][0])' \
  'PGUSER_EXTRA'

expect "a name the container is handed under no name at all is still refused" \
  'print(g._health({"test": ["CMD-SHELL", "check -U ${NOWHERE}"]}, {"A": "b"}, "db"))' \
  "REFUSAL: db: the health command names \${NOWHERE}"

echo
printf '%d passed, %d failed\n' "$PASSED" "$FAILED"
[[ $FAILED -eq 0 ]]
