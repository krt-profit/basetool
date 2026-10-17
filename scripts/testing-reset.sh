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
LOCKFILE="${IRI_LOCKFILE:-/var/lock/iri-deploy.lock}"
LOCK_WAIT="${IRI_RESET_LOCK_WAIT:-300}"
CONFIRM_FLAG="--wipe-testing-database"
WRITER_SERVICES=(frontend ingest backend)

usage() {
  printf 'usage: %s %s\n' "$(basename "$0")" "${CONFIRM_FLAG}" >&2
  printf 'Empties the Basetool database of the testing host (ADR-0278, REQ-OPS-050).\n' >&2
  printf 'Keycloak, Redis and every other volume stay as they are.\n' >&2
}

if [[ "${1:-}" != "${CONFIRM_FLAG}" || $# -ne 1 ]]; then
  usage
  exit 2
fi

[[ -f "${COMPOSE_DIR}/.env" ]] || fail "missing ${COMPOSE_DIR}/.env"
[[ "$(read_env APP_INSTANCE_TEST_SYSTEM)" == "true" ]] \
  || fail "refusing: ${COMPOSE_DIR}/.env does not set APP_INSTANCE_TEST_SYSTEM=true, so this is not the testing host"
[[ -n "$(read_env POSTGRES_DB)" ]] || fail "POSTGRES_DB is not set in ${COMPOSE_DIR}/.env"

rt_detect
log "container runtime: ${RT_BACKEND}"

exec 200>"${LOCKFILE}"
flock -w "${LOCK_WAIT}" 200 || fail "could not acquire the deploy lock within ${LOCK_WAIT}s"

STOPPED=false
# shellcheck disable=SC2317,SC2329
restart_writers() {
  if [[ "${STOPPED}" == "true" ]]; then
    log "starting ${WRITER_SERVICES[*]} again"
    rt_service_start backend ingest frontend || log "WARN: a writer did not start; check the units"
    STOPPED=false
  fi
}
trap restart_writers EXIT

log "stopping ${WRITER_SERVICES[*]}"
STOPPED=true
rt_service_stop "${WRITER_SERVICES[@]}" || fail "could not stop the writers"

log "dropping and recreating the Basetool database"
# shellcheck disable=SC2016
${RT_CLI} exec -i db-backend sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" dropdb -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 --if-exists --force "$POSTGRES_DB"' \
  || fail "dropdb failed; the writers are started again on the old database"
# shellcheck disable=SC2016
${RT_CLI} exec -i db-backend sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" createdb -U "$POSTGRES_USER" -h 127.0.0.1 -p 15432 "$POSTGRES_DB"' \
  || { STOPPED=false; fail "createdb failed; the writers stay stopped until the database exists again"; }

restart_writers
trap - EXIT
log "done: Flyway rebuilds the schema on the backend's start; re-seed with scripts/seed-briefing.py"
