#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail
set -o errtrace
umask 077

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${MIGRATE_ROOT:-}"
STAGE="${MIGRATE_STAGE:-${ROOT}/root/basetool-migration}"
WAIT_SECONDS="${MIGRATE_WAIT_SECONDS:-120}"
POLL_SECONDS="${MIGRATE_POLL_SECONDS:-2}"
RUNTIME_WAIT_SECONDS="${MIGRATE_RUNTIME_WAIT_SECONDS:-60}"
PYTHON="${MIGRATE_PYTHON:-python3}"
ENV_HELPER="${MIGRATE_ENV_HELPER:-${HERE}/migrate-host-env.py}"
EUID_VALUE="${MIGRATE_EUID:-${EUID}}"

OLD_USER=iri
NEW_USER=basetool
DEPLOY_USER=deploy
SUBID_START=100000
SUBID_COUNT=65536
VOLUMES=(edge-certs edge-acme-state edge-acme-webroot)

OLD_HOME=/home/iri
NEW_HOME=/home/basetool
NEW_DEPLOY_HOME=/var/lib/basetool/deploy
NEW_ROOTS=(/opt/basetool /etc/basetool /var/lib/basetool /var/log/basetool /var/cache/basetool)
NEW_PARENTS=(/etc/basetool /etc/basetool/monitoring /var/lib/basetool /var/log/basetool /var/cache/basetool)
UNIT_ROOT=/etc/containers/systemd/users

FCONTEXT_RULES=(
  "/opt/basetool(/.*)? container_file_t"
  "/opt/basetool/scripts(/.*)? bin_t"
  "/etc/basetool(/.*)? container_file_t"
  "/etc/basetool/deploy(/.*)? etc_t"
  "/var/lib/basetool(/.*)? container_file_t"
  "/var/lib/basetool/deploy(/.*)? var_lib_t"
  "/var/log/basetool/(backend|frontend|ingest|keycloak)(/.*)? container_file_t"
)
OLD_FCONTEXT_RULES=(
  "/var/iri(/.*)? container_file_t"
  "/var/iri/code/scripts(/.*)? bin_t"
)

STATIC_MOVES=(
  "/var/iri/code/.env|/etc/basetool/basetool.env|req"
  "/var/iri/code/env.d|/etc/basetool/env.d|req"
  "/var/iri/code/realm-export.json|/etc/basetool/realm-export.json|opt"
  "/var/iri/code|/opt/basetool|req"
  "/var/iri/secrets|/etc/basetool/secrets|req"
  "/var/iri/monitoring/secrets|/etc/basetool/monitoring/secrets|opt"
  "/var/iri/monitoring/certs|/etc/basetool/monitoring/certs|opt"
  "/var/iri/monitoring/textfile|/var/lib/basetool/textfile|opt"
  "/var/iri/monitoring/data|/var/lib/basetool/monitoring|opt"
  "/var/iri/db-backend|/var/lib/basetool/db-backend|req"
  "/var/iri/db-keycloak|/var/lib/basetool/db-keycloak|req"
  "/var/iri/redis|/var/lib/basetool/redis|req"
  "/var/iri/backup|/var/lib/basetool/backup|req"
  "/var/iri/backend/log|/var/log/basetool/backend|opt"
  "/var/iri/frontend/log|/var/log/basetool/frontend|opt"
  "/var/iri/ingest/log|/var/log/basetool/ingest|opt"
  "/var/iri/keycloak/log|/var/log/basetool/keycloak|opt"
  "/var/lib/iri/restic-cache|/var/cache/basetool/restic|opt"
  "/var/lib/iri|/var/lib/basetool/deploy|req"
  "/etc/iri/ssg-tailoring.xml|/etc/basetool/ssg-tailoring.xml|opt"
  "/etc/iri|/etc/basetool/deploy|req"
)
PLANNED_VAR_IRI=(code secrets monitoring db-backend db-keycloak redis backup backend frontend ingest keycloak)
RELEASE_ENTRIES=(docker-compose.yml docker-compose.monitoring.yml monitoring docker keycloak-theme quadlet host-scripts.sha256 scripts keycloak)
LEFTOVER_PARENTS=(
  /var/iri/backend /var/iri/frontend /var/iri/ingest /var/iri/keycloak /var/iri/monitoring /var/iri
)

RESIDUE_DIRS=(
  /etc/systemd/system /etc/default /etc/sysconfig /etc/logrotate.d /etc/tmpfiles.d /etc/sudoers.d
  /etc/alloy /etc/containers/systemd /etc/cron.d /etc/profile.d
)
STATE_ANCHORS=(
  config-previous config-stage config-blocked.marker config-apply.incomplete
  previous-digest-pin.yml current-digest-pin.yml last-deployed.digests
  failed.digests reapply-failed.digests mixed-release.since health-restart.digests
  keycloak-spi-previous.jar keycloak-spi-stage.jar
)
TREE_ANCHORS=(
  docker-compose.yml docker-compose.monitoring.yml monitoring docker keycloak-theme quadlet host-scripts.sha256
)

DRY=0
ACTIONS=0
PLAN=()
declare -A PLAN_TARGETS=()

trap 'printf "FATAL: a command failed (line %s)\n" "$LINENO" >&2' ERR

P() {
  printf '%s%s' "${ROOT}" "$1"
}

say() {
  printf '%s\n' "$*"
}

note() {
  printf '  %s\n' "$*"
}

die() {
  trap - ERR
  printf 'FATAL: %s\n' "$*" >&2
  exit 1
}

refuse() {
  trap - ERR
  printf 'REFUSED: %s\n' "$*" >&2
  exit 3
}

act() {
  ACTIONS=$(( ACTIONS + 1 ))
  if (( DRY )); then
    printf '  would: %s\n' "$*"
  else
    printf '  do: %s\n' "$*"
    case "$1" in
      semanage | usermod | groupmod | loginctl | restorecon | systemctl) ( umask 022; "$@" ) ;;
      *) "$@" ;;
    esac
  fi
}

act_to() {
  local out="$1"
  shift
  ACTIONS=$(( ACTIONS + 1 ))
  if (( DRY )); then
    printf '  would: %s > %s\n' "$*" "${out}"
  else
    printf '  do: %s > %s\n' "$*" "${out}"
    "$@" > "${out}"
  fi
}

finish() {
  if (( DRY )); then
    say "$1: dry run, ${ACTIONS} step(s) would run, nothing was changed"
  elif (( ACTIONS == 0 )); then
    say "$1: nothing to do, already done"
  else
    say "$1: done (${ACTIONS} step(s))"
  fi
}

usage() {
  cat <<'USAGE'
Usage: migrate-host-layout.sh [--dry-run] PHASE

Moves one Basetool host from the old account and directory layout to the current one.
Run as root, by hand, one phase at a time. Nothing runs automatically. Each phase refuses
(exit 3) while its precondition is unmet and is a no-op when it was done already.

Phases, in order:
  check            read-only report of every precondition and of what each phase would do
  stop             disable the old timers, wait for running jobs, stop alloy and the service
                   user's manager, park the Quadlet units (the volume units stay)
  export-volumes   export the three edge volumes as the old user into the root-only staging dir
  reset-podman     podman system reset as the old user (needs the three verified exports)
  accounts         rename user and group, subuid and subgid, home; linger for the new name
  move             move every directory and file to its new place, fix the label rules
  rewrite          rewrite basetool.env, its backups, backup.env and realm-export.json
  cleanup          remove the old units, logrotate and tmpfiles files and the old label rules
  import-volumes   re-create the edge volumes as the new user and import the exports

Options:
  --dry-run        print what the phase would do and change nothing
  -h, --help       this text

Exit codes: 0 done or nothing to do, 1 failed, 2 usage, 3 a precondition refused the phase.
Environment: MIGRATE_ROOT (path prefix, for tests), MIGRATE_STAGE (staging directory, default
/root/basetool-migration), MIGRATE_WAIT_SECONDS (how long the stop phase waits).
USAGE
}

need_root() {
  if (( EUID_VALUE != 0 )); then
    refuse "run as root"
  fi
}

exists_path() {
  [[ -e "$1" || -L "$1" ]]
}

