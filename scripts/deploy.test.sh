#!/usr/bin/env bash
#
# Regression tests for scripts/deploy.sh's decision logic — the idempotence
# fast-exit, the running-stack drift verification behind it, and their
# interplay with the bad-digest backoff and the rollback path.
#
# The script under test is run against a STUBBED `docker` CLI (and a no-op
# `flock`) placed first on PATH, so no daemon, no registry and no compose
# stack are needed — pure bash, runs in a couple of seconds. The stub records
# every invocation into a log file and answers from FAKE_* environment
# variables, which each scenario sets to model a specific host state.
#
# Usage:
#   scripts/deploy.test.sh
#
# The headline case is the 2026-07-02 production incident: the stack had been
# brought up manually WITHOUT the digest-pin overlay, so compose started an
# outdated build from the stale local `:stable` tag against a newer database.
# deploy.sh compared the GHCR target digests with its idempotence marker,
# reported "no change" and exited 0 — leaving the crash-looping stack alone.
# The drift verification must catch exactly this and fall through to a
# re-apply instead.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="${SCRIPT_DIR}/deploy.sh"

if [[ ! -f "${DEPLOY}" ]]; then
  echo "FATAL: script under test not found at ${DEPLOY}" >&2
  exit 1
fi

tests_run=0
tests_failed=0

# The five digests the stub registry resolves `:stable` to, and the matching
# local RepoDigest entries of a converged stack. Values are shaped like real
# digests but deliberately readable.
DIG_BACKEND="sha256:backend-current"
DIG_FRONTEND="sha256:frontend-current"
DIG_INGEST="sha256:ingest-current"
DIG_CONFIG="sha256:config-current"
DIG_KCSPI="sha256:kcspi-current"
MARKER="${DIG_BACKEND}|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"

REPO_BACKEND="ghcr.io/krt-profit/basetool-backend@${DIG_BACKEND}"
REPO_FRONTEND="ghcr.io/krt-profit/basetool-frontend@${DIG_FRONTEND}"
REPO_INGEST="ghcr.io/krt-profit/basetool-ingest@${DIG_INGEST}"

# Creates a throwaway temp directory and prints its absolute path. Each
# scenario gets its own so they cannot interfere with one another.
mktmp() {
  mktemp -d "${TMPDIR:-/tmp}/deploy-sh-test.XXXXXX"
}

