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

STATE_DIR="${IRI_STATE_DIR:-/var/lib/iri}"
BACKUP_DIR="${IRI_BACKUP_DIR:-/var/iri/backup}"
WORK_BASE="${BACKUP_DIR}/restore-drill"
BACKUP_ENV="${IRI_BACKUP_ENV:-/etc/iri/backup.env}"
COMPOSE_DIR="${IRI_COMPOSE_DIR:-/var/iri/code}"
DRILL_IMAGE_FALLBACK="docker.io/library/postgres:18-alpine"
DRILL_IMAGE="${IRI_DRILL_IMAGE:-}"
CONTAINER="iri-restore-drill"
READY_TIMEOUT="${IRI_DRILL_READY_TIMEOUT:-60}"
MIN_BACKEND_TABLES="${IRI_DRILL_MIN_BACKEND_TABLES:-20}"
MIN_KEYCLOAK_TABLES="${IRI_DRILL_MIN_KEYCLOAK_TABLES:-20}"

START_EPOCH="$(date +%s)"
OK_DB_BACKEND=0
OK_DB_KEYCLOAK=0
OK_GRAFANA_SQLITE=0
OK_MONITORING_SECRETS=0
OK_EDGE_CERTS=0
OK_ACME_STATE=0
OK_REDIS_ACL=0

KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

# shellcheck disable=SC2317,SC2329
write_drill_metrics() {
  local now dur prev
  now="$(date +%s)"
  dur=$(( now - START_EPOCH ))
  prev=0
  if [[ -f "${TEXTFILE_DIR}/restore_drill.prom" ]]; then
    prev="$(awk '/^basetool_restore_drill_last_success_timestamp /{print $2}' "${TEXTFILE_DIR}/restore_drill.prom" 2>/dev/null || echo 0)"
    [[ "${prev}" =~ ^[0-9]+$ ]] || prev=0
  fi
  if (( OK_DB_BACKEND == 1 && OK_DB_KEYCLOAK == 1 )); then
    prev="${now}"
  fi
  {
    echo "# HELP basetool_restore_drill_last_success_timestamp Unix time of the last fully-successful DB restore drill."
    echo "# TYPE basetool_restore_drill_last_success_timestamp gauge"
    echo "basetool_restore_drill_last_success_timestamp ${prev}"
    echo "# HELP basetool_restore_drill_duration_seconds Runtime of the last restore drill in seconds."
    echo "# TYPE basetool_restore_drill_duration_seconds gauge"
    echo "basetool_restore_drill_duration_seconds ${dur}"
    echo "# HELP basetool_restore_drill_artifact_ok Whether each backup artifact was restorable (1) or not (0)."
    echo "# TYPE basetool_restore_drill_artifact_ok gauge"
    echo "basetool_restore_drill_artifact_ok{artifact=\"db_backend\"} ${OK_DB_BACKEND}"
    echo "basetool_restore_drill_artifact_ok{artifact=\"db_keycloak\"} ${OK_DB_KEYCLOAK}"
    echo "basetool_restore_drill_artifact_ok{artifact=\"grafana_sqlite\"} ${OK_GRAFANA_SQLITE}"
    echo "basetool_restore_drill_artifact_ok{artifact=\"monitoring_secrets\"} ${OK_MONITORING_SECRETS}"
    echo "basetool_restore_drill_artifact_ok{artifact=\"edge_certs\"} ${OK_EDGE_CERTS}"
    echo "basetool_restore_drill_artifact_ok{artifact=\"acme_state\"} ${OK_ACME_STATE}"
    echo "basetool_restore_drill_artifact_ok{artifact=\"redis_acl\"} ${OK_REDIS_ACL}"
  } | write_textfile restore_drill.prom || true
}

[[ -f "${BACKUP_ENV}" ]] || fail "missing ${BACKUP_ENV}"
rt_detect
rt_wait_for_startup
log "container runtime: ${RT_BACKEND}"
if [[ -z "${DRILL_IMAGE}" ]]; then
  if DRILL_IMAGE="$(rt_unit_image db-backend "${COMPOSE_DIR}/quadlet/systemd")"; then
    log "drill image: ${DRILL_IMAGE} (db-backend's own pin)"
  else
    DRILL_IMAGE="${DRILL_IMAGE_FALLBACK}"
    log "WARN: no Image= readable in db-backend.container -- falling back to the unpinned ${DRILL_IMAGE}"
  fi
