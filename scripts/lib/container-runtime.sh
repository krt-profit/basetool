# shellcheck shell=bash

RT_BACKEND="${RT_BACKEND:-}"

RT_CLI="${RT_CLI:-}"

RT_SYSTEMCTL="${RT_SYSTEMCTL:-}"

RT_UNIT_DIR="${RT_UNIT_DIR:-}"

RT_LINGER_DIR="${RT_LINGER_DIR:-/var/lib/systemd/linger}"

RT_RUNTIME_BASE="${RT_RUNTIME_BASE:-/run/user}"

RT_RUNTIME_WAIT="${RT_RUNTIME_WAIT:-120}"
RT_STARTUP_WAIT="${RT_STARTUP_WAIT:-600}"
RT_POLL_INTERVAL="${RT_POLL_INTERVAL:-2}"

RT_RUNTIME_WAITED=0
RT_RUNTIME_SEEN=0
RT_PROBE_ERROR=""

RT_HOST_SERVICES="${RT_HOST_SERVICES:-alloy}"

RT_HOST_SYSTEMCTL="${RT_HOST_SYSTEMCTL:-}"

RT_CHANGED_SERVICES="${RT_CHANGED_SERVICES:-}"

rt_die() {
  if declare -F fail >/dev/null 2>&1; then
    fail "$*"
  fi
  printf 'container-runtime: %s\n' "$*" >&2
  exit 1
}

