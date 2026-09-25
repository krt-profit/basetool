#!/bin/bash

set -euo pipefail

IRI_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
. "${IRI_SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/container-runtime.sh
# shellcheck disable=SC1091
. "${IRI_SCRIPT_DIR}/lib/container-runtime.sh"

COMPOSE_DIR="${IRI_COMPOSE_DIR:-/var/iri/code}"
STATE_DIR="${IRI_STATE_DIR:-/var/lib/iri}"
BACKUP_DIR="${IRI_BACKUP_DIR:-/var/iri/backup}"
STAGING_BASE="${BACKUP_DIR}/staging"
LOCKFILE="${IRI_LOCKFILE:-/var/lock/iri-deploy.lock}"
BACKUP_ENV="${IRI_BACKUP_ENV:-/etc/iri/backup.env}"
REDIS_ACL_PATH="${IRI_REDIS_ACL_HOST_PATH:-/var/iri/redis/users.acl}"

WRITER_SERVICES=(frontend backend ingest)
LOCK_WAIT="${IRI_BACKUP_LOCK_WAIT:-300}"

KEEP_DAILY="${IRI_KEEP_DAILY:-7}"
KEEP_WEEKLY="${IRI_KEEP_WEEKLY:-4}"
KEEP_MONTHLY="${IRI_KEEP_MONTHLY:-6}"

HELPER_IMAGE_FALLBACK="docker.io/library/postgres:18-alpine"
HELPER_IMAGE="${IRI_BACKUP_HELPER_IMAGE:-}"

MON_DATA="${IRI_MONITORING_DIR:-/var/iri/monitoring}"
START_EPOCH="$(date +%s)"

QUIESCE=true
SKIP_UPLOAD=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-quiesce) QUIESCE=false; shift ;;
    --skip-upload) SKIP_UPLOAD=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help)
      cat <<'USAGE'
Usage: backup.sh [--no-quiesce] [--skip-upload] [--dry-run]

Captures a consistent full-restore backup set and pushes it client-side
encrypted to Nextcloud via restic. See docs/backup.md for the operator
runbook.

Options:
  --no-quiesce   Do NOT stop the writer services; rely on pg_dump's own MVCC
                 snapshot consistency. Zero downtime, slightly weaker cross-DB
                 guarantee (benign for this app).
  --skip-upload  Capture the dumps to staging but do not run restic (debugging).
  --dry-run      Resolve config and print the plan + `restic snapshots`; stop,
                 dump and upload nothing.
  -h, --help     Show this help.

Configuration (in /etc/iri/backup.env, sourced at start):
  RESTIC_REPOSITORY   e.g. rclone:nextcloud:Basetool-Backups   (required)
  RESTIC_PASSWORD     restic repo encryption password           (or RESTIC_PASSWORD_FILE)
  RCLONE_CONFIG       path to rclone.conf with the `nextcloud` webdav remote
  IRI_KEEP_DAILY / IRI_KEEP_WEEKLY / IRI_KEEP_MONTHLY  (GFS retention; 7/4/6)
USAGE
      exit 0 ;;
    *) echo "FATAL: unknown argument: $1 (try --help)" >&2; exit 1 ;;
  esac
done

write_backup_metrics() {
  local now dur
  now="$(date +%s)"; dur=$(( now - START_EPOCH ))
  {
    echo "# HELP basetool_backup_last_success_timestamp Unix time of the last successful off-site backup."
    echo "# TYPE basetool_backup_last_success_timestamp gauge"
    echo "basetool_backup_last_success_timestamp ${now}"
    echo "# HELP basetool_backup_duration_seconds Runtime of the last successful backup in seconds."
    echo "# TYPE basetool_backup_duration_seconds gauge"
    echo "basetool_backup_duration_seconds ${dur}"
  } | write_textfile backup.prom || true
}

[[ -f "${COMPOSE_DIR}/.env" ]] || fail "missing ${COMPOSE_DIR}/.env"
[[ -f "${BACKUP_ENV}" ]] || fail "missing ${BACKUP_ENV} (restic repo + rclone config; see docs/backup.md)"
rt_detect
rt_wait_for_startup
export RT_STACK_SERVICES="db-backend db-keycloak redis keycloak backend ingest frontend edge acme"
log "container runtime: ${RT_BACKEND}"
if [[ -z "${HELPER_IMAGE}" ]]; then
  if HELPER_IMAGE="$(rt_unit_image db-backend "${COMPOSE_DIR}/quadlet/systemd")"; then
    log "helper image: ${HELPER_IMAGE} (db-backend's own pin)"
  else
    HELPER_IMAGE="${HELPER_IMAGE_FALLBACK}"
    log "WARN: no Image= readable in db-backend.container -- falling back to the unpinned ${HELPER_IMAGE}"
  fi