fi
command -v restic >/dev/null 2>&1 || fail "restic not found (dnf install restic; ansible role: 10-packages.yml)"
command -v rclone >/dev/null 2>&1 || fail "rclone not found (dnf install rclone; ansible role: 10-packages.yml)"

export RESTIC_CACHE_DIR="${RESTIC_CACHE_DIR:-${STATE_DIR}/restic-cache}"
mkdir -p "${RESTIC_CACHE_DIR}" "${WORK_BASE}"

set -a
# shellcheck source=/dev/null
. "${BACKUP_ENV}"
set +a
[[ -n "${RESTIC_REPOSITORY:-}" ]] || fail "RESTIC_REPOSITORY not set in ${BACKUP_ENV}"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="${WORK_BASE}/${TS}"
mkdir -p "${WORK}"
chmod 700 "${WORK}"

# shellcheck disable=SC2317
cleanup() {
  local rc=$?
  rt_rm_force "${CONTAINER}"
  write_drill_metrics
  if [[ "${KEEP}" == "true" && "${rc}" -eq 0 ]]; then
    log "--keep: leaving restored dumps at ${WORK}"
  else
    rm -rf "${WORK}" 2>/dev/null || true
  fi
  exit "${rc}"
}
trap cleanup EXIT

log "restoring latest snapshot dumps from ${RESTIC_REPOSITORY}"
restic restore latest --tag basetool \
  --include '*/krt_basetool.dump' --include '*/keycloak.dump' \
  --include '*/monitoring/grafana.db' --include '*/monitoring/secrets.tar.gz' \
  --include '*/edge-certs.tar.gz' --include '*/edge-acme-state.tar.gz' \
  --include '*/config/users.acl' \
  --target "${WORK}" \
  || fail "restic restore failed"

check_artifact() {
  local found
  found="$(find "${WORK}" -name "$1" -size +0c -print -quit 2>/dev/null)"
  if [[ -n "${found}" ]]; then
    printf -v "$2" '%s' 1
    log "  present: $3"
  else
    log "  MISSING: $3 — $4"
  fi
}
log "checking the non-database restore surface (REQ-OPS-010)"
check_artifact 'edge-certs.tar.gz' OK_EDGE_CERTS 'the edge TLS material' \
  'a restored host cannot serve HTTPS, and re-issuing hits the Let'"'"'s Encrypt duplicate limit'
check_artifact 'edge-acme-state.tar.gz' OK_ACME_STATE 'the ACME account state' \
  'renewal starts from a new account and the issuance history is lost'
check_artifact 'users.acl' OK_REDIS_ACL 'the redis ACL' \
  'redis refuses to start, and a hand-written replacement can leave it open'

BACKEND_DUMP="$(find "${WORK}" -name krt_basetool.dump -print -quit)"
KEYCLOAK_DUMP="$(find "${WORK}" -name keycloak.dump -print -quit)"
[[ -s "${BACKEND_DUMP:-}" ]] || fail "backend dump not found in restored snapshot"
[[ -s "${KEYCLOAK_DUMP:-}" ]] || fail "keycloak dump not found in restored snapshot"
log "restored: $(du -h "${BACKEND_DUMP}" | cut -f1) backend, $(du -h "${KEYCLOAK_DUMP}" | cut -f1) keycloak"

rt_rm_force "${CONTAINER}"
log "starting throwaway Postgres (${DRILL_IMAGE})"
rt_run_detached "${CONTAINER}" "${DRILL_IMAGE}" \
  -e POSTGRES_USER=drill -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=postgres

log "waiting for it to become ready (timeout ${READY_TIMEOUT}s)"
deadline=$(( $(date +%s) + READY_TIMEOUT ))
until rt_exec "${CONTAINER}" pg_isready -U drill -d postgres >/dev/null 2>&1; do
  (( $(date +%s) < deadline )) || fail "throwaway Postgres did not become ready"
  sleep 2
