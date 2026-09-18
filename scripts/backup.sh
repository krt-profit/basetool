#!/bin/bash
# =============================================================================
# Profit Basetool — consistent, encrypted, off-site backup
#
# Captures the full-restore backup surface and pushes it CLIENT-SIDE ENCRYPTED
# to a Nextcloud WebDAV target via restic (over an rclone remote). Runs nightly
# at 04:15 from iri-backup.timer, or manually:
#   sudo -u deploy /var/iri/code/scripts/backup.sh                # full run
#   sudo -u deploy /var/iri/code/scripts/backup.sh --no-quiesce   # online dump, zero downtime
#   sudo -u deploy /var/iri/code/scripts/backup.sh --skip-upload  # dump only, no restic push
#   sudo -u deploy /var/iri/code/scripts/backup.sh --dry-run      # show plan + snapshots, change nothing
#
# WHAT IS CAPTURED (the full-restore surface — docs/specs/backup-recovery.md,
# REQ-OPS-008/009):
#   * pg_dump -Fc of the backend database          (krt_basetool)
#   * pg_dump -Fc of the Keycloak database          (keycloak — the live source
#     of truth for realm/users/clients, NOT the sanitized realm-export.json)
#   * the edge's TLS material and ACME account      (the edge-certs,
#     edge-acme-state and edge-acme-webroot volumes) — without these a restored
#     host cannot serve HTTPS, and re-issuing runs into Let's Encrypt's limit of
#     five duplicate certificates per week for this SAN set
#   * host secrets and configuration needed to stand the stack up
#     (.env, keystore.p12, realm-export.json, keycloak/providers, and the redis
#     users.acl — which is ACCESS CONTROL, not session data: redis refuses to
#     start without the file its --aclfile names, ADR-0088)
#   * the monitoring plane (epic #936, ADR-0072)     — Grafana SQLite (consistent
#     copy), the rendered monitoring secrets/certs, the Alertmanager state, and a
#     WEEKLY Prometheus TSDB snapshot (admin API) protecting the 180-day archive
#   NOT captured by design: Redis (sessions just re-login), logs, the WireGuard
#     wg0.conf key (operator backs that up out-of-band — REQ-OPS-010), and — a
#     DELIBERATE data-protection decision (ADR-0072) — the Loki log store, whose
#     GFS retention would silently extend the approved 31-day IP retention; Tempo
#     traces and exporter/textfile data (regenerable) are excluded too.
#
# CONSISTENCY (REQ-OPS-009): pg_dump alone is already a transactionally
# consistent snapshot, but to obtain one globally quiescent instant we briefly
# STOP the writer services (frontend, backend, ingest) for the DUMP only — the
# edge serves the existing maintenance page meanwhile — then restart them BEFORE the
# slow restic upload. The user-facing window is therefore the dump duration
# (seconds), never the upload. A trap guarantees the stack is restarted even if
# a dump step fails, so production is never left down.
#
# COORDINATION: acquires the SAME flock deploy.sh uses (/var/lock/iri-deploy.lock)
# so a 5-minute deploy tick cannot recreate containers mid-backup, and vice
# versa. The lock is released as soon as the writers are back up, so the slow
# upload never blocks a deploy.
#
# SECRETS: the restic repo password + rclone/Nextcloud app-password live in
# /etc/iri/backup.env (root-only), never in git and never in the .env config
# bundle (REQ-OPS-005, REQ-OPS-012). The staged plaintext dumps live under
# /var/iri/backup/staging and are removed on every exit.
# =============================================================================

set -euo pipefail

# The container-runtime seam (ADR-0163, Phase 3): both shapes are live at once.
IRI_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=lib/container-runtime.sh
# shellcheck disable=SC1091
# repo-lint.yml runs shellcheck without -x, so it cannot follow a sourced file.
. "${IRI_SCRIPT_DIR}/lib/container-runtime.sh"

# --- Defaults / paths -------------------------------------------------------
COMPOSE_DIR="${IRI_COMPOSE_DIR:-/var/iri/code}"
STATE_DIR="${IRI_STATE_DIR:-/var/lib/iri}"
BACKUP_DIR="${IRI_BACKUP_DIR:-/var/iri/backup}"
STAGING_BASE="${BACKUP_DIR}/staging"
LOCKFILE="${IRI_LOCKFILE:-/var/lock/iri-deploy.lock}"
BACKUP_ENV="${IRI_BACKUP_ENV:-/etc/iri/backup.env}"
# The Redis ACL is CONFIGURATION, not session data -- see the capture below.
REDIS_ACL_PATH="${IRI_REDIS_ACL_HOST_PATH:-/var/iri/redis/users.acl}"
PROFILE=prod

