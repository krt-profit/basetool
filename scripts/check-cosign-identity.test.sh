#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Regression tests for scripts/check-cosign-identity.py.
#
# Builds throwaway repository layouts that reproduce each way the cosign signer identity can be
# wrong, then asserts the checker's exit status and its report. No network, no cosign -- pure
# python3 + bash.
#
# Usage:
#   scripts/check-cosign-identity.test.sh
#
# The fixtures are WRONG ON PURPOSE. The first one is the exact shape every copy had until
# 2026-09-22 (audit item CI-SEC-01): unanchored, so `refs/heads/main-x` and `refs/tags/vfoo` were
# trusted. "Repairing" a fixture would make this suite pass vacuously.
#
# The last case runs the checker against the repository itself, so a checker reduced to
# "return 0" cannot pass this suite either -- and neither can one that stopped finding the copies.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKER="${SCRIPT_DIR}/check-cosign-identity.py"

PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || PYTHON=python

tests_run=0
tests_failed=0
LAST_OUTPUT=""
LAST_STATUS=0

# The two copies as they are meant to be written: a promote-style flag inside a YAML run block
# and deploy.sh's overridable default. Quoted heredocs, so every backslash reaches the file as-is.
RELEASE_FLAG="$(cat <<'EOF'
                 --certificate-identity-regexp "^https://github\\.com/${REPO}/\\.github/workflows/release-images\\.yml@refs/(heads/main|tags/v[0-9]+\\.[0-9]+\\.[0-9]+)$" \
EOF
)"
MAIN_FLAG="$(cat <<'EOF'
                 --certificate-identity-regexp "^https://github\\.com/${REPO}/\\.github/workflows/release-images\\.yml@refs/heads/main$" \
EOF
)"
DEPLOY_DEFAULT="$(cat <<'EOF'
COSIGN_IDENTITY_REGEXP="${IRI_COSIGN_IDENTITY_REGEXP:-^https://github\\.com/${COSIGN_REPO}/\\.github/workflows/release-images\\.yml@refs/(heads/main|tags/v[0-9]+\\.[0-9]+\\.[0-9]+)$}"
EOF
)"

# Prints a minimal workflow whose job $1 runs `cosign verify` with the flag line $2.
workflow_with_flag() {
  printf 'jobs:\n  %s:\n    steps:\n      - run: |\n' "$1"
  cat <<'EOF'
          cosign verify "$IMAGE" \
EOF
  printf '%s\n' "$2"
  echo "            --certificate-oidc-issuer x"
}

# Creates a fixture repository from the three copies given as arguments (promote flag, reuse-gate
# flag, deploy.sh default) and prints its root. An empty argument leaves that copy out.
fixture() {
  local root
  root="$(mktemp -d)"
  mkdir -p "${root}/.github/workflows" "${root}/scripts"
  if [[ -n "$1" ]]; then
    workflow_with_flag promote "$1" > "${root}/.github/workflows/promote.yml"
  fi
  if [[ -n "$2" ]]; then
    workflow_with_flag plan "$2" > "${root}/.github/workflows/release-images.yml"
  fi
  if [[ -n "$3" ]]; then
    {
      echo '#!/usr/bin/env bash'
      cat <<'EOF'
COSIGN_REPO="${IRI_COSIGN_REPO:-krt-profit/basetool}"
EOF
      printf '%s\n' "$3"
    } > "${root}/scripts/deploy.sh"
    # A self-test next to it quotes a refused identity on purpose and must not count as a copy.
    printf -- '--certificate-identity-regexp "https://github.com/x/y"\n' > "${root}/scripts/deploy.test.sh"
  fi
  printf '%s' "${root}"
}

run_checker() {
  local root="$1"
  set +e
  LAST_OUTPUT="$("$PYTHON" "$CHECKER" --root "$root" 2>&1)"
  LAST_STATUS=$?
  set -e
}