done

dexec() { rt_exec "${CONTAINER}" "$@"; }

log "restoring backend dump → krt_basetool"
dexec createdb -U drill krt_basetool
rt_cp_to "${BACKEND_DUMP}" "${CONTAINER}" /tmp/krt_basetool.dump
dexec pg_restore -U drill -d krt_basetool --no-owner --no-privileges /tmp/krt_basetool.dump \
  || log "WARN: pg_restore (backend) reported non-fatal errors — verifying anyway"

log "restoring keycloak dump → keycloak"
dexec createdb -U drill keycloak
rt_cp_to "${KEYCLOAK_DUMP}" "${CONTAINER}" /tmp/keycloak.dump
dexec pg_restore -U drill -d keycloak --no-owner --no-privileges /tmp/keycloak.dump \
  || log "WARN: pg_restore (keycloak) reported non-fatal errors — verifying anyway"

q() { dexec psql -U drill -d "$1" -tAc "$2" | tr -d '[:space:]'; }

FLYWAY_ROWS="$(q krt_basetool "select count(*) from flyway_schema_history" 2>/dev/null || echo 0)"
BACKEND_TABLES="$(q krt_basetool "select count(*) from information_schema.tables where table_schema='public'" 2>/dev/null || echo 0)"
KEYCLOAK_TABLES="$(q keycloak "select count(*) from information_schema.tables where table_schema='public'" 2>/dev/null || echo 0)"

log "verification: flyway_schema_history rows=${FLYWAY_ROWS}, backend public tables=${BACKEND_TABLES}, keycloak public tables=${KEYCLOAK_TABLES}"

ok=true
if [[ "${FLYWAY_ROWS}" =~ ^[0-9]+$ && "${FLYWAY_ROWS}" -gt 0 \
      && "${BACKEND_TABLES}" =~ ^[0-9]+$ && "${BACKEND_TABLES}" -ge "${MIN_BACKEND_TABLES}" ]]; then
  OK_DB_BACKEND=1
else
  log "FAIL: backend restore incomplete (flyway rows=${FLYWAY_ROWS}, public tables=${BACKEND_TABLES} < ${MIN_BACKEND_TABLES})"
  ok=false
fi
if [[ "${KEYCLOAK_TABLES}" =~ ^[0-9]+$ && "${KEYCLOAK_TABLES}" -ge "${MIN_KEYCLOAK_TABLES}" ]]; then
  OK_DB_KEYCLOAK=1
else
  log "FAIL: keycloak public tables < ${MIN_KEYCLOAK_TABLES}"
  ok=false
fi

GRAFANA_DB="$(find "${WORK}" -name grafana.db -print -quit 2>/dev/null || true)"
if [[ -s "${GRAFANA_DB:-}" ]] && head -c 16 "${GRAFANA_DB}" 2>/dev/null | grep -q "SQLite format 3"; then
  OK_GRAFANA_SQLITE=1
  log "verification: grafana.db restored ($(du -h "${GRAFANA_DB}" | cut -f1), valid SQLite header)"
else
  log "WARN: grafana.db missing from snapshot or not a valid SQLite file (artifact_ok=0)"
fi
SECRETS_TAR="$(find "${WORK}" -name 'secrets.tar.gz' -print -quit 2>/dev/null || true)"
if [[ -s "${SECRETS_TAR:-}" ]] && tar tzf "${SECRETS_TAR}" >/dev/null 2>&1; then
  OK_MONITORING_SECRETS=1
  log "verification: monitoring secrets archive restored and readable"
else
  log "WARN: monitoring secrets archive missing from snapshot or unreadable (artifact_ok=0)"
fi

if [[ "${ok}" == "true" ]]; then
  log "RESTORE DRILL PASSED — the off-site DB backup is recoverable (monitoring artifacts: grafana=${OK_GRAFANA_SQLITE}, secrets=${OK_MONITORING_SECRETS})"
  exit 0
fi
fail "RESTORE DRILL FAILED — the latest backup did not restore cleanly (investigate immediately)"