dir_is_empty() {
  local entries
  [[ -d "$1" ]] || return 1
  shopt -s nullglob dotglob
  entries=("$1"/*)
  shopt -u nullglob dotglob
  (( ${#entries[@]} == 0 ))
}

empty_but_for() {
  local dir="$1" entry skip other
  shift
  shopt -s nullglob dotglob
  for entry in "${dir}"/*; do
    skip=0
    for other in "$@"; do
      if [[ "${entry}" == "${other}" ]]; then
        skip=1
      fi
    done
    if (( ! skip )); then
      shopt -u nullglob dotglob
      return 1
    fi
  done
  shopt -u nullglob dotglob
  return 0
}

user_exists() {
  awk -F: -v n="$1" '$1 == n { found = 1 } END { exit !found }' "$(P /etc/passwd)"
}

group_exists() {
  awk -F: -v n="$1" '$1 == n { found = 1 } END { exit !found }' "$(P /etc/group)"
}

passwd_field() {
  awk -F: -v n="$1" -v i="$2" '$1 == n { print $i }' "$(P /etc/passwd)"
}

service_user() {
  if user_exists "${OLD_USER}"; then
    printf '%s' "${OLD_USER}"
  elif user_exists "${NEW_USER}"; then
    printf '%s' "${NEW_USER}"
  fi
}

unit_busy() {
  case "$(systemctl is-active "$1" 2> /dev/null || true)" in
    active | activating | deactivating | reloading) return 0 ;;
    *) return 1 ;;
  esac
}

unit_enabled() {
  case "$(systemctl is-enabled "$1" 2> /dev/null || true)" in
    enabled*) return 0 ;;
    *) return 1 ;;
  esac
}

busy_lines() {
  local mode="$1" uid="$2"
  ps -eo uid=,pid=,comm= | awk -v m="${mode}" -v u="${uid}" -v lo="${SUBID_START}" \
    -v hi="$(( SUBID_START + SUBID_COUNT - 1 ))" \
    '($1 >= lo && $1 <= hi) || (m == "user" && $1 == u) { print }'
}

refuse_if_busy() {
  local mode="$1" uid="$2" what="$3" lines
  lines="$(busy_lines "${mode}" "${uid}")"
  if [[ -n "${lines}" ]]; then
    printf '%s\n' "${lines}" | sed -n '1,10s/^/    running (uid pid command): /p' >&2
    refuse "${what}"
  fi
}

wait_quiet() {
  local mode="$1" uid="$2" waited=0
  if (( DRY )); then
    return 0
  fi
  while [[ -n "$(busy_lines "${mode}" "${uid}")" ]]; do
    if (( waited >= WAIT_SECONDS )); then
      return 1
    fi
    sleep "${POLL_SECONDS}"
    waited=$(( waited + POLL_SECONDS ))
  done
}

start_manager() {
  local uid="$1" waited=0
  if ! unit_busy "user@${uid}.service"; then
    act systemctl start "user@${uid}.service"
  fi
  if (( DRY )); then
    return 0
  fi
  until [[ -d "$(P "/run/user/${uid}")" ]]; do
    if (( waited >= RUNTIME_WAIT_SECONDS )); then
      die "/run/user/${uid} did not appear within ${RUNTIME_WAIT_SECONDS}s"
    fi
    sleep "${POLL_SECONDS}"
    waited=$(( waited + POLL_SECONDS ))
  done
}

stop_manager() {
  local uid="$1"
  if unit_busy "user@${uid}.service"; then
    act systemctl stop "user@${uid}.service"
  fi
  end_pause_process "${uid}"
  if ! wait_quiet user "${uid}"; then
    busy_lines user "${uid}" | sed -n '1,10s/^/    running (uid pid command): /p' >&2
    refuse "processes of uid ${uid} or of its subuid range are still running"
  fi
}

start_runtime() {
  local uid="$1" waited=0
  if ! unit_busy "user-runtime-dir@${uid}.service"; then
    act systemctl start "user-runtime-dir@${uid}.service"
  fi
  if (( DRY )); then
    return 0
  fi
  until [[ -d "$(P "/run/user/${uid}")" ]]; do
    if (( waited >= RUNTIME_WAIT_SECONDS )); then
      die "/run/user/${uid} did not appear within ${RUNTIME_WAIT_SECONDS}s"
    fi
    sleep "${POLL_SECONDS}"
    waited=$(( waited + POLL_SECONDS ))
  done
}

end_pause_process() {
  local uid="$1"
  if ps -eo uid=,pid=,comm= | awk -v u="${uid}" '$1 == u && $3 == "catatonit" { found = 1 } END { exit !found }'; then
    act pkill -U "${uid}" -x catatonit
  fi
}

stop_runtime() {
  local uid="$1"
  if unit_busy "user-runtime-dir@${uid}.service"; then
    act systemctl stop "user-runtime-dir@${uid}.service"
  fi
  end_pause_process "${uid}"
  if ! wait_quiet user "${uid}"; then
    busy_lines user "${uid}" | sed -n '1,10s/^/    running (uid pid command): /p' >&2
    refuse "processes of uid ${uid} or of its subuid range are still running"
  fi
}

podman_store() {
  printf '%s/.local/share/containers/storage' "$(P "$1")"
}

as_user() {
  local name="$1" uid home
  shift
  uid="$(passwd_field "${name}" 3)"
  home="$(passwd_field "${name}" 6)"
  runuser -u "${name}" -- env HOME="${home}" XDG_RUNTIME_DIR="/run/user/${uid}" "$@"
}

private_dir() {
  local dir
  for dir in "$@"; do
    if [[ ! -d "${dir}" ]]; then
      act mkdir -p "${dir}"
      act chmod 0700 "${dir}"
    fi
  done
}

stage_init() {
  private_dir "${STAGE}" "${STAGE}/state" "${STAGE}/volumes"
}

stage_marked() {
  [[ -e "${STAGE}/state/$1" ]]
}

stage_mark() {
  stage_init
  act touch "${STAGE}/state/$1"
}

ensure_tree() {
  local path="$1" cur="" part
  local -a parts
  IFS=/ read -ra parts <<< "${path#/}"
  for part in "${parts[@]}"; do
    cur="${cur}/${part}"
    if [[ ! -d "$(P "${cur}")" ]]; then
      act mkdir "$(P "${cur}")"
      act chmod 0755 "$(P "${cur}")"
      act chown root:root "$(P "${cur}")"
    fi
  done
}

add_plan() {
  local req="$3"
  if [[ -n "${PLAN_TARGETS[$2]:-}" ]]; then
    req=collision
  fi
  PLAN_TARGETS["$2"]=1
  PLAN+=("$1|$2|${req}")
}

HOST_LOCAL=/etc/basetool/host-local

map_suffix() {
  local value="${1//IRI_BASETOOL_VERSION/BASETOOL_VERSION}"
  printf '%s' "${value//IRI_/BASETOOL_}"
}

emit_unmapped() {
  local dir="$1" target="$2" prefix="$3" f name known k
  shift 3
  shopt -s nullglob dotglob
  for f in "$(P "${dir}")"/*; do
    name="${f##*/}"
    known=0
    if [[ -n "${prefix}" && "${name}" == "${prefix}"* ]]; then
      known=1
    fi
    for k in "$@"; do
      if [[ "${name}" == "${k}" ]]; then
        known=1
      fi
    done
    if (( ! known )); then
      printf '%s/%s|%s/%s|opt\n' "${dir}" "${name}" "${target}" "${name}"
    fi
  done
  shopt -u nullglob dotglob
}

unmapped_rows() {
  local svc
  emit_unmapped /var/iri/code "${HOST_LOCAL}/release" .env.backup- .env env.d realm-export.json "${RELEASE_ENTRIES[@]}"
  emit_unmapped /var/iri/monitoring "${HOST_LOCAL}/monitoring" "" secrets certs textfile data
  emit_unmapped /var/iri "${HOST_LOCAL}/data" "" "${PLANNED_VAR_IRI[@]}"
  for svc in backend frontend ingest keycloak; do
    emit_unmapped "/var/iri/${svc}" "${HOST_LOCAL}/data/${svc}" "" log
  done
}

