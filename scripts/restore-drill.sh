#!/bin/bash
# =============================================================================
# Profit Basetool — weekly restore drill (recoverability proof, REQ-OPS-011)
#
# A backup you have never restored is a hope, not a backup. This script pulls
# the LATEST restic snapshot from Nextcloud, restores both database dumps into a
# THROWAWAY PostgreSQL container, and verifies them with sanity queries. It
# touches NOTHING in production — its own ephemeral container only. On any
# failure it exits non-zero so iri-restore-drill.service shows `failed`,
# journald flags it, and any OnFailure= hook fires.
#
# Runs weekly from iri-restore-drill.timer, or manually:
#   sudo -u deploy /var/iri/code/scripts/restore-drill.sh
#   sudo -u deploy /var/iri/code/scripts/restore-drill.sh --keep   # don't tear down on success (debug)
#
# Verification (a restore that produced an empty/garbage DB must FAIL):
#   * backend  — flyway_schema_history exists AND has rows (migrations restored)
#   * backend  — public-schema table count above a floor
#   * keycloak — table count above a floor
# =============================================================================

set -euo pipefail

STATE_DIR="${IRI_STATE_DIR:-/var/lib/iri}"
BACKUP_DIR="${IRI_BACKUP_DIR:-/var/iri/backup}"
WORK_BASE="${BACKUP_DIR}/restore-drill"
BACKUP_ENV="${IRI_BACKUP_ENV:-/etc/iri/backup.env}"
DRILL_IMAGE="${IRI_DRILL_IMAGE:-postgres:18-alpine}"
CONTAINER="iri-restore-drill"
READY_TIMEOUT="${IRI_DRILL_READY_TIMEOUT:-60}"
MIN_BACKEND_TABLES="${IRI_DRILL_MIN_BACKEND_TABLES:-20}"
MIN_KEYCLOAK_TABLES="${IRI_DRILL_MIN_KEYCLOAK_TABLES:-20}"

# Monitoring textfile metrics (epic #936). The drill writes its own outcome so Prometheus can alert
# on drill failure, on any single non-restorable artifact, on staleness (>35d) AND on the metric
# being absent (never ran) — the systemd failed-unit signal alone cannot tell "failed" from "never
# ran". Per-artifact status (0=not restorable, 1=ok); the DB pair drives last-success, the monitoring
# artifacts are reported independently so a missing Grafana/secrets artifact alerts on its own.
TEXTFILE_DIR="${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}"
START_EPOCH="$(date +%s)"
OK_DB_BACKEND=0
OK_DB_KEYCLOAK=0
OK_GRAFANA_SQLITE=0
OK_MONITORING_SECRETS=0

KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
fail() { log "FATAL: $*"; exit 1; }

# Writes the restore-drill textfile metric atomically. last_success bumps only when BOTH DB dumps
# restored (the drill's core recoverability proof); a failed run preserves the previous last_success
# so it reads as staleness/artifact_ok=0 rather than the metric vanishing (reserved for "never ran").
# shellcheck disable=SC2317,SC2329  # invoked indirectly via the EXIT trap (cleanup), like cleanup() below
write_drill_metrics() {
  local now dur prev tmp
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
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  tmp="${TEXTFILE_DIR}/restore_drill.prom.$$"
  if {
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
  } > "${tmp}" 2>/dev/null; then
    mv -f "${tmp}" "${TEXTFILE_DIR}/restore_drill.prom" 2>/dev/null || true
  else
    log "WARN: could not write restore-drill textfile metric (${TEXTFILE_DIR})"
    rm -f "${tmp}" 2>/dev/null || true
  fi
}

# --- Pre-flight -------------------------------------------------------------
[[ -f "${BACKUP_ENV}" ]] || fail "missing ${BACKUP_ENV}"
command -v docker >/dev/null 2>&1 || fail "docker not found"
command -v restic >/dev/null 2>&1 || fail "restic not found"
command -v rclone >/dev/null 2>&1 || fail "rclone not found"

export DOCKER_CONFIG="${DOCKER_CONFIG:-${STATE_DIR}/.docker}"
export RESTIC_CACHE_DIR="${RESTIC_CACHE_DIR:-${STATE_DIR}/restic-cache}"
mkdir -p "${DOCKER_CONFIG}" "${RESTIC_CACHE_DIR}" "${WORK_BASE}"

