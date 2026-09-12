#!/usr/bin/env bash
# =============================================================================
# Validate the Loki ruler's alerting rules (LogQL).
#
# Nothing checked these until 2026-09-12. That matters more than it sounds: the
# Loki ruler loads a rule file, and a group it cannot parse is SKIPPED — the
# remaining alerts in that group simply never evaluate, and the only trace is one
# line in the ruler's own log at startup. A typo therefore disables alerts
# silently and indefinitely, which is the same failure shape as a probe that is
# never registered.
#
# It is not hypothetical either: the first rule added after this script was
# written contained `|~ "acme: (renew|…)"`, and the `: ` inside an unquoted YAML
# scalar made the whole file unparseable. cortextool rejected it in under a
# second.
#
# `rules lint` parses every file and then parses every `expr` as LogQL, so it
# catches both halves — malformed YAML and a valid-YAML expression that Loki
# would refuse. The rules are copied to a temporary directory first because the
# tool rewrites the files it lints in place, and a check must never modify the
# tree it is checking.
#
# Usage:  scripts/check-loki-rules.sh
# Exit:   0 = every rule file parses and every expression is valid LogQL
# =============================================================================
set -euo pipefail

# Git-Bash/MSYS rewrites arguments that look like POSIX paths; the container-side
# /rules would otherwise arrive as C:/Program Files/Git/rules.
export MSYS2_ARG_CONV_EXCL='/rules'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULES_DIR="${REPO_ROOT}/monitoring/loki/rules"
# Pinned by digest, like every other image this repository runs. cortex-tools
# publishes no semver tag for this line, so the digest IS the version.
IMAGE="grafana/cortex-tools:latest@sha256:ed86004a3d5eb7a8351fd1fa9c2a8f4f70c6b133eb57846c55aef9e4f485da0c"

[[ -d "${RULES_DIR}" ]] || { echo "FAIL: ${RULES_DIR} does not exist"; exit 1; }

mapfile -t RULE_FILES < <(find "${RULES_DIR}" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)
(( ${#RULE_FILES[@]} > 0 )) || { echo "FAIL: no rule files under ${RULES_DIR}"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
# Flattened on purpose: the tenant subdirectory ("fake") carries no meaning for
# a syntax check, and a flat directory keeps the container-side paths trivial.
for f in "${RULE_FILES[@]}"; do
  cp "${f}" "${WORK}/$(basename "${f}")"
done
chmod -R 0755 "${WORK}"

echo "==> linting ${#RULE_FILES[@]} Loki rule file(s)"
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

rc=0
for f in "${RULE_FILES[@]}"; do
  name="$(basename "${f}")"
  if ! docker run --rm --network none \
      -v "$(to_native "${WORK}")":/rules \
      "${IMAGE}" rules lint --backend=loki "/rules/${name}"; then
    echo "FAIL: ${f#"${REPO_ROOT}/"} is not a valid Loki rule file (message above)"
    rc=1
  fi
done

[[ "${rc}" -eq 0 ]] || exit 1
echo "==> every Loki rule file parses and every expression is valid LogQL"