fi
command -v restic >/dev/null 2>&1 || fail "restic not found (dnf install restic; ansible role: 10-packages.yml)"
command -v rclone >/dev/null 2>&1 || fail "rclone not found (dnf install rclone; ansible role: 10-packages.yml)"

export RESTIC_CACHE_DIR="${RESTIC_CACHE_DIR:-${STATE_DIR}/restic-cache}"
mkdir -p "${RESTIC_CACHE_DIR}" "${STAGING_BASE}"

set -a
# shellcheck source=/dev/null
. "${BACKUP_ENV}"
set +a
[[ -n "${RESTIC_REPOSITORY:-}" ]] || fail "RESTIC_REPOSITORY not set in ${BACKUP_ENV}"
[[ -n "${RESTIC_PASSWORD:-}${RESTIC_PASSWORD_FILE:-}" ]] || fail "RESTIC_PASSWORD or RESTIC_PASSWORD_FILE not set in ${BACKUP_ENV}"

KEYSTORE_PATH="$(read_env IRI_KEYSTORE_HOST_PATH)"
KEYSTORE_PATH="${KEYSTORE_PATH:-/var/iri/secrets/keystore.p12}"
INTERNAL_TLS_DIR="${IRI_INTERNAL_TLS_DIR:-/var/iri/secrets/tls}"

cd "${COMPOSE_DIR}"

if [[ "${DRY_RUN}" == "true" ]]; then
  log "DRY RUN — would back up: krt_basetool + keycloak dumps, the edge-certs/edge-acme-state/edge-acme-webroot volumes, .env, ${KEYSTORE_PATH}, ${INTERNAL_TLS_DIR} (if present), ${REDIS_ACL_PATH}, realm-export.json, keycloak/providers"
  log "DRY RUN — quiesce=${QUIESCE} (stop: ${WRITER_SERVICES[*]}); repo=${RESTIC_REPOSITORY}; retention ${KEEP_DAILY}/${KEEP_WEEKLY}/${KEEP_MONTHLY}"
  log "existing snapshots:"
  restic snapshots --compact 2>&1 | sed 's/^/  /' || log "  (repo not reachable / not initialized yet)"
  exit 0
fi

exec 200>"${LOCKFILE}"
if ! flock -w "${LOCK_WAIT}" 200; then
  fail "could not acquire deploy lock within ${LOCK_WAIT}s (a deploy may be running) — skipping this backup"
fi

TS="$(date -u +%Y%m%dT%H%M%SZ)"
STAGING="${STAGING_BASE}/${TS}"
mkdir -p "${STAGING}/config"
chmod 700 "${STAGING}"

QUIESCED=false
# shellcheck disable=SC2317
cleanup() {
  local rc=$?
  if [[ "${QUIESCED}" == "true" ]]; then
    log "cleanup: writers still stopped — restarting ${WRITER_SERVICES[*]}"
    rt_service_start "${WRITER_SERVICES[@]}" >/dev/null 2>&1 || log "WARN: failed to restart writers during cleanup"
    QUIESCED=false
  fi
  if [[ -n "${STAGING:-}" && -d "${STAGING}" ]]; then
    rm -rf "${STAGING}"
  fi
  exit "${rc}"
}
trap cleanup EXIT

if [[ "${QUIESCE}" == "true" ]]; then
  log "quiescing writers for the dump: stop ${WRITER_SERVICES[*]} (the edge serves the maintenance page)"
  rt_service_stop "${WRITER_SERVICES[@]}"
  QUIESCED=true
else
  log "running ONLINE (no quiesce): relying on pg_dump MVCC snapshot consistency"
fi

log "dumping backend database (krt_basetool)"
# shellcheck disable=SC2016
rt_exec db-backend sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 -Fc "$POSTGRES_DB"' \
  > "${STAGING}/krt_basetool.dump"

