#!/usr/bin/env bash
#
# Regression tests for scripts/render-env-d.py.
#
# Pure python + bash against throwaway template directories -- no host, no
# containers, no network, runs in about a second.
#
# Usage:
#   scripts/render-env-d.test.sh
#
# Three of the scenarios below are not hypothetical. They are the mistakes this
# tool exists to prevent, each of which had already happened once:
#
#   * A half-rendered environment reports success. The first bring-up of this
#     stack sourced the host .env inside an `if` condition, where `set -e` is
#     inert, so a failed read produced empty values and postgres came up on its
#     built-in defaults -- a new empty cluster, wrong directory, wrong port,
#     behind a health check that could never pass. `refuses_and_writes_nothing`
#     pins the opposite behaviour.
#   * The generated header documents the ${NAME:-default} forms by example. A
#     renderer that interpolates comment lines mangles its own documentation,
#     and the result still looks like a valid env file. `header_survives` pins it.
#   * A secret containing a `$` must survive verbatim. `dollar_in_value` pins it.

# shellcheck disable=SC2016
# The single quotes are the subject of this file, not an oversight. Every test
# feeds the renderer a LITERAL ${...} template and asserts what it produces; a
# double-quoted string would let bash expand the template before the tool ever
# saw it, and every assertion would then compare bash's output to itself. Applied
# file-wide rather than per line because all 22 occurrences are the same case --
# the same reasoning as the targeted disable in docker/edge/render-and-run.sh.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDERER="${SCRIPT_DIR}/render-env-d.py"
PY="${PYTHON:-python3}"

if [[ ! -f "$RENDERER" ]]; then
  echo "FATAL: renderer not found at ${RENDERER}" >&2
  exit 1
fi

PASSED=0
FAILED=0
say() { printf '%s\n' "$*"; }
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/render-env-d-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# Builds a scenario: $1 name, $2 .env content, $3 template content.
# Leaves $SC_DIR/{env,tmpl,out} ready for the renderer.
scenario() {
  SC_DIR="${WORK}/$1"
  mkdir -p "${SC_DIR}/tmpl" "${SC_DIR}/out"
  printf '%s\n' "$2" > "${SC_DIR}/env"
  printf '%s\n' "$3" > "${SC_DIR}/tmpl/svc.env.tmpl"
}

render() { "$PY" "$RENDERER" --env "${SC_DIR}/env" --templates "${SC_DIR}/tmpl" --out "${SC_DIR}/out" "$@"; }
rendered() { cat "${SC_DIR}/out/svc.env" 2>/dev/null; }

# Asserts that rendering $3 against .env $2 yields a line exactly equal to $4.
expect_line() {
  local name="$1" envc="$2" tmpl="$3" want="$4"
  scenario "$name" "$envc" "$tmpl"
  if ! render >/dev/null 2>&1; then
    bad "${name}: renderer exited non-zero"
    return
  fi
  if rendered | grep -qxF "$want"; then
    ok "${name}"
  else
    bad "${name}: wanted ${want@Q}, got: $(rendered | grep -v '^#' | tr '\n' '|')"
  fi
}

# =============================================================================
say ""
say "== interpolation semantics, as compose defines them =="
# =============================================================================
expect_line "plain_reference"        'A=yes'            'K=${A}'                      'K=yes'
expect_line "unset_is_empty"         'A=yes'            'K=${MISSING}'                'K='
expect_line "default_when_unset"     'A=yes'            'K=${MISSING:-fallback}'      'K=fallback'
expect_line "colon_dash_takes_empty" 'E='               'K=${E:-fallback}'            'K=fallback'
expect_line "bare_dash_keeps_empty"  'E='               'K=${E-fallback}'             'K='
expect_line "set_beats_default"      'A=yes'            'K=${A:-fallback}'            'K=yes'
expect_line "embedded_in_a_string"   'A=yes'            'K=pre-${A}-post'             'K=pre-yes-post'

# The real templates contain exactly this shape for the Keycloak issuer
# (ADR-0167): the issuer derives from the hostname, which itself has a default.
expect_line "nested_default" \
  'H=https://x.example/auth' \
  'K=${ISS:-${H:-https://fallback/auth}/realms/iri}' \
  'K=https://x.example/auth/realms/iri'
expect_line "nested_default_all_unset" \
  '# nothing set' \
  'K=${ISS:-${H:-https://fallback/auth}/realms/iri}' \
  'K=https://fallback/auth/realms/iri'

