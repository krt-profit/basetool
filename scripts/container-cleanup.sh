#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Reclaims unused container resources on the production host: stopped containers, unused images,
# the build cache, unused networks and - on Docker only - anonymous volumes. Anything still in use,
# and anything inside its step's `until=` window, is left alone.
#
# Scheduled by iri-container-cleanup.timer (Saturday 02:00 UTC).
#
# WHY THIS IS NOT THE OLD docker-cleanup.sh WITH A NEW NAME
# ---------------------------------------------------------
# It replaces `scripts/docker-cleanup.sh`, which called `docker` directly and was the only
# operational script that did not go through `lib/container-runtime.sh`. On the rootless Podman host
# there is no `docker` binary at all (the Ansible role installs `podman` and not `podman-docker`),
# so the weekly run failed at its first command while the timer stayed enabled -- measured on the
# migration target 2026-09-21.
#
# Two of the five steps could NOT be translated command-for-command, and a mechanical rename would
# have been worse than the broken job it replaced:
#
#   * `volume prune` - Docker's, without `--all`, removes ONLY anonymous volumes. Podman has no such
#     distinction: `podman volume prune` is documented as "Volumes that are not currently owned by a
#     container will be removed. Note all data will be destroyed", and its only filter is `label=`.
#     Measured on the target the same day, `podman volume ls --filter dangling=true` listed
#     `edge-certs` and `edge-acme-state` -- the edge's TLS material and the ACME account, which are
#     in no snapshot and were carried across by hand. They are "dangling" whenever the stack is
#     down, which is exactly when a maintenance job runs. So on Podman this step is SKIPPED, and the
#     leak it used to paper over was fixed at its source instead: `rt_rm_force` now removes a
#     container's anonymous volume with the container (ADR-0194).
#
#   * `builder prune` - on Podman `builder prune` is an alias for `image prune` ("Remove unused
#     images"). Running both would be the same step twice, not a build-cache sweep. Podman builds
#     nothing on this host anyway; the images arrive pre-built and signed.
#
# USAGE
#   scripts/container-cleanup.sh              # reclaim
#   scripts/container-cleanup.sh --dry-run    # show the plan and the current usage, remove nothing
#   scripts/container-cleanup.sh --help
#
set -euo pipefail

# `sudo -u <service user>` keeps the CALLER's working directory, and the service user cannot
# traverse root's or the deploy account's home. Without this every RT_CLI call on a rootless host
# dies with "cannot chdir to /root: Permission denied" -- an error that names a directory having
# nothing to do with the command.
cd /

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=lib/container-runtime.sh
# shellcheck disable=SC1091  # repo-lint runs shellcheck without -x, so it cannot follow this
. "${SCRIPT_DIR}/lib/container-runtime.sh"

# --- Configuration (every value overridable by environment) -----------------
# `until=` values take Go duration strings: 24h, 168h, 336h, 720h ...
IMAGE_UNTIL="${IRI_CLEANUP_IMAGE_UNTIL:-336h}"       # 14 days - the rollback buffer
BUILDER_UNTIL="${IRI_CLEANUP_BUILDER_UNTIL:-168h}"   # 7 days
CONTAINER_UNTIL="${IRI_CLEANUP_CONTAINER_UNTIL:-24h}"
NETWORK_UNTIL="${IRI_CLEANUP_NETWORK_UNTIL:-24h}"
PRUNE_VOLUMES="${IRI_CLEANUP_PRUNE_VOLUMES:-true}"
LOCKFILE="${IRI_CLEANUP_LOCKFILE:-/var/lock/iri-container-cleanup.lock}"

# Monitoring textfile metrics (epic #936). The textfile carries richer per-outcome detail (last
# success, duration, reclaimed bytes) than the systemd collector's unit-level success, and is what
# the "container-cleanup stale >8d or absent" warning reads via
# basetool_container_cleanup_last_success_timestamp.
TEXTFILE_DIR="${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}"
START_EPOCH="$(date +%s)"

DRY_RUN=false

# --- Arguments --------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    -h|--help)
      cat <<'USAGE'
Usage: container-cleanup.sh [--dry-run]

Removes unused container resources: stopped containers, unused images, the build cache (Docker
only), unused networks and - on Docker only - anonymous volumes. Resources still in use, and
anything inside its step's until= window, are left untouched.

Options:
  --dry-run    Remove nothing; print the current disk usage and the steps that would run.
  -h, --help   Show this help.

Environment (defaults in brackets):
  IRI_CLEANUP_IMAGE_UNTIL=336h       minimum age of an unused image
  IRI_CLEANUP_BUILDER_UNTIL=168h     minimum age of build cache (Docker only)
  IRI_CLEANUP_CONTAINER_UNTIL=24h    minimum age of a stopped container
  IRI_CLEANUP_NETWORK_UNTIL=24h      minimum age of an unused network
  IRI_CLEANUP_PRUNE_VOLUMES=true     prune anonymous volumes (Docker only; ignored on Podman)
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

# --- Helpers ----------------------------------------------------------------
log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

# Runs one prune step, or prints it under --dry-run. A failing step must NOT abort the run: a
# briefly-held reference can block a single prune without the other steps needing to be skipped.
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

# Converts a go-units size ("1.5GB", "512MB", "0B") to whole bytes.
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