# Builds a complete fake host layout under $1: compose dir with a dummy
# compose file and .env, state dir, GHCR token, keystore, the stub `docker`
# and `flock` binaries, and an empty invocation log. Exports the per-scenario
# path variables the runner and the assertions use.
setup_host() {
  local tmp="$1"
  T_COMPOSE_DIR="${tmp}/code"
  T_STATE_DIR="${tmp}/state"
  T_FAKE_BIN="${tmp}/bin"
  T_DOCKER_LOG="${tmp}/docker-invocations.log"
  T_TOKEN="${tmp}/ghcr-token"
  T_LOCK="${tmp}/deploy.lock"

  mkdir -p "${T_COMPOSE_DIR}" "${T_STATE_DIR}" "${T_FAKE_BIN}"
  echo "# dummy compose file — never parsed, the docker CLI is stubbed" \
    > "${T_COMPOSE_DIR}/docker-compose.yml"
  printf 'IRI_KEYSTORE_HOST_PATH=%s/keystore.p12\n' "${tmp}" > "${T_COMPOSE_DIR}/.env"
  : > "${tmp}/keystore.p12"
  echo "fake-token" > "${T_TOKEN}"
  : > "${T_DOCKER_LOG}"

  # Stub docker CLI. Dispatches on the subcommand, records every invocation,
  # and answers from FAKE_* environment variables (container ids are cid-<key>,
  # <key> is the lookup suffix):
  #   FAKE_PS_<svc>            container id(s) `compose ps -aq <svc>` prints
  #   FAKE_STATE_<key>         rendered state template, e.g. running/healthy
  #   FAKE_ONEOFF_<key>        com.docker.compose.oneoff label value (True/…)
  #   FAKE_REPODIGESTS_<key>   space-joined RepoDigests of the running image
  #   FAKE_UP_RC               exit code of `compose up` (default 0)
  cat > "${T_FAKE_BIN}/docker" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'docker %s\n' "$*" >> "${FAKE_DOCKER_LOG}"

lookup() { # lookup <prefix> <name> — indirect ${<prefix>_<name>} with empty default
  local var="$1_$2"
  printf '%s' "${!var:-}"
}

case "${1:-}" in
  login)
    cat > /dev/null
    exit 0
    ;;
  buildx)
    # buildx imagetools inspect <ref> --format '{{.Manifest.Digest}}'
    case "${4:-}" in
      *basetool-backend:*) echo "${FAKE_REMOTE_BACKEND}" ;;
      *basetool-frontend:*) echo "${FAKE_REMOTE_FRONTEND}" ;;
      *basetool-ingest:*) echo "${FAKE_REMOTE_INGEST}" ;;
      *basetool-config:*) echo "${FAKE_REMOTE_CONFIG}" ;;
      *basetool-keycloak-spi:*) echo "${FAKE_REMOTE_KCSPI}" ;;
      *) exit 1 ;;
    esac
    ;;
  compose)
    sub=""
    for a in "$@"; do
      case "$a" in
        version | ps | pull | up) sub="$a"; break ;;
      esac
    done
    case "${sub}" in
      version) echo "2.29.0" ;;
      ps)
        svc="${!#}"
        val="$(lookup FAKE_PS "${svc}")"
        if [[ -n "${val}" ]]; then
          printf '%s\n' "${val}"
        fi
        ;;
      pull) exit "${FAKE_PULL_RC:-0}" ;;
      up) exit "${FAKE_UP_RC:-0}" ;;
      *) exit 0 ;;
    esac
    ;;
  inspect)
    # inspect --format <fmt> <cid>; container ids are cid-<key>
    svc="${4#cid-}"
    case "${3:-}" in
      *'.State.Status'*)
        # The drift probe renders "<oneoff-label>|<status>/<health>".
        val="$(lookup FAKE_STATE "${svc}")"
        printf '%s|%s\n' "$(lookup FAKE_ONEOFF "${svc}")" "${val:-running/healthy}"
        ;;
      '{{.Image}}')
        printf 'img-%s\n' "${svc}"
        ;;
      *) exit 1 ;;
    esac
    ;;
  image)
    case "${2:-}" in
      inspect)
        # image inspect --format '{{join .RepoDigests " "}}' img-<svc>
        printf '%s\n' "$(lookup FAKE_REPODIGESTS "${5#img-}")"
        ;;
      prune) exit 0 ;;
      *) exit 1 ;;
    esac
    ;;
  create | rm) exit 0 ;;
  cp)
    # docker cp <cid>:/config/. <dest>/ — when a scenario supplies a fixture bundle,
    # populate the extraction target from it; otherwise a no-op like create/rm.
    if [[ -n "${FAKE_CONFIG_BUNDLE:-}" && "${2:-}" == *:/config/. ]]; then
      cp -R "${FAKE_CONFIG_BUNDLE}/." "${3%/}/" 2>/dev/null || true
    fi
    exit 0
    ;;
  *) exit 1 ;;
esac
FAKE
  chmod +x "${T_FAKE_BIN}/docker"

  # No-op flock so the test needs no util-linux (and the real one, where it
  # exists, is not exercised — locking is not under test here).
  printf '#!/usr/bin/env bash\nexit 0\n' > "${T_FAKE_BIN}/flock"
  chmod +x "${T_FAKE_BIN}/flock"

  # MSYS/NTFS hosts (noacl mounts, e.g. Git Bash on Windows) cannot chmod,
  # which makes coreutils `install -m` fail. Mode bits are irrelevant to the
  # decision logic under test, so on such hosts only, shim `install` to drop
  # the `-m <mode>` pair and delegate to the real binary. On Linux (CI) the
  # probe succeeds and the real install is used untouched.
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

