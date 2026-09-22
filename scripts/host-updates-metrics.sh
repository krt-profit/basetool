#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Writes the host's patch state into node_exporter's textfile directory (OPS-SEC-01, REQ-OPS-032):
#
#   basetool_host_updates_last_run_timestamp_seconds   when dnf-automatic last finished
#   basetool_host_updates_last_run_success             whether that run succeeded (1) or not (0)
#   basetool_host_reboot_required                      1 when an installed update (a kernel, glibc,
#                                                      systemd, ...) only takes effect after a reboot
#
# WHY IT EXISTS. dnf-automatic applies security updates unattended, and "unattended" is exactly
# the part nobody watches: a run that fails every night, a timer that was disabled, or a kernel fix
# that sits installed and inactive for weeks because nothing said a reboot was due all look like a
# patched host from the outside. HostSecurityUpdatesFailing, HostSecurityUpdatesStale and
# HostRebootRequired read these three series.
#
# HOW IT RUNS. Twice, from two places the Ansible role installs (tasks/45-updates.yml):
#
#   * as `ExecStopPost=` of dnf-automatic.service, where systemd hands it $SERVICE_RESULT -- so
#     the run's timestamp and outcome are recorded by the run itself, not inferred later;
#   * at boot, from iri-host-updates-metrics.service, WITHOUT $SERVICE_RESULT -- which only
#     re-reads whether a reboot is still required (after a reboot it no longer is) and carries the
#     last run's values over unchanged, the same way deploy.sh preserves its outcome timestamps.
#
# Read-only apart from its one output file, which is written to a temporary name beside it and
# renamed into place, so node_exporter never reads half a file. Exit 0 unless that write failed.
#
# Environment (defaults in brackets):
#   IRI_HOST_UPDATES_METRICS_FILE   the output file [/var/iri/monitoring/textfile/host-updates.prom]
#   IRI_NEEDS_RESTARTING            the command that answers "is a reboot required" [auto]
#   SERVICE_RESULT                  set by systemd for ExecStopPost=; absent at boot

set -uo pipefail

OUT="${IRI_HOST_UPDATES_METRICS_FILE:-/var/iri/monitoring/textfile/host-updates.prom}"

# Reads one sample value out of the previous file, or prints nothing. Only a plain number counts, so
# a corrupted file cannot carry garbage forward into the next one.
previous() {
  local value
  [[ -r "${OUT}" ]] || return 0
  value="$(awk -v n="$1" '$1 == n { print $2 }' "${OUT}" 2>/dev/null | tail -n 1)"
  [[ "${value}" =~ ^[0-9]+$ ]] && printf '%s' "${value}"
  return 0
}

# `needs-restarting -r` exits 1 when a reboot is required and 0 when it is not; anything else is "it
# could not tell", and then the series is left OUT rather than guessed -- a missing reading is
# visible in Prometheus, a wrong 0 is not.
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
