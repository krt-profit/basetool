#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="${SCRIPT_DIR}/deploy.sh"

if [[ ! -f "${DEPLOY}" ]]; then
  echo "FATAL: script under test not found at ${DEPLOY}" >&2
  exit 1
fi

tests_run=0
tests_failed=0

hexdig() {
  local tag="$1" pad=""
  while (( ${#tag} + ${#pad} < 64 )); do pad="${pad}0"; done
  printf 'sha256:%s%s' "${tag}" "${pad}"
}

DIG_BACKEND="$(hexdig beef)"
DIG_FRONTEND="$(hexdig face)"
DIG_INGEST="$(hexdig 1ce)"
DIG_CONFIG="$(hexdig c0ffee)"
DIG_KCSPI="$(hexdig 5b1)"
DIG_CONFIG_NEXT="$(hexdig c0ff1e)"
MARKER="${DIG_BACKEND}|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"

REPO_BACKEND="ghcr.io/krt-profit/basetool-backend@${DIG_BACKEND}"
REPO_FRONTEND="ghcr.io/krt-profit/basetool-frontend@${DIG_FRONTEND}"
REPO_INGEST="ghcr.io/krt-profit/basetool-ingest@${DIG_INGEST}"

mktmp() {
  mktemp -d "${TMPDIR:-/tmp}/deploy-sh-test.XXXXXX"
}

T_MINIMAL="$(mktemp -d "${TMPDIR:-/tmp}/deploy-sh-minimal.XXXXXX")"
T_MINIMAL_OK=false
if ln -s /dev/null "${T_MINIMAL}/.symprobe" 2>/dev/null && [[ -L "${T_MINIMAL}/.symprobe" ]]; then
  rm -f "${T_MINIMAL}/.symprobe"
  IFS=':' read -ra t_path_dirs <<< "${PATH}"
  for t_dir in "${t_path_dirs[@]}"; do
    [[ -d "${t_dir}" ]] || continue
    for t_cmd in "${t_dir}"/*; do
      [[ -f "${t_cmd}" && -x "${t_cmd}" ]] || continue
      t_name="${t_cmd##*/}"
      [[ "${t_name}" == "skopeo" ]] && continue
      [[ -e "${T_MINIMAL}/${t_name}" ]] && continue
      ln -s "${t_cmd}" "${T_MINIMAL}/${t_name}" 2>/dev/null || true
    done
  done
  if PATH="${T_MINIMAL}" env bash -c 'exit 0' >/dev/null 2>&1; then
    T_MINIMAL_OK=true
  fi
fi
rm -f "${T_MINIMAL}/.symprobe"
trap 'rm -rf "${T_MINIMAL}"' EXIT

setup_host() {
  local tmp="$1"
  T_COMPOSE_DIR="${tmp}/code"
  T_STATE_DIR="${tmp}/state"
  T_FAKE_BIN="${tmp}/bin"
  T_DOCKER_LOG="${tmp}/docker-invocations.log"
  T_TOKEN="${tmp}/ghcr-token"
  T_LOCK="${tmp}/deploy.lock"

  mkdir -p "${T_COMPOSE_DIR}" "${T_STATE_DIR}" "${T_FAKE_BIN}"
  echo "# dummy compose file — never parsed; the units are the deployment" \
    > "${T_COMPOSE_DIR}/docker-compose.yml"
  seed_units "${tmp}"
  printf 'IRI_KEYSTORE_HOST_PATH=%s/keystore.p12\n' "${tmp}" > "${T_COMPOSE_DIR}/.env"
  : > "${tmp}/keystore.p12"
  echo "fake-token" > "${T_TOKEN}"
  : > "${T_DOCKER_LOG}"

  printf '#!/usr/bin/env bash\nexit 0\n' > "${T_FAKE_BIN}/flock"
  chmod +x "${T_FAKE_BIN}/flock"

  cat > "${T_FAKE_BIN}/podman" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'podman %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
printf 'podman-cwd %s\n' "${PWD}" >> "${FAKE_DOCKER_LOG}"

lookup() { local var="$1_$2"; printf '%s' "${!var:-}"; }

case "${1:-}" in
  login) cat > /dev/null; exit 0 ;;
  ps)
    svc=""
    for a in "$@"; do
      case "$a" in
        label=PODMAN_SYSTEMD_UNIT=*) svc="${a#label=PODMAN_SYSTEMD_UNIT=}"; svc="${svc%.service}" ;;
      esac
    done
    if [[ -n "${svc}" ]]; then
      val="$(lookup FAKE_PS "${svc}")"
      if [[ -n "${val}" ]]; then printf '%s\n' "${val}"; fi
    else
      if [[ -n "${FAKE_EDGE_PS:-}" ]]; then printf '%s\n' "${FAKE_EDGE_PS}"; fi
      if [[ -n "${FAKE_MON_PS:-}" ]]; then printf '%s\n' "${FAKE_MON_PS}"; fi
    fi
    exit 0
    ;;
  exec)
    if [[ -n "${FAKE_EDGE_CERT_LINES:-}" ]]; then printf '%s\n' "${FAKE_EDGE_CERT_LINES}"; fi
    exit "${FAKE_EDGE_EXEC_RC:-0}"
    ;;
  inspect)
    cid="${!#}"
    case "$*" in
      *RepoDigests*) printf '%s\n' "$(lookup FAKE_REPODIGESTS "${cid#img-cid-}")" ;;
      *.Image*)      printf 'img-%s\n' "${cid}" ;;
      *)             val="$(lookup FAKE_STATE "${cid#cid-}")"; printf '%s\n' "${val:-running/healthy}" ;;
    esac
    exit 0
    ;;
  image)
    img="${!#}"
    printf '%s\n' "$(lookup FAKE_REPODIGESTS "${img#img-cid-}")"
    exit 0
    ;;
  create) echo "created-cid"; exit 0 ;;
  cp)
    if [[ -n "${FAKE_KCSPI_JAR:-}" && "${2:-}" == *:/providers/keycloak-spi.jar && "${3:-}" == "-" ]]; then
      jar_dir="$(mktemp -d)"
      printf '%s\n' "${FAKE_KCSPI_JAR}" > "${jar_dir}/keycloak-spi.jar"
      tar -cf - -C "${jar_dir}" keycloak-spi.jar 2>/dev/null
      rm -rf "${jar_dir}"
      exit 0
    fi
    if [[ -n "${FAKE_CONFIG_BUNDLE:-}" && "${3:-}" == "-" ]]; then
      tar -cf - -C "${FAKE_CONFIG_BUNDLE}" . 2>/dev/null
    fi
    exit 0
    ;;
  rm) exit 0 ;;
  pull)
    ref=""
    for a in "$@"; do
      case "$a" in
        pull | --quiet | -q) ;;
        *) ref="$a" ;;
      esac
    done
    case "${ref}" in
      */*@sha256:*) ;;
      *)
        echo "Error: invalid reference \"${ref}\": a bare name is not a pullable image reference" >&2
        exit 125
        ;;
    esac
    exit "${FAKE_PULL_RC:-0}"
    ;;
  *)      exit 0 ;;
esac
FAKE
  chmod +x "${T_FAKE_BIN}/podman"

  cat > "${T_FAKE_BIN}/skopeo" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'skopeo %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
case "$*" in
  *basetool-backend:*)      d="${FAKE_REMOTE_BACKEND}" ;;
  *basetool-frontend:*)     d="${FAKE_REMOTE_FRONTEND}" ;;
  *basetool-ingest:*)       d="${FAKE_REMOTE_INGEST}" ;;
  *basetool-config:*)       d="${FAKE_REMOTE_CONFIG}" ;;
  *basetool-keycloak-spi:*) d="${FAKE_REMOTE_KCSPI}" ;;
  *) exit 1 ;;
esac
printf '{"Digest":"%s"}\n' "$d"
FAKE
  chmod +x "${T_FAKE_BIN}/skopeo"

  cat > "${T_FAKE_BIN}/systemctl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'systemctl %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
case "$*" in
  *daemon-reload*) exit 0 ;;
  *start*|*restart*|*stop*)
    if [[ -n "${FAKE_UNIT_DOWN_FILE:-}" ]]; then
      down="${FAKE_UNIT_DOWN_FILE}" pending="${FAKE_UNIT_DOWN_FILE}.pending" starts="${FAKE_UNIT_DOWN_FILE}.starts"
      live_jar="${IRI_COMPOSE_DIR:-}/keycloak/providers/keycloak-spi.jar"
      touch "${down}" "${pending}" "${starts}"
      verb="" units=()
      for a in "$@"; do
        case "$a" in
          start | restart | stop) verb="$a" ;;
          *.service) units+=("${a%.service}") ;;
        esac
      done
      is_down() { grep -qx "$1" "${down}"; }
      mark_down() { is_down "$1" || echo "$1" >> "${down}"; }
      mark_up() { grep -vx "$1" "${down}" > "${down}.next" || true; mv "${down}.next" "${down}"; }
      is_stuck_itself() {
        [[ " ${FAKE_STUCK_UNITS:-} " == *" $1 "* ]] || return 1
        if [[ -n "${FAKE_STUCK_WITH_JAR:-}" && "$(head -n 1 "${live_jar}" 2>/dev/null)" == "${FAKE_STUCK_WITH_JAR}" ]]; then
          return 0
        fi
        [[ "${FAKE_STUCK_AFTER_KEYCLOAK_RESTART:-}" == "true" && -f "${down}.keycloak-restarted" ]]
      }
      is_stuck() {
        is_stuck_itself "$1" && return 0
        local req req_var
        for req in db-keycloak db-backend redis keycloak backend; do
          req_var="FAKE_REQUIRED_BY_${req//-/_}"
          if [[ " ${!req_var:-} " == *" $1 "* ]] && is_stuck_itself "${req}"; then
            return 0
          fi
        done
        return 1
      }
      container_start() {
        echo "$1" >> "${starts}"
        if [[ "$1" == "keycloak" ]]; then
          printf 'keycloak-jar-at-start %s\n' "$(head -n 1 "${live_jar}" 2>/dev/null || echo '<none>')" >> "${FAKE_DOCKER_LOG}"
        fi
        mark_up "$1"
      }
      while IFS= read -r queued; do
        [[ -n "${queued}" ]] || continue
        is_stuck "${queued}" || container_start "${queued}"
      done < "${pending}"
      : > "${pending}"
      case "${verb}" in
        stop)
          for unit in "${units[@]}"; do
            [[ "${unit}" == "keycloak" ]] && touch "${down}.keycloak-restarted"
            deps_var="FAKE_REQUIRED_BY_${unit//-/_}"
            for dep in "${unit}" ${!deps_var:-}; do mark_down "${dep}"; done
          done
          exit 0
          ;;
        restart)
          unit="${units[0]}"
          [[ "${unit}" == "keycloak" ]] && touch "${down}.keycloak-restarted"
          deps_var="FAKE_REQUIRED_BY_${unit//-/_}"
          for dep in ${!deps_var:-}; do
            if ! is_down "${dep}"; then mark_down "${dep}"; echo "${dep}" >> "${pending}"; fi
          done
          if is_stuck "${unit}"; then
            mark_down "${unit}"
            echo "Job for ${unit}.service failed because the control process exited with error code." >&2
            exit 1
          fi
          container_start "${unit}"
          ;;
        start)
          unit="${units[0]}"
          if is_stuck "${unit}"; then
            mark_down "${unit}"
            echo "Job for ${unit}.service failed because the control process exited with error code." >&2
            exit 1
          fi
          if is_down "${unit}"; then container_start "${unit}"; fi
          ;;
      esac
    fi
    [[ "$*" == *stop* ]] && exit 0
    if [[ -n "${FAKE_UNHEALTHY_DIGEST:-}" && -n "${RT_UNIT_DIR:-}" ]]; then
      unit=""
      for a in "$@"; do
        case "$a" in *.service) unit="${a%.service}" ;; esac
      done
      pin="${RT_UNIT_DIR}/${unit}.container.d/10-digest-pin.conf"
      if [[ -n "${unit}" && -f "${pin}" ]] && grep -q "${FAKE_UNHEALTHY_DIGEST}" "${pin}"; then
        echo "Job for ${unit}.service failed: start operation timed out" >&2
        exit 1
      fi
    fi
    exit "${FAKE_UP_RC:-0}"
    ;;
esac
exit 0
FAKE
  chmod +x "${T_FAKE_BIN}/systemctl"

  printf '#!/usr/bin/env bash\nexit 0\n' > "${T_FAKE_BIN}/quadlet"
  chmod +x "${T_FAKE_BIN}/quadlet"

  cat > "${T_FAKE_BIN}/cosign" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'cosign %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
if [[ -n "${FAKE_COSIGN_FAIL_TIMES:-}" ]]; then
  counter="${FAKE_DOCKER_LOG}.attempts.$(printf '%s' "${2:-ref}" | tr -c 'A-Za-z0-9' '_')"
  n=0
  [[ -f "${counter}" ]] && n="$(cat "${counter}")"
  n=$(( n + 1 ))
  printf '%s' "${n}" > "${counter}"
  if (( n <= FAKE_COSIGN_FAIL_TIMES )); then
    echo "Error: fetching signature: TRANSIENT registry error (attempt ${n})" >&2
    exit 1
  fi
fi
if [[ "${FAKE_COSIGN_RC:-0}" != "0" ]]; then
  echo "Error: no matching signatures" >&2
fi
if [[ -n "${FAKE_COSIGN_SUBJECT:-}" ]]; then
  identity_re=""
  prev=""
  for arg in "$@"; do
    if [[ "${prev}" == "--certificate-identity-regexp" ]]; then
      identity_re="${arg}"
    fi
    prev="${arg}"
  done
  if [[ -z "${identity_re}" ]] || ! [[ "${FAKE_COSIGN_SUBJECT}" =~ ${identity_re} ]]; then
    echo "Error: none of the expected identities matched what was in the certificate, got subjects [${FAKE_COSIGN_SUBJECT}]" >&2
    exit 1
  fi
fi
exit "${FAKE_COSIGN_RC:-0}"
FAKE
  chmod +x "${T_FAKE_BIN}/cosign"

  if ! install -d -m 0700 "${tmp}/.permprobe" 2>/dev/null; then
    cat > "${T_FAKE_BIN}/install" <<'SHIM'
#!/usr/bin/env bash
set -euo pipefail
args=()
skip=false
for a in "$@"; do
  if [[ "${skip}" == "true" ]]; then
    skip=false
    continue
  fi
  case "$a" in
    -m) skip=true ;;
    *) args+=("$a") ;;
  esac
done
exec /usr/bin/install "${args[@]}"
SHIM
    chmod +x "${T_FAKE_BIN}/install"
  fi
  rm -rf "${tmp}/.permprobe"
}

seed_units() {
  T_UNIT_DIR="${1}/units"
  mkdir -p "${T_UNIT_DIR}"
  local svc
  for svc in backend frontend ingest; do
    printf '[Container]\nContainerName=%s\nImage=placeholder\n' "${svc}" > "${T_UNIT_DIR}/${svc}.container"
  done
  printf 'Volume=%s/keystore.p12:/run/secrets/keystore.p12:ro\n' "$1" >> "${T_UNIT_DIR}/backend.container"
}

seed_monitoring_units() {
  local svc
  for svc in prometheus loki blackbox-exporter; do
    printf '[Container]\nContainerName=%s\nImage=placeholder\n' "${svc}" > "${T_UNIT_DIR}/${svc}.container"
  done
}

APPLIED="systemctl --user start db-backend.service"

assert_no_apply() {
  local desc="$1"
  if ! grep -qE 'systemctl (--user )?(start|restart) ' "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (a unit was started or restarted)"
  fi
}

assert_no_monitoring_recreate() {
  local desc="$1"
  if ! grep -qE 'restart (prometheus|blackbox-exporter|alloy)\.service' "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (a monitoring component was recreated)"
  fi
}

write_marker() {
  echo "$1" > "${T_STATE_DIR}/last-deployed.digests"
}

run_deploy() {
  local -a script_args=() extra_env=()
  local seen_sep=false arg rc=0
  for arg in "$@"; do
    if [[ "${arg}" == "--" ]]; then
      seen_sep=true
      continue
    fi
    if [[ "${seen_sep}" == "true" ]]; then
      extra_env+=("${arg}")
    else
      script_args+=("${arg}")
    fi
  done
  local run_path="${T_FAKE_BIN}:${PATH}"
  if [[ "${RUN_DEPLOY_MINIMAL_PATH:-0}" == "1" ]]; then
    run_path="${T_FAKE_BIN}:${T_MINIMAL}"
  fi
  LAST_OUTPUT="$(
    env \
      PATH="${run_path}" \
      IRI_COMPOSE_DIR="${T_COMPOSE_DIR}" \
      IRI_STATE_DIR="${T_STATE_DIR}" \
      IRI_MONITORING_TEXTFILE_DIR="${T_STATE_DIR}/textfile" \
      IRI_LOCKFILE="${T_LOCK}" \
      IRI_GHCR_TOKEN_FILE="${T_TOKEN}" \
      FAKE_DOCKER_LOG="${T_DOCKER_LOG}" \
      FAKE_REMOTE_BACKEND="${DIG_BACKEND}" \
      FAKE_REMOTE_FRONTEND="${DIG_FRONTEND}" \
      FAKE_REMOTE_INGEST="${DIG_INGEST}" \
      FAKE_REMOTE_CONFIG="${DIG_CONFIG}" \
      FAKE_REMOTE_KCSPI="${DIG_KCSPI}" \
      RT_BACKEND=podman \
      IRI_QUADLET_BIN="${T_FAKE_BIN}/quadlet" \
      RT_UNIT_DIR="${T_UNIT_DIR}" \
      "${extra_env[@]}" \
      bash "${DEPLOY}" "${script_args[@]}" 2>&1
  )" || rc=$?
  return "${rc}"
}

converged_env() {
  printf '%s\n' \
    "FAKE_PS_backend=cid-backend" \
    "FAKE_PS_frontend=cid-frontend" \
    "FAKE_PS_ingest=cid-ingest" \
    "FAKE_REPODIGESTS_backend=${REPO_BACKEND}" \
    "FAKE_REPODIGESTS_frontend=${REPO_FRONTEND}" \
    "FAKE_REPODIGESTS_ingest=${REPO_INGEST}"
}

record() {
  local ok="$1" desc="$2"
  tests_run=$((tests_run + 1))
  if [[ "$ok" -eq 1 ]]; then
    echo "  ok   - ${desc}"
  else
    tests_failed=$((tests_failed + 1))
    echo "  FAIL - ${desc}"
    echo "----- deploy.sh output -----"
    echo "${LAST_OUTPUT}"
    echo "----- recorded invocations -----"
    cat "${T_DOCKER_LOG}" 2>/dev/null || true
    echo "-------------------------------"
  fi
}

assert_exit() {
  local expected="$1" actual="$2" desc="$3"
  if [[ "$actual" -eq "$expected" ]]; then
    record 1 "${desc} (exit ${expected})"
  else
    record 0 "${desc} (expected exit ${expected}, got ${actual})"
  fi
}

assert_contains() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" == *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output missing: '${needle}')"
  fi
}

assert_excludes() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" != *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output unexpectedly contained: '${needle}')"
  fi
}

assert_docker() {
  local needle="$1" desc="$2"
  if grep -qF -- "$needle" "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (no docker invocation matching: '${needle}')"
  fi
}

assert_no_docker() {
  local needle="$1" desc="$2"
  if ! grep -qF -- "$needle" "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (unexpected docker invocation matching: '${needle}')"
  fi
}

scenario_converged_noop() {
  echo "Scenario: marker matches, stack converged (must fast-exit)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "converged stack exits 0"
  assert_contains "no change" "the no-op is reported"
  assert_contains "(running stack verified)" "the fast exit states the stack was verified"
  assert_no_docker "podman pull" "nothing is pulled"
  assert_no_apply "nothing is restarted"
  assert_no_docker "cosign verify" "no signature verification on the steady-state no-op"
  rm -rf "${tmp}"
}

scenario_stale_image_drift() {
  echo "Scenario: marker matches, backend runs an outdated image (must re-apply)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "FAKE_REPODIGESTS_backend=ghcr.io/krt-profit/basetool-backend@sha256:backend-stale" || rc=$?
  assert_exit 0 "$rc" "drift re-apply succeeds"
  assert_contains "drift: backend: running image" "the stale backend image is reported as drift"
  assert_contains "re-applying" "the run falls through to a re-apply"
  assert_contains "deploy successful" "the re-apply completes"
  assert_docker "${APPLIED}" "the stack is re-applied through its units"
  if [[ ! -f "${T_STATE_DIR}/failed.digests" ]]; then
    record 1 "no failure is recorded for a successful re-apply"
  else
    record 0 "no failure is recorded for a successful re-apply (failed.digests exists)"
  fi
  rm -rf "${tmp}"
}

scenario_unhealthy_drift() {
  echo "Scenario: marker matches, backend unhealthy at target image (targeted restart, no rollback)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=restarting/unhealthy" || rc=$?
  assert_exit 0 "$rc" "a resolved targeted restart exits 0"
  assert_contains "drift: backend: container state restarting/unhealthy" \
    "the unhealthy state is reported as drift"
  assert_contains "targeted restart (not a release rollback)" \
    "the run takes the runtime-health path, not a release rollback"
  assert_docker "systemctl --user stop backend.service" "only the affected service is recreated (stopped...)"
  assert_docker "systemctl --user start backend.service" "...and started again"
  assert_no_docker "systemctl --user restart" "...and nothing is restarted"
  assert_contains "health drift resolved" "the targeted restart is reported resolved"
  assert_excludes "re-applying" "the full re-apply path is NOT taken for a health-only drift"
  assert_excludes "rolling back" "no release rollback happens"
  assert_no_docker "cosign verify" "a targeted restart does not re-verify signatures"
  assert_no_docker "podman pull" "a targeted restart does not re-pull images"
  if [[ ! -f "${T_STATE_DIR}/textfile/deploy.prom" ]] \
     || ! grep -q 'basetool_deploy_last_rollback_timestamp [1-9]' \
            "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "no false DeployRolledBack metric is written for a runtime-health blip"
  else
    record 0 "no false DeployRolledBack metric is written for a runtime-health blip"
  fi
  rm -rf "${tmp}"
}

scenario_health_drift_restart_fails() {
  echo "Scenario: unhealthy at-target backend whose targeted restart fails (health-restart signal, not a rollback)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=restarting/unhealthy" "FAKE_UP_RC=1" || rc=$?
  assert_exit 1 "$rc" "a failed targeted restart exits non-zero"
  assert_contains "did NOT restore health (attempt #1)" "the failed restart is recorded"
  assert_excludes "rolling back" "a runtime-health fault is never a release rollback"
  assert_excludes "deploy successful" "it is not reported as a successful deploy"
  if grep -q 'basetool_deploy_last_health_restart_failed_timestamp [1-9]' \
       "${T_STATE_DIR}/textfile/deploy-health.prom" 2>/dev/null; then
    record 1 "the health-restart-failed gauge is written"
  else
    record 0 "the health-restart-failed gauge is written"
  fi
  if [[ ! -f "${T_STATE_DIR}/textfile/deploy.prom" ]] \
     || ! grep -q 'basetool_deploy_last_rollback_timestamp [1-9]' \
            "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "no DeployRolledBack metric is written for a runtime-health fault"
  else
    record 0 "no DeployRolledBack metric is written for a runtime-health fault"
  fi
  if grep -qF "${MARKER} 1 " "${T_STATE_DIR}/health-restart.digests" 2>/dev/null; then
    record 1 "health-restart.digests records the target with count 1"
  else
    record 0 "health-restart.digests records the target with count 1"
  fi
  rm -rf "${tmp}"
}

scenario_health_drift_respects_backoff() {
  echo "Scenario: unhealthy at-target backend, targeted restart in backoff window (must skip)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  printf '%s 1 %d\n' "${MARKER}" "$(date +%s)" > "${T_STATE_DIR}/health-restart.digests"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=restarting/unhealthy" || rc=$?
  assert_exit 1 "$rc" "a backed-off health-drift tick exits non-zero"
  assert_contains "in backoff" "the targeted restart is throttled by its backoff"
  assert_no_apply "nothing is restarted during the health-restart backoff window"
  rm -rf "${tmp}"
}

scenario_mixed_drift_is_structural() {
  echo "Scenario: health drift on one service + structural drift on another → full re-apply"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "FAKE_STATE_backend=restarting/unhealthy" \
    "FAKE_REPODIGESTS_ingest=ghcr.io/krt-profit/basetool-ingest@sha256:ingest-stale" || rc=$?
  assert_exit 0 "$rc" "the mixed-drift re-apply succeeds"
  assert_contains "re-applying" "a structural divergence forces the full re-apply path"
  assert_contains "deploy successful" "the re-apply completes"
  assert_docker "cosign verify" "the full re-apply verifies signatures"
  assert_excludes "targeted restart (not a release rollback)" \
    "the targeted-restart path is not taken when any drift is structural"
  rm -rf "${tmp}"
}

scenario_missing_container_drift() {
  echo "Scenario: marker matches, ingest container missing (must re-apply)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_ingest=" || rc=$?
  assert_exit 0 "$rc" "half-down-stack re-apply succeeds"
  assert_contains "drift: ingest: no container" "the missing container is reported as drift"
  assert_excludes "drift: backend" "the healthy backend is not flagged"
  assert_docker "${APPLIED}" "the stack is re-applied"
  rm -rf "${tmp}"
}

scenario_new_promotion() {
  echo "Scenario: marker differs — normal promotion deploy (no drift lines)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "promotion deploy succeeds"
  assert_excludes "drift:" "no drift lines on the normal promotion path"
  assert_contains "deploy successful" "the promotion is applied"
  assert_docker "${APPLIED}" "the stack is applied"
  rm -rf "${tmp}"
}

scenario_check_only_drift() {
  echo "Scenario: --check-only over a drifted stack (must not apply)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy --check-only -- "${fake[@]}" "FAKE_PS_backend=" || rc=$?
  assert_exit 0 "$rc" "check-only exits 0"
  assert_contains "check-only: would re-apply" "check-only reports the pending drift re-apply"
  assert_docker "cosign verify" "check-only runs the signature preflight"
  assert_contains "all signatures verified OK" "check-only reports the signatures verified"
  assert_no_docker "podman pull" "check-only pulls nothing"
  assert_no_apply "check-only restarts nothing"
  rm -rf "${tmp}"
}

scenario_drift_respects_backoff() {
  echo "Scenario: drifted stack, target in backoff window (must skip)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  printf '%s 1 %d\n' "${MARKER}" "$(date +%s)" > "${T_STATE_DIR}/reapply-failed.digests"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_backend=" || rc=$?
  assert_exit 0 "$rc" "backed-off drift tick exits 0"
  assert_contains "drift: backend: no container" "the drift is still reported"
  assert_contains "in backoff window" "the re-apply is throttled by the backoff"
  assert_no_apply "nothing is restarted during the backoff window"
  rm -rf "${tmp}"
}

scenario_drift_reapply_fails() {
  echo "Scenario: drift re-apply fails health gate (must record failure)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  echo "services: {}" > "${T_STATE_DIR}/current-digest-pin.yml"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_backend=" "FAKE_UP_RC=1" || rc=$?
  assert_exit 1 "$rc" "a failed drift re-apply exits non-zero"
  assert_contains "health check failed" "the health-gate failure is reported"
  assert_contains "recorded re-apply failure #1" "the failure feeds the backoff"
  if grep -qF "${MARKER} 1 " "${T_STATE_DIR}/reapply-failed.digests" 2>/dev/null; then
    record 1 "reapply-failed.digests records the target marker with count 1"
  else
    record 0 "reapply-failed.digests records the target marker with count 1"
  fi
  if [[ ! -f "${T_STATE_DIR}/failed.digests" ]]; then
    record 1 "failed.digests, the release backoff, is not written by a re-apply"
  else
    record 0 "failed.digests, the release backoff, is not written by a re-apply ($(cat "${T_STATE_DIR}/failed.digests"))"
  fi
  rm -rf "${tmp}"
}

scenario_starting_grace() {
  echo "Scenario: container inside healthcheck start period (fast-exit; stale image still drifts)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=running/starting" || rc=$?
  assert_exit 0 "$rc" "start-period container counts as converged"
  assert_contains "(running stack verified)" "the tick fast-exits during the start window"
  assert_no_apply "no re-apply races the start-up"
  rm -rf "${tmp}"

  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  rc=0
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=running/starting" \
    "FAKE_REPODIGESTS_backend=ghcr.io/krt-profit/basetool-backend@sha256:backend-stale" || rc=$?
  assert_exit 0 "$rc" "stale image in start period still re-applies"
  assert_contains "drift: backend: running image" "the stale image is reported despite the start period"
  assert_docker "${APPLIED}" "the stack is re-applied"
  rm -rf "${tmp}"
}

scenario_monitoring_config_reload() {
  echo "Scenario: config change touches only prometheus/ → recreate prometheus, not alloy/blackbox"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  seed_monitoring_units
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  echo "scrape_interval: 30s" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}/monitoring/prometheus" \
    "${bundle}/monitoring/alloy" "${bundle}/monitoring/blackbox"
  echo "# dummy compose file" > "${bundle}/docker-compose.yml"
  echo "# dummy monitoring compose" > "${bundle}/docker-compose.monitoring.yml"
  echo "scrape_interval: 15s  # bumped" > "${bundle}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${bundle}/monitoring/alloy/config.alloy"
  echo "same" > "${bundle}/monitoring/blackbox/blackbox.yml"
  mkdir -p "${T_STATE_DIR}/monitoring-reload"
  cp -R "${T_COMPOSE_DIR}/monitoring/prometheus" "${T_STATE_DIR}/monitoring-reload/prometheus"
  cp -R "${T_COMPOSE_DIR}/monitoring/alloy" "${T_STATE_DIR}/monitoring-reload/alloy"
  cp -R "${T_COMPOSE_DIR}/monitoring/blackbox" "${T_STATE_DIR}/monitoring-reload/blackbox-exporter"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "IRI_MONITORING_ENABLED=true" \
    "FAKE_CONFIG_BUNDLE=${bundle}" \
    "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_exit 0 "$rc" "config-change deploy with a monitoring recreate succeeds"
  assert_docker "systemctl --user start loki.service" "the monitoring stack is reconciled"
  assert_docker "systemctl --user restart prometheus.service" "prometheus is recreated (its slice changed)"
  assert_no_docker "restart alloy.service" "alloy is left alone (its slice is unchanged)"
  assert_no_docker "restart blackbox-exporter.service" "blackbox is left alone (its slice is unchanged)"
  rm -rf "${tmp}"
}

scenario_monitoring_reload_no_drift() {
  echo "Scenario: monitoring enabled, config already converged → reconcile but no recreate"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  seed_monitoring_units
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  echo "scrape_interval: 30s" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  mkdir -p "${T_STATE_DIR}/monitoring-reload"
  cp -R "${T_COMPOSE_DIR}/monitoring/prometheus" "${T_STATE_DIR}/monitoring-reload/prometheus"
  cp -R "${T_COMPOSE_DIR}/monitoring/alloy" "${T_STATE_DIR}/monitoring-reload/alloy"
  cp -R "${T_COMPOSE_DIR}/monitoring/blackbox" "${T_STATE_DIR}/monitoring-reload/blackbox-exporter"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "IRI_MONITORING_ENABLED=true" \
    "FAKE_REPODIGESTS_backend=ghcr.io/krt-profit/basetool-backend@sha256:backend-stale" || rc=$?
  assert_exit 0 "$rc" "monitoring-enabled deploy over a converged config succeeds"
  assert_docker "systemctl --user start loki.service" "the monitoring stack is still reconciled"
  assert_no_monitoring_recreate "no service is recreated when on-disk matches the applied snapshot"
  if grep -q '^basetool_monitoring_reconcile_disabled{component="deploy"} 0' \
       "${T_STATE_DIR}/textfile/monitoring-reconcile.prom" 2>/dev/null; then
    record 1 "the reconcile-disabled gauge is 0 when the reconcile is enabled and runs"
  else
    record 0 "the reconcile-disabled gauge is 0 when the reconcile is enabled and runs"
  fi
  rm -rf "${tmp}"
}

scenario_monitoring_reload_self_heals_on_noop() {
  echo "Scenario: converged no-op but Prometheus config drifted → self-healing recreate on the fast exit"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  seed_monitoring_units
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  echo "  - targets: [ingest:11272]" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  mkdir -p "${T_STATE_DIR}/monitoring-reload/prometheus"
  echo "  - targets: [ingest:11262]" \
    > "${T_STATE_DIR}/monitoring-reload/prometheus/prometheus.yml"
  cp -R "${T_COMPOSE_DIR}/monitoring/alloy" "${T_STATE_DIR}/monitoring-reload/alloy"
  cp -R "${T_COMPOSE_DIR}/monitoring/blackbox" "${T_STATE_DIR}/monitoring-reload/blackbox-exporter"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "IRI_MONITORING_ENABLED=true" || rc=$?
  assert_exit 0 "$rc" "the self-healing no-op tick exits 0"
  assert_contains "no change" "it is still the idempotence no-op for the app stack"
  assert_no_docker "podman pull" "the app stack is not pulled on the fast exit"
  assert_no_docker "${APPLIED}" "the app stack is not re-applied on the fast exit"
  assert_docker "systemctl --user start loki.service" "the monitoring unit reconcile also runs on the fast exit"
  assert_docker "systemctl --user restart prometheus.service" "the stale Prometheus config is self-healed (recreated)"
  assert_no_docker "restart alloy.service" "alloy is already converged — left alone"
  assert_no_docker "restart blackbox-exporter.service" "blackbox is already converged — left alone"
  if grep -q '^basetool_monitoring_config_applied_timestamp{component="prometheus"} [1-9]' \
       "${T_STATE_DIR}/textfile/monitoring-config.prom" 2>/dev/null; then
    record 1 "the config-applied timestamp metric is emitted for PrometheusConfigStale"
  else
    record 0 "the config-applied timestamp metric is emitted for PrometheusConfigStale"
  fi
  rm -rf "${tmp}"
}

scenario_monitoring_compose_def_applied_on_noop() {
  echo "Scenario: converged no-op, monitoring enabled, config converged → plain up -d still reconciles compose-def drift"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  seed_monitoring_units
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  echo "scrape_interval: 30s" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  mkdir -p "${T_STATE_DIR}/monitoring-reload"
  cp -R "${T_COMPOSE_DIR}/monitoring/prometheus" "${T_STATE_DIR}/monitoring-reload/prometheus"
  cp -R "${T_COMPOSE_DIR}/monitoring/alloy" "${T_STATE_DIR}/monitoring-reload/alloy"
  cp -R "${T_COMPOSE_DIR}/monitoring/blackbox" "${T_STATE_DIR}/monitoring-reload/blackbox-exporter"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "IRI_MONITORING_ENABLED=true" || rc=$?
  assert_exit 0 "$rc" "the converged no-op tick exits 0"
  assert_contains "no change" "it is still the idempotence no-op for the app stack"
  assert_no_docker "podman pull" "the app stack is not pulled on the fast exit"
  assert_no_docker "${APPLIED}" "the app stack is not re-applied on the fast exit"
  assert_docker "systemctl --user start loki.service" "the monitoring unit reconcile runs on the converged no-op"
  assert_no_monitoring_recreate "no service is recreated when the config subtree is converged"
  rm -rf "${tmp}"
}

scenario_monitoring_reconcile_disabled_when_running() {
  echo "Scenario: monitoring stack running but IRI_MONITORING_ENABLED unset → WARN + reconcile-disabled gauge=1"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  seed_monitoring_units
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_MON_PS=prometheus" || rc=$?
  assert_exit 0 "$rc" "the gated-but-running no-op tick still exits 0"
  assert_contains "no change" "it is still the idempotence no-op"
  assert_contains "iri-monitoring is RUNNING but IRI_MONITORING_ENABLED != 'true'" \
    "the gated-off-but-running condition is logged as a loud WARN"
  assert_no_monitoring_recreate "nothing is recreated while the reconcile is gated off"
  if grep -q '^basetool_monitoring_reconcile_disabled{component="deploy"} 1' \
       "${T_STATE_DIR}/textfile/monitoring-reconcile.prom" 2>/dev/null; then
    record 1 "the reconcile-disabled gauge is 1 (MonitoringReconcileDisabled can fire)"
  else
    record 0 "the reconcile-disabled gauge is 1 (MonitoringReconcileDisabled can fire)"
  fi
  rm -rf "${tmp}"
}

scenario_monitoring_flag_read_from_env_file() {
  echo "Scenario: IRI_MONITORING_ENABLED in the compose .env enables the reconcile"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  printf 'SOME_SECRET=must-not-leak\nIRI_MONITORING_ENABLED="true"\n' >> "${T_COMPOSE_DIR}/.env"
  seed_monitoring_units
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_MON_PS=prometheus" || rc=$?
  assert_exit 0 "$rc" "the tick still exits 0"
  assert_excludes "iri-monitoring is RUNNING but IRI_MONITORING_ENABLED != 'true'" \
    "the gated-off WARN is gone once the flag is read from .env"
  if grep -q '^basetool_monitoring_reconcile_disabled{component="deploy"} 0' \
       "${T_STATE_DIR}/textfile/monitoring-reconcile.prom" 2>/dev/null; then
    record 1 "the reconcile-disabled gauge is 0 (reconcile is armed)"
  else
    record 0 "the reconcile-disabled gauge is 0 (reconcile is armed)"
  fi
  assert_excludes "must-not-leak" "no other .env value is pulled into the deploy environment"
  rm -rf "${tmp}"
}

scenario_signature_verified_on_apply() {
  echo "Scenario: promotion verifies signatures before applying"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "verified promotion succeeds"
  assert_contains "verifying image signatures" "the verification step runs"
  assert_docker "cosign verify" "cosign verify is invoked for the resolved digests"
  assert_contains "backend: signature OK" "the backend signature is reported OK"
  assert_docker "${APPLIED}" "the stack is applied after verification"
  rm -rf "${tmp}"
}

scenario_signature_failure_aborts() {
  echo "Scenario: a failed signature verification aborts before pull/apply"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_COSIGN_RC=1" || rc=$?
  assert_exit 1 "$rc" "an untrusted digest fails the deploy"
  assert_contains "cosign signature verification failed" "the security abort is reported"
  assert_contains "last cosign error: Error: no matching signatures" \
    "the abort quotes cosign's own reason instead of only that it failed"
  assert_no_docker "podman pull" "nothing is pulled from an untrusted target"
  assert_no_apply "the stack is not recreated on an untrusted target"
  if grep -q 'basetool_deploy_last_failure_timestamp [1-9]' \
       "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "a deploy-failure metric is written for the verification failure"
  else
    record 0 "a deploy-failure metric is written for the verification failure"
  fi
  rm -rf "${tmp}"
}

scenario_transient_verify_failure_retries() {
  echo "Scenario: a transient cosign failure is retried, not escalated"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_COSIGN_FAIL_TIMES=1" "IRI_COSIGN_VERIFY_DELAY=0" || rc=$?
  assert_exit 0 "$rc" "a single transient verify failure does not fail the deploy"
  assert_contains "verify attempt 1/3 failed" "the failed attempt is logged"
  assert_contains "TRANSIENT registry error" "cosign's stderr reaches the operator log"
  assert_contains "backend: signature OK" "the retry succeeds and the gate passes"
  assert_docker "${APPLIED}" "the stack is applied after the retry"
  rm -rf "${tmp}"
}

scenario_signature_identity_is_anchored() {
  echo "Scenario: the signer identity regexp accepts main and release tags only"
  local tmp rc ref subject
  local prefix="https://github.com/krt-profit/basetool/.github/workflows/release-images.yml@refs/"
  for ref in heads/main tags/v1.9.2; do
    tmp="$(mktmp)"
    setup_host "${tmp}"
    write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"
    mapfile -t fake < <(converged_env)
    rc=0
    run_deploy -- "${fake[@]}" "FAKE_COSIGN_SUBJECT=${prefix}${ref}" "IRI_COSIGN_VERIFY_DELAY=0" || rc=$?
    assert_exit 0 "$rc" "a signature minted on refs/${ref} is trusted"
    assert_contains "backend: signature OK" "refs/${ref}: the gate reports the signature OK"
    rm -rf "${tmp}"
  done
  for subject in \
    "${prefix}heads/main-x" \
    "${prefix}heads/maintenance" \
    "${prefix}tags/vfoo" \
    "${prefix}tags/v1.9.2-rc1" \
    "https://github.com/krt-profit/basetool/.github/workflows/promote.yml@refs/heads/main"; do
    tmp="$(mktmp)"
    setup_host "${tmp}"
    write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"
    mapfile -t fake < <(converged_env)
    rc=0
    run_deploy -- "${fake[@]}" "FAKE_COSIGN_SUBJECT=${subject}" "IRI_COSIGN_VERIFY_DELAY=0" || rc=$?
    assert_exit 1 "$rc" "a signature minted as ${subject##*/workflows/} is refused"
    assert_contains "none of the expected identities matched" \
      "${subject##*/workflows/}: the abort quotes cosign's identity mismatch"
    assert_no_apply "${subject##*/workflows/}: nothing is applied"
    rm -rf "${tmp}"
  done
}