log "dumping Keycloak database (keycloak)"
# shellcheck disable=SC2016
rt_exec db-keycloak sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -h 127.0.0.1 -p 15433 -Fc "$POSTGRES_DB"' \
  > "${STAGING}/keycloak.dump"

for _vol in edge-certs edge-acme-state edge-acme-webroot; do
  if ! rt_volume_exists "${_vol}"; then
    log "  ${_vol}: not present on this host — skipped"
    continue
  fi
  if rt_read_mount "${_vol}" "${HELPER_IMAGE}" tar -C /src -cz . \
       > "${STAGING}/${_vol}.tar.gz" 2>/dev/null \
     && [[ -s "${STAGING}/${_vol}.tar.gz" ]]; then
    log "  ${_vol}: captured"
  else
    rm -f "${STAGING}/${_vol}.tar.gz"
    log "  WARN: could not capture ${_vol} — the certificates are NOT in this snapshot"
  fi
done

if [[ -f "${REDIS_ACL_PATH}" ]]; then
  if rt_read_mount "$(dirname "${REDIS_ACL_PATH}")" "${HELPER_IMAGE}" \
       cat "/src/$(basename "${REDIS_ACL_PATH}")" > "${STAGING}/config/users.acl" 2>/dev/null \
     && [[ -s "${STAGING}/config/users.acl" ]]; then
    log "capturing the redis ACL (${REDIS_ACL_PATH})"
  else
    rm -f "${STAGING}/config/users.acl"
    log "WARN: could not read ${REDIS_ACL_PATH} — redis will NOT start from this snapshot"
  fi
else
  log "WARN: no redis ACL at ${REDIS_ACL_PATH} — redis will NOT start from this snapshot"
fi

log "capturing host config (.env, keystore, realm-export, providers)"
cp -p "${COMPOSE_DIR}/.env" "${STAGING}/config/dotenv"
if [[ -f "${KEYSTORE_PATH}" ]]; then
  if rt_read_mount "$(dirname "${KEYSTORE_PATH}")" "${HELPER_IMAGE}" \
       cat "/src/$(basename "${KEYSTORE_PATH}")" > "${STAGING}/config/keystore.p12" 2>/dev/null \
     && [[ -s "${STAGING}/config/keystore.p12" ]]; then
    :
  else
    rm -f "${STAGING}/config/keystore.p12"
    log "WARN: could not read keystore at ${KEYSTORE_PATH} via ${HELPER_IMAGE} — skipped"
  fi
else
  log "WARN: keystore not found at ${KEYSTORE_PATH} — skipped"
fi
if [[ -d "${INTERNAL_TLS_DIR}" ]]; then
  if rt_read_mount "${INTERNAL_TLS_DIR}" "${HELPER_IMAGE}" tar -C /src -cf - . \
       > "${STAGING}/config/internal-tls.tar" 2>/dev/null \
     && [[ -s "${STAGING}/config/internal-tls.tar" ]]; then
    :
  else
    rm -f "${STAGING}/config/internal-tls.tar"
    log "WARN: could not read the internal TLS material at ${INTERNAL_TLS_DIR} via ${HELPER_IMAGE} — skipped"
  fi
fi
if [[ -f "${COMPOSE_DIR}/realm-export.json" ]]; then
  if rt_read_mount "${COMPOSE_DIR}" "${HELPER_IMAGE}" cat /src/realm-export.json        > "${STAGING}/config/realm-export.json" 2>/dev/null      && [[ -s "${STAGING}/config/realm-export.json" ]]; then
    :
  else
    rm -f "${STAGING}/config/realm-export.json"
    log "WARN: could not read ${COMPOSE_DIR}/realm-export.json — skipped"
  fi
fi
if [[ -d "${COMPOSE_DIR}/keycloak/providers" ]]; then
  tar -C "${COMPOSE_DIR}/keycloak" -czf "${STAGING}/config/providers.tar.gz" providers 2>/dev/null \
    || log "WARN: could not archive keycloak/providers — skipped"
fi

if [[ "${QUIESCED}" == "true" ]]; then
  log "dumps captured — restarting writers (${WRITER_SERVICES[*]})"
  if ! rt_service_start "${WRITER_SERVICES[@]}"; then
    log "WARN: one or more writers did not return to health — continuing, so the dumps still reach the repository"
  fi
  QUIESCED=false
fi

