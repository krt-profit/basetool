#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MONITORING_DIR="${MONITORING_DIR:-${REPO_ROOT}/monitoring}"
COMPOSE_FILE="${COMPOSE_FILE:-${REPO_ROOT}/docker-compose.monitoring.yml}"

PYTHON="${PYTHON:-python3}"
command -v "$PYTHON" >/dev/null 2>&1 || PYTHON=python

declare -A AM_DUMMY=(
  [SMTP_SMARTHOST]="smtp.example.invalid:587"
  [SMTP_FROM]="alerts@example.invalid"
  [SMTP_AUTH_USERNAME]="ci-dummy-user"
  [SMTP_AUTH_PASSWORD]="ci-dummy-password"
  [ALERT_EMAIL_TO]="ops@example.invalid"
  [HEARTBEAT_URL]="https://heartbeat.example.invalid/ping/ci"
  [DISCORD_WEBHOOK_URL]="https://discord.example.invalid/api/webhooks/0/ci"
)

failures=()
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

image_of() {
  local ref
  ref="$("$PYTHON" -c '
import sys, yaml
with open(sys.argv[1], encoding="utf-8") as f:
    doc = yaml.safe_load(f)
print(((doc.get("services") or {}).get(sys.argv[2]) or {}).get("image", ""))
' "$COMPOSE_FILE" "$1")"
  case "$ref" in
    *@sha256:*) printf '%s' "$ref" ;;
    *)
      echo "::error title=monitoring-configs::service '$1' in ${COMPOSE_FILE##*/} has no digest-pinned image ('${ref}')" >&2
      return 1
      ;;
  esac
}

run_check() {
  local name="$1"
  shift
  echo "=== ${name}"
  if "$@"; then
    echo "--- ${name}: OK"
  else
    echo "::error title=monitoring-configs::${name} failed"
    failures+=("$name")
  fi
}

check_prometheus() {
  local image
  image="$(image_of prometheus)" || return 1
  docker run --rm --entrypoint promtool \
    -v "${MONITORING_DIR}/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
    -v "${MONITORING_DIR}/prometheus/alerts:/etc/prometheus/alerts:ro" \
    "$image" check config /etc/prometheus/prometheus.yml
}

check_alertmanager() {
  local image template rendered name used unknown=0
  image="$(image_of alertmanager)" || return 1
  template="${MONITORING_DIR}/alertmanager/alertmanager.yml.tmpl"
  rendered="${WORK}/alertmanager.yml"
  used="$(grep -v '^[[:space:]]*#' "$template" | grep -o "\\\${[A-Za-z_][A-Za-z0-9_]*}" | sort -u || true)"
  for name in $used; do
    name="${name#\$\{}"
    name="${name%\}}"
    if [[ -z "${AM_DUMMY[$name]+set}" ]]; then
      echo "alertmanager.yml.tmpl uses \${${name}}, which the documented render does not set — it would render EMPTY" >&2
      unknown=1
    fi
  done
  [[ "$unknown" -eq 0 ]] || return 1
  (
    for name in "${!AM_DUMMY[@]}"; do
      export "${name}=${AM_DUMMY[$name]}"
    done
    envsubst < "$template" > "$rendered"
  )
  if grep -nF "\${" "$rendered" | grep -v '^[0-9]*:[[:space:]]*#'; then
    echo "the rendered alertmanager.yml still contains \${...}" >&2
    return 1
  fi
  chmod 0755 "$WORK"
  chmod 0644 "$rendered"
  docker run --rm --entrypoint amtool \
    -v "${rendered}:/etc/alertmanager/alertmanager.yml:ro" \
    "$image" check-config /etc/alertmanager/alertmanager.yml
}

check_alloy_fmt() {
  local image
  image="$(image_of alloy)" || return 1
  docker run --rm -v "${MONITORING_DIR}/alloy:/cfg:ro" "$image" fmt --test /cfg/config.alloy
}

check_alloy_validate() {
  local image out
  image="$(image_of alloy)" || return 1
  out="$(docker run --rm -v "${MONITORING_DIR}/alloy:/cfg:ro" "$image" validate /cfg/config.alloy 2>&1 || true)"
  if [[ -n "$out" ]]; then
    printf '%s\n' "$out" >&2
    return 1
  fi
  echo "alloy validate: no output"
}

check_loki() {
  local image
  image="$(image_of loki)" || return 1
  docker run --rm \
    -v "${MONITORING_DIR}/loki/loki-config.yml:/etc/loki/loki-config.yml:ro" \
    -v "${MONITORING_DIR}/loki/rules:/etc/loki/rules:ro" \
    "$image" -config.file=/etc/loki/loki-config.yml -verify-config
}

run_check "prometheus: promtool check config" check_prometheus
run_check "alertmanager: amtool check-config (rendered template)" check_alertmanager
run_check "alloy: fmt --test" check_alloy_fmt
run_check "alloy: validate (empty output)" check_alloy_validate
run_check "loki: -verify-config" check_loki

if [[ "${#failures[@]}" -ne 0 ]]; then
  echo "FAIL: ${#failures[@]} monitoring config check(s) failed: ${failures[*]}" >&2
  exit 1
fi
echo "OK: all monitoring config checks passed"
