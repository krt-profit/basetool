#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

cd /

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/container-runtime.sh
# shellcheck disable=SC1091
. "${SCRIPT_DIR}/lib/container-runtime.sh"

IMAGE_UNTIL="${IRI_CLEANUP_IMAGE_UNTIL:-336h}"
CONTAINER_UNTIL="${IRI_CLEANUP_CONTAINER_UNTIL:-24h}"
NETWORK_UNTIL="${IRI_CLEANUP_NETWORK_UNTIL:-24h}"
LOCKFILE="${IRI_CLEANUP_LOCKFILE:-/var/lock/iri-container-cleanup.lock}"

START_EPOCH="$(date +%s)"

DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      cat <<'USAGE'
Usage: container-cleanup.sh [--dry-run]

Removes unused container resources: stopped containers, unused images and unused networks.
Volumes are never pruned (ADR-0194). Resources still in use, and anything inside its step's
until= window, are left untouched.

Options:
  --dry-run    Remove nothing; print the current disk usage and the steps that would run.
  -h, --help   Show this help.

Environment (defaults in brackets):
  IRI_CLEANUP_IMAGE_UNTIL=336h       minimum age of an unused image
  IRI_CLEANUP_CONTAINER_UNTIL=24h    minimum age of a stopped container
  IRI_CLEANUP_NETWORK_UNTIL=24h      minimum age of an unused network
  IRI_CLEANUP_LOCKFILE=/var/lock/iri-container-cleanup.lock
  IRI_MONITORING_TEXTFILE_DIR=/var/iri/monitoring/textfile
USAGE
      exit 0
      ;;
    *)
      echo "FATAL: unknown argument: $1 (see --help)" >&2
      exit 1
      ;;
  esac
done

run_prune() {
  local label="$1"
  shift
  if [[ "${DRY_RUN}" == "true" ]]; then
    log "[dry-run] ${label}: $*"
    return 0
  fi
  log "${label} ..."
  if "$@"; then
    log "${label}: OK"
  else
    log "[WARN] ${label}: failed (exit $?), continuing with the next step"
  fi
}

to_bytes() {
  awk -v s="$1" 'BEGIN{
    if (s=="") { print 0; exit }
    u="B"; v=s
    if (match(s,/[A-Za-z]+$/)) { u=substr(s,RSTART,RLENGTH); v=substr(s,1,RSTART-1) }
    m=1
    if (u=="B") m=1;
    else if (u=="kB"||u=="KB") m=1000;
    else if (u=="MB") m=1000000;
    else if (u=="GB") m=1000000000;
    else if (u=="TB") m=1000000000000;
    else if (u=="KiB") m=1024;
    else if (u=="MiB") m=1048576;
    else if (u=="GiB") m=1073741824;
    else if (u=="TiB") m=1099511627776;
    printf "%d", (v*m)
  }'
}

df_total_bytes() {
  local total=0 line b
  while IFS= read -r line; do
    b="$(to_bytes "${line}")"
    total=$(( total + b ))
  done < <(${RT_CLI} system df --format '{{.Size}}' 2>/dev/null || true)
  printf '%d' "${total}"
}

write_cleanup_metrics() {
  local reclaimed="$1" now dur
  now="$(date +%s)"
  dur=$(( now - START_EPOCH ))
  {
    echo "# HELP basetool_container_cleanup_last_success_timestamp Unix time of the last successful container cleanup."
    echo "# TYPE basetool_container_cleanup_last_success_timestamp gauge"
    echo "basetool_container_cleanup_last_success_timestamp ${now}"
    echo "# HELP basetool_container_cleanup_duration_seconds Runtime of the last container cleanup in seconds."
    echo "# TYPE basetool_container_cleanup_duration_seconds gauge"
    echo "basetool_container_cleanup_duration_seconds ${dur}"
    echo "# HELP basetool_container_cleanup_reclaimed_bytes Bytes reclaimed by the last container cleanup."
    echo "# TYPE basetool_container_cleanup_reclaimed_bytes gauge"
    echo "basetool_container_cleanup_reclaimed_bytes ${reclaimed}"
  } | write_textfile container_cleanup.prom || true
}

exec 200>"${LOCKFILE}"
flock -n 200 || {
  log "[ERROR] a cleanup run is already in progress (lock: ${LOCKFILE}). Aborting."
  exit 1
}

rt_detect
rt_wait_for_startup
log "runtime: ${RT_BACKEND} (${RT_CLI})"

read -r -a RT_CLI_ARGV <<< "${RT_CLI}"

echo "================================================================"
log "starting container cleanup${DRY_RUN:+ (dry run)}"
log "windows: images>${IMAGE_UNTIL}, containers>${CONTAINER_UNTIL}, networks>${NETWORK_UNTIL}; volumes are never pruned (ADR-0194)"
echo "================================================================"

log "disk usage BEFORE:"
${RT_CLI} system df || true
BEFORE_BYTES="$(df_total_bytes)"

run_prune "stopped containers" \
  "${RT_CLI_ARGV[@]}" container prune --force --filter "until=${CONTAINER_UNTIL}"

run_prune "unused images" \
  "${RT_CLI_ARGV[@]}" image prune --all --force --filter "until=${IMAGE_UNTIL}"

run_prune "unused networks" \
  "${RT_CLI_ARGV[@]}" network prune --force --filter "until=${NETWORK_UNTIL}"

echo "----------------------------------------------------------------"
log "disk usage AFTER:"
${RT_CLI} system df || true

if [[ "${DRY_RUN}" != "true" ]]; then
  AFTER_BYTES="$(df_total_bytes)"
  RECLAIMED=$(( BEFORE_BYTES - AFTER_BYTES ))
  (( RECLAIMED < 0 )) && RECLAIMED=0
  write_cleanup_metrics "${RECLAIMED}"
  log "textfile metric written: basetool_container_cleanup_* (reclaimed=${RECLAIMED} bytes)"
fi

echo "================================================================"
log "container cleanup finished${DRY_RUN:+ (dry run - nothing removed)}"
echo "================================================================"