# A generated password routinely contains $ and -. It must survive byte for byte.
expect_line "dollar_in_value"        'P=aa$bb-cc'       'K=${P}'                      'K=aa$bb-cc'
expect_line "quotes_are_stripped"    'P="quoted"'       'K=${P}'                      'K=quoted'

# =============================================================================
say ""
say "== a required variable that is missing stops everything =="
# =============================================================================
scenario "refuses_and_writes_nothing" '# empty' 'K=${NEEDED:?NEEDED must be set in .env}'
if render >/dev/null 2>&1; then
  bad "refuses_and_writes_nothing: renderer exited 0 on a missing required variable"
else
  ok "refuses_and_writes_nothing: non-zero exit"
fi
if [[ -f "${SC_DIR}/out/svc.env" ]]; then
  bad "refuses_and_writes_nothing: it wrote a file anyway"
else
  ok "refuses_and_writes_nothing: nothing was written"
fi
# Capture first, then match. `render | grep` would be judged by pipefail on the
# RENDERER's exit status, which is 1 here by design -- so the assertion would fail
# precisely when the tool behaves correctly.
refusal="$(render 2>&1)"
if printf '%s' "$refusal" | grep -qF 'NEEDED must be set in .env'; then
  ok "refuses_and_writes_nothing: the message names the variable and its reason"
else
  bad "refuses_and_writes_nothing: the refusal did not carry the template's message"
fi

scenario "colon_question_rejects_empty" 'NEEDED=' 'K=${NEEDED:?must be set}'
if render >/dev/null 2>&1; then
  bad "colon_question_rejects_empty: an empty value satisfied \${NAME:?}"
else
  ok "colon_question_rejects_empty"
fi

scenario "bare_question_accepts_empty" 'NEEDED=' 'K=${NEEDED?must be set}'
if render >/dev/null 2>&1; then
  ok "bare_question_accepts_empty"
else
  bad "bare_question_accepts_empty: \${NAME?} wrongly rejected a set-but-empty value"
fi

# Every missing variable is named, not just the first -- one run, one fix list.
scenario "reports_every_missing" '# empty' 'A=${ONE:?first}
B=${TWO:?second}
C=${THREE:?third}'
out="$(render 2>&1)"
count=$(printf '%s\n' "$out" | grep -cE '^\s+(ONE|TWO|THREE) ')
if [[ "$count" -eq 3 ]]; then
  ok "reports_every_missing: all three named in one run"
else
  bad "reports_every_missing: named ${count} of 3"
fi

# =============================================================================
say ""
say "== the generated header must survive verbatim =="
# =============================================================================
HEADER='# Generated by scripts/generate-quadlet.py -- do not edit. Run the generator.
# A literal passes through; a ${NAME:-default} keeps its default when the host
# sets nothing; a ${NAME:?...} must be set or the render fails.'
scenario "header_survives" 'A=yes' "${HEADER}
K=\${A}"
if render >/dev/null 2>&1; then
  if rendered | grep -qF 'a ${NAME:-default} keeps its default' \
     && rendered | grep -qF 'a ${NAME:?...} must be set'; then
    ok "header_survives: the comment's own examples are untouched"
  else
    bad "header_survives: the header was interpolated -- its examples were mangled"
  fi
  # And crucially, the header's ${NAME:?...} example must NOT have triggered a refusal.
  ok "header_survives: a \${NAME:?} inside a comment does not refuse the render"
else
  bad "header_survives: a \${NAME:?...} in a COMMENT wrongly refused the whole render"
fi

# =============================================================================
say ""
say "== --check reports drift and writes nothing =="
# =============================================================================
scenario "check_detects_drift" 'A=yes' 'K=${A}'
render >/dev/null 2>&1
if render --check >/dev/null 2>&1; then
  ok "check_detects_drift: clean after a render"
else
  bad "check_detects_drift: reported drift against its own output"
fi
printf 'K=tampered\n' > "${SC_DIR}/out/svc.env"
if render --check >/dev/null 2>&1; then
  bad "check_detects_drift: did not notice a tampered file"
else
  ok "check_detects_drift: a tampered file is drift"
fi
if grep -qx 'K=tampered' "${SC_DIR}/out/svc.env"; then
  ok "check_detects_drift: --check wrote nothing"
else
  bad "check_detects_drift: --check overwrote the file it was only asked to inspect"
fi

scenario "check_detects_absence" 'A=yes' 'K=${A}'
if render --check >/dev/null 2>&1; then
  bad "check_detects_absence: an absent output file read as clean"