if rt_monitoring_configured && rt_is_running grafana; then
  mkdir -p "${STAGING}/monitoring"
  log "capturing Grafana SQLite (brief grafana stop for a consistent copy)"
  grafana_stopped=false
  if rt_service_stop grafana >/dev/null 2>&1; then
    grafana_stopped=true
  else
    log "WARN: could not stop grafana; copying its SQLite live (may be inconsistent)"
  fi
  if [[ -f "${MON_DATA}/data/grafana/grafana.db" ]]; then
    rt_read_mount "${MON_DATA}/data/grafana" "${HELPER_IMAGE}" \
      cat /src/grafana.db > "${STAGING}/monitoring/grafana.db" 2>/dev/null \
      || log "WARN: could not capture grafana.db"
  else
    log "WARN: grafana.db not found under ${MON_DATA}/data/grafana"
  fi
  [[ "${grafana_stopped}" == "true" ]] && { rt_monitoring_up grafana >/dev/null 2>&1 || log "WARN: failed to restart grafana"; }
  log "capturing monitoring secrets + certs"
  rt_read_mount "${MON_DATA}" "${HELPER_IMAGE}" \
    sh -c 'tar -C /src -cz secrets certs 2>/dev/null || true' > "${STAGING}/monitoring/secrets.tar.gz" 2>/dev/null \
    || log "WARN: could not archive monitoring secrets/certs"
  log "capturing Alertmanager state (silences + notification log)"
  rt_read_mount "${MON_DATA}/data/alertmanager" "${HELPER_IMAGE}" \
    sh -c 'tar -C /src -cz . 2>/dev/null || true' > "${STAGING}/monitoring/alertmanager.tar.gz" 2>/dev/null \
    || log "WARN: could not archive alertmanager state"
else
  log "monitoring stack not present/running — skipping monitoring artifact capture"
fi

flock -u 200 || true
log "deploy lock released; the rest runs while fully live"

if [[ "$(date -u +%u)" == "7" ]] && rt_monitoring_configured && rt_is_running prometheus; then
  mkdir -p "${STAGING}/monitoring"
  log "weekly Prometheus TSDB snapshot via admin API"
  snap_json="$(rt_prometheus_snapshot 2>/dev/null || true)"
  snap_name="$(printf '%s' "${snap_json}" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')"
  if [[ "${snap_name}" =~ ^[0-9A-Za-z-]+$ ]]; then
    if rt_read_mount "${MON_DATA}/data/prometheus/snapshots/${snap_name}" "${HELPER_IMAGE}" \
         sh -c 'tar -C /src -cz .' > "${STAGING}/monitoring/prometheus-tsdb-snapshot.tar.gz" 2>/dev/null \
       && [[ -s "${STAGING}/monitoring/prometheus-tsdb-snapshot.tar.gz" ]]; then
      log "captured Prometheus TSDB snapshot ${snap_name}"
    else
      rm -f "${STAGING}/monitoring/prometheus-tsdb-snapshot.tar.gz"
      log "WARN: could not archive the Prometheus TSDB snapshot ${snap_name}"
    fi
    rt_prometheus_snapshot_remove "${snap_name}" >/dev/null 2>&1 \
      || log "WARN: could not remove snapshot ${snap_name} from the Prometheus volume"
  else
    log "WARN: Prometheus TSDB snapshot failed (answer did not name a snapshot)"
  fi
fi

if [[ "${SKIP_UPLOAD}" == "true" ]]; then
  log "--skip-upload: dumps staged at ${STAGING} (will be removed on exit); not pushing to restic"
  exit 0
fi

if ! restic snapshots >/dev/null 2>&1; then
  log "restic repository not reachable yet — attempting one-time init"
  restic init || fail "restic init failed — check ${BACKUP_ENV} (repo URL, password, rclone remote)"
fi

log "uploading encrypted snapshot to ${RESTIC_REPOSITORY}"
restic backup --tag basetool --host basetool-prod "${STAGING}"

log "applying GFS retention (keep daily=${KEEP_DAILY} weekly=${KEEP_WEEKLY} monthly=${KEEP_MONTHLY}) + prune"
restic forget --tag basetool \
  --keep-daily "${KEEP_DAILY}" --keep-weekly "${KEEP_WEEKLY}" --keep-monthly "${KEEP_MONTHLY}" \
  --prune

log "verifying repository integrity (restic check)"
restic check

write_backup_metrics

log "backup complete"