# Writes the idempotence marker for the scenario's state dir.
write_marker() {
  echo "$1" > "${T_STATE_DIR}/last-deployed.digests"
}

# Runs deploy.sh against the scenario host with the FAKE_* variables given as
# extra VAR=VALUE arguments after `--`. Output lands in LAST_OUTPUT, the exit
# code is returned. The remote registry answers with the current digest set
# unless a scenario overrides it.
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
  LAST_OUTPUT="$(
    env \
      PATH="${T_FAKE_BIN}:${PATH}" \
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
      "${extra_env[@]}" \
      bash "${DEPLOY}" "${script_args[@]}" 2>&1
  )" || rc=$?
  return "${rc}"
}

# The FAKE_* set describing a fully converged, healthy stack. Scenarios start
# from this and override single aspects.
converged_env() {
  printf '%s\n' \
    "FAKE_PS_backend=cid-backend" \
    "FAKE_PS_frontend=cid-frontend" \
    "FAKE_PS_ingest=cid-ingest" \
    "FAKE_REPODIGESTS_backend=${REPO_BACKEND}" \
    "FAKE_REPODIGESTS_frontend=${REPO_FRONTEND}" \
    "FAKE_REPODIGESTS_ingest=${REPO_INGEST}"
}

# Records a passed/failed assertion with a description; dumps LAST_OUTPUT and
# the stub-docker invocation log on failure so a red test is self-diagnosing.
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
    echo "----- docker invocations -----"
    cat "${T_DOCKER_LOG}" 2>/dev/null || true
    echo "-------------------------------"
  fi
}

# assert_exit <expected-rc> <actual-rc> <description>.
assert_exit() {
  local expected="$1" actual="$2" desc="$3"
  if [[ "$actual" -eq "$expected" ]]; then
    record 1 "${desc} (exit ${expected})"
  else
    record 0 "${desc} (expected exit ${expected}, got ${actual})"
  fi
}

# assert_contains <substring> <description> — fails unless LAST_OUTPUT
# contains the substring.
assert_contains() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" == *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output missing: '${needle}')"
  fi
}

# assert_excludes <substring> <description> — fails if LAST_OUTPUT contains it.
assert_excludes() {
  local needle="$1" desc="$2"
  if [[ "$LAST_OUTPUT" != *"$needle"* ]]; then
    record 1 "$desc"
  else
    record 0 "$desc (output unexpectedly contained: '${needle}')"
  fi
}

# assert_docker <substring> <description> — fails unless the stub recorded an
# invocation containing the substring.
assert_docker() {
  local needle="$1" desc="$2"
  if grep -qF "$needle" "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (no docker invocation matching: '${needle}')"
  fi
}

# assert_no_docker <substring> <description> — fails if the stub recorded one.
assert_no_docker() {
  local needle="$1" desc="$2"
  if ! grep -qF "$needle" "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (unexpected docker invocation matching: '${needle}')"
  fi
}

# ---------------------------------------------------------------------------
# Scenario 1: marker matches AND the running stack is converged — the genuine
# no-op. Must keep the fast exit and must not pull or restart anything.
# ---------------------------------------------------------------------------
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
  assert_no_docker " pull " "nothing is pulled"
  assert_no_docker " up " "nothing is restarted"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 2 (the incident): marker matches but the backend container runs an
