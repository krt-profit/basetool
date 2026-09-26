#!/usr/bin/env bash
set -euo pipefail

export MSYS2_ARG_CONV_EXCL='/rules'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULES_DIR="${REPO_ROOT}/monitoring/loki/rules"
IMAGE="grafana/cortex-tools:latest@sha256:ed86004a3d5eb7a8351fd1fa9c2a8f4f70c6b133eb57846c55aef9e4f485da0c"

[[ -d "${RULES_DIR}" ]] || { echo "FAIL: ${RULES_DIR} does not exist"; exit 1; }

mapfile -t RULE_FILES < <(find "${RULES_DIR}" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)
(( ${#RULE_FILES[@]} > 0 )) || { echo "FAIL: no rule files under ${RULES_DIR}"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
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