rt_detect() {
  if [[ -n "${RT_BACKEND}" ]]; then
    case "${RT_BACKEND}" in
      podman) RT_CLI="${RT_CLI:-podman}"; RT_SYSTEMCTL="${RT_SYSTEMCTL:-systemctl --user}" ;;
      *) rt_die "RT_BACKEND=${RT_BACKEND} is not podman -- the Docker runtime was retired on 2026-09-22" ;;
    esac
    return 0
  fi

  if command -v podman >/dev/null 2>&1; then
    RT_BACKEND=podman
    local me
    me="$(id -un 2>/dev/null || true)"
    if [[ -n "${me}" && -e "${RT_LINGER_DIR}/${me}" ]] \
       && podman ps --format '{{.Names}}' >/dev/null 2>&1; then
      RT_CLI=podman
      RT_SYSTEMCTL="systemctl --user"
      RT_HOST_SYSTEMCTL="${RT_HOST_SYSTEMCTL:-systemctl}"
      RT_UNIT_DIR="${RT_UNIT_DIR:-${HOME}/.config/containers/systemd}"
      return 0
    fi
    local lingerfile u uid
    for lingerfile in "${RT_LINGER_DIR}"/*; do
      [[ -e "${lingerfile}" ]] || continue
      u="$(basename "${lingerfile}")"
      uid="$(id -u "${u}" 2>/dev/null)" || continue
      if rt_probe_service_user "${u}" "${uid}"; then
        RT_CLI="sudo -n -u ${u} podman"
        RT_SYSTEMCTL="sudo -n -u ${u} XDG_RUNTIME_DIR=${RT_RUNTIME_BASE}/${uid} systemctl --user"
        RT_HOST_SYSTEMCTL="${RT_HOST_SYSTEMCTL:-sudo -n systemctl}"
        RT_UNIT_DIR="${RT_UNIT_DIR:-/etc/containers/systemd/users/${uid}}"
        return 0
      fi
    done
    local why=""
    (( RT_RUNTIME_WAITED > 0 )) && why+=", after waiting ${RT_RUNTIME_WAITED}s for the service user's runtime under ${RT_RUNTIME_BASE} to come up"
    if (( RT_RUNTIME_WAITED > 0 && RT_RUNTIME_SEEN == 0 )); then
      why+="; it never became visible to this process -- a unit that starts before user@<uid>.service never sees it (the 20-service-user.conf ordering drop-in, ansible/roles/basetool_host/tasks/25-scripts.yml)"
    fi
    [[ -n "${RT_PROBE_ERROR}" ]] && why+="; podman said: ${RT_PROBE_ERROR}"
    rt_die "podman is installed but no lingering user could be found that owns the containers (looked in ${RT_LINGER_DIR}${why})"
  fi

  rt_die "this host has no podman"
}

rt_runtime_visible() {
  local dir="${RT_RUNTIME_BASE}/$1" owner
  [[ -d "${dir}" ]] || return 1
  owner="$(stat -c '%u' "${dir}" 2>/dev/null)" || return 1
  [[ "${owner}" == "$1" ]]
}

rt_probe_service_user() {
  local u="$1" uid="$2" waited=0 err state announced=""
  RT_PROBE_ERROR=""
  while :; do
    if rt_runtime_visible "${uid}"; then
      RT_RUNTIME_SEEN=1
      if err="$(sudo -n -u "${u}" podman ps --format '{{.Names}}' 2>&1 >/dev/null)"; then
        (( waited > 0 )) && echo "container-runtime: ${u}'s runtime answered after ${waited}s" >&2
        return 0
      fi
      err="${err%$'\n'}"
      RT_PROBE_ERROR="${err##*$'\n'}"
      state="$(sudo -n -u "${u}" XDG_RUNTIME_DIR="${RT_RUNTIME_BASE}/${uid}" systemctl --user is-system-running 2>/dev/null)" || true
      case "${state}" in
        initializing|starting|"") ;;
        *) return 1 ;;
      esac
    fi
    (( waited >= RT_RUNTIME_WAIT )) && return 1
    if [[ -z "${announced}" ]]; then
      announced=1
      if (( RT_RUNTIME_SEEN )); then
        echo "container-runtime: ${u}'s manager is '${state:-not answering}' and its runtime refuses yet --" \
             "waiting up to ${RT_RUNTIME_WAIT}s for its first container" >&2
      else
        echo "container-runtime: ${u} lingers but its runtime ${RT_RUNTIME_BASE}/${uid} is not up yet --" \
             "waiting up to ${RT_RUNTIME_WAIT}s for its manager to start" >&2
      fi
    fi
    sleep "${RT_POLL_INTERVAL}"
    waited=$(( waited + RT_POLL_INTERVAL ))
    RT_RUNTIME_WAITED=${waited}
  done
}

rt_wait_for_startup() {
  local waited=0 state
  while :; do
    state="$(${RT_SYSTEMCTL} is-system-running 2>/dev/null)" || true
    case "${state}" in
      running|degraded)
        (( waited > 0 )) && echo "container-runtime: the service user's manager is ${state} after ${waited}s" >&2
        return 0 ;;
      initializing|starting|"") ;;
      *) rt_die "the service user's manager reports '${state}' -- refusing to start a maintenance job now" ;;
    esac
    if (( waited >= RT_STARTUP_WAIT )); then
      rt_die "the service user's manager was still '${state:-not answering}' after ${RT_STARTUP_WAIT}s -- refusing to run against a stack that has not finished starting"
    fi
    (( waited == 0 )) && echo "container-runtime: the service user's manager is '${state:-not answering}' -- waiting up to ${RT_STARTUP_WAIT}s for its startup to finish" >&2
    sleep "${RT_POLL_INTERVAL}"
    waited=$(( waited + RT_POLL_INTERVAL ))
  done
}

rt_resolve_digest() {
  local ref="$1" out
  [[ -n "${RT_BACKEND}" ]] || rt_die "rt_resolve_digest before rt_detect"
  command -v skopeo >/dev/null 2>&1 || rt_die "skopeo is not installed; it is how a tag is resolved without pulling"
  out="$(skopeo inspect --no-tags "docker://${ref}" 2>/dev/null)" || return 1
  printf '%s' "${out}" | sed -n 's/.*"Digest"[[:space:]]*:[[:space:]]*"\(sha256:[a-f0-9]\{64\}\)".*/\1/p' | head -1
}

rt_login() {
  local registry="$1" user="$2" pwfile="$3"
  ${RT_CLI} login "${registry}" --username "${user}" --password-stdin < "${pwfile}" || return 1

  podman login "${registry}" --username "${user}" --password-stdin < "${pwfile}"
}