# Writer services quiesced for the dump. Keycloak + the two Postgres DBs + Redis
# + the edge stay up (the edge must, to serve the maintenance page). An array so
# each name is passed as its own argument (no word-splitting landmines).
WRITER_SERVICES=(frontend backend ingest)
STOP_TIMEOUT="${IRI_BACKUP_STOP_TIMEOUT:-30}"
LOCK_WAIT="${IRI_BACKUP_LOCK_WAIT:-300}"

# GFS retention (REQ-OPS-008). Overridable from backup.env.
KEEP_DAILY="${IRI_KEEP_DAILY:-7}"
KEEP_WEEKLY="${IRI_KEEP_WEEKLY:-4}"
KEEP_MONTHLY="${IRI_KEEP_MONTHLY:-6}"

# Image used for the throwaway helper that reads root-owned paths and named
# volumes (the deploy user cannot open them directly; a container running as root
# can).
# Defaults to the Postgres image, which is always present on the host.
HELPER_IMAGE="${IRI_BACKUP_HELPER_IMAGE:-postgres:18-alpine}"

# Monitoring-plane backup (epic #936, ADR-0072). Best-effort and fully guarded so a host WITHOUT the
# monitoring stack is unaffected. Loki data is deliberately EXCLUDED (its GFS retention would silently
# extend the approved 31-day IP retention); Tempo + exporter/textfile data are excluded too (ADR-0072).
MON_COMPOSE="${COMPOSE_DIR}/docker-compose.monitoring.yml"
MON_DATA="${IRI_MONITORING_DIR:-/var/iri/monitoring}"
TEXTFILE_DIR="${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}"
CURL_IMAGE="${IRI_BACKUP_CURL_IMAGE:-curlimages/curl:8.11.1}"
START_EPOCH="$(date +%s)"

QUIESCE=true
SKIP_UPLOAD=false
DRY_RUN=false

# --- CLI args ---------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-quiesce) QUIESCE=false; shift ;;
    --skip-upload) SKIP_UPLOAD=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help)
      cat <<'USAGE'
Usage: backup.sh [--no-quiesce] [--skip-upload] [--dry-run]

Captures a consistent full-restore backup set and pushes it client-side
encrypted to Nextcloud via restic. See the header of this file and
docs/backup.md for the operator runbook.

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

# --- Helpers ----------------------------------------------------------------
log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
fail() { log "FATAL: $*"; exit 1; }

# Read a single KEY=value from the host .env (same approach as deploy.sh). Never
# echoes the value to logs.
read_env() {
  grep -E "^$1=" "${COMPOSE_DIR}/.env" 2>/dev/null | tail -n1 | cut -d= -f2- | tr -d '"' || true
}

# The runtime seam owns every container operation below; the two `docker compose`
# wrappers that used to live here went with their call sites.


# Writes the backup outcome textfile metric (node_exporter textfile collector; epic #936). Only
# called after a fully successful upload + restic check, so age>26h or absent() reliably means the
# backup missed or failed (ADR-0072 alert wiring).
write_backup_metrics() {
  local now dur tmp
  now="$(date +%s)"; dur=$(( now - START_EPOCH ))
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  tmp="${TEXTFILE_DIR}/backup.prom.$$"
  if {
    echo "# HELP basetool_backup_last_success_timestamp Unix time of the last successful off-site backup."
    echo "# TYPE basetool_backup_last_success_timestamp gauge"
    echo "basetool_backup_last_success_timestamp ${now}"
    echo "# HELP basetool_backup_duration_seconds Runtime of the last successful backup in seconds."
    echo "# TYPE basetool_backup_duration_seconds gauge"
    echo "basetool_backup_duration_seconds ${dur}"
  } > "${tmp}" 2>/dev/null; then
    mv -f "${tmp}" "${TEXTFILE_DIR}/backup.prom" 2>/dev/null || true
  else
    log "WARN: could not write backup textfile metric (${TEXTFILE_DIR})"
    rm -f "${tmp}" 2>/dev/null || true
  fi
}