# OUTDATED image — e.g. started manually off the stale local :stable tag.
# Must report the drift and re-apply the pinned digest set.
# ---------------------------------------------------------------------------
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
  assert_docker " up -d" "the stack is re-applied via compose up"
  if [[ ! -f "${T_STATE_DIR}/failed.digests" ]]; then
    record 1 "no failure is recorded for a successful re-apply"
  else
    record 0 "no failure is recorded for a successful re-apply (failed.digests exists)"
  fi
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 3: marker matches but the backend container is unhealthy (crash
# loop). Must be treated as drift, not as "no change".
# ---------------------------------------------------------------------------
scenario_unhealthy_drift() {
  echo "Scenario: marker matches, backend unhealthy (must re-apply)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_STATE_backend=restarting/unhealthy" || rc=$?
  assert_exit 0 "$rc" "unhealthy-stack re-apply succeeds"
  assert_contains "drift: backend: container state restarting/unhealthy" \
    "the unhealthy state is reported as drift"
  assert_docker " up -d" "the stack is re-applied"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 4: marker matches but a service has NO container at all (stack
# half-down). Every missing service is reported; the run re-applies.
# ---------------------------------------------------------------------------
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
  assert_docker " up -d" "the stack is re-applied"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 5: marker DIFFERS (a new promotion) — the normal deploy path. The
# drift verification must not run and must not log drift lines.
# ---------------------------------------------------------------------------
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
  assert_docker " up -d" "the stack is applied"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 6: --check-only over a drifted stack. Must report that it WOULD
# re-apply, and must not pull or restart anything.
# ---------------------------------------------------------------------------
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
  assert_no_docker " pull " "check-only pulls nothing"
  assert_no_docker " up " "check-only restarts nothing"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 7: drifted stack whose target is in the bad-digest backoff window.
# The drift re-apply must respect the backoff instead of flapping every tick.
# ---------------------------------------------------------------------------
scenario_drift_respects_backoff() {
  echo "Scenario: drifted stack, target in backoff window (must skip)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  printf '%s 1 %d\n' "${MARKER}" "$(date +%s)" > "${T_STATE_DIR}/failed.digests"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "FAKE_PS_backend=" || rc=$?
  assert_exit 0 "$rc" "backed-off drift tick exits 0"
  assert_contains "drift: backend: no container" "the drift is still reported"
  assert_contains "in backoff window" "the re-apply is throttled by the backoff"
  assert_no_docker " up " "nothing is restarted during the backoff window"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 8: drift re-apply whose health gate fails. Must roll back, record
# the failure for the backoff, and exit non-zero.
# ---------------------------------------------------------------------------
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
  assert_contains "recorded health-check failure #1" "the failure feeds the backoff"
  if grep -qF "${MARKER} 1 " "${T_STATE_DIR}/failed.digests" 2>/dev/null; then
    record 1 "failed.digests records the target marker with count 1"
  else
    record 0 "failed.digests records the target marker with count 1"
  fi
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 9: a leftover one-off `docker compose run` container (exited debug
# shell / ad-hoc job) sits next to the healthy replica. `ps -aq` lists it, but
# it is not part of the deployed stack and must not defeat the fast exit.
# ---------------------------------------------------------------------------
scenario_oneoff_ignored() {
  echo "Scenario: leftover one-off run container next to healthy replica (must fast-exit)"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "FAKE_PS_backend=cid-backend"$'\n'"cid-backend_oneoff" \
    "FAKE_ONEOFF_backend_oneoff=True" \
    "FAKE_STATE_backend_oneoff=exited/no-healthcheck" || rc=$?
  assert_exit 0 "$rc" "one-off leftover does not defeat the fast exit"
  assert_contains "(running stack verified)" "the stack still counts as converged"
  assert_excludes "drift:" "the one-off is not reported as drift"
  assert_no_docker " up " "nothing is restarted"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 10: a container still inside its healthcheck start period
# (running/starting — e.g. restart policies bringing the stack up after a host
# reboot while a tick fires) counts as converged for this tick; re-applying
# would race the start-up and could record a false backoff failure. A stale
# image must STILL drift regardless of the start period.
# ---------------------------------------------------------------------------
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
  assert_no_docker " up " "no re-apply races the start-up"
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
  assert_docker " up -d" "the stack is re-applied"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 11: a config-bundle change that touches ONLY the prometheus slice of
# the monitoring tree, monitoring enabled. After the health-gated app `up` and
# the non-gating monitoring `up -d`, deploy.sh must SIGHUP-reload ONLY the
# service whose slice changed (prometheus); Alloy and blackbox, whose slices are
# byte-identical across the apply, must be left running untouched.
# ---------------------------------------------------------------------------
scenario_monitoring_config_reload() {
  echo "Scenario: config change touches only prometheus/ → reload prometheus, not alloy/blackbox"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  echo "# dummy monitoring compose" > "${T_COMPOSE_DIR}/docker-compose.monitoring.yml"
  # The live (pre-apply) monitoring tree — snapshotted as the diff baseline.
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  echo "old" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  # The promoted config bundle: prometheus.yml differs, alloy/blackbox identical.
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}/monitoring/prometheus" \
    "${bundle}/monitoring/alloy" "${bundle}/monitoring/blackbox"
  echo "# dummy compose file" > "${bundle}/docker-compose.yml"
  echo "# dummy monitoring compose" > "${bundle}/docker-compose.monitoring.yml"
  echo "new" > "${bundle}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${bundle}/monitoring/alloy/config.alloy"
  echo "same" > "${bundle}/monitoring/blackbox/blackbox.yml"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "IRI_MONITORING_ENABLED=true" \
    "FAKE_CONFIG_BUNDLE=${bundle}" \
    "FAKE_REMOTE_CONFIG=sha256:config-next" || rc=$?
  assert_exit 0 "$rc" "config-change deploy with a monitoring reload succeeds"
  assert_docker "monitoring.yml up -d" "the monitoring stack is reconciled"
  assert_docker "kill -s SIGHUP prometheus" "prometheus is SIGHUP-reloaded (its slice changed)"
  assert_no_docker "kill -s SIGHUP alloy" "alloy is left alone (its slice is unchanged)"
  assert_no_docker "kill -s SIGHUP blackbox-exporter" "blackbox is left alone (its slice is unchanged)"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 12: monitoring enabled, a normal image re-apply but NO config-bundle
# change. The monitoring stack must still be reconciled, but nothing may be
# SIGHUP-reloaded — the reload is gated on a config tree actually being swapped
# in this tick, so a plain 5-minute app tick never bounces the monitoring config.
# ---------------------------------------------------------------------------
scenario_monitoring_reload_gated_on_config_change() {
  echo "Scenario: monitoring enabled, no config change → reconcile but no SIGHUP reload"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  echo "# dummy monitoring compose" > "${T_COMPOSE_DIR}/docker-compose.monitoring.yml"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "IRI_MONITORING_ENABLED=true" \
    "FAKE_REPODIGESTS_backend=ghcr.io/krt-profit/basetool-backend@sha256:backend-stale" || rc=$?
  assert_exit 0 "$rc" "monitoring-enabled deploy without a config change succeeds"
  assert_docker "monitoring.yml up -d" "the monitoring stack is still reconciled"
  assert_no_docker "kill -s SIGHUP" "no service is reloaded when the config tree did not change"
  rm -rf "${tmp}"
}

scenario_converged_noop
scenario_stale_image_drift
scenario_unhealthy_drift
scenario_missing_container_drift
scenario_new_promotion
scenario_check_only_drift
scenario_drift_respects_backoff
scenario_drift_reapply_fails
scenario_oneoff_ignored
scenario_starting_grace
scenario_monitoring_config_reload
scenario_monitoring_reload_gated_on_config_change

echo
if [[ "$tests_failed" -eq 0 ]]; then
  echo "All ${tests_run} deploy.sh tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} deploy.sh test(s) failed."
exit 1