rt_service_container_ids() {
  local svc="$1"
  ${RT_CLI} ps -aq --filter "label=PODMAN_SYSTEMD_UNIT=${svc}.service" 2>/dev/null || true
}

rt_container_probe() {
  local cid="$1"
  ${RT_CLI} inspect \
    --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' \
    "${cid}" 2>/dev/null || printf 'gone'
}

rt_container_image_id() {
  ${RT_CLI} inspect --format '{{.Image}}' "$1" 2>/dev/null || true
}

rt_image_repo_digests() {
  ${RT_CLI} image inspect --format '{{join .RepoDigests " "}}' "$1" 2>/dev/null || true
}

rt_apply() {
  ${RT_SYSTEMCTL} daemon-reload || return 1
  local svc rc=0
  for svc in "$@"; do
    ${RT_SYSTEMCTL} start "${svc}.service" || rc=1
  done
  return "${rc}"
}

rt_pull() {
  local pair rc=0
  for pair in "$@"; do
    ${RT_CLI} pull --quiet "${pair#*=}" >/dev/null 2>&1 || rc=1
  done
  return "${rc}"
}

rt_apply_stack() {
  ${RT_SYSTEMCTL} daemon-reload || return 1
  local svc rc=0
  if [[ $# -eq 0 ]]; then
    local -a svcs=()
    read -ra svcs <<< "${RT_STACK_SERVICES:?RT_STACK_SERVICES is unset and no services were named}"
    set -- "${svcs[@]}"
  fi
  for svc in "$@"; do
    case " ${RT_CHANGED_SERVICES} " in
      *" ${svc} "*) ${RT_SYSTEMCTL} restart "${svc}.service" || rc=1 ;;
      *)            ${RT_SYSTEMCTL} start   "${svc}.service" || rc=1 ;;
    esac
  done
  return "${rc}"
}

rt_recreate() {
  ${RT_SYSTEMCTL} daemon-reload || return 1
  ${RT_SYSTEMCTL} restart "$1.service"
}