record() {
  local ok="$1" desc="$2"
  tests_run=$((tests_run + 1))
  if [[ "$ok" -eq 1 ]]; then
    echo "  ok   - ${desc}"
  else
    tests_failed=$((tests_failed + 1))
    echo "  FAIL - ${desc}"
    while IFS= read -r line; do echo "      ${line}"; done <<<"${LAST_OUTPUT}"
  fi
}

expect_pass() {
  local desc="$1"
  if [[ "$LAST_STATUS" -eq 0 ]]; then record 1 "$desc"; else record 0 "$desc (expected exit 0, got ${LAST_STATUS})"; fi
}

expect_fail_with() {
  local needle="$1" desc="$2"
  if [[ "$LAST_STATUS" -ne 0 && "$LAST_OUTPUT" == *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (expected a failure mentioning '${needle}', got exit ${LAST_STATUS})"
  fi
}

echo "check-cosign-identity self-tests"

# 1. The intended shape passes.
root="$(fixture "$RELEASE_FLAG" "$MAIN_FLAG" "$DEPLOY_DEFAULT")"
run_checker "$root"
expect_pass "three anchored, agreeing copies pass"
if [[ "$LAST_OUTPUT" == *"deploy.test.sh"* ]]; then
  record 0 "a *.test.sh file is not read as a copy"
else
  record 1 "a *.test.sh file is not read as a copy"
fi
rm -rf "$root"

# 2. The pre-2026-09-22 shape: unanchored, and `v.+` for the tag.
old_flag="${RELEASE_FLAG//\^https/https}"
old_flag="${old_flag//\[0-9\]+\\\\.\[0-9\]+\\\\.\[0-9\]+)\$/.+)}"
root="$(fixture "$old_flag" "$MAIN_FLAG" "$DEPLOY_DEFAULT")"
run_checker "$root"
expect_fail_with "not anchored at the start" "the historical unanchored promote copy fails"
expect_fail_with "must refuse" "and its behaviour is reported, not only its shape"
rm -rf "$root"

# 3. Anchored at the start only -- a suffix after `main` is still trusted.
root="$(fixture "${RELEASE_FLAG//)\$\"/)\"}" "$MAIN_FLAG" "$DEPLOY_DEFAULT")"
run_checker "$root"
expect_fail_with "not anchored at the end" "a copy missing the trailing \$ fails"
rm -rf "$root"

# 4. Anchored but too permissive: `v.+` still admits `vfoo`.
loose="${DEPLOY_DEFAULT//\[0-9\]+\\\\.\[0-9\]+\\\\.\[0-9\]+)/.+)}"
root="$(fixture "$RELEASE_FLAG" "$MAIN_FLAG" "$loose")"
run_checker "$root"
expect_fail_with "differs from" "a deploy.sh default that drifted from the workflows fails"
expect_fail_with "must refuse https://github.com/krt-profit/basetool/.github/workflows/release-images.yml@refs/tags/vfoo" \
  "an anchored but loose tag class is caught by behaviour"
rm -rf "$root"

# 5. The reuse gate widened to the release alternation -- it must stay main-only.
root="$(fixture "$RELEASE_FLAG" "${RELEASE_FLAG}" "$DEPLOY_DEFAULT")"
run_checker "$root"
expect_fail_with "no main-only identity regexp found" "a reuse gate that trusts tags too is refused"
rm -rf "$root"

# 6. The host copy is missing: the gate must not pass on the CI copies alone.
root="$(fixture "$RELEASE_FLAG" "$MAIN_FLAG" "")"
run_checker "$root"
expect_fail_with "no identity regexp found in scripts/" "a missing deploy.sh copy is reported"
rm -rf "$root"

# 7. Nothing recognisable at all.
root="$(fixture "" "" "")"
run_checker "$root"
expect_fail_with "would pass vacuously" "an empty tree fails instead of passing vacuously"
rm -rf "$root"

# 8. The repository itself.
run_checker "$REPO_ROOT"
expect_pass "the repository's own copies pass"

echo "${tests_run} test(s), ${tests_failed} failure(s)"
if [[ "$tests_failed" -ne 0 ]]; then
  exit 1
fi