build_plan() {
  local f suffix row from to req
  local -A seen=()
  PLAN=()
  PLAN_TARGETS=()
  shopt -s nullglob
  for f in "$(P /var/iri/code)"/.env.backup-*; do
    suffix="${f##*/.env.backup-}"
    add_plan "/var/iri/code/.env.backup-${suffix}" "/etc/basetool/basetool.env.backup-$(map_suffix "${suffix}")" opt
  done
  for f in "$(P /etc/basetool)"/basetool.env.backup-*; do
    suffix="${f##*/basetool.env.backup-}"
    add_plan "/var/iri/code/.env.backup-${suffix}" "/etc/basetool/basetool.env.backup-${suffix}" opt
  done
  while IFS='|' read -r from to req; do
    if [[ -n "${from}" ]]; then
      add_plan "${from}" "${to}" "${req}"
    fi
  done < <(unmapped_rows)
  for row in "${STATIC_MOVES[@]}"; do
    PLAN+=("${row}")
  done
  for f in "$(P /var/log)"/iri-*; do
    if [[ -f "${f}" ]]; then
      seen["${f##*/iri-}"]=1
    fi
  done
  for f in "$(P /var/log/basetool)"/*; do
    if [[ -f "${f}" ]]; then
      seen["${f##*/}"]=1
    fi
  done
  for suffix in "${!seen[@]}"; do
    add_plan "/var/log/iri-${suffix}" "/var/log/basetool/${suffix}" opt
  done
  shopt -u nullglob
}

row_state() {
  local from="$1" to="$2" req="$3" f t
  if [[ "${req}" == collision ]]; then
    printf 'conflict'
    return 0
  fi
  f="$(P "${from}")"
  t="$(P "${to}")"
  if ! exists_path "${f}"; then
    if exists_path "${t}"; then
      printf 'done'
    else
      printf 'absent-%s' "${req}"
    fi
  elif ! exists_path "${t}"; then
    printf 'todo'
  elif dir_is_empty "${t}"; then
    printf 'todo-replace'
  else
    printf 'conflict'
  fi
}

plan_blockers() {
  local row from to req state blockers=0
  for row in "${PLAN[@]}"; do
    IFS='|' read -r from to req <<< "${row}"
    state="$(row_state "${from}" "${to}" "${req}")"
    case "${state}" in
      conflict | absent-req) blockers=$(( blockers + 1 )) ;;
    esac
  done
  printf '%s' "${blockers}"
}

print_plan() {
  local row from to req state
  for row in "${PLAN[@]}"; do
    IFS='|' read -r from to req <<< "${row}"
    state="$(row_state "${from}" "${to}" "${req}")"
    note "[${state}] ${from} -> ${to}"
  done
}

env_file_unsafe() {
  local mode
  mode="$(stat -L -c %a "$1")" || return 0
  [[ "${mode}" =~ ^[0-7]+$ ]] || return 0
  (( 8#${mode} & 8#026 ))
}

unsafe_env_names() {
  local f
  shopt -s nullglob dotglob
  for f in "$(P /var/iri/code)"/.env* "$(P /etc/iri)/backup.env" "$(P /etc/basetool)"/basetool.env* \
           "$(P /etc/basetool/deploy)/backup.env" "$(P "${HOST_LOCAL}")"/*/.env* \
           "$(P /var/iri/code/env.d)"/* "$(P /etc/basetool/env.d)"/*; do
    if [[ -f "${f}" ]] && env_file_unsafe "${f}"; then
      printf '%s\n' "${f#"${ROOT}"}"
    fi
  done
  shopt -u nullglob dotglob
}

check_env_modes() {
  local name
  while IFS= read -r name; do
    report block "env file ${name} is group-writable or world-accessible: remove the group-write and all other-access bits before the window"
  done < <(unsafe_env_names)
}

ensure_private_tree() {
  local path="$1" cur="${HOST_LOCAL}" part
  local -a parts
  ensure_tree /etc/basetool
  IFS=/ read -ra parts <<< "${path#"${HOST_LOCAL}"}"
  for part in "" "${parts[@]}"; do
    if [[ -n "${part}" ]]; then
      cur="${cur}/${part}"
    fi
    if [[ ! -d "$(P "${cur}")" ]]; then
      act mkdir "$(P "${cur}")"
      act chmod 0700 "$(P "${cur}")"
      act chown root:root "$(P "${cur}")"
    elif [[ "$(stat -c '%u:%g %a' "$(P "${cur}")")" != "0:0 700" ]]; then
      act chmod 0700 "$(P "${cur}")"
      act chown root:root "$(P "${cur}")"
    fi
  done
}

move_one() {
  local from="$1" to="$2" req="$3" state
  state="$(row_state "${from}" "${to}" "${req}")"
  case "${state}" in
    todo | todo-replace)
      if [[ "${to}" == "${HOST_LOCAL}"/* ]]; then
        ensure_private_tree "$(dirname "${to}")"
      else
        ensure_tree "$(dirname "${to}")"
      fi
      if [[ "${state}" == todo-replace ]]; then
        act rmdir "$(P "${to}")"
      fi
      act mv "$(P "${from}")" "$(P "${to}")"
      ;;
    done) note "already in place: ${to}" ;;
    absent-opt) note "absent, optional: ${from}" ;;
    absent-req) die "required path is absent on both sides: ${from}" ;;
    conflict) die "both ${from} and ${to} exist" ;;
  esac
}

fcontext_listed() {
  semanage fcontext -l -C 2> /dev/null | awk -v p="$1" '$1 == p { found = 1 } END { exit !found }'
}

fcontext_has() {
  semanage fcontext -l -C 2> /dev/null | awk -v p="$1" -v t=":$2:" '$1 == p && index($0, t) > 0 { found = 1 } END { exit !found }'
}

fcontext_set() {
  local pattern="$1" type="$2"
  if fcontext_has "${pattern}" "${type}"; then
    note "label rule present: ${pattern} ${type}"
  elif fcontext_listed "${pattern}"; then
    act semanage fcontext -m -t "${type}" "${pattern}"
  else
    act semanage fcontext -a -t "${type}" "${pattern}"
  fi
}

fcontext_drop() {
  if fcontext_listed "$1"; then
    act semanage fcontext -d "$1"
  else
    note "label rule absent: $1"
  fi
}

restore_labels() {
  local root pending
  local -a args=()
  for root in "$@"; do
    if [[ -e "$(P "${root}")" ]]; then
      args+=("$(P "${root}")")
    fi
  done
  if (( ${#args[@]} == 0 )); then
    return 0
  fi
  pending="$(restorecon -nRFv "${args[@]}")"
  if [[ -n "${pending}" ]]; then
    act restorecon -RF "${args[@]}"
  fi
}

unit_dir_for() {
  local who
  who="$(service_user)"
  printf '%s/%s' "${UNIT_ROOT}" "$(passwd_field "${who}" 3)"
}

volume_export_ok() {
  local dir="${STAGE}/volumes" name="$1"
  [[ -s "${dir}/${name}.tar" && -s "${dir}/${name}.tar.sha256" ]] || return 1
  ( cd "${dir}" && sha256sum --check --status "${name}.tar.sha256" )
}

volumes_exported() {
  local name
  for name in "${VOLUMES[@]}"; do
    volume_export_ok "${name}" || return 1
  done
}

sha_of() {
  ( cd "$1" && sha256sum "$2" )
}

export_volume() {
  local user="$1" name="$2" dir="${STAGE}/volumes"
  act_to "${dir}/${name}.tar.partial" as_user "${user}" podman volume export "${name}"
  if (( ! DRY )); then
    tar -tf "${dir}/${name}.tar.partial" > /dev/null || die "the export of ${name} is not a readable tar archive"
    [[ -s "${dir}/${name}.tar.partial" ]] || die "the export of ${name} is empty"
  fi
  act mv "${dir}/${name}.tar.partial" "${dir}/${name}.tar"
  act_to "${dir}/${name}.tar.sha256" sha_of "${dir}" "${name}.tar"
}

import_tar() {
  as_user "$1" podman volume import "$2" - < "$3"
}

volume_entries() {
  local listing
  listing="$(as_user "$1" podman volume export "$2" | tar -tf -)" || return 1
  printf '%s\n' "${listing}" | sed -e 's#^\./##' -e '/^$/d' | sort
}

import_marker() {
  printf 'import-%s.done' "$1"
}

precondition_stopped() {
  local uid="$1" f unit unit_dir leftovers
  shopt -s nullglob
  for f in "$(P /etc/systemd/system)"/iri-*.timer; do
    unit="${f##*/}"
    if unit_busy "${unit}"; then
      shopt -u nullglob
      refuse "${unit} is still active: run the stop phase first"
    fi
  done
  shopt -u nullglob
  if unit_busy "user@${uid}.service"; then
    refuse "the user manager user@${uid}.service is running: run the stop phase first"
  fi
  refuse_if_busy containers "${uid}" "container processes (subuid range) are still running: run the stop phase first"
  unit_dir="$(P "$(unit_dir_for)")"
  if [[ -d "${unit_dir}" ]]; then
    leftovers="$(find "${unit_dir}" -mindepth 1 -maxdepth 1 ! -name '*.volume' -print -quit)"
    if [[ -n "${leftovers}" ]]; then
      refuse "Quadlet units are still in $(unit_dir_for): run the stop phase first"
    fi
  fi
}

park_units() {
  local dir="$1" f name
  [[ -d "${dir}" ]] || return 0
  shopt -s nullglob dotglob
  for f in "${dir}"/*; do
    name="${f##*/}"
    case "${name}" in
      *.volume) continue ;;
    esac
    private_dir "${STAGE}/units"
    act mv "${f}" "${STAGE}/units/${name}"
  done
  shopt -u nullglob dotglob
}