scenario_break_glass_skips_verify() {
  echo "Scenario: IRI_COSIGN_VERIFY=false skips verification (loudly) and still applies"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "IRI_COSIGN_VERIFY=false" || rc=$?
  assert_exit 0 "$rc" "break-glass deploy succeeds"
  assert_contains "signature verification DISABLED" "the disabled gate is logged loudly"
  assert_no_docker "cosign verify" "cosign is not invoked when the gate is disabled"
  assert_docker "${APPLIED}" "the stack is still applied under break-glass"
  rm -rf "${tmp}"
}

scenario_cosign_off_path() {
  echo "Scenario: cosign is installed but not on PATH (sudo secure_path)"
  local tmp rc=0 alt
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"

  alt="${tmp}/not-on-path"
  mkdir -p "${alt}"
  mv "${T_FAKE_BIN}/cosign" "${alt}/cosign"

  mapfile -t fake < <(converged_env)
  RUN_DEPLOY_MINIMAL_PATH=1 run_deploy -- "${fake[@]}" \
    "IRI_COSIGN_SEARCH_PATH=${alt}" || rc=$?
  assert_exit 0 "$rc" "the deploy succeeds with cosign off PATH but findable"
  assert_contains "but not on PATH" "it says where it found cosign, rather than aborting"
  assert_docker "cosign verify" "and it still verifies every signature"

  : > "${T_DOCKER_LOG}"
  rc=0
  write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"
  RUN_DEPLOY_MINIMAL_PATH=1 run_deploy -- "${fake[@]}" \
    "IRI_COSIGN_SEARCH_PATH=${tmp}/nowhere" || rc=$?
  assert_exit 1 "$rc" "a genuinely missing cosign still fails closed"
  assert_contains "cosign not found on PATH or under" "the abort names where it looked"
  assert_no_docker "cosign verify" "nothing is verified when cosign is absent"

  mv "${alt}/cosign" "${T_FAKE_BIN}/cosign"
  rm -rf "${tmp}"
}

