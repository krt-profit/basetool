#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -uo pipefail

OUT="${IRI_HOST_UPDATES_METRICS_FILE:-/var/iri/monitoring/textfile/host-updates.prom}"

previous() {
  local value
  [[ -r "${OUT}" ]] || return 0
  value="$(awk -v n="$1" '$1 == n { print $2 }' "${OUT}" 2>/dev/null | tail -n 1)"
  [[ "${value}" =~ ^[0-9]+$ ]] && printf '%s' "${value}"
  return 0
}

reboot_required() {
  local rc
  if [[ -n "${IRI_NEEDS_RESTARTING:-}" ]]; then
    ${IRI_NEEDS_RESTARTING} -r >/dev/null 2>&1
  elif command -v needs-restarting >/dev/null 2>&1; then
    needs-restarting -r >/dev/null 2>&1
  elif command -v dnf >/dev/null 2>&1; then
    dnf -q needs-restarting -r >/dev/null 2>&1
  else
    return 0
  fi
  rc=$?
  case "${rc}" in
    0) printf '0' ;;
    1) printf '1' ;;
  esac
  return 0
}

last_ts="$(previous basetool_host_updates_last_run_timestamp_seconds)"
last_ok="$(previous basetool_host_updates_last_run_success)"
if [[ -n "${SERVICE_RESULT:-}" ]]; then
  last_ts="$(date +%s)"
  if [[ "${SERVICE_RESULT}" == "success" ]]; then last_ok=1; else last_ok=0; fi
fi
reboot="$(reboot_required)"

dir="$(dirname "${OUT}")"
tmp="$(mktemp "${dir}/.host-updates.prom.XXXXXX" 2>/dev/null)" || {
  echo "host-updates-metrics: cannot create a temporary file in ${dir}" >&2
  exit 1
}
{
  if [[ -n "${last_ts}" ]]; then
    echo "# HELP basetool_host_updates_last_run_timestamp_seconds Unix time dnf-automatic last finished a run on this host."
    echo "# TYPE basetool_host_updates_last_run_timestamp_seconds gauge"
    echo "basetool_host_updates_last_run_timestamp_seconds ${last_ts}"
  fi
  if [[ -n "${last_ok}" ]]; then
    echo "# HELP basetool_host_updates_last_run_success Whether the last dnf-automatic run succeeded (1) or failed (0)."
    echo "# TYPE basetool_host_updates_last_run_success gauge"
    echo "basetool_host_updates_last_run_success ${last_ok}"
  fi
  if [[ -n "${reboot}" ]]; then
    echo "# HELP basetool_host_reboot_required 1 when an installed update only takes effect after a reboot (needs-restarting -r)."
    echo "# TYPE basetool_host_reboot_required gauge"
    echo "basetool_host_reboot_required ${reboot}"
  fi
} > "${tmp}" || { rm -f "${tmp}"; echo "host-updates-metrics: cannot write ${tmp}" >&2; exit 1; }
chmod 0644 "${tmp}"
mv -f "${tmp}" "${OUT}" || { rm -f "${tmp}"; echo "host-updates-metrics: cannot move ${OUT} into place" >&2; exit 1; }
exit 0