phase_stop() {
  local who uid f unit waited=0 running
  local -a timers=() services=()
  who="$(service_user)"
  [[ -n "${who}" ]] || refuse "neither ${OLD_USER} nor ${NEW_USER} exists"
  if [[ "${who}" == "${NEW_USER}" ]]; then
    say "stop: ${OLD_USER} is already renamed to ${NEW_USER}; the stack that runs now is not touched"
    finish stop
    return 0
  fi
  uid="$(passwd_field "${who}" 3)"
  say "stop: timers, jobs and the user manager of ${who} (uid ${uid})"
  stage_init
  shopt -s nullglob
  for f in "$(P /etc/systemd/system)"/iri-*.timer; do
    timers+=("${f##*/}")
  done
  for f in "$(P /etc/systemd/system)"/iri-*.service; do
    services+=("${f##*/}")
  done
  shopt -u nullglob
  for unit in "${timers[@]}"; do
    if unit_busy "${unit}" || unit_enabled "${unit}"; then
      act systemctl disable --now "${unit}"
    fi
  done
  if (( ! DRY )); then
    while :; do
      running=""
      for unit in "${services[@]}"; do
        if unit_busy "${unit}"; then
          running+=" ${unit}"
        fi
      done
      if [[ -z "${running}" ]]; then
        break
      fi
      if (( waited >= WAIT_SECONDS )); then
        refuse "still running:${running} -- let the job finish, or stop it yourself and run this phase again"
      fi
      sleep "${POLL_SECONDS}"
      waited=$(( waited + POLL_SECONDS ))
    done
  fi
  for unit in "${services[@]}"; do
    if unit_enabled "${unit}"; then
      act systemctl disable "${unit}"
    fi
  done
  if unit_busy alloy.service; then
    act systemctl stop alloy.service
  fi
  if unit_busy dnf-automatic.timer; then
    act systemctl stop dnf-automatic.timer
    stage_mark dnf-stopped
  fi
  stop_manager "${uid}"
  park_units "$(P "$(unit_dir_for)")"
  finish stop
}

phase_export_volumes() {
  local uid name
  if volumes_exported; then
    say "export-volumes: ${VOLUMES[*]}"
    finish export-volumes
    return 0
  fi
  user_exists "${OLD_USER}" || refuse "${OLD_USER} does not exist (accounts already renamed, and the exports are missing?)"
  uid="$(passwd_field "${OLD_USER}" 3)"
  precondition_stopped "${uid}"
  for name in "${VOLUMES[@]}"; do
    if [[ ! -e "$(P "$(unit_dir_for)")/${name}.volume" ]]; then
      refuse "the Quadlet unit $(unit_dir_for)/${name}.volume does not exist"
    fi
  done
  say "export-volumes: ${VOLUMES[*]} as ${OLD_USER}"
  stage_init
  start_runtime "${uid}"
  if (( ! DRY )); then
    for name in "${VOLUMES[@]}"; do
      if ! as_user "${OLD_USER}" podman volume exists "${name}"; then
        stop_runtime "${uid}"
        refuse "volume ${name} does not exist in the store of ${OLD_USER}: nothing to export, and the reset must not run"
      fi
    done
  fi
  for name in "${VOLUMES[@]}"; do
    if volume_export_ok "${name}"; then
      note "already exported and verified: ${name}"
    else
      export_volume "${OLD_USER}" "${name}"
    fi
  done
  stop_runtime "${uid}"
  note "the exports hold private keys; ${STAGE} stays root-only"
  finish export-volumes
}

phase_reset_podman() {
  local uid home
  if stage_marked reset.done; then
    say "reset-podman: podman system reset as ${OLD_USER}"
    finish reset-podman
    return 0
  fi
  user_exists "${OLD_USER}" || refuse "${OLD_USER} does not exist and no reset was recorded"
  uid="$(passwd_field "${OLD_USER}" 3)"
  home="$(passwd_field "${OLD_USER}" 6)"
  precondition_stopped "${uid}"
  volumes_exported || refuse "no verified, non-empty export of ${VOLUMES[*]} in ${STAGE}/volumes: run export-volumes first"
  say "reset-podman: podman system reset as ${OLD_USER}"
  start_runtime "${uid}"
  if [[ -d "$(P "${home}")/.config/containers" && ! -e "${STAGE}/user-containers-config.tar" ]]; then
    act tar -C "$(P "${home}")" -cf "${STAGE}/user-containers-config.tar" .config/containers
    note "the reset keeps ${home}/.config/containers, which moves with the home; the archive is a safety copy"
  fi
  act as_user "${OLD_USER}" podman system reset --force
  stop_runtime "${uid}"
  if (( ! DRY )) && exists_path "$(podman_store "${home}")"; then
    refuse "a podman store reappeared under ${home} after the reset (a second podman process?): stop it and run the phase again"
  fi
  stage_mark reset.done
  finish reset-podman
}

validate_subid() {
  local file="$1" from="$2" to="$3" want="${SUBID_START}:${SUBID_COUNT}"
  [[ -f "${file}" ]] || refuse "${file#"${ROOT}"} does not exist"
  if grep -qE "^${to}:" "${file}"; then
    grep -qE "^${to}:${want}\$" "${file}" || refuse "${file#"${ROOT}"} gives ${to} another range than ${want}"
    if grep -qE "^${from}:" "${file}"; then
      refuse "${file#"${ROOT}"} has both ${from} and ${to}"
    fi
  elif ! grep -qE "^${from}:${want}\$" "${file}"; then
    refuse "${file#"${ROOT}"} has no ${from}:${want} line"
  fi
}

rename_subid() {
  local file="$1" from="$2" to="$3"
  if grep -qE "^${from}:" "${file}"; then
    act sed -i "s/^${from}:/${to}:/" "${file}"
  fi
}

accounts_pending() {
  local current="$1" home="$2"
  user_exists "${OLD_USER}" && return 0
  group_exists "${OLD_USER}" && return 0
  [[ "${home}" == "${OLD_HOME}" ]] && return 0
  exists_path "$(P "/var/lib/systemd/linger/${OLD_USER}")" && return 0
  grep -qE "^${OLD_USER}:" "$(P /etc/subuid)" && return 0
  grep -qE "^${OLD_USER}:" "$(P /etc/subgid)" && return 0
  [[ -n "${current}" ]] && ! exists_path "$(P "/var/lib/systemd/linger/${NEW_USER}")" && return 0
  return 1
}

