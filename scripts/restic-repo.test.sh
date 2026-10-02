#!/usr/bin/env bash
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="${HERE}/lib/restic-repo.sh"
[[ -f "${LIB}" ]] || { echo "FATAL: ${LIB} not found" >&2; exit 1; }

PASSED=0
FAILED=0
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/restic-repo-test.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/bin"
CALLS="${WORK}/calls"

cat > "${WORK}/bin/restic" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${STUB_CALLS}"
case "$1" in
  cat)
    [[ -n "${STUB_CAT_ERR:-}" ]] && printf '%s\n' "${STUB_CAT_ERR}" >&2
    exit "${STUB_CAT_RC:-0}" ;;
  init) exit "${STUB_INIT_RC:-0}" ;;
  unlock)
    printf '%s\n' "${STUB_UNLOCK_OUT:-}"
    exit "${STUB_UNLOCK_RC:-0}" ;;
  snapshots)
    printf '%s' "${STUB_SNAPSHOTS_JSON:-[]}"
    exit "${STUB_SNAPSHOTS_RC:-0}" ;;
  *) exit 0 ;;
esac
STUB
chmod +x "${WORK}/bin/restic"

run() {
  local -a call
  read -ra call <<< "$1"; shift
  : > "${CALLS}"
  # shellcheck disable=SC2016
  env -i PATH="${WORK}/bin:/usr/bin:/bin" STUB_CALLS="${CALLS}" HOME="${WORK}" \
    RESTIC_REPOSITORY="rclone:nextcloud:Test" BACKUP_ENV="/etc/iri/backup.env" "$@" \
    bash -c 'set -euo pipefail
      log() { printf "[t] %s\n" "$*"; }
      fail() { log "FATAL: $*"; exit 1; }
      . "$1"; shift; "$@"' _ "${LIB}" "${call[@]}" > "${WORK}/out" 2>&1
}

called() { grep -q -- "$1" "${CALLS}"; }

echo "== a reachable repository is opened and never initialised =="
run restic_open_repo STUB_CAT_RC=0
rc=$?
if [[ ${rc} -eq 0 ]]; then ok "a readable config opens the repository"; else bad "exit ${rc}: $(cat "${WORK}/out")"; fi
if ! called '^init'; then ok "no init against an existing repository"; else bad "init was called"; fi
if called '^cat config --no-lock'; then ok "the probe reads one file and takes no lock"; else bad "probe was: $(cat "${CALLS}")"; fi

echo "== only a repository that does not exist is initialised =="
run restic_open_repo STUB_CAT_RC=10
rc=$?
if [[ ${rc} -eq 0 ]] && called '^init'; then ok "exit 10 (repository does not exist) initialises it"; else bad "exit ${rc}, calls: $(cat "${CALLS}")"; fi
run restic_open_repo STUB_CAT_RC=10 STUB_INIT_RC=1
rc=$?
if [[ ${rc} -ne 0 ]] && grep -q 'FATAL: restic init failed' "${WORK}/out"; then ok "a failed init is fatal"; else bad "a failed init passed: $(cat "${WORK}/out")"; fi

echo "== a store that refuses is a failure, never an init =="
run restic_open_repo STUB_CAT_RC=1 STUB_CAT_ERR='rclone: CRITICAL: read metadata failed: 403 Forbidden'
rc=$?
if [[ ${rc} -ne 0 ]]; then ok "a 403 fails the run"; else bad "a 403 opened the repository"; fi
if ! called '^init'; then ok "a 403 does not initialise over the repository"; else bad "init was called on a 403"; fi
if grep -q '403 Forbidden' "${WORK}/out"; then ok "restic's own error reaches the log"; else bad "the error was swallowed: $(cat "${WORK}/out")"; fi
run restic_open_repo STUB_CAT_RC=12 STUB_CAT_ERR='Fatal: wrong password or no key found'
rc=$?
if [[ ${rc} -ne 0 ]] && ! called '^init'; then ok "a wrong password is not mistaken for a missing repository"; else bad "wrong password handled as missing"; fi

echo "== stale locks are cleared, and a failed unlock does not stop the run =="
run restic_clear_stale_locks STUB_UNLOCK_OUT='successfully removed 1 locks'
rc=$?
if [[ ${rc} -eq 0 ]] && grep -q 'restic unlock: successfully removed 1 locks' "${WORK}/out"; then ok "a removed stale lock is logged"; else bad "$(cat "${WORK}/out")"; fi
if grep -qx 'unlock' "${CALLS}"; then ok "plain unlock, which removes stale locks only"; else bad "unlock was called as: $(cat "${CALLS}")"; fi
run restic_clear_stale_locks STUB_UNLOCK_RC=1 STUB_UNLOCK_OUT='403 Forbidden'
rc=$?
if [[ ${rc} -eq 0 ]] && grep -q 'WARN: restic unlock failed' "${WORK}/out"; then ok "a failed unlock is a WARN"; else bad "$(cat "${WORK}/out")"; fi

echo "== retention groups by host and tag, not by the per-run staging path =="
run "restic_apply_retention basetool 7 4 6"
if grep -qx 'forget --tag basetool --group-by host,tags --keep-daily 7 --keep-weekly 4 --keep-monthly 6 --prune' "${CALLS}"; then
  ok "forget is grouped by host,tags"
else
  bad "forget was called as: $(cat "${CALLS}")"
fi

echo "== the snapshot count is read, or left out =="
run "restic_snapshot_count basetool" STUB_SNAPSHOTS_JSON='[{"id":"aa","short_id":"aa","parent":"bb"},{"id":"bb","short_id":"bb"}]'
if [[ "$(cat "${WORK}/out")" == 2 ]]; then ok "two snapshots count as 2"; else bad "counted '$(cat "${WORK}/out")'"; fi
if called '^snapshots --no-lock --tag basetool --json'; then ok "the count takes no lock"; else bad "called as: $(cat "${CALLS}")"; fi
run "restic_snapshot_count basetool" STUB_SNAPSHOTS_JSON='[]'
if [[ "$(cat "${WORK}/out")" == 0 ]]; then ok "an empty repository counts as 0"; else bad "counted '$(cat "${WORK}/out")'"; fi
run "restic_snapshot_count basetool" STUB_SNAPSHOTS_RC=1
rc=$?
if [[ ${rc} -ne 0 ]]; then ok "an unreadable count fails instead of reporting 0"; else bad "an unreadable count passed as '$(cat "${WORK}/out")'"; fi

printf '%d passed, %d failed\n' "${PASSED}" "${FAILED}"
[[ ${FAILED} -eq 0 ]]
