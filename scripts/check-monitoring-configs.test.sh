#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Regression tests for scripts/check-monitoring-configs.sh.
#
# Copies monitoring/ and docker-compose.monitoring.yml into a throwaway directory, breaks ONE
# configuration at a time, and asserts that the checker fails and names the check that caught it.
# A validation step that was never seen failing is a claim, not a check -- `alloy validate` is the
# standing example: it exits 0 on a broken file, so a gate on its exit code passes everything.
#
# Needs docker (the checks run the components' own images), python3 with PyYAML, and envsubst.
#
# Usage:
#   scripts/check-monitoring-configs.test.sh
#
# The fixtures are BROKEN ON PURPOSE. The last case runs the checker against the repository's own
# files, so a checker reduced to "exit 0" -- or one that stopped finding the files -- cannot pass.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CHECKER="${SCRIPT_DIR}/check-monitoring-configs.sh"

tests_run=0
tests_failed=0
LAST_OUTPUT=""
LAST_STATUS=0

# Prints the root of a fresh copy of the monitoring configuration. Readable by the images' users
# (nobody, loki), as the repository checkout is.
fixture() {
  local root
  root="$(mktemp -d)"
  chmod 0755 "$root"
  cp -R "${REPO_ROOT}/monitoring" "${root}/monitoring"
  cp "${REPO_ROOT}/docker-compose.monitoring.yml" "${root}/docker-compose.monitoring.yml"
  chmod -R a+rX "${root}"
  printf '%s' "$root"
}

run_checker() {
  local root="$1"
  set +e
  LAST_OUTPUT="$(MONITORING_DIR="${root}/monitoring" COMPOSE_FILE="${root}/docker-compose.monitoring.yml" \
    bash "$CHECKER" 2>&1)"
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

# expect_only_failure <check name> <description>: the run failed, the named check failed, and no
# OTHER check did -- so each fixture proves its own check, not a neighbour's.
expect_only_failure() {
  local name="$1" desc="$2" other bad=0
  if [[ "$LAST_STATUS" -eq 0 || "$LAST_OUTPUT" != *"::error title=monitoring-configs::${name} failed"* ]]; then
    bad=1
  fi
  for other in "prometheus: promtool check config" \
    "alertmanager: amtool check-config (rendered template)" \
    "alloy: fmt --test" "alloy: validate (empty output)" "loki: -verify-config"; do
    if [[ "$other" != "$name" && "$LAST_OUTPUT" == *"::error title=monitoring-configs::${other} failed"* ]]; then
      bad=1
    fi
  done
  if [[ "$bad" -eq 0 ]]; then record 1 "$desc"; else record 0 "$desc (exit ${LAST_STATUS})"; fi
}

echo "check-monitoring-configs self-tests"

# 1. prometheus.yml with a key Prometheus does not know.
root="$(fixture)"
printf '\nno_such_top_level_key: true\n' >> "${root}/monitoring/prometheus/prometheus.yml"
run_checker "$root"
expect_only_failure "prometheus: promtool check config" "an unknown key in prometheus.yml fails promtool"
rm -rf "$root"

# 2. prometheus.yml is valid, but a rule file it loads is not -- check config must follow rule_files.
root="$(fixture)"
printf 'groups:\n  - name: broken\n    rules:\n      - alert: Broken\n        expr: up ==\n' \
  > "${root}/monitoring/prometheus/alerts/zz-broken.yml"
run_checker "$root"
expect_only_failure "prometheus: promtool check config" "a broken rule file under alerts/ fails check config"
rm -rf "$root"

# 3. Alertmanager route naming a receiver that does not exist.
root="$(fixture)"
sed -i 's/^\(  receiver:\) .*/\1 no-such-receiver/' "${root}/monitoring/alertmanager/alertmanager.yml.tmpl"
run_checker "$root"
expect_only_failure "alertmanager: amtool check-config (rendered template)" \
  "a route to an undefined receiver fails amtool"
rm -rf "$root"

# 4. A placeholder the documented render does not set -- it would render empty on the host.
root="$(fixture)"
typo="\${SMTP_FROM_TYPO}"
sed -i "s|^\(  smtp_from:\) .*|\1 '${typo}'|" "${root}/monitoring/alertmanager/alertmanager.yml.tmpl"
run_checker "$root"
expect_only_failure "alertmanager: amtool check-config (rendered template)" \
  "an unknown \${PLACEHOLDER} in the template is refused"
if [[ "$LAST_OUTPUT" == *"${typo}"* ]]; then
  record 1 "the report names the unknown placeholder"
else
  record 0 "the report names the unknown placeholder"
fi
rm -rf "$root"

# 5. Alloy config that is not in canonical format.
root="$(fixture)"
sed -i '0,/^\([a-z]\)/s//    \1/' "${root}/monitoring/alloy/config.alloy"
run_checker "$root"
expect_only_failure "alloy: fmt --test" "a mis-indented config.alloy fails alloy fmt --test"
rm -rf "$root"

# 6. Alloy config forwarding to a component that does not exist: `alloy validate` prints an error
# and EXITS 0 -- this is the case a gate on its exit code would pass. The edit keeps the file
# canonically formatted, so only validate may catch it.
root="$(fixture)"
sed -i '0,/forward_to = \[loki\.write\.default\.receiver\]/s//forward_to = [loki.write.no_such_writer.receiver]/' \
  "${root}/monitoring/alloy/config.alloy"
run_checker "$root"
expect_only_failure "alloy: validate (empty output)" "an unknown Alloy component fails validate despite exit 0"
rm -rf "$root"

# 7. Loki config with a key Loki does not know.
root="$(fixture)"
printf '\nno_such_top_level_key: true\n' >> "${root}/monitoring/loki/loki-config.yml"
run_checker "$root"
expect_only_failure "loki: -verify-config" "an unknown key in loki-config.yml fails -verify-config"
rm -rf "$root"

# 8. An image pin without a digest is refused before anything runs against it.
root="$(fixture)"
sed -i 's|\(image: grafana/loki:[^@]*\)@sha256:[0-9a-f]*|\1|' "${root}/docker-compose.monitoring.yml"
run_checker "$root"
expect_only_failure "loki: -verify-config" "a tag-only image pin is refused"
rm -rf "$root"

# 9. The repository itself.
set +e
LAST_OUTPUT="$(bash "$CHECKER" 2>&1)"
LAST_STATUS=$?
set -e
if [[ "$LAST_STATUS" -eq 0 && "$LAST_OUTPUT" == *"OK: all monitoring config checks passed"* ]]; then
  record 1 "the repository's own monitoring configs pass"
else
  record 0 "the repository's own monitoring configs pass (exit ${LAST_STATUS})"
fi

echo "${tests_run} test(s), ${tests_failed} failure(s)"
if [[ "$tests_failed" -ne 0 ]]; then
  exit 1
fi