phase_accounts() {
  local current uid home
  if user_exists "${OLD_USER}" && user_exists "${NEW_USER}"; then
    refuse "both ${OLD_USER} and ${NEW_USER} exist"
  fi
  current="$(service_user)"
  [[ -n "${current}" ]] || refuse "neither ${OLD_USER} nor ${NEW_USER} exists"
  if group_exists "${OLD_USER}" && group_exists "${NEW_USER}"; then
    refuse "both group ${OLD_USER} and group ${NEW_USER} exist"
  fi
  uid="$(passwd_field "${current}" 3)"
  home="$(passwd_field "${current}" 6)"
  validate_subid "$(P /etc/subuid)" "${OLD_USER}" "${NEW_USER}"
  validate_subid "$(P /etc/subgid)" "${OLD_USER}" "${NEW_USER}"
  say "accounts: ${OLD_USER} -> ${NEW_USER} (uid ${uid} stays)"
  if ! accounts_pending "${current}" "${home}"; then
    finish accounts
    return 0
  fi
  if user_exists "${OLD_USER}"; then
    stage_marked reset.done || refuse "reset-podman has not completed: the home may only move after the reset"
  fi
  if [[ "${home}" == "${OLD_HOME}" ]] && exists_path "$(P "${NEW_HOME}")"; then
    refuse "${NEW_HOME} already exists"
  fi
  if user_exists "${OLD_USER}" && exists_path "$(podman_store "${home}")"; then
    refuse "a podman store exists under ${home}: it would move to the new home and break every podman call; stop what recreated it, remove ${STAGE}/state/reset.done and run reset-podman again"
  fi
  refuse_if_busy user "${uid}" "processes of uid ${uid} or of its subuid range are still running"
  if exists_path "$(P "/var/lib/systemd/linger/${OLD_USER}")"; then
    if user_exists "${OLD_USER}"; then
      act loginctl disable-linger "${OLD_USER}"
    else
      act rm -f "$(P "/var/lib/systemd/linger/${OLD_USER}")"
    fi
  fi
  if group_exists "${OLD_USER}"; then
    act groupmod -n "${NEW_USER}" "${OLD_USER}"
  fi
  if user_exists "${OLD_USER}"; then
    act usermod -l "${NEW_USER}" "${OLD_USER}"
  fi
  rename_subid "$(P /etc/subuid)" "${OLD_USER}" "${NEW_USER}"
  rename_subid "$(P /etc/subgid)" "${OLD_USER}" "${NEW_USER}"
  if [[ "${home}" == "${OLD_HOME}" ]]; then
    act usermod -d "${NEW_HOME}" -m "${NEW_USER}"
  fi
  if ! exists_path "$(P "/var/lib/systemd/linger/${NEW_USER}")"; then
    act loginctl enable-linger "${NEW_USER}"
  fi
  finish accounts
}

phase_move() {
  local row from to req blockers root unsafe
  user_exists "${NEW_USER}" || refuse "${NEW_USER} does not exist: run the accounts phase first"
  if user_exists "${OLD_USER}"; then
    refuse "${OLD_USER} still exists: run the accounts phase first"
  fi
  refuse_if_busy containers "$(passwd_field "${NEW_USER}" 3)" "container processes (subuid range) are still running"
  command -v semanage > /dev/null 2>&1 || refuse "semanage is not installed"
  unsafe="$(unsafe_env_names)"
  if [[ -n "${unsafe}" ]]; then
    printf '%s\n' "${unsafe}" | sed 's/^/  group-writable or world-accessible env file: /' >&2
    refuse "an env file is group-writable or world-accessible (see above); remove the group-write and all other-access bits and run the phase again"
  fi
  build_plan
  blockers="$(plan_blockers)"
  if (( blockers > 0 )); then
    print_plan
    refuse "${blockers} path(s) are in conflict or required but absent; fix them and run the phase again"
  fi
  say "move: ${#PLAN[@]} path(s)"
  for root in "${NEW_PARENTS[@]}"; do
    ensure_tree "${root}"
  done
  for row in "${PLAN[@]}"; do
    IFS='|' read -r from to req <<< "${row}"
    move_one "${from}" "${to}" "${req}"
  done
  if [[ "$(passwd_field "${DEPLOY_USER}" 6)" != "${NEW_DEPLOY_HOME}" ]]; then
    act usermod -d "${NEW_DEPLOY_HOME}" "${DEPLOY_USER}"
  fi
  for row in "${FCONTEXT_RULES[@]}"; do
    fcontext_set "${row%% *}" "${row##* }"
  done
  for row in "${OLD_FCONTEXT_RULES[@]}"; do
    fcontext_drop "${row%% *}"
  done
  restore_labels "${NEW_ROOTS[@]}"
  finish move
}

env_targets() {
  local f
  for f in "$(P /etc/basetool)/basetool.env" "$(P /etc/basetool/deploy)/backup.env"; do
    if [[ -f "${f}" ]]; then
      printf '%s\n' "${f}"
    fi
  done
}

env_backup_targets() {
  local f
  shopt -s nullglob
  for f in "$(P /etc/basetool)"/basetool.env.backup-*; do
    if [[ -f "${f}" ]]; then
      printf '%s\n' "${f}"
    fi
  done
  shopt -u nullglob
}

stage_original() {
  local file="$1" dest
  dest="${STAGE}/originals${file#"${ROOT}"}"
  if [[ -e "${dest}" ]]; then
    return 0
  fi
  private_dir "$(dirname "${dest}")"
  act cp -p "${file}" "${dest}"
}

run_helper() {
  local kind="$1" check="$2" file="$3" out rc=0
  if [[ "${check}" == 1 ]]; then
    out="$("${PYTHON}" -I "${ENV_HELPER}" "${kind}" --check "${file}")" || rc=$?
  else
    out="$("${PYTHON}" -I "${ENV_HELPER}" "${kind}" "${file}")" || rc=$?
  fi
  printf '%s\n' "${out}" | sed 's/^/  /'
  if [[ "${check}" != 1 ]] && grep -q ': changed;' <<< "${out}"; then
    ACTIONS=$(( ACTIONS + 1 ))
  fi
  return "${rc}"
}

phase_rewrite() {
  local file realm_export state_dir name bad=0
  local -a files=() backups=()
  [[ -f "$(P /etc/basetool/basetool.env)" ]] || refuse "/etc/basetool/basetool.env does not exist: run the move phase first"
  [[ -f "${ENV_HELPER}" ]] || die "the env helper ${ENV_HELPER} is missing"
  say "rewrite: keys, paths and the realm name in the host-local files (values are never printed)"
  while IFS= read -r file; do
    files+=("${file}")
  done < <(env_targets)
  realm_export="$(P /etc/basetool/realm-export.json)"
  for file in "${files[@]}"; do
    run_helper env 1 "${file}" || bad=1
  done
  while IFS= read -r file; do
    if run_helper env 1 "${file}"; then
      backups+=("${file}")
    else
      note "left as it is, and not blocking the others: ${file#"${ROOT}"}"
    fi
  done < <(env_backup_targets)
  files+=("${backups[@]}")
  if [[ -f "${realm_export}" ]]; then
    run_helper realm-export 1 "${realm_export}" || bad=1
  fi
  if (( bad )); then
    refuse "a host-local file cannot be rewritten as it is (see above); nothing was written"
  fi
  if (( ! DRY )); then
    stage_init
    for file in "${files[@]}"; do
      stage_original "${file}"
    done
    if [[ -f "${realm_export}" ]]; then
      stage_original "${realm_export}"
    fi
    for file in "${files[@]}"; do
      run_helper env 0 "${file}"
    done
    if [[ -f "${realm_export}" ]]; then
      run_helper realm-export 0 "${realm_export}"
    fi
  fi
  if ! stage_marked anchors.done; then
    state_dir="$(P "${NEW_DEPLOY_HOME}")"
    for name in "${STATE_ANCHORS[@]}"; do
      if exists_path "${state_dir}/${name}"; then
        private_dir "${STAGE}/anchors"
        act mv "${state_dir}/${name}" "${STAGE}/anchors/${name}"
      fi
    done
    for name in "${TREE_ANCHORS[@]}"; do
      if exists_path "$(P /opt/basetool)/${name}"; then
        private_dir "${STAGE}/release-tree"
        act mv "$(P /opt/basetool)/${name}" "${STAGE}/release-tree/${name}"
      fi
    done
    stage_mark anchors.done
  fi
  restore_labels /etc/basetool
  finish rewrite
}

stage_old_file() {
  local file="$1" dest
  dest="${STAGE}/old-files${file#"${ROOT}"}"
  if [[ -e "${dest}" || -L "${dest}" ]]; then
    return 0
  fi
  private_dir "$(dirname "${dest}")"
  act cp -a "${file}" "${dest}"
}