rt_await_stack() {
  local svc rc=0
  if [[ $# -eq 0 ]]; then
    local -a svcs=()
    read -ra svcs <<< "${RT_STACK_SERVICES:?RT_STACK_SERVICES is unset and no services were named}"
    set -- "${svcs[@]}"
  fi
  for svc in "$@"; do
    ${RT_SYSTEMCTL} start "${svc}.service" || rc=1
  done
  return "${rc}"
}

rt_restart() {
  ${RT_SYSTEMCTL} restart "$1.service"
}

rt_exec() {
  local c="$1"; shift
  ${RT_CLI} exec "${c}" "$@"
}

rt_extract_from_image() {
  local ref="$1" src="$2" dst="$3" cmd="${4:-}" cid rc=0
  if [[ -n "${cmd}" ]]; then
    cid="$(${RT_CLI} create "${ref}" "${cmd}" 2>/dev/null)" || return 1
  else
    cid="$(${RT_CLI} create "${ref}" 2>/dev/null)" || return 1
  fi
  if [[ "${dst}" == */ || -d "${dst}" ]]; then
    ${RT_CLI} cp "${cid}:${src}" - 2>/dev/null | tar -xf - -C "${dst%/}" || rc=1
  else
    ${RT_CLI} cp "${cid}:${src}" - 2>/dev/null | tar -xOf - > "${dst}" || rc=1
  fi
  ${RT_CLI} rm -f "${cid}" >/dev/null 2>&1 || true
  return "${rc}"
}

rt_pin_path() {
  printf '%s/%s.container.d/10-digest-pin.conf' "${RT_UNIT_DIR:?RT_UNIT_DIR is unset}" "$1"
}

rt_pin_write() {
  local svc="$1" ref="$2" path body
  path="$(rt_pin_path "${svc}")"
  mkdir -p "$(dirname "${path}")"
  body="$(printf '# Written by deploy.sh. The digest this release pinned; do not edit.\n[Container]\nImage=%s\n' "${ref}")"
  if [[ ! -f "${path}" ]] || [[ "$(cat "${path}")" != "${body}" ]]; then
    printf '%s\n' "${body}" > "${path}"
    rt_note_changed "${svc}"
  fi
}

rt_note_changed() {
  local svc="$1"
  case " ${RT_CHANGED_SERVICES} " in
    *" ${svc} "*) return 0 ;;
  esac
  RT_CHANGED_SERVICES="${RT_CHANGED_SERVICES}${RT_CHANGED_SERVICES:+ }${svc}"
}

rt_forget_changed() {
  local svc="$1" out="" s
  for s in ${RT_CHANGED_SERVICES}; do
    [[ "${s}" == "${svc}" ]] && continue
    out="${out}${out:+ }${s}"
  done
  RT_CHANGED_SERVICES="${out}"
}

rt_pin_clear() {
  rm -f "$(rt_pin_path "$1")"
}

rt_pin_record_pairs() {
  awk '
    /^  [a-z][a-z0-9-]*:[[:space:]]*$/ { svc = $1; sub(/:$/, "", svc); next }
    /^    image:[[:space:]]/          { if (svc != "") print svc "=" $2 }
  ' "$1" 2>/dev/null
}

rt_pin_apply() {
  local pair svc ref
  {
    printf '# Auto-generated by scripts/deploy.sh. Do not edit by hand -- it is rewritten\n'
    printf '# on every deploy. Pinning to the exact image digests makes a subsequent\n'
    printf '# tag flip in the registry a no-op until the next deploy.sh run.\n'
    printf 'services:\n'
    for pair in "$@"; do
      printf '  %s:\n    image: %s\n' "${pair%%=*}" "${pair#*=}"
    done
  } > "${RT_PIN_FILE:?RT_PIN_FILE is unset}"

  for pair in "$@"; do
    svc="${pair%%=*}"; ref="${pair#*=}"
    rt_pin_write "${svc}" "${ref}"
  done
}

rt_pin_save() {
  [[ -f "${RT_PIN_FILE:?RT_PIN_FILE is unset}" ]] || return 0
  cp "${RT_PIN_FILE}" "${RT_PIN_FILE_PREVIOUS:?RT_PIN_FILE_PREVIOUS is unset}"
}

rt_pin_rollback() {
  [[ -f "${RT_PIN_FILE_PREVIOUS:?RT_PIN_FILE_PREVIOUS is unset}" ]] || return 1
  cp "${RT_PIN_FILE_PREVIOUS}" "${RT_PIN_FILE:?RT_PIN_FILE is unset}"

  local pair
  while IFS= read -r pair; do
    [[ -n "${pair}" ]] || continue
    rt_pin_write "${pair%%=*}" "${pair#*=}"
  done < <(rt_pin_record_pairs "${RT_PIN_FILE}")
}

rt_monitoring_services() {
  if [[ -n "${RT_MONITORING_SERVICES:-}" ]]; then
    local -a listed=()
    read -ra listed <<< "${RT_MONITORING_SERVICES}"
    printf '%s\n' "${listed[@]}"
    return 0
  fi
  local f name
  for f in "${RT_UNIT_DIR:-}"/*.container; do
    [[ -f "${f}" ]] || continue
    name="$(basename "${f}" .container)"
    case " ${RT_STACK_SERVICES:-} " in
      *" ${name} "*) continue ;;
    esac
    printf '%s\n' "${name}"
  done
}

rt_monitoring_configured() {
  [[ -n "$(rt_monitoring_services)" ]]
}

rt_monitoring_up() {
  ${RT_SYSTEMCTL} daemon-reload || return 1
  local svc rc=0
  if [[ $# -eq 0 ]]; then
    local -a msvcs=()
    mapfile -t msvcs < <(rt_monitoring_services)
    set -- "${msvcs[@]}"
  fi
  for svc in "$@"; do
    case " ${RT_CHANGED_SERVICES} " in
      *" ${svc} "*)
        if ${RT_SYSTEMCTL} restart "${svc}.service"; then
          rt_forget_changed "${svc}"
        else
          rc=1
        fi
        ;;
      *) ${RT_SYSTEMCTL} start "${svc}.service" || rc=1 ;;
    esac
  done
  return "${rc}"
}

rt_monitoring_is_running() {
  local svc
  while IFS= read -r svc; do
    [[ -n "${svc}" ]] || continue
    rt_is_running "${svc}" && return 0
  done < <(rt_monitoring_services)
  return 1
}

rt_monitoring_recreate() {
  case " ${RT_HOST_SERVICES} " in
    *" $1 "*) ${RT_HOST_SYSTEMCTL:-systemctl} restart "$1.service" ;;
    *)        ${RT_SYSTEMCTL} restart "$1.service" ;;
  esac
}

rt_read_mount() {
  local src="$1" image="$2"
  shift 2
  ${RT_CLI} run --rm -v "${src}:/src:ro" "${image}" "$@"
}

rt_volume_exists() {
  ${RT_CLI} volume inspect "$1" >/dev/null 2>&1
}

rt_unit_image() {
  local svc="$1" fallback="${2:-}" dir file ref
  for dir in "${RT_UNIT_DIR:-}" "${fallback}"; do
    [[ -n "${dir}" ]] || continue
    file="${dir}/${svc}.container"
    [[ -r "${file}" ]] || continue
    ref="$(sed -n 's/^Image=\([^[:space:]]\{1,\}\)[[:space:]]*$/\1/p' "${file}" | tail -n 1)"
    if [[ -n "${ref}" ]]; then
      printf '%s\n' "${ref}"
      return 0
    fi
  done
  return 1
}

rt_prometheus_snapshot() {
  # shellcheck disable=SC2016
  rt_exec prometheus sh -c \
    'p="$(cat /etc/prometheus/secrets/web_password)" && a="$(printf "grafana:%s" "${p}" | base64 -w0)" && wget -q -O- --post-data="" --header="Authorization: Basic ${a}" http://127.0.0.1:9090/api/v1/admin/tsdb/snapshot'
}

rt_prometheus_snapshot_remove() {
  local name="$1"
  [[ "${name}" =~ ^[0-9A-Za-z-]+$ ]] || return 1
  rt_exec prometheus rm -rf "/prometheus/snapshots/${name}"
}

rt_rm_force() {
  ${RT_CLI} rm -f -v "$1" >/dev/null 2>&1 || true
}

rt_run_detached() {
  local name="$1" image="$2"
  shift 2
  ${RT_CLI} run -d --name "${name}" "$@" "${image}" >/dev/null
}

rt_cp_to() {
  local src="$1" ctr="$2" dst="$3" want got
  # shellcheck disable=SC2016
  ${RT_CLI} exec -i "${ctr}" sh -c 'cat > "$1"' sh "${dst}" < "${src}" || return 1
  want="$(wc -c < "${src}" | tr -d '[:space:]')"
  got="$(${RT_CLI} exec "${ctr}" stat -c %s "${dst}" 2>/dev/null | tr -d '[:space:]')"
  if [[ "${want}" != "${got}" ]]; then
    echo "rt_cp_to: ${dst} is ${got:-0} bytes in ${ctr}, expected ${want}" >&2
    return 1
  fi
}

rt_service_stop() {
  local svc rc=0
  for svc in "$@"; do ${RT_SYSTEMCTL} stop "${svc}.service" || rc=1; done
  return "${rc}"
}

rt_service_start() {
  local svc rc=0
  for svc in "$@"; do ${RT_SYSTEMCTL} start "${svc}.service" || rc=1; done
  return "${rc}"
}

rt_prune_images() {
  ${RT_CLI} image prune --force --filter "until=$1" >/dev/null 2>&1 || true
}

rt_prune_networks() {
  ${RT_CLI} network prune -f >/dev/null 2>&1 || true
}

rt_prune() {
  rt_prune_images 720h
  rt_prune_networks
}

rt_is_running() {
  ${RT_CLI} ps --format '{{.Names}}' 2>/dev/null | grep -qx -- "$1"
}