scenario_token_expiry_metric() {
  echo "Scenario: GHCR token-expiry gauge is written even on the no-op tick"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  printf '2026-10-01\n' > "${T_TOKEN}.expiry"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "no-op tick with a token expiry file exits 0"
  assert_contains "no change" "the tick is still the idempotence no-op"
  if grep -q '^basetool_ghcr_token_expiry_timestamp [1-9]' \
       "${T_STATE_DIR}/textfile/ghcr-token.prom" 2>/dev/null; then
    record 1 "the token-expiry gauge is written on the no-op tick"
  else
    record 0 "the token-expiry gauge is written on the no-op tick"
  fi
  rm -rf "${tmp}"
}

scenario_token_expiry_removed_clears_the_gauge() {
  echo "Scenario: removing the expiry file removes the gauge"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"

  printf '2026-10-01\n' > "${T_TOKEN}.expiry"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "the tick that records an expiry exits 0"
  if grep -q '^basetool_ghcr_token_expiry_timestamp [1-9]' \
       "${T_STATE_DIR}/textfile/ghcr-token.prom" 2>/dev/null; then
    record 1 "the gauge exists before the expiry file is removed"
  else
    record 0 "the gauge exists before the expiry file is removed"
  fi

  rm -f "${T_TOKEN}.expiry"
  rc=0
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "the tick after removing the expiry file exits 0"
  if [[ -e "${T_STATE_DIR}/textfile/ghcr-token.prom" ]]; then
    record 0 "the gauge file is gone once no expiry is recorded"
  else
    record 1 "the gauge file is gone once no expiry is recorded"
  fi

  : > "${T_TOKEN}.expiry"
  rc=0
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "the tick with an empty expiry file exits 0"
  if [[ -e "${T_STATE_DIR}/textfile/ghcr-token.prom" ]]; then
    record 0 "an empty expiry file leaves no gauge behind either"
  else
    record 1 "an empty expiry file leaves no gauge behind either"
  fi

  rm -rf "${tmp}"
}

scenario_forced_gated_rollback_keeps_marker() {
  echo "Scenario: a rolled-back --force stateful-infra apply keeps the block marker"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  mkdir -p "${T_COMPOSE_DIR}/quadlet/systemd"
  printf '[Container]\nImage=docker.io/postgres:18-alpine\n' > "${T_COMPOSE_DIR}/quadlet/systemd/db-backend.container"
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}/quadlet/systemd"
  echo "# promoted compose" > "${bundle}/docker-compose.yml"
  printf '[Container]\nImage=docker.io/postgres:19-alpine\n' > "${bundle}/quadlet/systemd/db-backend.container"
  write_marker "${MARKER}"
  echo "${DIG_BACKEND}|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG_NEXT}|${DIG_KCSPI}" \
    > "${T_STATE_DIR}/config-blocked.marker"
  echo "services: {}" > "${T_STATE_DIR}/current-digest-pin.yml"
  mapfile -t fake < <(converged_env)
  run_deploy --force -- "${fake[@]}" \
    "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" "FAKE_UP_RC=1" || rc=$?
  assert_exit 1 "$rc" "the failed forced apply exits non-zero"
  assert_contains "stateful-infra upgrade forced" "the --force path through the gate is taken"
  assert_contains "health check failed" "the apply fails its health gate and rolls back"
  if [[ -f "${T_STATE_DIR}/config-blocked.marker" ]]; then
    record 1 "the block marker survives a rolled-back forced apply"
  else
    record 0 "the block marker survives a rolled-back forced apply"
  fi
  rm -rf "${tmp}"
}

scenario_config_bundle_secret_rejected() {
  echo "Scenario: a config bundle carrying a *.pem is rejected before apply"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}"
  echo "# dummy compose" > "${bundle}/docker-compose.yml"
  echo "-----BEGIN PRIVATE KEY-----" > "${bundle}/leaked.pem"
  write_marker "${MARKER}"
  echo "services: {}" > "${T_STATE_DIR}/current-digest-pin.yml"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_exit 1 "$rc" "a secret-carrying bundle aborts the deploy"
  assert_contains "forbidden secret-shaped file" "the widened secret gate rejects the .pem"
  assert_no_docker "${APPLIED}" "nothing is applied when the bundle carries a secret"
  rm -rf "${tmp}"
}

scenario_converged_noop
scenario_stale_image_drift
scenario_unhealthy_drift
scenario_health_drift_restart_fails
scenario_health_drift_respects_backoff
scenario_mixed_drift_is_structural
scenario_missing_container_drift
scenario_new_promotion
scenario_check_only_drift
scenario_drift_respects_backoff
scenario_drift_reapply_fails
scenario_starting_grace
scenario_monitoring_config_reload
scenario_monitoring_reload_no_drift
scenario_monitoring_reload_self_heals_on_noop
scenario_monitoring_compose_def_applied_on_noop
scenario_monitoring_reconcile_disabled_when_running
scenario_monitoring_flag_read_from_env_file
scenario_signature_verified_on_apply
scenario_signature_failure_aborts
scenario_transient_verify_failure_retries
scenario_signature_identity_is_anchored
scenario_break_glass_skips_verify
scenario_cosign_off_path
scenario_check_only_noop_verifies() {
  echo "Scenario: --check-only over a converged stack still verifies signatures"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy --check-only -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "check-only over a converged stack exits 0"
  assert_contains "check-only: no change" "it reports the no-op"
  assert_docker "cosign verify" "it still runs the signature preflight"
  assert_contains "all signatures verified OK" "the signatures verify"
  assert_no_apply "nothing is applied"
  rm -rf "${tmp}"
}

scenario_config_mirrors_edge() {
  echo "Scenario: every docker/ subtree in the promoted bundle is mirrored onto the host"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}/docker/edge/conf.d" "${bundle}/docker/edge/include"     "${bundle}/docker/acme" "${bundle}/docker/maintenance/static"
  echo "# dummy compose file" > "${bundle}/docker-compose.yml"
  echo "worker_processes auto;" > "${bundle}/docker/edge/nginx.conf"
  echo "# vhost" > "${bundle}/docker/edge/conf.d/10-frontend.conf"
  echo "# include" > "${bundle}/docker/edge/include/proxy.conf"
  echo "#!/bin/sh" > "${bundle}/docker/acme/publish-loop.sh"
  echo "<html></html>" > "${bundle}/docker/maintenance/static/index.html"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}"     "FAKE_CONFIG_BUNDLE=${bundle}"     "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_exit 0 "$rc" "a config-only change applies"
  if [[ -f "${T_COMPOSE_DIR}/docker/edge/nginx.conf" ]]; then
    record 1 "docker/edge/nginx.conf reached the host as a FILE"
  else
    record 0 "docker/edge/nginx.conf did not reach the host (mirror_dir list not extended?)"
  fi
  if [[ -f "${T_COMPOSE_DIR}/docker/edge/conf.d/10-frontend.conf"      && -f "${T_COMPOSE_DIR}/docker/edge/include/proxy.conf" ]]; then
    record 1 "the conf.d and include trees came with it"
  else
    record 0 "conf.d / include were not mirrored"
  fi
  if [[ -f "${T_COMPOSE_DIR}/docker/acme/publish-loop.sh" ]]; then
    record 1 "docker/acme/publish-loop.sh reached the host"
  else
    record 0 "docker/acme was not mirrored (the ACME loop has nothing to run)"
  fi
  if [[ -f "${T_COMPOSE_DIR}/docker/maintenance/static/index.html" ]]; then
    record 1 "docker/maintenance came with it too"
  else
    record 0 "docker/maintenance was not mirrored"
  fi
}

scenario_config_mirror_ignores_caller_umask() {
  echo "Scenario: the mirrored config tree does not depend on the caller's umask"
  local tmp rc=0 saved mode_lax mode_strict
  for saved in 022 027; do
    tmp="$(mktmp)"
    setup_host "${tmp}"
    local bundle="${tmp}/bundle"
    mkdir -p "${bundle}/docker/edge/conf.d"
    echo "# dummy compose file" > "${bundle}/docker-compose.yml"
    echo "worker_processes auto;" > "${bundle}/docker/edge/nginx.conf"
    echo "# vhost" > "${bundle}/docker/edge/conf.d/10-frontend.conf"
    write_marker "${MARKER}"
    mapfile -t fake < <(converged_env)

    local before
    before="$(umask)"
    umask "${saved}"
    rc=0
    run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" \
      "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
    umask "${before}"

    assert_exit 0 "$rc" "a config-only change applies with the caller's umask ${saved}"
    if [[ "${saved}" == "022" ]]; then
      mode_lax="$(stat -c '%a' "${T_COMPOSE_DIR}/docker/edge" 2>/dev/null || echo unknown)"
    else
      mode_strict="$(stat -c '%a' "${T_COMPOSE_DIR}/docker/edge" 2>/dev/null || echo unknown)"
    fi
    rm -rf "${tmp}"
  done

  if [[ "${mode_lax}" == "unknown" || "${mode_strict}" == "unknown" ]]; then
    record 0 "could not read the mirrored directory's mode"
  elif [[ "${mode_lax}" == "${mode_strict}" ]]; then
    record 1 "umask 022 and 027 both produce ${mode_strict} on docker/edge"
  else
    record 0 "the caller's umask leaked into the tree: 022 -> ${mode_lax}, 027 -> ${mode_strict}"
  fi
}

write_infra_units() {
  mkdir -p "$1/quadlet/systemd"
  echo "# dummy compose file" > "$1/docker-compose.yml"
  printf '[Container]\nImage=docker.io/postgres:18-alpine@sha256:%s\n' \
    1111111111111111111111111111111111111111111111111111111111111111 > "$1/quadlet/systemd/db-backend.container"
  printf '[Container]\nImage=quay.io/keycloak/keycloak:%s@sha256:%s\n' "$2" "$3" > "$1/quadlet/systemd/keycloak.container"
}

scenario_infra_digest_refresh_is_not_gated() {
  echo "Scenario: a same-tag digest refresh applies; a tag change is still gated"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}"

  write_infra_units "${T_COMPOSE_DIR}" 26.7 \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  write_infra_units "${bundle}" 26.7 \
    bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" \
    "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_exit 0 "$rc" "a digest-only infra refresh completes"
  assert_excludes "CARVE-OUT" "it is not treated as a stateful upgrade"
  assert_excludes "operator-gated" "and it is not skipped"

  rc=0
  setup_host "${tmp}/two"
  mkdir -p "${tmp}/bundle2"
  write_infra_units "${T_COMPOSE_DIR}" 26.7 \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  write_infra_units "${tmp}/bundle2" 26.8 \
    cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${tmp}/bundle2" \
    "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_contains "CARVE-OUT" "a tag change is still refused"
  assert_no_docker "${APPLIED}" "and nothing is applied"

  rc=0
  setup_host "${tmp}/three"
  write_infra_units "${T_COMPOSE_DIR}" 26.7 \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  write_infra_units "${tmp}/bundle3" 26.7 \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  sed -i 's#postgres:18-alpine#postgres:19-alpine#' "${tmp}/bundle3/quadlet/systemd/db-backend.container"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${tmp}/bundle3" \
    "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_contains "CARVE-OUT" "a qualified postgres major change in the units is refused"
  assert_contains "new: postgres:19-alpine" "...and names the pin without the registry prefix"

  rm -rf "${tmp}"
}

scenario_edge_reloads_a_renewed_certificate() {
  echo "Scenario: a renewed certificate recreates the edge, an unchanged one does not"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  mkdir -p "${T_COMPOSE_DIR}/docker/edge/conf.d"
  echo "worker_processes auto;" > "${T_COMPOSE_DIR}/docker/edge/nginx.conf"
  echo "# vhost" > "${T_COMPOSE_DIR}/docker/edge/conf.d/10-frontend.conf"
  mkdir -p "${T_STATE_DIR}/edge"
  cp -R "${T_COMPOSE_DIR}/docker/edge" "${T_STATE_DIR}/edge/config"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  local seeded="aaaa1111  /etc/nginx/certs/profit-base.online/fullchain.pem"
  local renewed="bbbb2222  /etc/nginx/certs/profit-base.online/fullchain.pem"

  : > "${T_DOCKER_LOG}"
  run_deploy -- "${fake[@]}" "FAKE_EDGE_PS=edge" "FAKE_EDGE_CERT_LINES=${seeded}" || rc=$?
  assert_exit 0 "$rc" "a converged tick with unseen certificates succeeds"
  assert_contains "certificates differs" "the certificate drift is named in the log"
  assert_docker "systemctl --user restart edge.service" "the edge is recreated to load them"
  if [[ -s "${T_STATE_DIR}/edge/certs.sha256" ]]; then
    record 1 "the fingerprint is persisted for the next tick"
  else
    record 0 "no fingerprint was written (the volume was never readable?)"
  fi

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_EDGE_PS=edge" "FAKE_EDGE_CERT_LINES=${seeded}" || rc=$?
  assert_exit 0 "$rc" "an unchanged tick succeeds"
  assert_no_docker "systemctl --user restart edge.service" "an unchanged certificate recreates nothing"

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_EDGE_PS=edge" "FAKE_EDGE_CERT_LINES=${renewed}" || rc=$?
  assert_exit 0 "$rc" "the renewal tick succeeds"
  assert_docker "systemctl --user restart edge.service" "a renewed certificate is loaded"

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_EDGE_PS=edge" "FAKE_EDGE_EXEC_RC=1" || rc=$?
  assert_exit 0 "$rc" "a tick whose certificate read fails still succeeds"
  assert_no_docker "systemctl --user restart edge.service" "an unreadable certificate recreates nothing"

  rm -rf "${tmp}"
}