# --- Pre-flight -------------------------------------------------------------
[[ -f "${COMPOSE_DIR}/docker-compose.yml" ]] || fail "missing ${COMPOSE_DIR}/docker-compose.yml"
[[ -f "${COMPOSE_DIR}/.env" ]] || fail "missing ${COMPOSE_DIR}/.env"
[[ -f "${BACKUP_ENV}" ]] || fail "missing ${BACKUP_ENV} (restic repo + rclone config; see docs/backup.md)"
rt_detect
export RT_COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.yml" RT_PROFILE="${PROFILE}"
export RT_PROJECT_DIR="${COMPOSE_DIR}" RT_MONITORING_FILE="${MON_COMPOSE}"
export RT_MONITORING_SERVICES="prometheus loki tempo grafana alertmanager blackbox-exporter postgres-exporter-backend postgres-exporter-keycloak redis-exporter"
export RT_STOP_TIMEOUT="${STOP_TIMEOUT}"
log "container runtime: ${RT_BACKEND}"
command -v restic >/dev/null 2>&1 || fail "restic not found (apt install restic)"
command -v rclone >/dev/null 2>&1 || fail "rclone not found (apt install rclone)"

# The deploy user has no usable $HOME; pin the tool config/cache dirs into
# STATE_DIR (already in the systemd unit's ReadWritePaths) so docker/restic do
# not try to write under an unreachable home.
export DOCKER_CONFIG="${DOCKER_CONFIG:-${STATE_DIR}/.docker}"
export RESTIC_CACHE_DIR="${RESTIC_CACHE_DIR:-${STATE_DIR}/restic-cache}"
mkdir -p "${DOCKER_CONFIG}" "${RESTIC_CACHE_DIR}" "${STAGING_BASE}"

# Load the backup secrets/config (RESTIC_REPOSITORY, RESTIC_PASSWORD, RCLONE_CONFIG, …).
set -a
# shellcheck source=/dev/null  # operator-provided host file, not in the repo
. "${BACKUP_ENV}"
set +a
[[ -n "${RESTIC_REPOSITORY:-}" ]] || fail "RESTIC_REPOSITORY not set in ${BACKUP_ENV}"
[[ -n "${RESTIC_PASSWORD:-}${RESTIC_PASSWORD_FILE:-}" ]] || fail "RESTIC_PASSWORD or RESTIC_PASSWORD_FILE not set in ${BACKUP_ENV}"

KEYSTORE_PATH="$(read_env IRI_KEYSTORE_HOST_PATH)"
KEYSTORE_PATH="${KEYSTORE_PATH:-/var/iri/secrets/keystore.p12}"

cd "${COMPOSE_DIR}"

# Compose v2 is a Docker-only prerequisite. Under Quadlet the equivalent question was
# already answered by rt_detect, which found the containers or refused.
if [[ "${RT_BACKEND}" == docker ]]; then
  docker compose version --short >/dev/null 2>&1 || fail "docker compose v2 not available"
fi

# --- Dry run ----------------------------------------------------------------
if [[ "${DRY_RUN}" == "true" ]]; then
  log "DRY RUN — would back up: krt_basetool + keycloak dumps, the edge-certs/edge-acme-state/edge-acme-webroot volumes, .env, ${KEYSTORE_PATH}, ${REDIS_ACL_PATH}, realm-export.json, keycloak/providers"
  log "DRY RUN — quiesce=${QUIESCE} (stop: ${WRITER_SERVICES[*]}); repo=${RESTIC_REPOSITORY}; retention ${KEEP_DAILY}/${KEEP_WEEKLY}/${KEEP_MONTHLY}"
  log "existing snapshots:"
  restic snapshots --compact 2>&1 | sed 's/^/  /' || log "  (repo not reachable / not initialized yet)"
  exit 0
fi

# --- Lock (shared with deploy.sh) -------------------------------------------
exec 200>"${LOCKFILE}"
if ! flock -w "${LOCK_WAIT}" 200; then
  fail "could not acquire deploy lock within ${LOCK_WAIT}s (a deploy may be running) — skipping this backup"
fi

# --- Staging + restart safety net -------------------------------------------
TS="$(date -u +%Y%m%dT%H%M%SZ)"
STAGING="${STAGING_BASE}/${TS}"
mkdir -p "${STAGING}/config"
chmod 700 "${STAGING}"