# Sum of the Size column of `<cli> system df`, in bytes. Best effort: 0 on any failure.
df_total_bytes() {
  local total=0 line b
  while IFS= read -r line; do
    b="$(to_bytes "${line}")"
    total=$(( total + b ))
  done < <(${RT_CLI} system df --format '{{.Size}}' 2>/dev/null || true)
  printf '%d' "${total}"
}

# Writes the textfile metric atomically (.tmp then mv) so the collector never reads a half-written
# file.
write_cleanup_metrics() {
  local reclaimed="$1" now dur tmp
  now="$(date +%s)"
  dur=$(( now - START_EPOCH ))
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  tmp="${TEXTFILE_DIR}/container_cleanup.prom.$$"
  if {
    echo "# HELP basetool_container_cleanup_last_success_timestamp Unix time of the last successful container cleanup."
    echo "# TYPE basetool_container_cleanup_last_success_timestamp gauge"
    echo "basetool_container_cleanup_last_success_timestamp ${now}"
    echo "# HELP basetool_container_cleanup_duration_seconds Runtime of the last container cleanup in seconds."
    echo "# TYPE basetool_container_cleanup_duration_seconds gauge"
    echo "basetool_container_cleanup_duration_seconds ${dur}"
    echo "# HELP basetool_container_cleanup_reclaimed_bytes Bytes reclaimed by the last container cleanup."
    echo "# TYPE basetool_container_cleanup_reclaimed_bytes gauge"
    echo "basetool_container_cleanup_reclaimed_bytes ${reclaimed}"
  } > "${tmp}" 2>/dev/null; then
    mv -f "${tmp}" "${TEXTFILE_DIR}/container_cleanup.prom" 2>/dev/null \
      || log "[WARN] could not move the textfile metric into place (${TEXTFILE_DIR})"
  else
    log "[WARN] could not write the textfile metric (${TEXTFILE_DIR})"
    rm -f "${tmp}" 2>/dev/null || true
  fi
}

# --- Lock: one cleanup run at a time ----------------------------------------
exec 200>"${LOCKFILE}"
flock -n 200 || {
  log "[ERROR] a cleanup run is already in progress (lock: ${LOCKFILE}). Aborting."
  exit 1
}

# --- Precondition: a reachable container runtime -----------------------------
rt_detect
log "runtime: ${RT_BACKEND} (${RT_CLI})"

# RT_CLI can be a whole invocation -- `sudo -n -u <service user> podman` on a rootless host -- so it
# has to be word-split before it can be PASSED AS ARGUMENTS to run_prune. Splitting it once into an
# array keeps every call site quotable; leaving it unquoted there would be SC2086, and quoting it
# would look for a binary literally named "sudo -n -u iri podman".
read -r -a RT_CLI_ARGV <<< "${RT_CLI}"

echo "================================================================"
log "starting container cleanup${DRY_RUN:+ (dry run)}"
log "windows: images>${IMAGE_UNTIL}, cache>${BUILDER_UNTIL}, containers>${CONTAINER_UNTIL}, networks>${NETWORK_UNTIL}, volumes=${PRUNE_VOLUMES}"
echo "================================================================"

log "disk usage BEFORE:"
${RT_CLI} system df || true
BEFORE_BYTES="$(df_total_bytes)"

# --- Steps ------------------------------------------------------------------
# Order: containers first (it releases image references), then images, the build cache, networks,
# and last - Docker only - anonymous volumes.
run_prune "stopped containers" \
  "${RT_CLI_ARGV[@]}" container prune --force --filter "until=${CONTAINER_UNTIL}"

run_prune "unused images" \
  "${RT_CLI_ARGV[@]}" image prune --all --force --filter "until=${IMAGE_UNTIL}"

if [[ "${RT_BACKEND}" == "docker" ]]; then
  run_prune "build cache" \
    "${RT_CLI_ARGV[@]}" builder prune --force --filter "until=${BUILDER_UNTIL}"
else
  log "build cache: skipped - on Podman 'builder prune' is an alias for 'image prune', already run"
fi

run_prune "unused networks" \
  "${RT_CLI_ARGV[@]}" network prune --force --filter "until=${NETWORK_UNTIL}"

if [[ "${PRUNE_VOLUMES}" != "true" ]]; then
  log "volumes: skipped by IRI_CLEANUP_PRUNE_VOLUMES=false"
elif [[ "${RT_BACKEND}" == "docker" ]]; then
  # Without --all this removes ONLY anonymous unused volumes. Named volumes, and the /var/iri bind
  # mounts (which are not volumes at all), are untouched.
  run_prune "anonymous unused volumes" \
    "${RT_CLI_ARGV[@]}" volume prune --force
else
  # See the header. podman volume prune would take edge-certs and edge-acme-state with it whenever
  # the stack is down, and offers no way to say "anonymous only". The leak this step used to absorb
  # is fixed where it is made: rt_rm_force removes a container's anonymous volume with it.
  log "volumes: skipped - podman volume prune has no anonymous-only mode and would destroy named volumes (ADR-0194)"
fi

echo "----------------------------------------------------------------"
log "disk usage AFTER:"
${RT_CLI} system df || true

# Monitoring signal, on a real run only. reclaimed = freed bytes per `system df`, best effort and
# never negative.
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