scenario_check_only_verify_fail() {
  echo "Scenario: --check-only with a bad signature exits non-zero, writes no metric"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy --check-only -- "${fake[@]}" "FAKE_COSIGN_RC=1" || rc=$?
  assert_exit 1 "$rc" "check-only exits non-zero on a failed verification"
  assert_contains "SIGNATURE VERIFICATION FAILED" "the failure is reported"
  assert_no_apply "nothing is applied"
  if [[ ! -f "${T_STATE_DIR}/textfile/deploy.prom" ]] \
     || ! grep -q 'basetool_deploy_last_failure_timestamp [1-9]' \
            "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "no deploy-failure metric is written for a dry-run verification failure"
  else
    record 0 "no deploy-failure metric is written for a dry-run verification failure"
  fi
  rm -rf "${tmp}"
}

scenario_config_mirrors_edge
scenario_config_mirror_ignores_caller_umask
scenario_edge_reloads_a_renewed_certificate
scenario_infra_digest_refresh_is_not_gated
scenario_token_expiry_metric
scenario_token_expiry_removed_clears_the_gauge
scenario_forced_gated_rollback_keeps_marker
scenario_config_bundle_secret_rejected
scenario_check_only_noop_verifies
scenario_check_only_verify_fail

PDIG_BACKEND="${DIG_BACKEND}"
PDIG_FRONTEND="${DIG_FRONTEND}"
PDIG_INGEST="${DIG_INGEST}"
PDIG_CONFIG="${DIG_CONFIG}"
PDIG_KCSPI="${DIG_KCSPI}"
PDIG_BACKEND_NEW="$(hexdig dead)"
PMARKER="${PDIG_BACKEND}|${PDIG_FRONTEND}|${PDIG_INGEST}|${PDIG_CONFIG}|${PDIG_KCSPI}"
PMARKER_OLD="$(hexdig ba5e)|${PDIG_FRONTEND}|${PDIG_INGEST}|${PDIG_CONFIG}|${PDIG_KCSPI}"

podman_env() {
  printf '%s\n' \
    "RT_BACKEND=podman" \
    "IRI_QUADLET_BIN=${T_FAKE_BIN}/quadlet" \
    "RT_UNIT_DIR=${T_UNIT_DIR}" \
    "FAKE_REMOTE_BACKEND=${PDIG_BACKEND}" \
    "FAKE_REMOTE_FRONTEND=${PDIG_FRONTEND}" \
    "FAKE_REMOTE_INGEST=${PDIG_INGEST}" \
    "FAKE_REMOTE_CONFIG=${PDIG_CONFIG}" \
    "FAKE_REMOTE_KCSPI=${PDIG_KCSPI}"
}

podman_converged_env() {
  printf '%s\n' \
    "FAKE_PS_backend=cid-backend" \
    "FAKE_PS_frontend=cid-frontend" \
    "FAKE_PS_ingest=cid-ingest" \
    "FAKE_REPODIGESTS_backend=ghcr.io/krt-profit/basetool-backend@${PDIG_BACKEND}" \
    "FAKE_REPODIGESTS_frontend=ghcr.io/krt-profit/basetool-frontend@${PDIG_FRONTEND}" \
    "FAKE_REPODIGESTS_ingest=ghcr.io/krt-profit/basetool-ingest@${PDIG_INGEST}"
}

podman_units_empty() {
  T_UNIT_DIR="${1}/units"
  rm -rf "${T_UNIT_DIR}"
  mkdir -p "${T_UNIT_DIR}"
}

podman_units() {
  podman_units_empty "$1"
  local svc
  for svc in backend frontend ingest; do
    printf '[Container]
ContainerName=%s
Image=placeholder
' "${svc}"       > "${T_UNIT_DIR}/${svc}.container"
  done
  printf 'Volume=%s/keystore.p12:/run/secrets/keystore.p12:ro\n' "$1" >> "${T_UNIT_DIR}/backend.container"
}

scenario_podman_resolves_without_pulling() {
  echo "Scenario: podman resolves a tag through skopeo, and never pulls to do it"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 0 "$rc" "podman: a converged stack exits 0"
  assert_contains "container runtime: podman" "podman: the runtime is detected as podman"
  assert_docker "skopeo inspect" "podman: the tag is resolved with skopeo"
  assert_no_docker "buildx" "podman: buildx is never reached for a digest"
  assert_no_docker "podman pull" "podman: resolving a tag does not PULL the image"
  assert_no_docker "compose" "podman: compose is never invoked"
  rm -rf "${tmp}"
}

scenario_podman_applies_through_systemd() {
  echo "Scenario: podman applies through systemd, and the unit start IS the health gate"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER_OLD}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 0 "$rc" "podman: a deploy that moves the backend exits 0"
  assert_contains "deploy successful" "podman: the deploy reports success"
  assert_docker "systemctl --user daemon-reload" "podman: the units are re-read before anything starts"
  assert_docker "systemctl --user stop backend.service" "podman: a re-pinned service is stopped, so the new image actually lands"
  assert_docker "systemctl --user start backend.service" "podman: ...and started again"
  assert_docker "systemctl --user start db-backend.service" "podman: ...and an untouched one is only started, so the database stays up"
  assert_no_docker "compose" "podman: compose is never invoked on the apply path"
  if grep -q "${PMARKER}" "${T_STATE_DIR}/last-deployed.digests"; then
    record 1 "podman: the idempotence marker advances to the new target"
  else
    record 0 "podman: the idempotence marker advances to the new target"
  fi
  rm -rf "${tmp}"
}