QUIESCED=false
# shellcheck disable=SC2317  # cleanup runs indirectly via the EXIT trap set below
cleanup() {
  local rc=$?
  # Safety net: if we stopped the writers and never restarted them (a dump
  # failed), bring them back so production is not left down.
  if [[ "${QUIESCED}" == "true" ]]; then
    log "cleanup: writers still stopped — restarting ${WRITER_SERVICES[*]}"
    rt_service_start "${WRITER_SERVICES[@]}" >/dev/null 2>&1 || log "WARN: failed to restart writers during cleanup"
    QUIESCED=false
  fi
  # The staged dumps contain plaintext secrets + PII — never leave them around.
  if [[ -n "${STAGING:-}" && -d "${STAGING}" ]]; then
    rm -rf "${STAGING}"
  fi
  exit "${rc}"
}
trap cleanup EXIT

# --- Quiesce writers (REQ-OPS-009) ------------------------------------------
if [[ "${QUIESCE}" == "true" ]]; then
  log "quiescing writers for the dump: stop ${WRITER_SERVICES[*]} (NPM serves the maintenance page)"
  rt_service_stop "${WRITER_SERVICES[@]}"
  QUIESCED=true
else
  log "running ONLINE (no quiesce): relying on pg_dump MVCC snapshot consistency"
fi

# --- Dump the databases (creds stay inside the containers) ------------------
log "dumping backend database (krt_basetool)"
# SC2016: the $VARs are intentionally single-quoted — they must expand inside the
# container from its own env, not on the host (keeps the password off the host arg list).
# shellcheck disable=SC2016
rt_exec db-backend sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 -Fc "$POSTGRES_DB"' \
  > "${STAGING}/krt_basetool.dump"

log "dumping Keycloak database (keycloak)"
# shellcheck disable=SC2016  # see the note above — expand inside the container, not the host
rt_exec db-keycloak sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump -U "$POSTGRES_USER" -h 127.0.0.1 -p 15433 -Fc "$POSTGRES_DB"' \
  > "${STAGING}/keycloak.dump"

# --- Capture the edge's TLS material and ACME account state -----------------
#
# This replaces the nginx-proxy-manager capture, and it is not a rename. NPM was
# removed from the stack; its successor keeps the same material in three NAMED
# VOLUMES, and for a while the backup captured the retired proxy's directory and
# none of them. REQ-OPS-010 says the set is "exactly what a full restore needs",
# and without these a restore cannot serve HTTPS:
#
#   edge-certs        the issued certificates and their private keys
#   edge-acme-state   the ACME account key and the issuance history
#   edge-acme-webroot the http-01 challenge root
#
# Losing the ACME state is not merely inconvenient. Let's Encrypt allows five
# duplicate certificates per week for a SAN set, so a host rebuilt without them
# re-issues into a rate limit — and the migration plan's cutover explicitly seeds
# the certificates from the old host rather than re-issuing.
#
# Each volume is optional and reported: a host that does not run the edge has
# none of them, and that must read differently from one where the read failed.
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

# --- Capture the Redis ACL, which is configuration and not session data -----
#
# /var/iri/redis holds two different things: the append-only files and dump.rdb,
# which are sessions and are deliberately excluded, and users.acl, which is the
# access control. ADR-0088: redis-server is started with --aclfile, refuses to
# start without the file that names, and an ACL file missing a `default` entry
# makes redis reset that user to `nopass ~* &* +@all` at load.
#
# Nothing generates it — it is host-provisioned — so a restore without it
# produces a redis that does not come up, and a hand-written replacement that
# omits one line produces one that is wide open.
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

