# shellcheck shell=bash

: "${RESTIC_REPO_MISSING_RC:=10}"

restic_log_lines() {
  local prefix="$1" text="$2" line
  while IFS= read -r line; do
    [[ -n "${line}" ]] && log "${prefix}${line}"
  done <<< "${text}"
  return 0
}

restic_open_repo() {
  local out rc
  out="$(restic cat config --no-lock 2>&1 >/dev/null)" && return 0
  rc=$?
  if [[ "${rc}" -eq "${RESTIC_REPO_MISSING_RC}" ]]; then
    log "restic repository does not exist yet — initialising it once"
    restic init || fail "restic init failed — check ${BACKUP_ENV:-the backup env} (repo URL, password, rclone remote)"
    return 0
  fi
  restic_log_lines "  restic: " "${out}"
  fail "restic repository ${RESTIC_REPOSITORY:-} is unreachable (restic exit ${rc}) — refusing to initialise over it; the store answered, so read the lines above"
}

restic_clear_stale_locks() {
  local out
  if out="$(restic unlock 2>&1)"; then
    restic_log_lines "restic unlock: " "${out}"
  else
    restic_log_lines "  restic: " "${out}"
    log "WARN: restic unlock failed — a stale lock may block this run"
  fi
  return 0
}

restic_apply_retention() {
  local tag="$1" daily="$2" weekly="$3" monthly="$4"
  restic forget --tag "${tag}" --group-by host,tags \
    --keep-daily "${daily}" --keep-weekly "${weekly}" --keep-monthly "${monthly}" \
    --prune
}

restic_snapshot_count() {
  local tag="$1" json count
  json="$(restic snapshots --no-lock --tag "${tag}" --json 2>/dev/null)" || return 1
  count="$(printf '%s' "${json}" | { grep -o '"short_id"' || true; } | wc -l | tr -d ' ')"
  [[ "${count}" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "${count}"
}
