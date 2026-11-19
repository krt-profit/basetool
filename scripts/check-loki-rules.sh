#!/usr/bin/env bash
set -euo pipefail

export MSYS2_ARG_CONV_EXCL='/work'

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RULES_DIR="${REPO_ROOT}/monitoring/loki/rules"
LOKITOOL_VERSION="3.7.10"
LOKITOOL_SHA256="c9bed8adae26a6638b198b7e0c8390bb0f9c8743d37be0af91759d40780d3848"
RUNNER_IMAGE="alpine:3@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf"

[[ -d "${RULES_DIR}" ]] || {
  echo "FAIL: ${RULES_DIR} does not exist"
  exit 1
}

mapfile -t RULE_FILES < <(find "${RULES_DIR}" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort)
((${#RULE_FILES[@]} > 0)) || {
  echo "FAIL: no rule files under ${RULES_DIR}"
  exit 1
}

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/rules"
for f in "${RULE_FILES[@]}"; do
  cp "${f}" "${WORK}/rules/$(basename "${f}")"
done

echo "==> fetching lokitool ${LOKITOOL_VERSION}"
curl -fsSL --retry 5 --retry-delay 10 \
  "https://github.com/grafana/loki/releases/download/v${LOKITOOL_VERSION}/lokitool-linux-amd64.zip" \
  -o "${WORK}/lokitool.zip"
echo "${LOKITOOL_SHA256}  ${WORK}/lokitool.zip" | sha256sum -c - >/dev/null || {
  echo "FAIL: lokitool-linux-amd64.zip ${LOKITOOL_VERSION} does not match its pinned SHA-256"
  exit 1
}
chmod -R a+rX "${WORK}"

echo "==> linting ${#RULE_FILES[@]} Loki rule file(s)"
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# lokitool parses every rule's expr with Loki's LogQL parser and exits non-zero on the first error;
# --dry-run keeps it from rewriting the file.
docker run --rm --network none --platform linux/amd64 \
  -v "$(to_native "${WORK}")":/work:ro \
  "${RUNNER_IMAGE}" sh -c '
set -eu
unzip -p /work/lokitool.zip >/tmp/lokitool
chmod 0755 /tmp/lokitool
rc=0
for f in /work/rules/*; do
  if ! /tmp/lokitool rules lint --dry-run "$f"; then
    echo "FAIL: monitoring/loki/rules/${f##*/} is not a valid Loki rule file (message above)"
    rc=1
  fi
done
exit "$rc"
'

echo "==> every Loki rule file parses and every expression is valid LogQL"