else
  ok "check_detects_absence"
fi

# =============================================================================
say ""
say "== one file per service, which is what keeps the allow-list closed =="
# =============================================================================
SC_DIR="${WORK}/per_service"
mkdir -p "${SC_DIR}/tmpl" "${SC_DIR}/out"
printf 'SHARED=s\nONLY_A=a\n' > "${SC_DIR}/env"
printf 'K=${SHARED}\nSECRET=${ONLY_A}\n' > "${SC_DIR}/tmpl/alpha.env.tmpl"
printf 'K=${SHARED}\n'                   > "${SC_DIR}/tmpl/beta.env.tmpl"
if render >/dev/null 2>&1; then
  if [[ -f "${SC_DIR}/out/alpha.env" && -f "${SC_DIR}/out/beta.env" ]]; then
    ok "per_service: one output file per template"
  else
    bad "per_service: expected alpha.env and beta.env"
  fi
  if grep -q 'SECRET=' "${SC_DIR}/out/beta.env" 2>/dev/null; then
    bad "per_service: beta received a variable its own template never named"
  else
    ok "per_service: beta sees only what its template names"
  fi
else
  bad "per_service: renderer exited non-zero"
fi

# Mode: these files carry secrets and must not be world-readable.
#
# Skipped where the filesystem cannot express a Unix mode at all. On Windows
# (Git Bash / MSYS) chmod only toggles the read-only bit, so every file reports
# 644 no matter what the renderer asked for -- asserting 640 there would fail on
# a correct tool. The capability is probed rather than the OS name guessed.
probe="${WORK}/mode-probe"
: > "$probe"; chmod 640 "$probe" 2>/dev/null
if [[ "$(stat -c '%a' "$probe" 2>/dev/null)" == "640" ]]; then
  actual="$(stat -c '%a' "${SC_DIR}/out/alpha.env" 2>/dev/null)"
  if [[ "$actual" == "640" ]]; then
    ok "per_service: written 0640"
  else
    bad "per_service: mode is ${actual}, wanted 640"
  fi
else
  say "  skip  per_service: this filesystem cannot express Unix modes (chmod 640 did not stick)"
fi

# =============================================================================
say ""
say "== a retired service's rendered secrets do not stay on the host =="
# =============================================================================
# Until 2026-09-18 --check only compared templates that still EXIST against what
# they render to. A <service>.env whose template had been retired was never
# looked at: not drift, not removed, and the check printed "N file(s) match the
# templates and the .env" over the top of a 0640 file holding that service's
# secrets. The sibling generator reports its leftovers; this one has to remove
# them, because these carry credentials rather than unit text.
scenario "stale" 'A=yes' 'K=${A}'
printf 'OLD_SECRET=leftover\n' > "${SC_DIR}/out/retired.env"

if render --check >/dev/null 2>"${SC_DIR}/check.err"; then
  bad "stale: --check reported success with an orphaned retired.env present"
else
  if grep -q 'retired.env' "${SC_DIR}/check.err"; then
    ok "stale: --check names the orphaned file"
  else
    bad "stale: --check failed but did not name retired.env: $(cat "${SC_DIR}/check.err")"
  fi
fi

# A file the tool does not own must survive, or "clean up the output directory"
# becomes a licence to delete whatever else is in it.
printf 'keep me\n' > "${SC_DIR}/out/notes.txt"
render >"${SC_DIR}/write.out" 2>&1
if [[ -e "${SC_DIR}/out/retired.env" ]]; then
  bad "stale: a plain run left retired.env behind"
else
  ok "stale: a plain run removes it"
fi
if grep -q 'removed retired.env' "${SC_DIR}/write.out"; then
  ok "stale: the removal is named, not silently counted"
else
  bad "stale: the removal was not reported: $(cat "${SC_DIR}/write.out")"
fi
if [[ -e "${SC_DIR}/out/notes.txt" ]]; then
  ok "stale: a non-.env file in the same directory is left alone"
else
  bad "stale: notes.txt was deleted -- the removal is not bounded to *.env"
fi
if render --check >/dev/null 2>&1; then
  ok "stale: --check is clean once the leftover is gone"
else
  bad "stale: --check still red after the cleanup run"
fi

# =============================================================================
say ""
say "=========================================="
say "  passed: ${PASSED}   failed: ${FAILED}"
say "=========================================="
[[ "$FAILED" -eq 0 ]] || exit 1
