#!/usr/bin/env bash
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${HERE}/testing-reset.sh"
[[ -f "$SCRIPT" ]] || { echo "FATAL: ${SCRIPT} not found" >&2; exit 1; }

PASSED=0
FAILED=0
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/testing-reset-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
BIN="${WORK}/bin"
LOG="${WORK}/calls.log"
mkdir -p "$BIN" "${WORK}/code" "${WORK}/config"

make_stub() {
  local name="$1"
  local bash_path; bash_path="$(command -v bash)"
  cat > "${BIN}/${name}" <<STUB
#!${bash_path}
printf '%s %s\n' "${name}" "\$*" >> "${LOG}"
for pat in \${STUB_FAIL:-}; do
  case "\$*" in *"\${pat}"*) exit 1 ;; esac
done
exit 0
STUB
  chmod +x "${BIN}/${name}"
}

make_stub podman
make_stub systemctl
make_stub flock

run_reset() {
  local env_body="$1"; shift
  printf '%s\n' "$env_body" > "${WORK}/config/basetool.env"
  : > "$LOG"
  PATH="${BIN}:${PATH}" RT_BACKEND=podman RT_CLI="" RT_SYSTEMCTL="" \
    BASETOOL_COMPOSE_DIR="${WORK}/code" BASETOOL_CONFIG_DIR="${WORK}/config" BASETOOL_LOCKFILE="${WORK}/lock" \
    BASETOOL_MONITORING_TEXTFILE_DIR="${WORK}/textfile" \
    bash "$SCRIPT" "$@" > "${WORK}/out" 2>&1
  echo $? > "${WORK}/rc"
}

line_of() {
  grep -nF -- "$1" "$LOG" | head -n 1 | cut -d: -f1
}

TESTING=$'APP_INSTANCE_TEST_SYSTEM=true\nPOSTGRES_DB=krt_basetool'

run_reset "$TESTING"
if [[ "$(cat "${WORK}/rc")" == "2" && ! -s "$LOG" ]]; then
  ok "without the confirmation flag it prints the usage and touches nothing"
else
  bad "without the confirmation flag it prints the usage and touches nothing (rc $(cat "${WORK}/rc"))"
fi

run_reset "$TESTING" --wipe-testing-database extra
if [[ "$(cat "${WORK}/rc")" == "2" && ! -s "$LOG" ]]; then
  ok "an extra argument is refused"
else
  bad "an extra argument is refused (rc $(cat "${WORK}/rc"))"
fi

run_reset $'POSTGRES_DB=krt_basetool' --wipe-testing-database
if [[ "$(cat "${WORK}/rc")" == "1" ]] && ! grep -q 'podman\|systemctl' "$LOG"; then
  ok "a host without APP_INSTANCE_TEST_SYSTEM=true is refused before any call"
else
  bad "a host without APP_INSTANCE_TEST_SYSTEM=true is refused before any call"
fi

run_reset $'APP_INSTANCE_TEST_SYSTEM=false\nPOSTGRES_DB=krt_basetool' --wipe-testing-database
if [[ "$(cat "${WORK}/rc")" == "1" ]] && ! grep -q 'podman\|systemctl' "$LOG"; then
  ok "APP_INSTANCE_TEST_SYSTEM=false is refused"
else
  bad "APP_INSTANCE_TEST_SYSTEM=false is refused"
fi

run_reset "$TESTING" --wipe-testing-database
stop="$(line_of 'stop frontend.service')"
drop="$(line_of 'dropdb')"
create="$(line_of 'createdb')"
start="$(line_of 'start backend.service')"
if [[ "$(cat "${WORK}/rc")" == "0" && -n "$stop" && -n "$drop" && -n "$create" && -n "$start" ]] \
   && (( stop < drop && drop < create && create < start )); then
  ok "the writers stop, the database is dropped and recreated, then the writers start"
else
  bad "the writers stop, the database is dropped and recreated, then the writers start"
  sed 's/^/        /' "$LOG"
fi
if grep -q 'stop ingest.service' "$LOG" && grep -q 'stop backend.service' "$LOG" \
   && grep -q 'start frontend.service' "$LOG"; then
  ok "all three writers are stopped and started"
else
  bad "all three writers are stopped and started"
fi
if grep -q 'exec -i db-backend' "$LOG" && ! grep -qi 'keycloak\|redis' "$LOG"; then
  ok "only the Basetool database is touched"
else
  bad "only the Basetool database is touched"
fi

STUB_FAIL=dropdb run_reset "$TESTING" --wipe-testing-database
if [[ "$(cat "${WORK}/rc")" != "0" ]] && grep -q 'start backend.service' "$LOG" \
   && ! grep -q 'createdb' "$LOG"; then
  ok "a failed drop starts the writers again on the old database"
else
  bad "a failed drop starts the writers again on the old database"
fi

STUB_FAIL=createdb run_reset "$TESTING" --wipe-testing-database
if [[ "$(cat "${WORK}/rc")" != "0" ]] && ! grep -q 'start backend.service' "$LOG"; then
  ok "a failed create leaves the writers stopped"
else
  bad "a failed create leaves the writers stopped"
fi

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
(( FAILED == 0 ))