phase_cleanup() {
  local dir f row left leftovers=0
  local -a old=() removed=()
  user_exists "${NEW_USER}" || refuse "${NEW_USER} does not exist: run the accounts phase first"
  [[ -f "$(P /etc/basetool/basetool.env)" ]] || refuse "/etc/basetool/basetool.env does not exist: run the move phase first"
  [[ ! -e "$(P /var/iri/code)" ]] || refuse "/var/iri/code still exists: run the move phase first"
  if grep -qE '^[[:space:]]*(export[[:space:]]+)?IRI_' "$(P /etc/basetool/basetool.env)"; then
    refuse "basetool.env still has IRI_ keys: run the rewrite phase first"
  fi
  if [[ ! -f "$(P /etc/systemd/system/basetool-deploy.service)" || ! -f "$(P /etc/systemd/system/basetool-deploy.timer)" ]]; then
    refuse "the basetool-* units are not installed yet: run the Ansible role first, or this leaves the host without any unit"
  fi
  say "cleanup: old units, drop-ins, logrotate and tmpfiles files, label rules, stale locks"
  carry_dropins
  shopt -s nullglob
  for dir in /etc/systemd/system /etc/logrotate.d /etc/tmpfiles.d; do
    for f in "$(P "${dir}")"/iri-* "$(P "${dir}")"/*.wants/iri-*; do
      old+=("${f}")
    done
  done
  for f in "$(P /etc/tmpfiles.d)"/iri-locks.conf "$(P /run/lock)"/iri-*.lock "$(P /var/lib/systemd/linger)/${OLD_USER}" \
           "$(P /var/lib/systemd/timers)"/stamp-iri-*.timer; do
    old+=("${f}")
  done
  shopt -u nullglob
  for f in "${old[@]}"; do
    if exists_path "${f}"; then
      if [[ "${f}" != "$(P /run/lock)"/* && "${f}" != "$(P /var/lib/systemd/linger)"/* && "${f}" != "$(P /var/lib/systemd/timers)"/* ]]; then
        stage_old_file "${f}"
      fi
      act rm -rf "${f}"
    fi
  done
  if (( ACTIONS > 0 )); then
    act systemctl daemon-reload
    act systemctl reset-failed 'iri-*'
  fi
  for row in "${OLD_FCONTEXT_RULES[@]}"; do
    fcontext_drop "${row%% *}"
  done
  for dir in "${LEFTOVER_PARENTS[@]}"; do
    if [[ -d "$(P "${dir}")" ]]; then
      if empty_but_for "$(P "${dir}")" "${removed[@]}"; then
        act rmdir "$(P "${dir}")"
        removed+=("$(P "${dir}")")
      else
        left="$(find "$(P "${dir}")" -mindepth 1 -maxdepth 1 -printf '%f ')"
        note "left in ${dir}: ${left}"
        leftovers=1
      fi
    fi
  done
  if (( leftovers )); then
    die "the old tree still holds the entries above; move or remove them by hand, then run cleanup again"
  fi
  finish cleanup
}

restart_dnf_timer() {
  if stage_marked dnf-stopped; then
    if ! unit_busy dnf-automatic.timer; then
      act systemctl start dnf-automatic.timer
    fi
    act rm -f "${STAGE}/state/dnf-stopped"
  fi
}

phase_import_volumes() {
  local uid unit_dir name marker entries
  user_exists "${NEW_USER}" || refuse "${NEW_USER} does not exist: run the accounts phase first"
  if user_exists "${OLD_USER}"; then
    refuse "${OLD_USER} still exists: run the accounts phase first"
  fi
  exists_path "$(P "/var/lib/systemd/linger/${NEW_USER}")" || refuse "${NEW_USER} is not lingering"
  stage_marked reset.done || refuse "reset-podman has not completed"
  volumes_exported || refuse "no verified, non-empty export of ${VOLUMES[*]} in ${STAGE}/volumes"
  uid="$(passwd_field "${NEW_USER}" 3)"
  unit_dir="$(P "${UNIT_ROOT}/${uid}")"
  for name in "${VOLUMES[@]}"; do
    if [[ ! -e "${unit_dir}/${name}.volume" ]]; then
      refuse "the Quadlet unit ${UNIT_ROOT}/${uid}/${name}.volume does not exist"
    fi
  done
  say "import-volumes: re-create the edge volumes as ${NEW_USER} and import the exports"
  marker=0
  for name in "${VOLUMES[@]}"; do
    if ! stage_marked "$(import_marker "${name}")"; then
      marker=1
    fi
  done
  if (( marker == 0 )); then
    restart_dnf_timer
    finish import-volumes
    return 0
  fi
  start_manager "${uid}"
  if (( ! DRY )); then
    as_user "${NEW_USER}" systemctl --user daemon-reload
  fi
  for name in "${VOLUMES[@]}"; do
    if stage_marked "$(import_marker "${name}")"; then
      note "already imported: ${name}"
      continue
    fi
    act as_user "${NEW_USER}" systemctl --user start "${name}-volume.service"
    if (( DRY )); then
      continue
    fi
    as_user "${NEW_USER}" podman volume exists "${name}" || die "volume ${name} was not created by ${name}-volume.service"
    entries="$(volume_entries "${NEW_USER}" "${name}")" || die "cannot read volume ${name} of ${NEW_USER}; it is not treated as empty"
    if [[ -n "${entries}" ]]; then
      refuse "volume ${name} already holds content and no import of it was recorded; not importing over it"
    fi
    act import_tar "${NEW_USER}" "${name}" "${STAGE}/volumes/${name}.tar"
    stage_mark "$(import_marker "${name}")"
  done
  note "check the ownership: ls -ln of each volume's mountpoint must show uids in ${SUBID_START}-$(( SUBID_START + SUBID_COUNT - 1 ))"
  restart_dnf_timer
  finish import-volumes
}

report() {
  local tag="$1"
  shift
  printf '[%s] %s\n' "${tag}" "$*"
}

check_tools() {
  local tool
  for tool in runuser loginctl usermod groupmod systemctl semanage restorecon tar sha256sum "${PYTHON}"; do
    if command -v "${tool}" > /dev/null 2>&1; then
      report ok "tool present: ${tool}"
    else
      report block "tool missing: ${tool}"
    fi
  done
  if command -v podman > /dev/null 2>&1; then
    report ok "podman: $(podman --version 2> /dev/null || printf unknown)"
  else
    report block "tool missing: podman"
  fi
}

check_accounts() {
  local who uid gid home lines
  if user_exists "${OLD_USER}" && user_exists "${NEW_USER}"; then
    report block "both ${OLD_USER} and ${NEW_USER} exist"
  elif user_exists "${OLD_USER}"; then
    uid="$(passwd_field "${OLD_USER}" 3)"
    gid="$(passwd_field "${OLD_USER}" 4)"
    home="$(passwd_field "${OLD_USER}" 6)"
    report todo "account ${OLD_USER} uid ${uid} gid ${gid} home ${home} will become ${NEW_USER}"
    if grep -qE "^${OLD_USER}:${SUBID_START}:${SUBID_COUNT}\$" "$(P /etc/subuid)" 2> /dev/null \
       && grep -qE "^${OLD_USER}:${SUBID_START}:${SUBID_COUNT}\$" "$(P /etc/subgid)" 2> /dev/null; then
      report ok "subuid and subgid: ${OLD_USER}:${SUBID_START}:${SUBID_COUNT}"
    else
      report block "subuid/subgid do not both hold ${OLD_USER}:${SUBID_START}:${SUBID_COUNT}"
    fi
    if exists_path "$(P "${NEW_HOME}")"; then
      report block "${NEW_HOME} already exists: usermod -m would refuse"
    fi
    if group_exists "${NEW_USER}"; then
      report block "group ${NEW_USER} already exists"
    fi
  elif user_exists "${NEW_USER}"; then
    report ok "account ${NEW_USER} exists (uid $(passwd_field "${NEW_USER}" 3)), ${OLD_USER} is gone: the accounts phase is done"
  else
    report block "neither ${OLD_USER} nor ${NEW_USER} exists"
  fi
  who="$(service_user)"
  if [[ -n "${who}" ]]; then
    uid="$(passwd_field "${who}" 3)"
    if exists_path "$(P "/var/lib/systemd/linger/${who}")"; then
      report ok "linger enabled for ${who}"
    else
      report todo "linger is not enabled for ${who}"
    fi
    lines="$(busy_lines user "${uid}")"
    if [[ -n "${lines}" ]]; then
      report todo "processes of uid ${uid} or its subuid range run: $(printf '%s\n' "${lines}" | wc -l | tr -d '[:space:]')"
    else
      report ok "no process of uid ${uid} or its subuid range runs"
    fi
  fi
  if user_exists "${DEPLOY_USER}"; then
    report ok "deploy account home: $(passwd_field "${DEPLOY_USER}" 6)"
  else
    report block "account ${DEPLOY_USER} does not exist"
  fi
}

check_units() {
  local f unit n=0 dir name
  shopt -s nullglob
  for f in "$(P /etc/systemd/system)"/iri-*.timer "$(P /etc/systemd/system)"/iri-*.service; do
    unit="${f##*/}"
    n=$(( n + 1 ))
    if unit_busy "${unit}"; then
      report todo "old unit ${unit} is active"
    elif unit_enabled "${unit}"; then
      report todo "old unit ${unit} is enabled"
    else
      report info "old unit ${unit} is present, not active, not enabled"
    fi
  done
  shopt -u nullglob
  if (( n == 0 )); then
    report info "no old systemd units found"
  fi
  if [[ -f "$(P /etc/systemd/system/basetool-deploy.service)" ]]; then
    report ok "the basetool-* units are installed (the cleanup phase may run)"
  else
    report todo "the basetool-* units are not installed yet (the cleanup phase needs the Ansible role first)"
  fi
  if [[ -n "$(service_user)" ]]; then
    dir="$(P "$(unit_dir_for)")"
    if [[ -d "${dir}" ]]; then
      report info "Quadlet units in $(unit_dir_for): $(find "${dir}" -maxdepth 1 -name '*.container' | wc -l | tr -d '[:space:]') container, $(find "${dir}" -maxdepth 1 -name '*.network' | wc -l | tr -d '[:space:]') network, $(find "${dir}" -maxdepth 1 -name '*.volume' | wc -l | tr -d '[:space:]') volume unit(s)"
      for name in "${VOLUMES[@]}"; do
        if [[ -e "${dir}/${name}.volume" ]]; then
          report ok "volume unit present: ${name}.volume"
        else
          report block "volume unit missing: ${name}.volume"
        fi
      done
    else
      report block "unit directory $(unit_dir_for) does not exist"
    fi
  fi
}