scenario_podman_pin_is_a_dropin() {
  echo "Scenario: the digest pin is a Quadlet drop-in, not just a record"
  local tmp
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER_OLD}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" >/dev/null 2>&1 || true
  local pin="${T_UNIT_DIR}/backend.container.d/10-digest-pin.conf"
  if [[ -f "${pin}" ]] \
    && grep -q '^\[Container\]$' "${pin}" \
    && grep -q "^Image=.*@${PDIG_BACKEND}\$" "${pin}"; then
    record 1 "podman: the pin is a [Container] drop-in binding Image= by digest"
  else
    record 0 "podman: the pin is a [Container] drop-in binding Image= by digest"
  fi
  if grep -rq "${PDIG_BACKEND}" "${T_STATE_DIR}"/*.yml 2>/dev/null; then
    record 1 "podman: the pin record is written beside the drop-ins"
  else
    record 0 "podman: the pin record is written beside the drop-ins"
  fi
  rm -rf "${tmp}"
}

scenario_podman_health_gate_rolls_back() {
  echo "Scenario: a unit that never reports healthy rolls the release BACK, not forward"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER_OLD}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" >/dev/null 2>&1 || true
  local pin="${T_UNIT_DIR}/backend.container.d/10-digest-pin.conf"

  : > "${T_DOCKER_LOG}"
  run_deploy -- "${pod[@]}" "${conv[@]}" \
    "FAKE_REMOTE_BACKEND=${PDIG_BACKEND_NEW}" \
    "FAKE_UNHEALTHY_DIGEST=${PDIG_BACKEND_NEW}" || rc=$?
  assert_exit 1 "$rc" "podman: a failed health gate exits non-zero"
  assert_contains "rolling back" "podman: the failed release is rolled back"
  assert_contains "rolled back to previous digest pin successfully" \
    "podman: the rollback's own apply reaches health"
  if grep -q "${PDIG_BACKEND_NEW}" "${pin}" 2>/dev/null; then
    record 0 "podman: the drop-in is rebound away from the failed digest"
  elif grep -q "${PDIG_BACKEND}" "${pin}" 2>/dev/null; then
    record 1 "podman: the drop-in is rebound away from the failed digest"
  else
    record 0 "podman: the drop-in is rebound away from the failed digest"
  fi
  rm -rf "${tmp}"
}

scenario_podman_refuses_without_skopeo() {
  echo "Scenario: a podman host without skopeo refuses instead of proceeding"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  rm -f "${T_FAKE_BIN}/skopeo"
  local use_minimal=0 eff_path="${T_FAKE_BIN}:${PATH}"
  if [[ "${T_MINIMAL_OK}" == "true" ]]; then
    use_minimal=1
    eff_path="${T_FAKE_BIN}:${T_MINIMAL}"
  fi
  if PATH="${eff_path}" command -v skopeo >/dev/null 2>&1; then
    record 0 "podman: the scenario's PATH really cannot reach a skopeo"
  else
    record 1 "podman: the scenario's PATH really cannot reach a skopeo"
  fi
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  RUN_DEPLOY_MINIMAL_PATH="${use_minimal}" run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 1 "$rc" "podman: a host without skopeo refuses"
  assert_contains "skopeo not available" "podman: and names the missing tool"
  rm -rf "${tmp}"
}

scenario_podman_refuses_without_quadlet() {
  echo "Scenario: a podman host whose Quadlet generator is missing refuses"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" "IRI_QUADLET_BIN=${tmp}/no-such-quadlet" || rc=$?
  assert_exit 1 "$rc" "podman: a host without the Quadlet generator refuses"
  assert_contains "Quadlet generator is missing" "podman: and says which piece is absent"
  rm -rf "${tmp}"
}

scenario_podman_resolves_without_pulling
scenario_podman_applies_through_systemd
scenario_podman_pin_is_a_dropin
scenario_podman_health_gate_rolls_back
scenario_podman_refuses_without_skopeo
scenario_podman_refuses_without_quadlet

write_bundle() {
  local b="$1"
  mkdir -p "${b}/quadlet/systemd" "${b}/quadlet/env.d"
  echo "# promoted compose" > "${b}/docker-compose.yml"
  printf '[Container]\nContainerName=backend\nImage=placeholder\n'  > "${b}/quadlet/systemd/backend.container"
  printf '[Container]\nContainerName=frontend\nImage=placeholder\n' > "${b}/quadlet/systemd/frontend.container"
  printf '[Network]\nNetworkName=net-app\n'                          > "${b}/quadlet/systemd/net-app.network"
  # shellcheck disable=SC2016
  printf 'BACKEND_TOKEN=${IRI_KEYSTORE_HOST_PATH:?}\n'                > "${b}/quadlet/env.d/backend.env.tmpl"
}

write_env_renderer() {
  cat > "${T_FAKE_BIN}/render-env-d.py" <<'REND'
#!/usr/bin/env bash
set -euo pipefail
printf 'render-env-d %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
out=""
prev=""
for a in "$@"; do
  [[ "${prev}" == "--out" ]] && out="$a"
  prev="$a"
done
[[ -n "${out}" ]] || exit 2
mkdir -p "${out}"
echo "rendered" > "${out}/backend.env"
REND
  chmod +x "${T_FAKE_BIN}/render-env-d.py"
}

scenario_podman_bundle_installs_the_units() {
  echo "Scenario: the config bundle delivers the Quadlet units, which is how a release reaches a Podman host"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_env_renderer
  local bundle="${tmp}/bundle"
  write_bundle "${bundle}"

  printf '[Container]\nContainerName=backend\nImage=OLD\n' > "${T_UNIT_DIR}/backend.container"
  printf '[Container]\nContainerName=retired\n'             > "${T_UNIT_DIR}/retired.container"
  mkdir -p "${T_UNIT_DIR}/backend.container.d"
  echo "# an operator drop-in" > "${T_UNIT_DIR}/backend.container.d/20-host-alias.conf"

  write_marker "${PDIG_BACKEND}|${PDIG_FRONTEND}|${PDIG_INGEST}|$(hexdig 0ldc0)|${PDIG_KCSPI}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" \
    "FAKE_CONFIG_BUNDLE=${bundle}" \
    "IRI_ENV_RENDERER=${T_FAKE_BIN}/render-env-d.py" \
    "IRI_ENV_D_DIR=${T_COMPOSE_DIR}/env.d" || rc=$?
  assert_exit 0 "$rc" "podman: a deploy that ships new units exits 0"

  if grep -q '^Image=placeholder$' "${T_UNIT_DIR}/backend.container" 2>/dev/null; then
    record 1 "podman: the release's unit replaced the stale one on the host"
  else
    record 0 "podman: the release's unit replaced the stale one on the host"
  fi
  if [[ -f "${T_UNIT_DIR}/net-app.network" ]]; then
    record 1 "podman: networks and volumes ride the same path as containers"
  else
    record 0 "podman: networks and volumes ride the same path as containers"
  fi
  if [[ -f "${T_UNIT_DIR}/backend.container.d/20-host-alias.conf" ]]; then
    record 1 "podman: an operator drop-in survives a unit install"
  else
    record 0 "podman: an operator drop-in survives a unit install"
  fi
  if [[ -f "${T_COMPOSE_DIR}/env.d/backend.env" ]]; then
    record 1 "podman: env.d is rendered on the host, from the host's own .env"
  else
    record 0 "podman: env.d is rendered on the host, from the host's own .env"
  fi
  rm -rf "${tmp}"
}

scenario_podman_retired_unit_is_stopped_then_removed() {
  echo "Scenario: a unit the release retires is stopped BEFORE its file is removed"
  local tmp
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_env_renderer
  local bundle="${tmp}/bundle"
  write_bundle "${bundle}"
  printf '[Container]\nContainerName=retired\n' > "${T_UNIT_DIR}/retired.container"
  write_marker "${PDIG_BACKEND}|${PDIG_FRONTEND}|${PDIG_INGEST}|$(hexdig 0ldc0)|${PDIG_KCSPI}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  : > "${T_DOCKER_LOG}"
  run_deploy -- "${pod[@]}" "${conv[@]}" \
    "FAKE_CONFIG_BUNDLE=${bundle}" \
    "IRI_ENV_RENDERER=${T_FAKE_BIN}/render-env-d.py" \
    "IRI_ENV_D_DIR=${T_COMPOSE_DIR}/env.d" >/dev/null 2>&1 || true

  if [[ -f "${T_UNIT_DIR}/retired.container" ]]; then
    record 0 "podman: the retired unit file is removed"
  else
    record 1 "podman: the retired unit file is removed"
  fi
  assert_docker "stop retired.service" "podman: ...and it was stopped first, so no container is orphaned"
  rm -rf "${tmp}"
}

scenario_podman_first_deploy_fills_an_empty_unit_dir() {
  echo "Scenario: the first deploy on a freshly provisioned host installs the units it finds none of"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units_empty "${tmp}"
  write_env_renderer
  local bundle="${tmp}/bundle"
  write_bundle "${bundle}"
  write_marker "${PMARKER}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" \
    "FAKE_CONFIG_BUNDLE=${bundle}" \
    "IRI_ENV_RENDERER=${T_FAKE_BIN}/render-env-d.py" \
    "IRI_ENV_D_DIR=${T_COMPOSE_DIR}/env.d" || rc=$?
  assert_exit 0 "$rc" "podman: a converged host with no units still exits 0"
  if [[ -f "${T_UNIT_DIR}/backend.container" ]]; then
    record 1 "podman: an empty unit directory is filled from the bundle"
  else
    record 0 "podman: an empty unit directory is filled from the bundle"
  fi
  rm -rf "${tmp}"
}

scenario_podman_runs_without_a_compose_file() {
  echo "Scenario: a Quadlet host has no compose file, and the pre-flight must not demand one"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  rm -f "${T_COMPOSE_DIR}/docker-compose.yml"
  printf '[Container]\nContainerName=backend\nImage=x\n' > "${T_UNIT_DIR}/backend.container"
  write_marker "${PMARKER}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 0 "$rc" "podman: a host with no compose file deploys"
  assert_excludes "required file missing" "podman: and the pre-flight does not ask for one"
  rm -rf "${tmp}"
}

scenario_podman_changed_monitoring_and_acme_units_are_restarted() {
  echo "Scenario: a release that changes a monitoring unit or acme restarts them -- once -- and only them"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_env_renderer
  local bundle="${tmp}/bundle"
  write_bundle "${bundle}"
  printf '[Container]\nContainerName=prometheus\nImage=NEW\n' > "${bundle}/quadlet/systemd/prometheus.container"
  printf '[Container]\nContainerName=loki\nImage=same\n'      > "${bundle}/quadlet/systemd/loki.container"
  printf '[Container]\nContainerName=acme\nImage=NEW\n'       > "${bundle}/quadlet/systemd/acme.container"
  printf '[Container]\nContainerName=prometheus\nImage=OLD\n' > "${T_UNIT_DIR}/prometheus.container"
  printf '[Container]\nContainerName=loki\nImage=same\n'      > "${T_UNIT_DIR}/loki.container"
  printf '[Container]\nContainerName=acme\nImage=OLD\n'       > "${T_UNIT_DIR}/acme.container"
  write_marker "${PDIG_BACKEND}|${PDIG_FRONTEND}|${PDIG_INGEST}|$(hexdig 0ldc0)|${PDIG_KCSPI}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  : > "${T_DOCKER_LOG}"
  run_deploy -- "${pod[@]}" "${conv[@]}" \
    "IRI_MONITORING_ENABLED=true" \
    "FAKE_CONFIG_BUNDLE=${bundle}" \
    "IRI_ENV_RENDERER=${T_FAKE_BIN}/render-env-d.py" \
    "IRI_ENV_D_DIR=${T_COMPOSE_DIR}/env.d" || rc=$?
  assert_exit 0 "$rc" "podman: the release deploys"

  local n
  n="$(grep -c '^systemctl --user restart prometheus\.service$' "${T_DOCKER_LOG}" || true)"
  if [[ "${n}" == "1" ]]; then
    record 1 "podman: a changed monitoring unit is restarted, so the new definition lands"
  else
    record 0 "podman: a changed monitoring unit is restarted exactly once (restarted ${n} times)"
  fi
  assert_docker "systemctl --user start loki.service" "podman: ...an unchanged one is only started"
  assert_no_docker "systemctl --user restart loki.service" "podman: ...and never recreated for somebody else's change"
  assert_count '^systemctl --user stop .*acme\.service' 1 "podman: a changed acme unit is recreated (stopped, then started) with the stack"
  assert_docker "systemctl --user start acme.service" "podman: ...and started again"
  rm -rf "${tmp}"
}

scenario_podman_acme_is_part_of_the_stack() {
  echo "Scenario: acme is started with the stack, like every other prod-profile service"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER_OLD}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  : > "${T_DOCKER_LOG}"
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 0 "$rc" "podman: a deploy that moves the backend exits 0"
  assert_docker "systemctl --user start acme.service" "podman: an unchanged acme unit is started, so a stopped one comes back"
  assert_no_docker "systemctl --user restart acme.service" "podman: ...and not recreated when its unit did not move"
  assert_count '^systemctl --user stop .*acme\.service' 0 "podman: ...not stopped either"
  rm -rf "${tmp}"
}

scenario_podman_keystore_checked_where_the_unit_mounts_it() {
  echo "Scenario: under Quadlet the keystore pre-flight reads the unit's Volume=, not .env"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  printf 'IRI_KEYSTORE_HOST_PATH=%s/nowhere.p12\n' "${tmp}" > "${T_COMPOSE_DIR}/.env"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 0 "$rc" "podman: a stale .env keystore path does not block a host whose units mount an existing file"
  assert_excludes "nowhere.p12" "podman: ...and is not what the pre-flight looked at"
  rm -rf "${tmp}"
}

scenario_podman_missing_mounted_keystore_refuses() {
  echo "Scenario: ...and a keystore the units mount but the host lacks refuses, even when .env's exists"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  sed -i "s#^Volume=.*:/run/secrets/keystore.p12:ro\$#Volume=${tmp}/absent.p12:/run/secrets/keystore.p12:ro#" \
    "${T_UNIT_DIR}/backend.container"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 1 "$rc" "podman: a missing mounted keystore refuses before anything is applied"
  assert_contains "required file missing: ${tmp}/absent.p12" "podman: ...and names the path the unit mounts"
  rm -rf "${tmp}"
}

scenario_podman_missing_per_service_keystore_refuses() {
  echo "Scenario: every /run/secrets/*.p12 the units mount is checked, not only the first one"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  printf 'Volume=%s/tls/ingest.p12:/run/secrets/keystore.p12:ro\n' "${tmp}" >> "${T_UNIT_DIR}/ingest.container"
  printf 'Volume=%s/keystore.p12:/run/secrets/internal-truststore.p12:ro\n' "${tmp}" >> "${T_UNIT_DIR}/ingest.container"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 1 "$rc" "podman: a missing per-service keystore refuses before anything is applied"
  assert_contains "required file missing: ${tmp}/tls/ingest.p12" "podman: ...and names that service's file"
  rm -rf "${tmp}"
}

scenario_podman_missing_edge_trust_anchor_refuses() {
  echo "Scenario: ...and so is the edge's Grafana trust anchor, whose absence would keep the edge down"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  printf 'Volume=%s/certs/grafana.crt:/etc/nginx/grafana-upstream.crt:ro\n' "${tmp}" >> "${T_UNIT_DIR}/backend.container"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 1 "$rc" "podman: a missing edge trust anchor refuses before anything is applied"
  assert_contains "required file missing: ${tmp}/certs/grafana.crt" "podman: ...and names the file"
  rm -rf "${tmp}"
}

scenario_podman_runs_podman_from_root_dir() {
  echo "Scenario: podman is never run from the caller's working directory"
  local tmp rc=0 first
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  : > "${T_DOCKER_LOG}"
  (cd "${tmp}" && run_deploy -- "${pod[@]}" "${conv[@]}") || rc=$?
  assert_exit 0 "$rc" "podman: a converged stack started by hand exits 0"
  first="$(grep -m1 '^podman-cwd ' "${T_DOCKER_LOG}" || true)"
  if [[ "${first}" == "podman-cwd /" ]]; then
    record 1 "podman: the first podman call runs from /, which the service user can always enter"
  else
    record 0 "podman: the first podman call runs from / (got '${first}')"
  fi
  if grep -qx "podman-cwd ${tmp}" "${T_DOCKER_LOG}"; then
    record 0 "podman: no podman call inherits the caller's working directory"
  else
    record 1 "podman: no podman call inherits the caller's working directory"
  fi
  rm -rf "${tmp}"
}

write_rsync_stub() {
  cat > "${T_FAKE_BIN}/rsync" <<'RSYNC'
#!/usr/bin/env bash
set -euo pipefail
printf 'rsync %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
src="${*: -2:1}"
dst="${*: -1}"
for rule in ${FAKE_RSYNC_FAIL_RULES:-}; do
  if [[ "${src}" == *"${rule%%=>*}"* && "${dst}" == *"${rule#*=>}"* ]]; then
    echo "rsync: [receiver] mkstemp \"${dst}.publish-loop.sh.XXXXXX\" failed: Permission denied (13)" >&2
    echo "rsync error: some files/attrs were not transferred (see previous errors) (code 23) at main.c(1338) [generator=3.4.1]" >&2
    exit 23
  fi
done
rm -rf "${dst}"
mkdir -p "${dst}"
cp -R "${src}." "${dst}"
RSYNC
  chmod +x "${T_FAKE_BIN}/rsync"
}

seed_live_config_tree() {
  local t="$1" u
  mkdir -p "${t}/docker/acme" "${t}/docker/edge" "${t}/monitoring/prometheus" "${t}/quadlet/systemd"
  echo "OLD acme" > "${t}/docker/acme/publish-loop.sh"
  echo "OLD edge" > "${t}/docker/edge/nginx.conf"
  echo "OLD prometheus" > "${t}/monitoring/prometheus/prometheus.yml"
  for u in "${T_UNIT_DIR}"/*.container; do
    cp "${u}" "${t}/quadlet/systemd/"
  done
}

write_mirror_bundle() {
  local b="$1" u
  mkdir -p "${b}/docker/acme" "${b}/docker/edge" "${b}/monitoring/prometheus" "${b}/quadlet/systemd"
  echo "# promoted compose" > "${b}/docker-compose.yml"
  echo "NEW acme" > "${b}/docker/acme/publish-loop.sh"
  echo "NEW edge" > "${b}/docker/edge/nginx.conf"
  echo "NEW prometheus" > "${b}/monitoring/prometheus/prometheus.yml"
  for u in "${T_UNIT_DIR}"/*.container; do
    cp "${u}" "${b}/quadlet/systemd/"
  done
  echo "# NEW release" >> "${b}/quadlet/systemd/backend.container"
}

assert_file_says() {
  local file="$1" want="$2" desc="$3" got
  got="$(head -n 1 "${file}" 2>/dev/null || echo "<missing>")"
  if [[ "${got}" == "${want}" ]]; then
    record 1 "${desc}"
  else
    record 0 "${desc} (${file} says '${got}', expected '${want}')"
  fi
}

assert_failure_metric() {
  if grep -q 'basetool_deploy_last_failure_timestamp [1-9]' "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "$1"
  else
    record 0 "$1 (no failure timestamp in deploy.prom)"
  fi
}

scenario_config_mirror_failure_is_recorded_and_undone() {
  echo "Scenario: a mirror that fails halfway through the config apply is recorded, undone and backed off (2026-09-25)"
  local tmp rc=0 target
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_rsync_stub
  seed_live_config_tree "${T_COMPOSE_DIR}"
  local bundle="${tmp}/bundle"
  write_mirror_bundle "${bundle}"
  write_marker "${MARKER}"
  echo "# the previous release's pin record" > "${T_STATE_DIR}/current-digest-pin.yml"
  target="${DIG_BACKEND}|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG_NEXT}|${DIG_KCSPI}"
  mapfile -t fake < <(converged_env)

  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" \
    "FAKE_RSYNC_FAIL_RULES=config-stage=>/docker/acme" || rc=$?
  assert_exit 23 "$rc" "the tick exits with rsync's own code instead of pretending nothing happened"
  assert_contains "Permission denied (13)" "rsync's own error is in the log"
  assert_contains "FATAL: deploy aborted before the health gate — step 'mirror ${T_COMPOSE_DIR}/docker/acme' failed (exit 23)" \
    "a FATAL line names the step, the path and the exit code"
  assert_failure_metric "the failure metric DeployFailed reads is written"
  if grep -qx "${target} 1 [0-9]*" "${T_STATE_DIR}/failed.digests" 2>/dev/null; then
    record 1 "the failure is recorded for the backoff, against this target"
  else
    record 0 "the failure is recorded for the backoff, against this target"
  fi
  assert_file_says "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml" "OLD prometheus" \
    "a subtree mirrored before the failure is restored"
  assert_file_says "${T_COMPOSE_DIR}/docker/edge/nginx.conf" "OLD edge" \
    "the edge configuration is restored too (it is in the snapshot now)"
  assert_file_says "${T_COMPOSE_DIR}/docker/acme/publish-loop.sh" "OLD acme" "the failed subtree is the previous one"
  assert_file_says "${T_STATE_DIR}/config-previous/docker/edge/nginx.conf" "OLD edge" \
    "config-previous/ holds the previous release, edge included"
  assert_contains "previous host config restored" "the restore says it completed"
  if [[ ! -f "${T_STATE_DIR}/config-apply.incomplete" ]]; then
    record 1 "a completed restore leaves no incomplete-apply marker"
  else
    record 0 "a completed restore leaves no incomplete-apply marker"
  fi
  if grep -q '# NEW release' "${T_UNIT_DIR}/backend.container"; then
    record 0 "the unit directory still holds the previous units"
  else
    record 1 "the unit directory still holds the previous units"
  fi
  if [[ ! -e "${T_UNIT_DIR}/backend.container.d/10-digest-pin.conf" ]] \
    && grep -qx '# the previous release.s pin record' "${T_STATE_DIR}/current-digest-pin.yml"; then
    record 1 "the digest pin is untouched by a failed config apply"
  else
    record 0 "the digest pin is untouched by a failed config apply"
  fi
  assert_no_apply "nothing is started or restarted"

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" \
    "FAKE_RSYNC_FAIL_RULES=config-stage=>/docker/acme" || rc=$?
  assert_exit 0 "$rc" "the next tick inside the backoff window is a quiet skip"
  assert_contains "in backoff window" "and says it is backing off"
  assert_excludes "config changed" "no config is staged while backing off"

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy --force -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_exit 0 "$rc" "--force after the fix applies the release"
  assert_file_says "${T_COMPOSE_DIR}/docker/acme/publish-loop.sh" "NEW acme" "the release's acme loop reaches the host"
  if [[ ! -f "${T_STATE_DIR}/failed.digests" ]]; then
    record 1 "the successful retry clears the failure record"
  else
    record 0 "the successful retry clears the failure record"
  fi
  rm -rf "${tmp}"
}

scenario_config_restore_failure_keeps_the_anchor() {
  echo "Scenario: when the restore fails as well, the tick says so and the next one does not snapshot the mixed tree"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_rsync_stub
  seed_live_config_tree "${T_COMPOSE_DIR}"
  local bundle="${tmp}/bundle"
  write_mirror_bundle "${bundle}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)

  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" \
    "FAKE_RSYNC_FAIL_RULES=config-stage=>/code/quadlet config-previous=>/code/monitoring" || rc=$?
  assert_exit 23 "$rc" "the tick exits non-zero"
  assert_contains "restoring the previous host config FAILED" "the failed restore is reported"
  assert_contains "INCONSISTENT" "and the operator is told the tree is mixed"
  assert_failure_metric "the failure metric is written although the restore failed"
  if [[ -f "${T_STATE_DIR}/config-apply.incomplete" ]]; then
    record 1 "the incomplete-apply marker stays"
  else
    record 0 "the incomplete-apply marker stays"
  fi

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy --force -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_exit 0 "$rc" "the retry applies the release"
  assert_contains "keeping ${T_STATE_DIR}/config-previous as the rollback anchor" \
    "the retry does not snapshot the half-applied tree"
  assert_file_says "${T_STATE_DIR}/config-previous/docker/edge/nginx.conf" "OLD edge" \
    "config-previous/ still holds the last consistent release"
  if [[ ! -f "${T_STATE_DIR}/config-apply.incomplete" ]]; then
    record 1 "a completed apply clears the marker"
  else
    record 0 "a completed apply clears the marker"
  fi
  rm -rf "${tmp}"
}

scenario_pull_failure_after_config_apply_is_undone() {
  echo "Scenario: a pull that fails after the config was applied puts the config AND the pin back"
  local tmp rc=0 dig_new pin
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_rsync_stub
  seed_live_config_tree "${T_COMPOSE_DIR}"
  local bundle="${tmp}/bundle"
  write_mirror_bundle "${bundle}"
  write_marker "${MARKER}"
  printf 'services:\n  backend:\n    image: %s\n  frontend:\n    image: %s\n  ingest:\n    image: %s\n' \
    "${REPO_BACKEND}" "${REPO_FRONTEND}" "${REPO_INGEST}" > "${T_STATE_DIR}/current-digest-pin.yml"
  pin="${T_UNIT_DIR}/backend.container.d/10-digest-pin.conf"
  mkdir -p "${pin%/*}"
  printf '[Container]\nImage=%s\n' "${REPO_BACKEND}" > "${pin}"
  dig_new="$(hexdig beef2)"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" \
    "FAKE_REMOTE_BACKEND=${dig_new}" "FAKE_PULL_RC=1" || rc=$?
  assert_exit 1 "$rc" "a failed pull exits non-zero"
  assert_contains "step 'pull the release images' failed (exit 1)" "the FATAL line names the pull"
  assert_failure_metric "the failure metric is written"
  assert_file_says "${T_COMPOSE_DIR}/docker/edge/nginx.conf" "OLD edge" "the applied config tree is put back"
  if grep -q "${dig_new}" "${pin}" 2>/dev/null; then
    record 0 "the drop-in no longer binds the release that was never pulled"
  elif grep -q "${DIG_BACKEND}" "${pin}" 2>/dev/null; then
    record 1 "the drop-in no longer binds the release that was never pulled"
  else
    record 0 "the drop-in no longer binds the release that was never pulled (drop-in missing)"
  fi
  assert_no_apply "nothing is started or restarted"
  rm -rf "${tmp}"
}

scenario_config_preflight_refuses_an_unwritable_subtree() {
  echo "Scenario: a subtree the deploy account cannot write is refused before anything changes"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  seed_live_config_tree "${T_COMPOSE_DIR}"
  local bundle="${tmp}/bundle"
  write_mirror_bundle "${bundle}"
  write_marker "${MARKER}"
  chmod 0555 "${T_COMPOSE_DIR}/docker/acme"
  if [[ -w "${T_COMPOSE_DIR}/docker/acme" ]]; then
    echo "  skip - this runner cannot make a directory unwritable (root, or a noacl mount)"
    chmod 0755 "${T_COMPOSE_DIR}/docker/acme"
    rm -rf "${tmp}"
    return 0
  fi
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  chmod 0755 "${T_COMPOSE_DIR}/docker/acme"
  assert_exit 1 "$rc" "the pre-flight refuses"
  assert_contains "PRE-FLIGHT: ${T_COMPOSE_DIR}/docker/acme is not owned or not writable" \
    "and names the directory"
  assert_failure_metric "the refusal is a recorded failure"
  assert_file_says "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml" "OLD prometheus" \
    "no subtree was mirrored"
  if [[ ! -d "${T_STATE_DIR}/config-previous" ]]; then
    record 1 "not even the snapshot was taken"
  else
    record 0 "not even the snapshot was taken"
  fi
  rm -rf "${tmp}"
}

scenario_carve_out_leaves_no_pin_behind() {
  echo "Scenario: a gated stateful-infra change refuses without writing the pin or a failure"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_infra_units "${T_COMPOSE_DIR}" 26.6 \
    aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
  local bundle="${tmp}/bundle"
  write_infra_units "${bundle}" 26.7 \
    bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" \
    "FAKE_REMOTE_BACKEND=$(hexdig beef3)" || rc=$?
  assert_exit 3 "$rc" "the carve-out refuses"
  assert_contains "CARVE-OUT" "and says why"
  if [[ ! -e "${T_UNIT_DIR}/backend.container.d/10-digest-pin.conf" && ! -e "${T_STATE_DIR}/current-digest-pin.yml" ]]; then
    record 1 "no digest pin is written for a release the gate held back"
  else
    record 0 "no digest pin is written for a release the gate held back"
  fi
  assert_excludes "FATAL: deploy aborted" "a deliberate refusal is not recorded as a failure"
  if [[ ! -f "${T_STATE_DIR}/failed.digests" ]]; then
    record 1 "and puts nothing into the backoff"
  else
    record 0 "and puts nothing into the backoff"
  fi
  rm -rf "${tmp}"
}

DIG_KCSPI_OLD="$(hexdig 5b0)"
DIG_BACKEND_OLD="$(hexdig ba5e)"
DIG_FRONTEND_OLD="$(hexdig f00d)"
DIG_INGEST_OLD="$(hexdig 0dd)"

spi_host() {
  local tmp="$1" b="$2" f="$3" i="$4" svc ref
  setup_host "${tmp}"
  mkdir -p "${T_COMPOSE_DIR}/keycloak/providers"
  echo "OLD jar" > "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar"
  {
    printf 'services:\n'
    for svc in backend frontend ingest; do
      case "${svc}" in
        backend) ref="ghcr.io/krt-profit/basetool-backend@${b}" ;;
        frontend) ref="ghcr.io/krt-profit/basetool-frontend@${f}" ;;
        ingest) ref="ghcr.io/krt-profit/basetool-ingest@${i}" ;;
      esac
      printf '  %s:\n    image: %s\n' "${svc}" "${ref}"
      mkdir -p "${T_UNIT_DIR}/${svc}.container.d"
      printf '# Written by deploy.sh. The digest this release pinned; do not edit.\n[Container]\nImage=%s\n' "${ref}" \
        > "${T_UNIT_DIR}/${svc}.container.d/10-digest-pin.conf"
    done
  } > "${T_STATE_DIR}/current-digest-pin.yml"
  write_marker "${b}|${f}|${i}|${DIG_CONFIG}|${DIG_KCSPI_OLD}"
  T_DOWN="${tmp}/units-down"
}

spi_env() {
  converged_env
  printf '%s\n' \
    "FAKE_KCSPI_JAR=NEW jar" \
    "FAKE_UNIT_DOWN_FILE=${T_DOWN}" \
    "FAKE_REQUIRED_BY_keycloak=backend ingest frontend" \
    "FAKE_REQUIRED_BY_backend=ingest frontend" \
    "FAKE_REQUIRED_BY_db_keycloak=keycloak backend ingest frontend" \
    "FAKE_REQUIRED_BY_db_backend=backend ingest frontend" \
    "FAKE_REQUIRED_BY_redis=ingest frontend"
}

assert_nothing_down() {
  if [[ ! -s "${T_DOWN}" ]]; then
    record 1 "$1"
  else
    record 0 "$1 (still down: $(tr '\n' ' ' < "${T_DOWN}"))"
  fi
}

assert_starts() {
  local unit="$1" want="$2" desc="$3" got
  got="$(grep -cx "${unit}" "${T_DOWN}.starts" 2>/dev/null || true)"
  if [[ "${got:-0}" -eq "${want}" ]]; then
    record 1 "${desc}"
  else
    record 0 "${desc} (${unit} started ${got:-0} time(s), expected ${want}; starts: $(tr '\n' ' ' < "${T_DOWN}.starts" 2>/dev/null))"
  fi
}

assert_count() {
  local pattern="$1" want="$2" desc="$3" got
  got="$(grep -cE -- "${pattern}" "${T_DOCKER_LOG}" || true)"
  if [[ "${got:-0}" -eq "${want}" ]]; then
    record 1 "${desc}"
  else
    record 0 "${desc} ('${pattern}' matched ${got:-0} time(s), expected ${want})"
  fi
}

assert_marker() {
  if grep -qxF "$1" "${T_STATE_DIR}/last-deployed.digests" 2>/dev/null; then
    record 1 "$2"
  else
    record 0 "$2 (marker is '$(cat "${T_STATE_DIR}/last-deployed.digests" 2>/dev/null)')"
  fi
}

assert_pin_binds() {
  if grep -q "@${2}\$" "${T_UNIT_DIR}/${1}.container.d/10-digest-pin.conf" 2>/dev/null; then
    record 1 "$3"
  else
    record 0 "$3 ($(tail -n 1 "${T_UNIT_DIR}/${1}.container.d/10-digest-pin.conf" 2>/dev/null))"
  fi
}

assert_metric_set() {
  if grep -q "^${1} [1-9]" "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "$2"
  else
    record 0 "$2 (no ${1} stamp in deploy.prom)"
  fi
}

assert_no_success_stamp() {
  if grep -q 'basetool_deploy_last_success_timestamp 0$' "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "$1"
  else
    record 0 "$1"
  fi
}

scenario_spi_and_apps_move_in_one_restart_window() {
  echo "Scenario: a release that moves the provider JAR AND the app images restarts each unit once, keycloak on the new JAR"
  local tmp rc=0
  tmp="$(mktmp)"
  spi_host "${tmp}" "${DIG_BACKEND_OLD}" "${DIG_FRONTEND_OLD}" "${DIG_INGEST_OLD}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "one window: the deploy succeeds"
  assert_contains "keycloak-spi changed" "one window: the moved JAR is noticed"
  assert_file_says "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar" "NEW jar" "one window: the new JAR is live"
  assert_docker "keycloak-jar-at-start NEW jar" "one window: keycloak starts on the NEW JAR"
  assert_no_docker "keycloak-jar-at-start OLD jar" "one window: ...and never on the old one"
  assert_starts keycloak 1 "one window: keycloak is started once"
  assert_starts backend 1 "one window: backend is started once"
  assert_starts ingest 1 "one window: ingest is started once, not again after backend's restart"
  assert_starts frontend 1 "one window: frontend is started once, not again after backend's restart"
  assert_count '^systemctl --user stop ' 1 "one window: the re-defined units go down in ONE stop"
  assert_count '^systemctl --user restart (keycloak|backend|ingest|frontend)\.service' 0 \
    "one window: no stack unit is restarted (a restart travels along Requires= and re-runs dependents)"
  assert_nothing_down "one window: no application unit is left stopped"
  assert_contains "release parts: app images [backend frontend ingest]" "one window: the log names the images that move"
  assert_contains "provider JAR: yes" "one window: ...and the JAR"
  assert_contains "keycloak-spi provider JAR applied with the release" "one window: the JAR is reported applied with the release"
  assert_contains "deploy successful" "one window: success is reported"
  assert_marker "${MARKER}" "one window: the marker advances to the new images AND the new JAR"
  rm -rf "${tmp}"
}

scenario_apps_only_restart_each_unit_once() {
  echo "Scenario: a release that re-pins backend, frontend and ingest restarts each of them once, and keycloak not at all"
  local tmp rc=0
  tmp="$(mktmp)"
  spi_host "${tmp}" "${DIG_BACKEND_OLD}" "${DIG_FRONTEND_OLD}" "${DIG_INGEST_OLD}"
  write_marker "${DIG_BACKEND_OLD}|${DIG_FRONTEND_OLD}|${DIG_INGEST_OLD}|${DIG_CONFIG}|${DIG_KCSPI}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "apps only: the deploy succeeds"
  assert_starts backend 1 "apps only: backend is started once"
  assert_starts ingest 1 "apps only: ingest is started once (#2072's finding: it was restarted again after backend)"
  assert_starts frontend 1 "apps only: frontend is started once (#2072's finding: it was restarted again after backend)"
  assert_starts keycloak 0 "apps only: an unchanged JAR does not recreate keycloak"
  assert_no_docker "keycloak.service backend" "apps only: keycloak is not in the stop"
  assert_no_docker "restart keycloak.service" "apps only: ...and not restarted"
  assert_no_docker "stop keycloak.service" "apps only: ...and not stopped"
  assert_file_says "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar" "OLD jar" "apps only: the JAR is untouched"
  assert_contains "provider JAR: no" "apps only: the log says the JAR did not move"
  assert_nothing_down "apps only: no application unit is left stopped"
  rm -rf "${tmp}"
}

scenario_spi_only_is_the_whole_apply() {
  echo "Scenario: a release that moves only the provider JAR is one keycloak stop and one start of the stack"
  local tmp rc=0
  tmp="$(mktmp)"
  spi_host "${tmp}" "${DIG_BACKEND}" "${DIG_FRONTEND}" "${DIG_INGEST}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "jar only: the deploy succeeds"
  assert_docker "systemctl --user stop keycloak.service" "jar only: keycloak (and what requires it) is stopped"
  assert_count '^systemctl --user stop ' 1 "jar only: once"
  assert_docker "keycloak-jar-at-start NEW jar" "jar only: keycloak starts on the new JAR"
  assert_starts keycloak 1 "jar only: keycloak is started once"
  assert_starts backend 1 "jar only: backend comes back once"
  assert_starts ingest 1 "jar only: ingest comes back once"
  assert_starts frontend 1 "jar only: frontend comes back once"
  assert_contains "release parts: app images [none]" "jar only: no image moves"
  assert_nothing_down "jar only: no application unit is left stopped"
  assert_marker "${MARKER}" "jar only: the marker advances to the new JAR"
  rm -rf "${tmp}"
}

scenario_spi_gate_failure_rolls_back_images_and_jar() {
  echo "Scenario: keycloak failing on the new JAR rolls back the app images AND the JAR, in one rollback"
  local tmp rc=0
  tmp="$(mktmp)"
  spi_host "${tmp}" "${DIG_BACKEND_OLD}" "${DIG_FRONTEND_OLD}" "${DIG_INGEST_OLD}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_STUCK_UNITS=keycloak" "FAKE_STUCK_WITH_JAR=NEW jar" || rc=$?
  assert_exit 1 "$rc" "rollback: the run fails"
  assert_excludes "deploy successful" "rollback: success is never reported"
  assert_contains "did not come up, in start order: [keycloak backend ingest frontend]" \
    "rollback: the log names what did not come up, keycloak first"
  assert_contains "KEYCLOAK did not come up, and the provider JAR is the only part of this release that changed keycloak" \
    "rollback: the log says the JAR is the likely cause"
  assert_contains "this release changed: app images [backend frontend ingest]" "rollback: ...and what else the release changed"
  assert_file_says "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar" "OLD jar" "rollback: the previous JAR is live again"
  assert_pin_binds backend "${DIG_BACKEND_OLD}" "rollback: backend is bound to the previous image again"
  assert_pin_binds frontend "${DIG_FRONTEND_OLD}" "rollback: frontend is bound to the previous image again"
  assert_docker "keycloak-jar-at-start OLD jar" "rollback: keycloak comes back on the previous JAR"
  assert_contains "rolled back to previous digest pin successfully — the previous app digests + provider JAR are live again" \
    "rollback: one rollback names both parts"
  assert_nothing_down "rollback: no application unit is left stopped"
  assert_metric_set basetool_deploy_last_rollback_timestamp "rollback: the rollback metric is written"
  assert_no_success_stamp "rollback: no success timestamp is written"
  if grep -q "^${MARKER} 1 " "${T_STATE_DIR}/failed.digests" 2>/dev/null; then
    record 1 "rollback: the failure is recorded for the backoff, against the whole target"
  else
    record 0 "rollback: the failure is recorded for the backoff, against the whole target ($(cat "${T_STATE_DIR}/failed.digests" 2>/dev/null))"
  fi
  assert_marker "${DIG_BACKEND_OLD}|${DIG_FRONTEND_OLD}|${DIG_INGEST_OLD}|${DIG_CONFIG}|${DIG_KCSPI_OLD}" \
    "rollback: the marker stays on the previous release"
  rm -rf "${tmp}"
}

scenario_spi_app_failure_rolls_back_both_and_says_it_cannot_tell() {
  echo "Scenario: an app unit failing in a release that also moved the JAR rolls both back and says it cannot tell which"
  local tmp rc=0
  tmp="$(mktmp)"
  spi_host "${tmp}" "${DIG_BACKEND_OLD}" "${DIG_FRONTEND_OLD}" "${DIG_INGEST_OLD}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_STUCK_UNITS=frontend" "FAKE_STUCK_WITH_JAR=NEW jar" || rc=$?
  assert_exit 1 "$rc" "app failure: the run fails"
  assert_contains "keycloak is up on the new provider JAR; the first unit that did not come up is frontend (its image changed: yes)" \
    "app failure: the log names frontend and that keycloak came up"
  assert_contains "cannot tell which of them it was" "app failure: ...and says the blame is not exact"
  assert_file_says "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar" "OLD jar" "app failure: the JAR is rolled back too"
  assert_pin_binds frontend "${DIG_FRONTEND_OLD}" "app failure: frontend is bound to the previous image again"
  assert_nothing_down "app failure: no application unit is left stopped"
  assert_excludes "deploy successful" "app failure: success is never reported"
  rm -rf "${tmp}"
}

scenario_spi_only_failure_blames_the_jar() {
  echo "Scenario: keycloak failing in a release whose only change is the JAR names the JAR as the cause"
  local tmp rc=0
  tmp="$(mktmp)"
  spi_host "${tmp}" "${DIG_BACKEND}" "${DIG_FRONTEND}" "${DIG_INGEST}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_STUCK_UNITS=keycloak" "FAKE_STUCK_WITH_JAR=NEW jar" || rc=$?
  assert_exit 1 "$rc" "jar only, failed: the run fails"
  assert_contains "the provider JAR is the ONLY change in this release — the JAR is the cause" \
    "jar only, failed: the log names the JAR"
  assert_file_says "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar" "OLD jar" "jar only, failed: the previous JAR is live again"
  assert_contains "rolled back to previous digest pin successfully" "jar only, failed: the rollback reaches health"
  assert_nothing_down "jar only, failed: no application unit is left stopped"
  assert_metric_set basetool_deploy_last_rollback_timestamp "jar only, failed: the rollback metric is written"
  if grep -q "^${MARKER} 1 " "${T_STATE_DIR}/failed.digests" 2>/dev/null; then
    record 1 "jar only, failed: the failure is recorded for the backoff"
  else
    record 0 "jar only, failed: the failure is recorded for the backoff"
  fi
  rm -rf "${tmp}"
}

scenario_spi_rollback_that_does_not_heal_says_so() {
  echo "Scenario: a unit broken whatever the JAR fails the release, and the rollback says it did not heal"
  local tmp rc=0
  tmp="$(mktmp)"
  spi_host "${tmp}" "${DIG_BACKEND}" "${DIG_FRONTEND}" "${DIG_INGEST}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_STUCK_UNITS=ingest" "FAKE_STUCK_AFTER_KEYCLOAK_RESTART=true" || rc=$?
  assert_exit 1 "$rc" "no heal: the run fails"
  assert_file_says "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar" "OLD jar" "no heal: the previous JAR is live again"
  assert_contains "rollback ALSO failed — did not come up: [ingest]" "no heal: the log says the rollback did not heal, and what"
  assert_excludes "rolled back to previous digest pin successfully" "no heal: ...and does not claim it did"
  assert_excludes "deploy successful" "no heal: success is never reported"
  assert_metric_set basetool_deploy_last_rollback_timestamp "no heal: the rollback metric is written"
  rm -rf "${tmp}"
}

scenario_spi_extraction_failure_is_recorded() {
  echo "Scenario: a provider JAR that cannot be extracted is a recorded failure, and nothing on the host changes"
  local tmp rc=0
  tmp="$(mktmp)"
  spi_host "${tmp}" "${DIG_BACKEND_OLD}" "${DIG_FRONTEND_OLD}" "${DIG_INGEST_OLD}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_KCSPI_JAR=" || rc=$?
  assert_exit 1 "$rc" "extraction: the run fails"
  assert_contains "FATAL: deploy aborted before the health gate — step 'extract the keycloak-spi provider JAR' failed" \
    "extraction: the log names the step"
  assert_failure_metric "extraction: the failure metric DeployFailed reads is written (#2072's finding: it was silent)"
  if grep -q "^${MARKER} 1 " "${T_STATE_DIR}/failed.digests" 2>/dev/null; then
    record 1 "extraction: the failure is recorded for the backoff"
  else
    record 0 "extraction: the failure is recorded for the backoff"
  fi
  assert_file_says "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar" "OLD jar" "extraction: the live JAR is untouched"
  assert_pin_binds backend "${DIG_BACKEND_OLD}" "extraction: no pin was moved"
  assert_count '^systemctl --user (start|restart|stop) ' 0 "extraction: no unit was touched"
  rm -rf "${tmp}"
}

heal_host() {
  spi_host "$1" "${DIG_BACKEND}" "${DIG_FRONTEND}" "${DIG_INGEST}"
  write_marker "${MARKER}"
}

assert_health_stamp() {
  local metric="$1" want="$2" desc="$3" got=no
  if grep -q "^${metric} [1-9]" "${T_STATE_DIR}/textfile/deploy-health.prom" 2>/dev/null; then
    got=yes
  fi
  if [[ "${got}" == "${want}" ]]; then
    record 1 "${desc}"
  else
    record 0 "${desc} (${metric} stamped: ${got}, expected ${want})"
  fi
}

scenario_heal_unhealthy_backend_restarts_it_and_its_dependents_once() {
  echo "Scenario: an unhealthy backend is healed in one window -- backend, ingest and frontend start once, keycloak not at all"
  local tmp rc=0
  tmp="$(mktmp)"
  heal_host "${tmp}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=running/unhealthy" || rc=$?
  assert_exit 0 "$rc" "heal backend: the heal succeeds"
  assert_contains "targeted restart (not a release rollback)" "heal backend: the runtime-health path is taken"
  assert_starts backend 1 "heal backend: backend is started once"
  assert_starts ingest 1 "heal backend: ingest, which requires it, is started once"
  assert_starts frontend 1 "heal backend: frontend, which requires it, is started once"
  assert_starts keycloak 0 "heal backend: keycloak, which backend requires, is not touched"
  assert_count '^systemctl --user stop ' 1 "heal backend: one stop"
  assert_docker "systemctl --user stop backend.service" "heal backend: ...naming backend"
  assert_no_docker "stop keycloak.service" "heal backend: keycloak is not stopped"
  assert_count '^systemctl --user restart ' 0 \
    "heal backend: nothing is restarted (a restart returns before its dependents are back)"
  assert_nothing_down "heal backend: frontend and ingest are back before success is written (#2072's shape: they had no container)"
  assert_contains "health drift resolved" "heal backend: resolution is reported"
  assert_health_stamp basetool_deploy_last_stack_healthy_timestamp yes "heal backend: the healthy heartbeat is stamped"
  assert_health_stamp basetool_deploy_last_health_restart_failed_timestamp no "heal backend: no restart failure is stamped"
  assert_no_docker "podman pull" "heal backend: nothing is pulled"
  assert_no_docker "cosign verify" "heal backend: nothing is re-verified"
  rm -rf "${tmp}"
}

scenario_heal_unhealthy_backend_and_frontend_starts_frontend_once() {
  echo "Scenario: an unhealthy backend AND frontend are healed in one window -- frontend is not started a second time"
  local tmp rc=0
  tmp="$(mktmp)"
  heal_host "${tmp}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=running/unhealthy" "FAKE_STATE_frontend=running/unhealthy" || rc=$?
  assert_exit 0 "$rc" "heal two: the heal succeeds"
  assert_starts backend 1 "heal two: backend is started once"
  assert_starts ingest 1 "heal two: ingest is started once"
  assert_starts frontend 1 "heal two: frontend is started once, not again after backend's restart brought it back"
  assert_starts keycloak 0 "heal two: keycloak is not touched"
  assert_count '^systemctl --user stop ' 1 "heal two: both go down in one stop"
  assert_count '^systemctl --user restart ' 0 "heal two: nothing is restarted"
  assert_nothing_down "heal two: nothing is left stopped"
  rm -rf "${tmp}"
}

scenario_heal_unhealthy_frontend_touches_nothing_it_requires() {
  echo "Scenario: an unhealthy frontend is healed alone -- backend, keycloak and redis are not restarted"
  local tmp rc=0
  tmp="$(mktmp)"
  heal_host "${tmp}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_frontend=running/unhealthy" || rc=$?
  assert_exit 0 "$rc" "heal frontend: the heal succeeds"
  assert_starts frontend 1 "heal frontend: frontend is started once"
  assert_starts backend 0 "heal frontend: backend is not touched"
  assert_starts keycloak 0 "heal frontend: keycloak is not touched"
  assert_starts ingest 0 "heal frontend: ingest is not touched"
  assert_starts redis 0 "heal frontend: redis is not touched"
  assert_no_docker "stop backend.service" "heal frontend: backend is not stopped"
  assert_no_docker "stop keycloak.service" "heal frontend: keycloak is not stopped"
  assert_count '^systemctl --user restart ' 0 "heal frontend: nothing is restarted"
  assert_nothing_down "heal frontend: nothing is left stopped"
  rm -rf "${tmp}"
}

scenario_heal_that_fails_is_recorded_after_the_wait() {
  echo "Scenario: a heal whose backend does not come back records the failure, names what did not come up, and rolls nothing back"
  local tmp rc=0
  tmp="$(mktmp)"
  heal_host "${tmp}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=running/unhealthy" \
    "FAKE_STUCK_UNITS=backend" "FAKE_STUCK_WITH_JAR=OLD jar" || rc=$?
  assert_exit 1 "$rc" "heal fails: the run fails"
  assert_contains "did NOT restore health (attempt #1)" "heal fails: the failure is reported"
  assert_contains "health drift: did not come up, in start order: [backend ingest frontend]" \
    "heal fails: the log names what did not come up, in start order"
  assert_excludes "health drift resolved" "heal fails: resolution is not claimed"
  assert_excludes "rolling back" "heal fails: no release rollback"
  assert_health_stamp basetool_deploy_last_health_restart_failed_timestamp yes \
    "heal fails: the health-restart-failed gauge DeployHealthRestartFailing reads is stamped"
  assert_health_stamp basetool_deploy_last_stack_healthy_timestamp no "heal fails: the healthy heartbeat is not stamped"
  if [[ ! -f "${T_STATE_DIR}/textfile/deploy.prom" ]] \
     || ! grep -q 'basetool_deploy_last_rollback_timestamp [1-9]' "${T_STATE_DIR}/textfile/deploy.prom"; then
    record 1 "heal fails: no DeployRolledBack metric"
  else
    record 0 "heal fails: no DeployRolledBack metric"
  fi
  if grep -qF "${MARKER} 1 " "${T_STATE_DIR}/health-restart.digests" 2>/dev/null; then
    record 1 "heal fails: the heal backoff records attempt 1"
  else
    record 0 "heal fails: the heal backoff records attempt 1"
  fi
  assert_starts backend 0 "heal fails: backend was never started (stuck)"
  assert_count '^systemctl --user restart ' 0 "heal fails: nothing is restarted"
  rm -rf "${tmp}"
}

scenario_missing_frontend_and_ingest_are_started_not_restarted() {
  echo "Scenario: frontend and ingest with no container are started once; backend and keycloak are not touched"
  local tmp rc=0
  tmp="$(mktmp)"
  heal_host "${tmp}"
  printf 'frontend\ningest\n' > "${T_DOWN}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_frontend=" "FAKE_PS_ingest=" || rc=$?
  assert_exit 0 "$rc" "missing two: the re-apply succeeds"
  assert_contains "drift: frontend: no container" "missing two: frontend is reported"
  assert_contains "re-applying" "missing two: the structural path is taken"
  assert_starts frontend 1 "missing two: frontend is started once"
  assert_starts ingest 1 "missing two: ingest is started once"
  assert_starts backend 0 "missing two: backend, which they require, is not restarted"
  assert_starts keycloak 0 "missing two: keycloak is not restarted"
  assert_count '^systemctl --user (stop|restart) ' 0 "missing two: nothing is stopped or restarted"
  assert_nothing_down "missing two: nothing is left stopped"
  assert_contains "deploy successful" "missing two: success is reported"
  assert_health_stamp basetool_deploy_last_stack_healthy_timestamp yes "missing two: the healthy heartbeat is stamped"
  rm -rf "${tmp}"
}

scenario_missing_backend_is_started_not_restarted() {
  echo "Scenario: a backend with no container is started once; nothing else is touched"
  local tmp rc=0
  tmp="$(mktmp)"
  heal_host "${tmp}"
  printf 'backend\n' > "${T_DOWN}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_backend=" || rc=$?
  assert_exit 0 "$rc" "missing backend: the re-apply succeeds"
  assert_contains "drift: backend: no container" "missing backend: backend is reported"
  assert_starts backend 1 "missing backend: backend is started once"
  assert_starts ingest 0 "missing backend: ingest is not restarted"
  assert_starts frontend 0 "missing backend: frontend is not restarted"
  assert_starts keycloak 0 "missing backend: keycloak is not restarted"
  assert_count '^systemctl --user (stop|restart) ' 0 "missing backend: nothing is stopped or restarted"
  assert_nothing_down "missing backend: nothing is left stopped"
  rm -rf "${tmp}"
}

scenario_mixed_drift_does_not_stamp_an_unhealthy_stack_healthy() {
  echo "Scenario: a re-apply for a missing frontend does not stamp the stack healthy while backend is unhealthy"
  local tmp rc=0
  tmp="$(mktmp)"
  heal_host "${tmp}"
  printf 'frontend\n' > "${T_DOWN}"
  printf '%s 2 %d\n' "${MARKER}" "$(( $(date +%s) - 100000 ))" > "${T_STATE_DIR}/health-restart.digests"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_frontend=" "FAKE_STATE_backend=running/unhealthy" || rc=$?
  assert_exit 0 "$rc" "mixed: the re-apply succeeds"
  assert_contains "re-applying" "mixed: a structural finding takes the re-apply path (ADR-0083)"
  assert_starts frontend 1 "mixed: frontend is started once"
  assert_starts backend 0 "mixed: backend is not restarted by the re-apply"
  assert_contains "left for the targeted heal of the next tick" "mixed: the log says backend is not healed by this run"
  assert_health_stamp basetool_deploy_last_stack_healthy_timestamp no \
    "mixed: the healthy heartbeat is not stamped over an unhealthy backend"
  if grep -qF "${MARKER} 2 " "${T_STATE_DIR}/health-restart.digests" 2>/dev/null; then
    record 1 "mixed: the heal backoff of the still-unhealthy backend is kept"
  else
    record 0 "mixed: the heal backoff of the still-unhealthy backend is kept"
  fi
  rm -rf "${tmp}"
}

anchor_host() {
  heal_host "$1"
  local svc ref
  {
    printf 'services:\n'
    for svc in backend frontend ingest; do
      case "${svc}" in
        backend) ref="ghcr.io/krt-profit/basetool-backend@${DIG_BACKEND_OLD}" ;;
        frontend) ref="ghcr.io/krt-profit/basetool-frontend@${DIG_FRONTEND_OLD}" ;;
        ingest) ref="ghcr.io/krt-profit/basetool-ingest@${DIG_INGEST_OLD}" ;;
      esac
      printf '  %s:\n    image: %s\n' "${svc}" "${ref}"
    done
  } > "${T_STATE_DIR}/previous-digest-pin.yml"
  seed_release_tree "${T_STATE_DIR}/config-previous" "N-1"
  echo "N-1 jar" > "${T_STATE_DIR}/keycloak-spi-previous.jar"
}

seed_release_tree() {
  local t="$1" label="$2" u
  mkdir -p "${t}/docker/acme" "${t}/docker/edge" "${t}/monitoring/prometheus" "${t}/quadlet/systemd"
  echo "${label} acme" > "${t}/docker/acme/publish-loop.sh"
  echo "${label} edge" > "${t}/docker/edge/nginx.conf"
  echo "${label} prometheus" > "${t}/monitoring/prometheus/prometheus.yml"
  for u in "${T_UNIT_DIR}"/*.container; do
    if [[ -e "${u}" ]]; then
      cp "${u}" "${t}/quadlet/systemd/"
    fi
  done
}

assert_anchors_keep_previous() {
  local p="$1"
  if grep -q "@${DIG_BACKEND_OLD}\$" "${T_STATE_DIR}/previous-digest-pin.yml" 2>/dev/null \
     && ! grep -q "@${DIG_BACKEND}\$" "${T_STATE_DIR}/previous-digest-pin.yml" 2>/dev/null; then
    record 1 "${p}: previous-digest-pin.yml still names N-1"
  else
    record 0 "${p}: previous-digest-pin.yml still names N-1 (it says: $(grep 'image:' "${T_STATE_DIR}/previous-digest-pin.yml" 2>/dev/null | head -n 1))"
  fi
  assert_file_says "${T_STATE_DIR}/config-previous/docker/edge/nginx.conf" "N-1 edge" \
    "${p}: config-previous/ still holds N-1"
  assert_file_says "${T_STATE_DIR}/keycloak-spi-previous.jar" "N-1 jar" \
    "${p}: keycloak-spi-previous.jar still holds N-1"
}

assert_no_deploy_stamp() {
  if grep -q "^${1} [1-9]" "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 0 "$2"
  else
    record 1 "$2"
  fi
}

assert_failed_record() {
  if grep -qF "${1} ${2} " "${T_STATE_DIR}/failed.digests" 2>/dev/null; then
    record 1 "$3"
  else
    record 0 "$3 (failed.digests: $(cat "${T_STATE_DIR}/failed.digests" 2>/dev/null || echo '<none>'))"
  fi
}

assert_reapply_record() {
  if grep -qF "${1} ${2} " "${T_STATE_DIR}/reapply-failed.digests" 2>/dev/null; then
    record 1 "$3"
  else
    record 0 "$3 (reapply-failed.digests: $(cat "${T_STATE_DIR}/reapply-failed.digests" 2>/dev/null || echo '<none>'))"
  fi
}

scenario_reapply_that_fails_keeps_the_anchors_and_rolls_nothing_back() {
  echo "Scenario: a drift re-apply that fails its gate keeps the anchors on N-1, rolls nothing back, pages no DeployRolledBack"
  local tmp rc=0
  tmp="$(mktmp)"
  anchor_host "${tmp}"
  printf 'frontend\n' > "${T_DOWN}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_frontend=" "FAKE_STUCK_UNITS=frontend" "FAKE_STUCK_WITH_JAR=OLD jar" || rc=$?
  assert_exit 1 "$rc" "re-apply fails: the run fails"
  assert_contains "drift: frontend: no container" "re-apply fails: the drift is reported"
  assert_contains "re-applying" "re-apply fails: the re-apply path is taken"
  assert_contains "on a re-apply of the deployed release — not rolling back" "re-apply fails: the log says nothing is rolled back"
  assert_contains "did not come up, in start order: [frontend]" "re-apply fails: ...and names what did not come up"
  assert_excludes "rolled back to previous digest pin" "re-apply fails: no rollback to itself is claimed"
  assert_excludes "rollback ALSO failed" "re-apply fails: ...and none is attempted"
  assert_excludes "deploy successful" "re-apply fails: success is never reported"
  assert_anchors_keep_previous "re-apply fails"
  assert_pin_binds backend "${DIG_BACKEND}" "re-apply fails: backend stays bound to the deployed release, not to N-1"
  if grep -q "@${DIG_BACKEND}\$" "${T_STATE_DIR}/current-digest-pin.yml" 2>/dev/null; then
    record 1 "re-apply fails: the pin record still names the deployed release"
  else
    record 0 "re-apply fails: the pin record still names the deployed release"
  fi
  assert_marker "${MARKER}" "re-apply fails: the marker still names the deployed release"
  assert_no_deploy_stamp basetool_deploy_last_rollback_timestamp "re-apply fails: no DeployRolledBack stamp"
  assert_no_deploy_stamp basetool_deploy_last_failure_timestamp \
    "re-apply fails: no DeployFailed stamp either (the release had shipped)"
  assert_health_stamp basetool_deploy_last_health_restart_failed_timestamp yes \
    "re-apply fails: the gauge DeployHealthRestartFailing reads is stamped"
  assert_health_stamp basetool_deploy_last_stack_healthy_timestamp no "re-apply fails: the healthy heartbeat is not stamped"
  assert_contains "recorded re-apply failure #1" "re-apply fails: the failure is recorded"
  assert_reapply_record "${MARKER}" 1 "re-apply fails: the backoff record is keyed to the deployed target"

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_PS_frontend=" "FAKE_STUCK_UNITS=frontend" "FAKE_STUCK_WITH_JAR=OLD jar" || rc=$?
  assert_exit 0 "$rc" "re-apply fails: the next tick inside the backoff window is a quiet skip"
  assert_contains "in backoff window" "re-apply fails: ...and says it is backing off"
  assert_count '^systemctl --user (start|restart|stop) ' 0 "re-apply fails: nothing is started while backing off"
  assert_anchors_keep_previous "re-apply fails, backed off"
  rm -rf "${tmp}"
}

scenario_reapply_that_succeeds_keeps_the_anchors() {
  echo "Scenario: a drift re-apply that succeeds leaves every rollback anchor on N-1"
  local tmp rc=0
  tmp="$(mktmp)"
  anchor_host "${tmp}"
  printf 'frontend\n' > "${T_DOWN}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_frontend=" || rc=$?
  assert_exit 0 "$rc" "re-apply succeeds: the run succeeds"
  assert_contains "re-applying" "re-apply succeeds: the re-apply path is taken"
  assert_contains "deploy successful" "re-apply succeeds: success is reported"
  assert_contains "${T_STATE_DIR}/previous-digest-pin.yml keeps the previous release as the rollback anchor" \
    "re-apply succeeds: the log says the pin anchor is kept"
  assert_starts frontend 1 "re-apply succeeds: frontend is started once"
  assert_anchors_keep_previous "re-apply succeeds"
  assert_pin_binds frontend "${DIG_FRONTEND}" "re-apply succeeds: frontend is bound to the deployed release"
  assert_marker "${MARKER}" "re-apply succeeds: the marker is unchanged"
  assert_no_deploy_stamp basetool_deploy_last_rollback_timestamp "re-apply succeeds: no rollback stamp"
  rm -rf "${tmp}"
}

scenario_release_after_a_reapply_rotates_the_anchor_to_the_deployed_release() {
  echo "Scenario: a real release after a failed drift re-apply rotates the anchor to N, and its rollback lands on N"
  local tmp rc=0 dig_next
  tmp="$(mktmp)"
  anchor_host "${tmp}"
  printf 'frontend\n' > "${T_DOWN}"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_frontend=" "FAKE_STUCK_UNITS=frontend" "FAKE_STUCK_WITH_JAR=OLD jar" || rc=$?
  assert_exit 1 "$rc" "release after re-apply: the re-apply fails first"

  dig_next="$(hexdig beef5)"
  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_REMOTE_BACKEND=${dig_next}" "FAKE_UNHEALTHY_DIGEST=${dig_next}" || rc=$?
  assert_exit 1 "$rc" "release after re-apply: the failed release fails the run"
  assert_excludes "drift:" "release after re-apply: a change of target is a release, not a drift"
  if grep -q "@${DIG_BACKEND}\$" "${T_STATE_DIR}/previous-digest-pin.yml" 2>/dev/null; then
    record 1 "release after re-apply: previous-digest-pin.yml now names N, the release that was deployed"
  else
    record 0 "release after re-apply: previous-digest-pin.yml now names N, the release that was deployed ($(grep 'image:' "${T_STATE_DIR}/previous-digest-pin.yml" 2>/dev/null | head -n 1))"
  fi
  assert_contains "rolled back to previous digest pin successfully" "release after re-apply: the rollback reaches health"
  assert_pin_binds backend "${DIG_BACKEND}" "release after re-apply: the rollback lands on N, not on N-1 and not on N+1"
  assert_metric_set basetool_deploy_last_rollback_timestamp "release after re-apply: a real rollback does page DeployRolledBack"
  assert_failed_record "${dig_next}|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}" 1 \
    "release after re-apply: the release backoff holds N+1"
  assert_contains "recorded health-check failure #1" \
    "release after re-apply: N+1 starts at failure #1 — the re-apply's record is not its count"
  rm -rf "${tmp}"
}

scenario_reapply_of_lost_units_keeps_config_previous() {
  echo "Scenario: a re-apply that re-delivers the config (unit files gone) keeps config-previous/ on N-1 and restores nothing when it fails"
  local tmp rc=0
  tmp="$(mktmp)"
  anchor_host "${tmp}"
  write_rsync_stub
  seed_release_tree "${T_COMPOSE_DIR}" "N"
  local bundle="${tmp}/bundle"
  seed_release_tree "${bundle}" "N"
  echo "# promoted compose" > "${bundle}/docker-compose.yml"
  rm -rf "${T_UNIT_DIR}"
  mkdir -p "${T_UNIT_DIR}"
  mapfile -t fake < <(spi_env)

  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_RSYNC_FAIL_RULES=config-stage=>/docker/acme" || rc=$?
  assert_exit 23 "$rc" "lost units, failed: the tick exits with rsync's code"
  assert_contains "no Quadlet units on this host" "lost units, failed: the empty unit directory stages the bundle"
  assert_contains "re-applying" "lost units, failed: ...on the re-apply path"
  assert_contains "${T_STATE_DIR}/config-previous keeps the previous release as the rollback anchor" \
    "lost units, failed: the deployed tree is not snapshotted over the anchor"
  assert_anchors_keep_previous "lost units, failed"
  assert_file_says "${T_COMPOSE_DIR}/docker/edge/nginx.conf" "N edge" \
    "lost units, failed: the live tree stays the deployed release -- N-1 is not restored under it"
  assert_excludes "restoring previous host config" "lost units, failed: no restore is attempted"
  if [[ ! -f "${T_STATE_DIR}/config-apply.incomplete" ]]; then
    record 1 "lost units, failed: no incomplete-apply marker (the tree is one release)"
  else
    record 0 "lost units, failed: no incomplete-apply marker (the tree is one release)"
  fi
  assert_no_deploy_stamp basetool_deploy_last_failure_timestamp "lost units, failed: no DeployFailed stamp"
  assert_health_stamp basetool_deploy_last_health_restart_failed_timestamp yes \
    "lost units, failed: the restore-failed gauge is stamped"
  assert_reapply_record "${MARKER}" 1 "lost units, failed: the backoff record is keyed to the deployed target"

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy --force -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" || rc=$?
  assert_exit 0 "$rc" "lost units, forced: the re-apply succeeds"
  if [[ -f "${T_UNIT_DIR}/backend.container" ]]; then
    record 1 "lost units, forced: the units are back"
  else
    record 0 "lost units, forced: the units are back"
  fi
  assert_anchors_keep_previous "lost units, forced"
  if [[ ! -f "${T_STATE_DIR}/config-apply.incomplete" && ! -f "${T_STATE_DIR}/failed.digests" \
        && ! -f "${T_STATE_DIR}/reapply-failed.digests" ]]; then
    record 1 "lost units, forced: no incomplete-apply marker and no failure record remain"
  else
    record 0 "lost units, forced: no incomplete-apply marker and no failure record remain"
  fi
  rm -rf "${tmp}"
}

reapply_host() {
  anchor_host "$1"
  write_rsync_stub
  seed_release_tree "${T_COMPOSE_DIR}" "N"
  T_BUNDLE="$1/bundle"
  seed_release_tree "${T_BUNDLE}" "N"
  echo "# promoted compose" > "${T_BUNDLE}/docker-compose.yml"
}

DIG_BACKEND_NEXT="$(hexdig beef5)"
MARKER_NEXT="${DIG_BACKEND_NEXT}|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"

seed_rolled_back_next() {
  local now
  now="$(date +%s)"
  printf '%s 1 %d\n' "${MARKER_NEXT}" "$(( now - 60 ))" > "${T_STATE_DIR}/failed.digests"
  mkdir -p "${T_STATE_DIR}/textfile"
  printf '%s\n' \
    "basetool_deploy_last_success_timestamp 1000" \
    "basetool_deploy_last_rollback_timestamp $(( now - 60 ))" \
    "basetool_deploy_last_failure_timestamp 0" \
    "basetool_deploy_last_blocked_timestamp 0" > "${T_STATE_DIR}/textfile/deploy.prom"
}

scenario_reapply_flag_reapplies_the_deployed_release_and_rotates_nothing() {
  echo "Scenario: --reapply re-applies the deployed release N from the marker -- not the tag's N+1 -- and every anchor stays on N-1"
  local tmp rc=0
  tmp="$(mktmp)"
  reapply_host "${tmp}"
  seed_rolled_back_next
  printf '%s 1 %d\n' "${MARKER}" "$(date +%s)" > "${T_STATE_DIR}/reapply-failed.digests"
  mapfile -t fake < <(spi_env)
  run_deploy --reapply -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${T_BUNDLE}" "FAKE_KCSPI_JAR=OLD jar" \
    "FAKE_REMOTE_BACKEND=${DIG_BACKEND_NEXT}" || rc=$?
  assert_exit 0 "$rc" "--reapply: the run succeeds"
  assert_contains "--reapply: target is the deployed release recorded in" "--reapply: the target is read from the marker"
  assert_no_docker "basetool-backend:stable" "--reapply: no tag is resolved (the tag names N+1)"
  assert_contains "the running stack matches the deployed release — re-applying it anyway" \
    "--reapply: a converged stack is re-applied anyway, not a fast exit"
  assert_contains "--reapply asked for it — retrying now" "--reapply: the re-apply backoff does not hold back an explicit --reapply"
  assert_docker "cosign verify ghcr.io/krt-profit/basetool-backend@${DIG_BACKEND}" \
    "--reapply: the deployed digests are signature-verified like any apply"
  assert_contains "--reapply: re-delivering the deployed config bundle" "--reapply: the config bundle is re-delivered"
  assert_contains "${T_STATE_DIR}/config-previous keeps the previous release as the rollback anchor" \
    "--reapply: the live tree is not snapshotted over config-previous/"
  assert_contains "--reapply: the live provider JAR is the deployed release's — not swapped" \
    "--reapply: an identical live JAR is not swapped"
  assert_starts keycloak 0 "--reapply: ...and keycloak is not restarted for it"
  assert_count '^systemctl --user (stop|restart) (db-backend|db-keycloak|redis|keycloak|backend|ingest|frontend)\.service' 0 \
    "--reapply: no stack unit that already matches is stopped or restarted"
  assert_anchors_keep_previous "--reapply"
  assert_pin_binds backend "${DIG_BACKEND}" "--reapply: backend stays bound to N, not to the tag's N+1"
  assert_marker "${MARKER}" "--reapply: the marker still names N"
  assert_contains "deploy successful — the deployed release is re-applied" "--reapply: success is reported as a re-apply"
  assert_health_stamp basetool_deploy_last_stack_healthy_timestamp yes "--reapply: the stack-health heartbeat is stamped"
  if grep -qx 'basetool_deploy_last_success_timestamp 1000' "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "--reapply: no success stamp, so N+1's DeployRolledBack keeps firing"
  else
    record 0 "--reapply: no success stamp, so N+1's DeployRolledBack keeps firing ($(grep success "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null))"
  fi
  assert_failed_record "${MARKER_NEXT}" 1 "--reapply: N+1's release backoff is left as it was"
  if [[ ! -f "${T_STATE_DIR}/reapply-failed.digests" ]]; then
    record 1 "--reapply: the re-apply's own failure record is cleared"
  else
    record 0 "--reapply: the re-apply's own failure record is cleared"
  fi
  rm -rf "${tmp}"
}

scenario_reapply_flag_puts_back_a_differing_jar_without_rotating_its_anchor() {
  echo "Scenario: --reapply puts the deployed JAR back over a live one that differs, restarts keycloak once, and keeps keycloak-spi-previous.jar on N-1"
  local tmp rc=0
  tmp="$(mktmp)"
  reapply_host "${tmp}"
  mapfile -t fake < <(spi_env)
  run_deploy --reapply -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${T_BUNDLE}" || rc=$?
  assert_exit 0 "$rc" "--reapply jar: the run succeeds"
  assert_contains "the live provider JAR differed from the deployed release's — put back" "--reapply jar: the swap is reported"
  assert_file_says "${T_COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar" "NEW jar" "--reapply jar: the deployed JAR is live"
  assert_docker "keycloak-jar-at-start NEW jar" "--reapply jar: keycloak starts on it"
  assert_starts keycloak 1 "--reapply jar: keycloak is started once"
  assert_nothing_down "--reapply jar: nothing is left stopped"
  assert_anchors_keep_previous "--reapply jar"
  rm -rf "${tmp}"
}

scenario_reapply_flag_that_fails_rolls_nothing_back_and_backs_off_short() {
  echo "Scenario: a --reapply whose gate fails rolls nothing back, records a re-apply failure with the short backoff, and leaves N+1's backoff alone"
  local tmp rc=0
  tmp="$(mktmp)"
  reapply_host "${tmp}"
  seed_rolled_back_next
  printf 'frontend\n' > "${T_DOWN}"
  mapfile -t fake < <(spi_env)
  run_deploy --reapply -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${T_BUNDLE}" "FAKE_KCSPI_JAR=OLD jar" \
    "FAKE_REMOTE_BACKEND=${DIG_BACKEND_NEXT}" \
    "FAKE_PS_frontend=" "FAKE_STUCK_UNITS=frontend" "FAKE_STUCK_WITH_JAR=OLD jar" || rc=$?
  assert_exit 1 "$rc" "--reapply fails: the run fails"
  assert_contains "drift: frontend: no container" "--reapply fails: the drift is reported"
  assert_contains "on a re-apply of the deployed release — not rolling back" "--reapply fails: nothing is rolled back"
  assert_excludes "rolled back to previous digest pin" "--reapply fails: no rollback to itself is claimed"
  assert_excludes "rollback ALSO failed" "--reapply fails: ...and none is attempted"
  assert_anchors_keep_previous "--reapply fails"
  assert_pin_binds backend "${DIG_BACKEND}" "--reapply fails: backend stays bound to N"
  assert_marker "${MARKER}" "--reapply fails: the marker still names N"
  assert_contains "recorded re-apply failure #1" "--reapply fails: the failure is recorded as a re-apply failure"
  assert_contains "the next automatic attempt backs off 300s" "--reapply fails: ...with the heal's first backoff, not the release's 600 s"
  assert_reapply_record "${MARKER}" 1 "--reapply fails: the re-apply record is keyed to N"
  assert_failed_record "${MARKER_NEXT}" 1 "--reapply fails: N+1's release backoff is not overwritten"
  assert_health_stamp basetool_deploy_last_health_restart_failed_timestamp yes \
    "--reapply fails: the gauge DeployHealthRestartFailing reads is stamped"
  assert_health_stamp basetool_deploy_last_stack_healthy_timestamp no "--reapply fails: the healthy heartbeat is not stamped"
  if grep -qx 'basetool_deploy_last_failure_timestamp 0' "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "--reapply fails: no DeployFailed stamp (the release had shipped)"
  else
    record 0 "--reapply fails: no DeployFailed stamp (the release had shipped)"
  fi

  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${T_BUNDLE}" "FAKE_KCSPI_JAR=OLD jar" \
    "FAKE_PS_frontend=" "FAKE_STUCK_UNITS=frontend" "FAKE_STUCK_WITH_JAR=OLD jar" || rc=$?
  assert_exit 0 "$rc" "--reapply fails, next tick: a quiet skip"
  assert_contains "s/300s) — skipping this tick" "--reapply fails, next tick: backed off by the 300 s window"
  assert_count '^systemctl --user (start|restart|stop) ' 0 "--reapply fails, next tick: nothing is started while backing off"
  rm -rf "${tmp}"
}

scenario_reapply_flag_is_refused_without_a_deployed_release() {
  echo "Scenario: --reapply is refused on a host with no deployed release, with --tag, and when the pin disagrees with the marker -- before any registry call"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  mapfile -t fake < <(converged_env)
  run_deploy --reapply -- "${fake[@]}" || rc=$?
  assert_exit 1 "$rc" "fresh host: --reapply is refused"
  assert_contains "FATAL: --reapply: no deployed release on this host" "fresh host: the refusal says why"
  assert_contains "Deploy a release instead" "fresh host: ...and what to do instead"
  assert_no_docker "podman login" "fresh host: the registry is not contacted"
  assert_no_docker "skopeo" "fresh host: no tag is resolved"
  assert_no_apply "fresh host: nothing is started"
  if [[ ! -e "${T_STATE_DIR}/textfile/deploy.prom" && ! -e "${T_STATE_DIR}/failed.digests" \
        && ! -e "${T_STATE_DIR}/reapply-failed.digests" ]]; then
    record 1 "fresh host: no metric and no failure record -- a refusal is not a deploy failure"
  else
    record 0 "fresh host: no metric and no failure record -- a refusal is not a deploy failure"
  fi

  write_marker "${MARKER}"
  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy --reapply --tag 1.2.3 -- "${fake[@]}" || rc=$?
  assert_exit 1 "$rc" "with --tag: --reapply is refused"
  assert_contains "cannot be combined with --tag" "with --tag: the refusal says why"
  assert_no_docker "podman login" "with --tag: the registry is not contacted"

  printf 'services:\n  backend:\n    image: ghcr.io/krt-profit/basetool-backend@%s\n' "${DIG_BACKEND_OLD}" \
    > "${T_STATE_DIR}/current-digest-pin.yml"
  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy --reapply -- "${fake[@]}" || rc=$?
  assert_exit 1 "$rc" "pin disagrees: --reapply is refused"
  assert_contains "the host disagrees about what is deployed; refusing to guess" "pin disagrees: the refusal says why"
  assert_no_apply "pin disagrees: nothing is started"
  rm -rf "${tmp}"
}

scenario_deleting_the_marker_still_rotates_the_anchors() {
  echo "Scenario: deleting last-deployed.digests still turns N into a new release and rotates all three anchors onto it (documented; use --reapply)"
  local tmp rc=0
  tmp="$(mktmp)"
  reapply_host "${tmp}"
  rm -f "${T_STATE_DIR}/last-deployed.digests"
  mapfile -t fake < <(spi_env)
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${T_BUNDLE}" || rc=$?
  assert_exit 0 "$rc" "marker deleted: the run succeeds"
  assert_excludes "re-applying" "marker deleted: it is not a re-apply"
  if grep -q "@${DIG_BACKEND}\$" "${T_STATE_DIR}/previous-digest-pin.yml" 2>/dev/null; then
    record 1 "marker deleted: previous-digest-pin.yml now names N, the deployed release"
  else
    record 0 "marker deleted: previous-digest-pin.yml now names N, the deployed release"
  fi
  assert_file_says "${T_STATE_DIR}/config-previous/docker/edge/nginx.conf" "N edge" \
    "marker deleted: config-previous/ now holds N"
  assert_file_says "${T_STATE_DIR}/keycloak-spi-previous.jar" "OLD jar" \
    "marker deleted: keycloak-spi-previous.jar now holds N's JAR"
  rm -rf "${tmp}"
}

scenario_failed_drift_reapply_backs_off_with_the_heal_durations() {
  echo "Scenario: a failed drift re-apply backs off 300 s, doubling, capped at 1 h -- the heal's durations"
  local tmp rc=0
  tmp="$(mktmp)"
  anchor_host "${tmp}"
  printf 'frontend\n' > "${T_DOWN}"
  mapfile -t fake < <(spi_env)
  local -a stuck=("FAKE_PS_frontend=" "FAKE_STUCK_UNITS=frontend" "FAKE_STUCK_WITH_JAR=OLD jar")
  run_deploy -- "${fake[@]}" "${stuck[@]}" || rc=$?
  assert_exit 1 "$rc" "short backoff: the first re-apply fails"
  assert_contains "the next automatic attempt backs off 300s" "short backoff: failure #1 waits 300 s"
  assert_reapply_record "${MARKER}" 1 "short backoff: recorded in reapply-failed.digests"

  : > "${T_DOCKER_LOG}"; rc=0
  run_deploy -- "${fake[@]}" "${stuck[@]}" || rc=$?
  assert_exit 0 "$rc" "short backoff: the next tick is a quiet skip"
  assert_contains "s/300s) — skipping this tick" "short backoff: the first window is 300 s, not the release's 600 s"

  printf '%s 1 %d\n' "${MARKER}" "$(( $(date +%s) - 400 ))" > "${T_STATE_DIR}/reapply-failed.digests"
  : > "${T_DOCKER_LOG}"; rc=0
  run_deploy -- "${fake[@]}" "${stuck[@]}" || rc=$?
  assert_exit 1 "$rc" "short backoff: after 400 s the re-apply is retried (and fails again)"
  assert_contains "backoff of 300s elapsed — retrying" "short backoff: ...because the 300 s window has passed"
  assert_contains "recorded re-apply failure #2" "short backoff: the count goes on"
  assert_contains "the next automatic attempt backs off 600s" "short backoff: failure #2 waits 600 s (doubling)"

  : > "${T_DOCKER_LOG}"; rc=0
  run_deploy -- "${fake[@]}" "${stuck[@]}" || rc=$?
  assert_contains "s/600s) — skipping this tick" "short backoff: the second window is 600 s"

  printf '%s 9 %d\n' "${MARKER}" "$(date +%s)" > "${T_STATE_DIR}/reapply-failed.digests"
  : > "${T_DOCKER_LOG}"; rc=0
  run_deploy -- "${fake[@]}" "${stuck[@]}" || rc=$?
  assert_contains "s/3600s) — skipping this tick" "short backoff: capped at 3600 s, not the release's 21600 s"
  rm -rf "${tmp}"
}

scenario_failed_release_keeps_the_long_backoff() {
  echo "Scenario: a failed RELEASE still backs off 600 s, doubling -- the re-apply durations do not leak into it"
  local tmp rc=0
  tmp="$(mktmp)"
  heal_host "${tmp}"
  mapfile -t fake < <(spi_env)
  local -a next=("FAKE_REMOTE_BACKEND=${DIG_BACKEND_NEXT}" "FAKE_UNHEALTHY_DIGEST=${DIG_BACKEND_NEXT}")
  run_deploy -- "${fake[@]}" "${next[@]}" || rc=$?
  assert_exit 1 "$rc" "long backoff: the release fails and rolls back"
  assert_contains "rolled back to previous digest pin successfully" "long backoff: ...to N"
  assert_failed_record "${MARKER_NEXT}" 1 "long backoff: recorded in failed.digests"
  if [[ ! -f "${T_STATE_DIR}/reapply-failed.digests" ]]; then
    record 1 "long backoff: a release failure writes no re-apply record"
  else
    record 0 "long backoff: a release failure writes no re-apply record"
  fi

  : > "${T_DOCKER_LOG}"; rc=0
  run_deploy -- "${fake[@]}" "${next[@]}" || rc=$?
  assert_exit 0 "$rc" "long backoff: the next tick is a quiet skip"
  assert_contains "s/600s) — skipping this tick" "long backoff: the first window is 600 s"

  printf '%s 2 %d\n' "${MARKER_NEXT}" "$(date +%s)" > "${T_STATE_DIR}/failed.digests"
  : > "${T_DOCKER_LOG}"; rc=0
  run_deploy -- "${fake[@]}" "${next[@]}" || rc=$?
  assert_contains "s/1200s) — skipping this tick" "long backoff: the second window is 1200 s"
  rm -rf "${tmp}"
}

scenario_reapply_flag_reapplies_the_deployed_release_and_rotates_nothing
scenario_reapply_flag_puts_back_a_differing_jar_without_rotating_its_anchor
scenario_reapply_flag_that_fails_rolls_nothing_back_and_backs_off_short
scenario_reapply_flag_is_refused_without_a_deployed_release
scenario_deleting_the_marker_still_rotates_the_anchors
scenario_failed_drift_reapply_backs_off_with_the_heal_durations
scenario_failed_release_keeps_the_long_backoff

scenario_spi_and_apps_move_in_one_restart_window
scenario_apps_only_restart_each_unit_once
scenario_spi_only_is_the_whole_apply
scenario_spi_gate_failure_rolls_back_images_and_jar
scenario_spi_app_failure_rolls_back_both_and_says_it_cannot_tell
scenario_spi_only_failure_blames_the_jar
scenario_spi_rollback_that_does_not_heal_says_so
scenario_spi_extraction_failure_is_recorded

scenario_heal_unhealthy_backend_restarts_it_and_its_dependents_once
scenario_heal_unhealthy_backend_and_frontend_starts_frontend_once
scenario_heal_unhealthy_frontend_touches_nothing_it_requires
scenario_heal_that_fails_is_recorded_after_the_wait
scenario_missing_frontend_and_ingest_are_started_not_restarted
scenario_missing_backend_is_started_not_restarted
scenario_mixed_drift_does_not_stamp_an_unhealthy_stack_healthy

scenario_reapply_that_fails_keeps_the_anchors_and_rolls_nothing_back
scenario_reapply_that_succeeds_keeps_the_anchors
scenario_release_after_a_reapply_rotates_the_anchor_to_the_deployed_release
scenario_reapply_of_lost_units_keeps_config_previous

scenario_config_mirror_failure_is_recorded_and_undone
scenario_config_restore_failure_keeps_the_anchor
scenario_pull_failure_after_config_apply_is_undone
scenario_config_preflight_refuses_an_unwritable_subtree
scenario_carve_out_leaves_no_pin_behind

scenario_podman_bundle_installs_the_units
scenario_podman_retired_unit_is_stopped_then_removed
scenario_podman_first_deploy_fills_an_empty_unit_dir
scenario_podman_runs_without_a_compose_file
scenario_podman_changed_monitoring_and_acme_units_are_restarted
scenario_podman_acme_is_part_of_the_stack
scenario_podman_keystore_checked_where_the_unit_mounts_it
scenario_podman_missing_mounted_keystore_refuses
scenario_podman_missing_per_service_keystore_refuses
scenario_podman_missing_edge_trust_anchor_refuses
scenario_podman_runs_podman_from_root_dir

echo
if [[ "$tests_failed" -eq 0 ]]; then
  echo "All ${tests_run} deploy.sh tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} deploy.sh test(s) failed."
exit 1