set -a
# shellcheck source=/dev/null  # operator-provided host file, not in the repo
. "${BACKUP_ENV}"
set +a
[[ -n "${RESTIC_REPOSITORY:-}" ]] || fail "RESTIC_REPOSITORY not set in ${BACKUP_ENV}"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
WORK="${WORK_BASE}/${TS}"
mkdir -p "${WORK}"
chmod 700 "${WORK}"

# shellcheck disable=SC2317  # cleanup runs indirectly via the EXIT trap set below
cleanup() {
  local rc=$?
  docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  # Always emit the outcome metric — on success AND on any failure path — so absent() means "never
  # ran", not "failed once".
  write_drill_metrics
  if [[ "${KEEP}" == "true" && "${rc}" -eq 0 ]]; then
    log "--keep: leaving restored dumps at ${WORK}"
  else
    rm -rf "${WORK}" 2>/dev/null || true
  fi
  exit "${rc}"
}
trap cleanup EXIT

# --- Restore the latest snapshot's dumps ------------------------------------
log "restoring latest snapshot dumps from ${RESTIC_REPOSITORY}"
restic restore latest --tag basetool \
  --include '*/krt_basetool.dump' --include '*/keycloak.dump' \
  --include '*/monitoring/grafana.db' --include '*/monitoring/secrets.tar.gz' \
  --target "${WORK}" \
  || fail "restic restore failed"

BACKEND_DUMP="$(find "${WORK}" -name krt_basetool.dump -print -quit)"
KEYCLOAK_DUMP="$(find "${WORK}" -name keycloak.dump -print -quit)"
[[ -s "${BACKEND_DUMP:-}" ]] || fail "backend dump not found in restored snapshot"
[[ -s "${KEYCLOAK_DUMP:-}" ]] || fail "keycloak dump not found in restored snapshot"
log "restored: $(du -h "${BACKEND_DUMP}" | cut -f1) backend, $(du -h "${KEYCLOAK_DUMP}" | cut -f1) keycloak"

# --- Spin a throwaway Postgres + restore into it ----------------------------
docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
log "starting throwaway Postgres (${DRILL_IMAGE})"
docker run -d --name "${CONTAINER}" \
  -e POSTGRES_USER=drill -e POSTGRES_PASSWORD=drill -e POSTGRES_DB=postgres \
  "${DRILL_IMAGE}" >/dev/null

log "waiting for it to become ready (timeout ${READY_TIMEOUT}s)"
deadline=$(( $(date +%s) + READY_TIMEOUT ))
until docker exec "${CONTAINER}" pg_isready -U drill -d postgres >/dev/null 2>&1; do
  (( $(date +%s) < deadline )) || fail "throwaway Postgres did not become ready"
  sleep 2
done

dexec() { docker exec -i "${CONTAINER}" "$@"; }

log "restoring backend dump → krt_basetool"
dexec createdb -U drill krt_basetool
docker cp "${BACKEND_DUMP}" "${CONTAINER}:/tmp/krt_basetool.dump"
dexec pg_restore -U drill -d krt_basetool --no-owner --no-privileges /tmp/krt_basetool.dump \
  || log "WARN: pg_restore (backend) reported non-fatal errors — verifying anyway"

log "restoring keycloak dump → keycloak"
dexec createdb -U drill keycloak
docker cp "${KEYCLOAK_DUMP}" "${CONTAINER}:/tmp/keycloak.dump"
dexec pg_restore -U drill -d keycloak --no-owner --no-privileges /tmp/keycloak.dump \
  || log "WARN: pg_restore (keycloak) reported non-fatal errors — verifying anyway"

# --- Verify (the actual proof) ----------------------------------------------
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

# --- Monitoring artifacts (epic #936): are the Grafana SQLite DB + the monitoring secrets archive
# restorable? These are reported independently (their own artifact_ok metric + alert) and do NOT gate
# the DB-recoverability proof, so a not-yet-captured monitoring artifact never masks a DB failure and
# vice versa. Absent from the snapshot (e.g. the first drill before the monitoring stack's first
# backup) reads as artifact_ok=0 — the runbook sequences a backup before the first post-rollout drill.
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