report_unrecognised() {
  local dir="$1" label="$2" f name known k names=""
  shift 2
  shopt -s nullglob dotglob
  for f in "$(P "${dir}")"/*; do
    name="${f##*/}"
    known=0
    for k in "$@"; do
      if [[ "${name}" == "${k}" ]]; then
        known=1
      fi
    done
    if (( ! known )); then
      names+=" ${name}"
    fi
  done
  shopt -u nullglob dotglob
  if [[ -n "${names}" ]]; then
    report info "not recognised in the ${label} ${dir} (it moves as a whole, so these stay with it; check that no setting points at them):${names}"
  fi
}

check_plan() {
  local row from to req state
  build_plan
  for row in "${PLAN[@]}"; do
    IFS='|' read -r from to req <<< "${row}"
    state="$(row_state "${from}" "${to}" "${req}")"
    case "${state}" in
      todo | todo-replace) report todo "move ${from} -> ${to}" ;;
      done) report ok "moved ${from} -> ${to}" ;;
      absent-opt) report info "optional, absent: ${from}" ;;
      absent-req) report block "required, absent on both sides: ${from}" ;;
      conflict) report block "both exist: ${from} and ${to}" ;;
    esac
  done
  if [[ -f "$(P /var/lib/iri)/config-apply.incomplete" || -f "$(P "${NEW_DEPLOY_HOME}")/config-apply.incomplete" ]]; then
    report block "an earlier config apply did not complete (config-apply.incomplete): finish or undo it with the old deploy.sh first"
  fi
  report_unrecognised /var/lib/iri "deploy state directory" \
    current-digest-pin.yml previous-digest-pin.yml last-deployed.digests failed.digests reapply-failed.digests \
    config-stage config-previous config-blocked.marker config-apply.incomplete monitoring-reload edge \
    keycloak-spi-stage.jar keycloak-spi-previous.jar health-restart.digests mixed-release.since monitoring-apply.log \
    .docker .sigstore .cache .config .containers .local .ansible hardening restic-cache textfile
  report_unrecognised /etc/iri "deploy credential directory" ghcr-pull-token ghcr-pull-token-expiry backup.env rclone.conf ssg-tailoring.xml
  report info "plan: stop -> export-volumes -> reset-podman -> accounts -> move (${#PLAN[@]} path rows, ${#FCONTEXT_RULES[@]} label rules added, ${#OLD_FCONTEXT_RULES[@]} removed) -> rewrite -> [Ansible role] -> cleanup -> import-volumes"
}

check_env() {
  local f out rc realm_export
  if [[ ! -f "${ENV_HELPER}" ]]; then
    report block "env helper missing: ${ENV_HELPER}"
    return 0
  fi
  for f in "$(P /var/iri/code)/.env" "$(P /etc/iri)/backup.env" "$(P /etc/basetool)/basetool.env" \
           "$(P /etc/basetool/deploy)/backup.env"; do
    if [[ -f "${f}" ]]; then
      rc=0
      out="$("${PYTHON}" -I "${ENV_HELPER}" env --check "${f}")" || rc=$?
      printf '%s\n' "${out}" | sed 's/^/[info] /'
      if (( rc != 0 )); then
        report block "the env rewrite of ${f#"${ROOT}"} would refuse: see above"
      fi
    fi
  done
  shopt -s nullglob
  for f in "$(P /var/iri/code)"/.env.backup-* "$(P /etc/basetool)"/basetool.env.backup-*; do
    if [[ -f "${f}" ]]; then
      rc=0
      out="$("${PYTHON}" -I "${ENV_HELPER}" env --check "${f}")" || rc=$?
      printf '%s\n' "${out}" | sed 's/^/[info] /'
      if (( rc != 0 )); then
        report info "the backup ${f#"${ROOT}"} cannot be rewritten: it stays as it is and blocks nothing"
      fi
    fi
  done
  shopt -u nullglob
  realm_export="$(P /var/iri/code)/realm-export.json"
  if [[ ! -f "${realm_export}" ]]; then
    realm_export="$(P /etc/basetool)/realm-export.json"
  fi
  if [[ -f "${realm_export}" ]]; then
    rc=0
    out="$("${PYTHON}" -I "${ENV_HELPER}" realm-export --check "${realm_export}")" || rc=$?
    printf '%s\n' "${out}" | sed 's/^/[info] /'
    if (( rc != 0 )); then
      report block "the realm export cannot be rewritten: see above"
    fi
  else
    report block "realm-export.json not found: Keycloak would import a second realm if it carried the old name"
  fi
}

check_labels() {
  local row
  if ! command -v semanage > /dev/null 2>&1; then
    return 0
  fi
  for row in "${OLD_FCONTEXT_RULES[@]}"; do
    if fcontext_listed "${row%% *}"; then
      report todo "old label rule present: ${row%% *}"
    else
      report info "old label rule absent: ${row%% *}"
    fi
  done
  for row in "${FCONTEXT_RULES[@]}"; do
    if fcontext_has "${row%% *}" "${row##* }"; then
      report ok "label rule present: ${row%% *} ${row##* }"
    else
      report todo "label rule to add: ${row%% *} ${row##* }"
    fi
  done
}

check_stage() {
  local free name
  if [[ -d "${STAGE}" ]]; then
    report info "staging directory exists: ${STAGE}"
    for name in "${VOLUMES[@]}"; do
      if volume_export_ok "${name}"; then
        report ok "volume export verified: ${name}"
      else
        report todo "volume export missing or not verified: ${name}"
      fi
    done
    if stage_marked reset.done; then
      report ok "podman reset done"
    else
      report todo "podman reset not done"
    fi
  else
    report todo "staging directory ${STAGE} will be created (0700)"
  fi
  free="$(df -Pk "$(dirname "${STAGE}")" 2> /dev/null | awk 'NR == 2 { print int($4 / 1024) }')"
  report info "free space for the staging directory: ${free:-unknown} MiB"
}