# --- Capture host secrets / config needed for a full restore ----------------
log "capturing host config (.env, keystore, realm-export, providers)"
cp -p "${COMPOSE_DIR}/.env" "${STAGING}/config/dotenv"
if [[ -f "${KEYSTORE_PATH}" ]]; then
  # The keystore is a root-owned 0640 secret (REQ-OPS-016, #1018): readable only by
  # root, group 10001 (the JVM containers) and uid 1000 via a POSIX ACL (Keycloak).
  # The deploy user that runs this backup is none of those and CANNOT read it
  # directly — a plain `cp` here is what broke the 2026-07-06 run. Capture it the
  # same way as the other root-owned artifacts (NPM mount, grafana.db): stream the
  # bytes out through a throwaway root helper container. This uses docker access the
  # deploy user already has, so it needs no extra host ACL and leaves the keystore's
  # 0640 hardening untouched. Best-effort + loud: a read failure must never abort the
  # whole backup and lose the irreplaceable DB dumps (the original set -e landmine).
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
if [[ -f "${COMPOSE_DIR}/realm-export.json" ]]; then
  cp -p "${COMPOSE_DIR}/realm-export.json" "${STAGING}/config/realm-export.json"
fi
if [[ -d "${COMPOSE_DIR}/keycloak/providers" ]]; then
  tar -C "${COMPOSE_DIR}/keycloak" -czf "${STAGING}/config/providers.tar.gz" providers 2>/dev/null \
    || log "WARN: could not archive keycloak/providers — skipped"
fi

# --- Restart writers BEFORE the slow upload -------------------------------
if [[ "${QUIESCED}" == "true" ]]; then
  log "dumps captured — restarting writers (${WRITER_SERVICES[*]})"
  rt_service_start "${WRITER_SERVICES[@]}"
  QUIESCED=false
fi

# --- Capture the monitoring plane (epic #936, ADR-0072) ---------------------
# Grafana SQLite (consistent copy via a brief graceful stop — a clean shutdown checkpoints the WAL,
# so a plain copy of grafana.db is complete), the rendered secrets/certs (host-rebuild = restore, not
# re-provisioning), and the Alertmanager state (silences + notification log). Done while still holding
# the deploy lock so a concurrent deploy-monitoring `up` cannot restart Grafana mid-copy. Fully guarded
# and best-effort: a monitoring failure never fails the DB backup.
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

# --- Release the deploy lock; the slow upload runs while fully live ----------
flock -u 200 || true
log "deploy lock released; the rest runs while fully live"

# --- Weekly (Sunday) Prometheus TSDB snapshot into the backup ---------------
# Protects the 180-day metric archive + the #937 baseline against host/disk loss (ADR-0072). Uses the
# admin API (enabled ONLY together with basic auth) via a throwaway curl container on the core net —
# the host has no published Prometheus port by design. Best-effort; the snapshot dir is cleaned after
# staging so the prometheus volume does not grow unbounded.
if [[ "$(date -u +%u)" == "7" ]] && rt_monitoring_configured && rt_network_exists net-monitoring-core; then
  PROM_PW="$(read_env PROMETHEUS_WEB_PASSWORD)"
  if [[ -n "${PROM_PW}" ]]; then
    mkdir -p "${STAGING}/monitoring"
    log "weekly Prometheus TSDB snapshot via admin API"
    snap_json="$(rt_run_on_network net-monitoring-core "${CURL_IMAGE}" \
      -sS -u "grafana:${PROM_PW}" -XPOST http://prometheus:9090/api/v1/admin/tsdb/snapshot 2>/dev/null || true)"
    snap_name="$(printf '%s' "${snap_json}" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')"
    if [[ -n "${snap_name}" && -d "${MON_DATA}/data/prometheus/snapshots/${snap_name}" ]]; then
      if rt_read_mount "${MON_DATA}/data/prometheus/snapshots/${snap_name}" "${HELPER_IMAGE}" \
           sh -c 'tar -C /src -cz .' > "${STAGING}/monitoring/prometheus-tsdb-snapshot.tar.gz" 2>/dev/null; then
        log "captured Prometheus TSDB snapshot ${snap_name}"
      else
        log "WARN: could not archive the Prometheus TSDB snapshot"
      fi
      rm -rf "${MON_DATA}/data/prometheus/snapshots/${snap_name}" 2>/dev/null || true
    else
      log "WARN: Prometheus TSDB snapshot failed or dir missing (name='${snap_name:-}')"
    fi
  fi
fi

if [[ "${SKIP_UPLOAD}" == "true" ]]; then
  log "--skip-upload: dumps staged at ${STAGING} (will be removed on exit); not pushing to restic"
  exit 0
fi

# --- Push to Nextcloud via restic (client-side encrypted) -------------------
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

# Monitoring signal: record the successful backup for the "backup >26h or absent" alert (epic #936).
write_backup_metrics

log "backup complete"