check_residue() {
  local dir files f
  for dir in "${RESIDUE_DIRS[@]}"; do
    if [[ -e "$(P "${dir}")" ]]; then
      files="$(grep -rlIE '(^|[^A-Za-z])iri([^a-z]|$)|IRI_' "$(P "${dir}")" 2> /dev/null || true)"
      if [[ -n "${files}" ]]; then
        while IFS= read -r f; do
          report info "mentions the old layout (the Ansible role rewrites it): ${f#"${ROOT}"}"
        done <<< "${files}"
      fi
    fi
  done
}

unit_state() {
  if unit_busy "$1"; then
    printf 'active'
  else
    printf 'inactive'
  fi
}

check_window() {
  local who uid f unit active=0 enabled=0 names="" procs
  who="$(service_user)"
  [[ -n "${who}" ]] || return 0
  uid="$(passwd_field "${who}" 3)"
  shopt -s nullglob
  for f in "$(P /etc/systemd/system)"/iri-*.timer "$(P /etc/systemd/system)"/iri-*.service; do
    unit="${f##*/}"
    if unit_busy "${unit}"; then
      active=$(( active + 1 ))
      names+=" ${unit}"
    fi
    if unit_enabled "${unit}"; then
      enabled=$(( enabled + 1 ))
    fi
  done
  shopt -u nullglob
  procs="$(busy_lines containers "${uid}" | wc -l | tr -d '[:space:]')"
  report info "window: ${active} old unit(s) active${names:+ (${names# })}, ${enabled} enabled; stop will disable them and wait for running jobs"
  report info "window: alloy.service $(unit_state alloy.service), dnf-automatic.timer $(unit_state dnf-automatic.timer), user@${uid}.service $(unit_state "user@${uid}.service"); stop will stop all three"
  report info "window: ${procs} process(es) in the subuid range now; none may remain when export-volumes starts"
}

check_filesystems() {
  local pair old new od nd
  for pair in "/var/iri /opt" "/var/iri /etc" "/var/iri /var/lib" "/var/iri /var/log" \
              "/var/lib/iri /var/lib" "/var/lib/iri /var/cache" "/etc/iri /etc"; do
    old="${pair% *}"
    new="${pair#* }"
    if [[ -e "$(P "${old}")" ]]; then
      od="$(stat -c %d "$(P "${old}")")"
      nd="$(stat -c %d "$(P "$(nearest_existing "${new}")")")"
      if [[ "${od}" != "${nd}" ]]; then
        report block "${old} and ${new} are on different filesystems: mv would copy instead of rename"
      fi
    fi
  done
}

nearest_existing() {
  local path="$1"
  while [[ "${path}" != / && ! -e "$(P "${path}")" ]]; do
    path="$(dirname "${path}")"
  done
  printf '%s' "${path}"
}

check_service_owned() {
  local who uid dir names
  who="$(service_user)"
  [[ -n "${who}" ]] || return 0
  uid="$(passwd_field "${who}" 3)"
  dir="$(P /var/lib/iri)"
  if [[ ! -d "${dir}" ]]; then
    dir="$(P "${NEW_DEPLOY_HOME}")"
  fi
  if [[ -d "${dir}" ]]; then
    names="$(find "${dir}" -mindepth 1 -maxdepth 1 -uid "${uid}" -printf '%f ')"
    if [[ -n "${names}" ]]; then
      report info "entries of the deploy state directory owned by the service uid (they move with it, nothing is deleted): ${names}"
    fi
  fi
}

dropin_plan() {
  local dir base type name file
  shopt -s nullglob
  for dir in "$(P /etc/systemd/system)"/iri-*.service.d "$(P /etc/systemd/system)"/iri-*.timer.d; do
    base="${dir##*/}"
    for file in "${dir}"/*; do
      if [[ -f "${file}" ]]; then
        name="${file##*/}"
        case "${name}" in
          10-deploy-account-sandbox.conf | 20-service-user.conf) type=managed ;;
          *) type=carry ;;
        esac
        printf '%s|%s|%s\n' "${type}" "${file}" "$(P /etc/systemd/system)/basetool-${base#iri-}/${name}"
      fi
    done
  done
  shopt -u nullglob
}

check_dropins() {
  local type src dest out rc
  while IFS='|' read -r type src dest; do
    if [[ "${type}" == managed ]]; then
      report info "role-managed drop-in, not carried over: ${src#"${ROOT}"}"
    else
      rc=0
      out="$("${PYTHON}" -I "${ENV_HELPER}" unit --check "${src}")" || rc=$?
      printf '%s\n' "${out}" | sed 's/^/[info] /'
      if (( rc != 0 )); then
        report block "the drop-in ${src#"${ROOT}"} cannot be carried over: see above"
      else
        report todo "drop-in carried over: ${src#"${ROOT}"} -> ${dest#"${ROOT}"}"
      fi
    fi
  done < <(dropin_plan)
}

carry_dropins() {
  local type src dest work bad=0
  local -a srcs=() dests=()
  while IFS='|' read -r type src dest; do
    if [[ "${type}" == carry ]]; then
      srcs+=("${src}")
      dests+=("${dest}")
    fi
  done < <(dropin_plan)
  local i
  for i in "${!srcs[@]}"; do
    run_helper unit 1 "${srcs[i]}" || bad=1
  done
  if (( bad )); then
    refuse "a hand-made drop-in cannot be carried over as it is (see above); nothing was changed"
  fi
  if (( DRY )); then
    for i in "${!srcs[@]}"; do
      note "would carry over ${srcs[i]#"${ROOT}"} -> ${dests[i]#"${ROOT}"}"
    done
    return 0
  fi
  for i in "${!srcs[@]}"; do
    work="${STAGE}/dropin-work/${srcs[i]#"${ROOT}/etc/systemd/system/"}"
    if [[ ! -e "${work}" ]]; then
      private_dir "$(dirname "${work}")"
      act cp -p "${srcs[i]}" "${work}"
      run_helper unit 0 "${work}"
    fi
    if exists_path "${dests[i]}"; then
      cmp -s "${work}" "${dests[i]}" || refuse "${dests[i]#"${ROOT}"} exists with other content than the rewritten ${srcs[i]#"${ROOT}"}"
    else
      if [[ ! -d "$(dirname "${dests[i]}")" ]]; then
        act mkdir "$(dirname "${dests[i]}")"
        act chmod 0755 "$(dirname "${dests[i]}")"
        act chown root:root "$(dirname "${dests[i]}")"
      fi
      act cp -p "${work}" "${dests[i]}"
    fi
  done
  if (( ${#srcs[@]} > 0 )); then
    restore_labels /etc/systemd/system
  fi
}

phase_check() {
  say "check: read-only report (nothing is written)"
  if (( EUID_VALUE != 0 )); then
    report block "not root: the files that matter are not all readable, so this report cannot be trusted"
  fi
  check_tools
  check_accounts
  check_units
  check_window
  check_plan
  check_filesystems
  check_service_owned
  check_dropins
  check_env
  check_env_modes
  check_labels
  check_stage
  check_residue
  report info "Keycloak: the realm rename is a separate step (scripts/rename-keycloak-realm.py); this report does not look at it"
}

main() {
  local phase="" out
  cd /
  while (( $# > 0 )); do
    case "$1" in
      --dry-run) DRY=1 ;;
      -h | --help)
        usage
        exit 0
        ;;
      -*)
        usage >&2
        exit 2
        ;;
      *)
        if [[ -n "${phase}" ]]; then
          usage >&2
          exit 2
        fi
        phase="$1"
        ;;
    esac
    shift
  done
  if [[ -z "${phase}" ]]; then
    usage >&2
    exit 2
  fi
  case "${phase}" in
    check)
      DRY=1
      out="$(phase_check)"
      printf '%s\n' "${out}"
      if grep -q '^\[block\]' <<< "${out}"; then
        exit 3
      fi
      ;;
    stop) need_root; phase_stop ;;
    export-volumes) need_root; phase_export_volumes ;;
    reset-podman) need_root; phase_reset_podman ;;
    accounts) need_root; phase_accounts ;;
    move) need_root; phase_move ;;
    rewrite) need_root; phase_rewrite ;;
    cleanup) need_root; phase_cleanup ;;
    import-volumes) need_root; phase_import_volumes ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
