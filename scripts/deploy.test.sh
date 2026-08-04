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
  ps)
    # Top-level `docker ps --filter label=com.docker.compose.project=iri-monitoring
    # --format '{{.Names}}'` — model whether the monitoring compose project has running
    # containers via FAKE_MON_PS (a container name means "running"; empty/unset means none).
    if [[ "$*" == *"com.docker.compose.project=iri-monitoring"* && -n "${FAKE_MON_PS:-}" ]]; then
      printf '%s\n' "${FAKE_MON_PS}"
    fi
    exit 0
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

  # Stub cosign for the host-side signature gate (REQ-OPS-015). Records the
  # invocation and exits FAKE_COSIGN_RC (default 0 = signature trusted); a
  # scenario sets FAKE_COSIGN_RC=1 to model a verification failure (a :stable
  # tag moved to an untrusted digest). Placed first on PATH like `docker` so the
  # real cosign (absent on the test runner) is never exercised.
  cat > "${T_FAKE_BIN}/cosign" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'cosign %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
exit "${FAKE_COSIGN_RC:-0}"
FAKE
  chmod +x "${T_FAKE_BIN}/cosign"

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
  assert_no_docker "cosign verify" "no signature verification on the steady-state no-op"
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
# Scenario 3: marker matches, backend on the TARGET image but unhealthy — a
# RUNTIME fault on the deployed release, not a wrong release (the 2026-07-09
# native-thread incident shape). Must take the targeted-restart path (restart
# ONLY the affected service, no re-pull, no signature re-verify) and must NOT
# roll the release back or write a DeployRolledBack metric.
# ---------------------------------------------------------------------------
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
  assert_docker "up -d --no-deps --force-recreate" "only the affected service is force-recreated"
  assert_contains "health drift resolved" "the targeted restart is reported resolved"
  assert_excludes "re-applying" "the full re-apply path is NOT taken for a health-only drift"
  assert_excludes "rolling back" "no release rollback happens"
  assert_no_docker "cosign verify" "a targeted restart does not re-verify signatures"
  assert_no_docker " pull " "a targeted restart does not re-pull images"
  if [[ ! -f "${T_STATE_DIR}/textfile/deploy.prom" ]] \
     || ! grep -q 'basetool_deploy_last_rollback_timestamp [1-9]' \
            "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "no false DeployRolledBack metric is written for a runtime-health blip"
  else
    record 0 "no false DeployRolledBack metric is written for a runtime-health blip"
  fi
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 3b: the targeted restart of the unhealthy at-target service FAILS to
# restore health. Must record a distinct health-restart-failed signal (NOT a
# deploy rollback), feed its own backoff, and exit non-zero — the truthful
# "runtime is broken on the deployed release" state, never a false DeployRolledBack.
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# Scenario 3c: an unhealthy at-target service whose targeted restart already
# failed and is inside its restart-backoff window must SKIP the tick (exit
# non-zero) rather than force-recreate the container every 5 minutes.
# ---------------------------------------------------------------------------
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
  assert_no_docker " up " "nothing is restarted during the health-restart backoff window"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 3d: a drift report that mixes a runtime-health divergence (backend
# unhealthy on the target image) with a STRUCTURAL one (ingest on a stale image)
# must take the full apply/rollback path — a structural mismatch outranks the
# targeted-restart shortcut, since a wrong release must be corrected first.
# ---------------------------------------------------------------------------
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
  assert_docker "cosign verify" "check-only runs the signature preflight"
  assert_contains "all signatures verified OK" "check-only reports the signatures verified"
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
# the non-gating monitoring `up -d`, deploy.sh must FORCE-RECREATE ONLY the
# service whose slice changed (prometheus) — re-resolving its inode-pinned
# single-file config mount; Alloy and blackbox, whose slices are byte-identical
# across the apply, must be left running untouched.
# ---------------------------------------------------------------------------
scenario_monitoring_config_reload() {
  echo "Scenario: config change touches only prometheus/ → recreate prometheus, not alloy/blackbox"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  echo "# dummy monitoring compose" > "${T_COMPOSE_DIR}/docker-compose.monitoring.yml"
  # The live (pre-apply) monitoring tree — snapshotted as the diff baseline. The changed
  # prometheus.yml deliberately differs in BYTE LENGTH from the bundle's version below:
  # mirror_dir uses rsync on Linux (CI), whose size+mtime quick-check would skip a same-size,
  # same-second edit as "unchanged" and never apply it — so the on-disk config would not
  # actually change and the reload would (correctly) not fire. Windows uses the cp -R fallback,
  # which always copies. Different lengths guarantee the edit lands on every host.
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  echo "scrape_interval: 30s" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  # The promoted config bundle: prometheus.yml differs, alloy/blackbox identical.
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}/monitoring/prometheus" \
    "${bundle}/monitoring/alloy" "${bundle}/monitoring/blackbox"
  echo "# dummy compose file" > "${bundle}/docker-compose.yml"
  echo "# dummy monitoring compose" > "${bundle}/docker-compose.monitoring.yml"
  echo "scrape_interval: 15s  # bumped" > "${bundle}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${bundle}/monitoring/alloy/config.alloy"
  echo "same" > "${bundle}/monitoring/blackbox/blackbox.yml"
  # Seed the reload baseline as if a prior tick had already converged the running monitoring config
  # to the LIVE (pre-apply) tree, so only the slice the bundle actually changes (prometheus) drifts.
  mkdir -p "${T_STATE_DIR}/monitoring-reload"
  cp -R "${T_COMPOSE_DIR}/monitoring/prometheus" "${T_STATE_DIR}/monitoring-reload/prometheus"
  cp -R "${T_COMPOSE_DIR}/monitoring/alloy" "${T_STATE_DIR}/monitoring-reload/alloy"
  cp -R "${T_COMPOSE_DIR}/monitoring/blackbox" "${T_STATE_DIR}/monitoring-reload/blackbox-exporter"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" \
    "IRI_MONITORING_ENABLED=true" \
    "FAKE_CONFIG_BUNDLE=${bundle}" \
    "FAKE_REMOTE_CONFIG=sha256:config-next" || rc=$?
  assert_exit 0 "$rc" "config-change deploy with a monitoring recreate succeeds"
  assert_docker "monitoring.yml up -d" "the monitoring stack is reconciled"
  assert_docker "force-recreate --no-deps prometheus" "prometheus is force-recreated (its slice changed)"
  assert_no_docker "force-recreate --no-deps alloy" "alloy is left alone (its slice is unchanged)"
  assert_no_docker "force-recreate --no-deps blackbox-exporter" "blackbox is left alone (its slice is unchanged)"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 12: monitoring enabled, a normal image re-apply, and the running
# monitoring config already MATCHES the last applied snapshot (steady state).
# The monitoring stack is reconciled (up -d) but nothing is force-recreated — a
# plain tick over a converged monitoring config must never bounce it.
# ---------------------------------------------------------------------------
scenario_monitoring_reload_no_drift() {
  echo "Scenario: monitoring enabled, config already converged → reconcile but no recreate"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  echo "# dummy monitoring compose" > "${T_COMPOSE_DIR}/docker-compose.monitoring.yml"
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  echo "scrape_interval: 30s" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  # Seed the reload baseline to MATCH the on-disk tree exactly → no drift, so no service is signalled.
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
  assert_docker "monitoring.yml up -d" "the monitoring stack is still reconciled"
  assert_no_docker "--force-recreate" "no service is recreated when on-disk matches the applied snapshot"
  if grep -q '^basetool_monitoring_reconcile_disabled{component="deploy"} 0' \
       "${T_STATE_DIR}/textfile/monitoring-reconcile.prom" 2>/dev/null; then
    record 1 "the reconcile-disabled gauge is 0 when the reconcile is enabled and runs"
  else
    record 0 "the reconcile-disabled gauge is 0 when the reconcile is enabled and runs"
  fi
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 12b: the 2026-07-11 ingest TargetDown regression. A converged no-op
# (marker matches, stack healthy) with monitoring enabled, but the on-disk
# Prometheus config differs from the snapshot Prometheus was last recreated for —
# a prior tick's recreate was lost, or a rollback bypassed it. Even on the
# fast-exit no-op path deploy.sh must SELF-HEAL: force-recreate prometheus so the
# committed config lands (re-resolving its inode-pinned single-file mount),
# WITHOUT pulling or re-applying the app stack; alloy/blackbox, already converged,
# stay untouched.
# ---------------------------------------------------------------------------
scenario_monitoring_reload_self_heals_on_noop() {
  echo "Scenario: converged no-op but Prometheus config drifted → self-healing recreate on the fast exit"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  echo "# dummy monitoring compose" > "${T_COMPOSE_DIR}/docker-compose.monitoring.yml"
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  # On-disk carries the committed post-ADR-0090 target (11272); alloy/blackbox are already converged.
  echo "  - targets: [ingest:11272]" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  # The running Prometheus was last reloaded for the STALE (pre-ADR-0090) 11262 target.
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
  assert_no_docker " pull " "the app stack is not pulled on the fast exit"
  assert_no_docker "profile prod up" "the app stack is not re-applied on the fast exit"
  assert_docker "monitoring.yml up -d" "the monitoring compose-definition reconcile (up -d) also runs on the fast exit"
  assert_docker "force-recreate --no-deps prometheus" "the stale Prometheus config is self-healed (force-recreate)"
  assert_no_docker "force-recreate --no-deps alloy" "alloy is already converged — left alone"
  assert_no_docker "force-recreate --no-deps blackbox-exporter" "blackbox is already converged — left alone"
  if grep -q '^basetool_monitoring_config_applied_timestamp{component="prometheus"} [1-9]' \
       "${T_STATE_DIR}/textfile/monitoring-config.prom" 2>/dev/null; then
    record 1 "the config-applied timestamp metric is emitted for PrometheusConfigStale"
  else
    record 0 "the config-applied timestamp metric is emitted for PrometheusConfigStale"
  fi
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 12d (2026-07-17): the compose-definition apply gap. A converged no-op
# tick with monitoring enabled and the config subtree already converged (no
# force-recreate) must STILL run the plain monitoring `up -d` on the fast-exit
# path, so a docker-compose.monitoring.yml DEFINITION drift (a service's
# mem_limit / environment / volumes / image pin — invisible to the per-subtree
# config-FILE diff) is applied per-service by compose. Before this, reconcile
# only force-recreated on config-FILE drift, so an alloy 256M->384M limit change
# (and a cadvisor mount change) never reached the running container on a quiet
# host — the mem_limit stayed stale for days while disk said 384M.
# ---------------------------------------------------------------------------
scenario_monitoring_compose_def_applied_on_noop() {
  echo "Scenario: converged no-op, monitoring enabled, config converged → plain up -d still reconciles compose-def drift"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  echo "# dummy monitoring compose" > "${T_COMPOSE_DIR}/docker-compose.monitoring.yml"
  mkdir -p "${T_COMPOSE_DIR}/monitoring/prometheus" \
    "${T_COMPOSE_DIR}/monitoring/alloy" "${T_COMPOSE_DIR}/monitoring/blackbox"
  echo "scrape_interval: 30s" > "${T_COMPOSE_DIR}/monitoring/prometheus/prometheus.yml"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/alloy/config.alloy"
  echo "same" > "${T_COMPOSE_DIR}/monitoring/blackbox/blackbox.yml"
  # Baseline MATCHES the on-disk tree exactly → no config-subtree drift → no force-recreate expected.
  mkdir -p "${T_STATE_DIR}/monitoring-reload"
  cp -R "${T_COMPOSE_DIR}/monitoring/prometheus" "${T_STATE_DIR}/monitoring-reload/prometheus"
  cp -R "${T_COMPOSE_DIR}/monitoring/alloy" "${T_STATE_DIR}/monitoring-reload/alloy"
  cp -R "${T_COMPOSE_DIR}/monitoring/blackbox" "${T_STATE_DIR}/monitoring-reload/blackbox-exporter"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  run_deploy -- "${fake[@]}" "IRI_MONITORING_ENABLED=true" || rc=$?
  assert_exit 0 "$rc" "the converged no-op tick exits 0"
  assert_contains "no change" "it is still the idempotence no-op for the app stack"
  assert_no_docker " pull " "the app stack is not pulled on the fast exit"
  assert_no_docker "profile prod up" "the app stack is not re-applied on the fast exit"
  assert_docker "monitoring.yml up -d" "the monitoring compose-definition reconcile (up -d) runs on the converged no-op"
  assert_no_docker "--force-recreate" "no service is force-recreated when the config subtree is converged"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 12c: the 2026-07-13 blind-spot. The iri-monitoring stack is RUNNING
# but IRI_MONITORING_ENABLED is unset, so deploy.sh's monitoring reconcile is
# gated off — on-disk config changes silently never reach the running Prometheus,
# and PrometheusConfigStale cannot see it (its applied-stamp series is written
# only from inside the gated reconcile). deploy.sh must make it LOUD: a per-tick
# WARN and the self-standing basetool_monitoring_reconcile_disabled gauge = 1
# (the signal MonitoringReconcileDisabled fires on), without recreating anything.
# ---------------------------------------------------------------------------
scenario_monitoring_reconcile_disabled_when_running() {
  echo "Scenario: monitoring stack running but IRI_MONITORING_ENABLED unset → WARN + reconcile-disabled gauge=1"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  # No IRI_MONITORING_ENABLED (defaults to false); FAKE_MON_PS models a running iri-monitoring project.
  run_deploy -- "${fake[@]}" "FAKE_MON_PS=prometheus" || rc=$?
  assert_exit 0 "$rc" "the gated-but-running no-op tick still exits 0"
  assert_contains "no change" "it is still the idempotence no-op"
  assert_contains "iri-monitoring is RUNNING but IRI_MONITORING_ENABLED != 'true'" \
    "the gated-off-but-running condition is logged as a loud WARN"
  assert_no_docker "--force-recreate" "nothing is recreated while the reconcile is gated off"
  if grep -q '^basetool_monitoring_reconcile_disabled{component="deploy"} 1' \
       "${T_STATE_DIR}/textfile/monitoring-reconcile.prom" 2>/dev/null; then
    record 1 "the reconcile-disabled gauge is 1 (MonitoringReconcileDisabled can fire)"
  else
    record 0 "the reconcile-disabled gauge is 1 (MonitoringReconcileDisabled can fire)"
  fi
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 12d: the flag set where an operator naturally puts it — the compose
# `.env` — must actually take effect. `iri-deploy.service` declares no
# `EnvironmentFile=`, so before this it did not: the value read `true` on disk
# while every tick logged "IRI_MONITORING_ENABLED != 'true'" and monitoring config
# was rsynced but never reloaded into the running Prometheus. That cost two
# incidents (2026-07-13, 2026-08-03) in which fixed alert rules kept firing their
# old version. Also pins that only THIS key is read — sourcing `.env` would pull
# every production secret into the deploy process's environment.
# ---------------------------------------------------------------------------
scenario_monitoring_flag_read_from_env_file() {
  echo "Scenario: IRI_MONITORING_ENABLED in the compose .env enables the reconcile"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"
  # Appended, not written: setup_host already put IRI_KEYSTORE_HOST_PATH there and deploy.sh needs
  # it. Quoted value on purpose — an operator writes either form, and both must work.
  printf 'SOME_SECRET=must-not-leak\nIRI_MONITORING_ENABLED="true"\n' >> "${T_COMPOSE_DIR}/.env"
  # A host whose iri-monitoring project is running necessarily HAS the monitoring compose file —
  # deploy.sh only writes the armed gauge after checking for it, so leaving it out models a state
  # that cannot occur and silently skips the very assertion this scenario exists for.
  echo "# dummy monitoring compose file — the docker CLI is stubbed" \
    > "${T_COMPOSE_DIR}/docker-compose.monitoring.yml"
  mapfile -t fake < <(converged_env)
  # Deliberately NOT passing IRI_MONITORING_ENABLED: the .env is the only source.
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

# ---------------------------------------------------------------------------
# Scenario 13: a normal promotion (marker differs) must cosign-verify every
# resolved digest against the release-images signature BEFORE it pulls or applies
# (REQ-OPS-015), and only then proceed.
# ---------------------------------------------------------------------------
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
  assert_docker " up -d" "the stack is applied after verification"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 14: a resolved digest whose signature does NOT verify (a :stable tag
# moved out-of-band to an untrusted digest) must abort the deploy before any
# pull / up, record a failure metric, and exit non-zero (REQ-OPS-015).
# ---------------------------------------------------------------------------
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
  assert_no_docker " pull " "nothing is pulled from an untrusted target"
  assert_no_docker " up " "the stack is not recreated on an untrusted target"
  if grep -q 'basetool_deploy_last_failure_timestamp [1-9]' \
       "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "a deploy-failure metric is written for the verification failure"
  else
    record 0 "a deploy-failure metric is written for the verification failure"
  fi
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 15: break-glass IRI_COSIGN_VERIFY=false rides out a Sigstore outage —
# the deploy proceeds without verifying, but says so loudly and invokes no cosign.
# ---------------------------------------------------------------------------
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
  assert_docker " up -d" "the stack is still applied under break-glass"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 16: the GHCR token-expiry gauge is emitted on EVERY tick — including
# the idempotence no-op — so the GhcrPullTokenExpiring alert never goes stale.
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# Scenario 17: a --force apply of a gated stateful-infra change whose health gate
# FAILS must leave the config-blocked marker in place (it is cleared only on a
# SUCCESSFUL apply), so the next automatic tick quietly skips instead of
# re-firing the CARVE-OUT alert. Regression guard for the pre-apply marker delete.
# ---------------------------------------------------------------------------
scenario_forced_gated_rollback_keeps_marker() {
  echo "Scenario: a rolled-back --force stateful-infra apply keeps the block marker"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  # Live compose carries a postgres pin; the promoted bundle bumps it → a gated
  # stateful-infra change (infra_image_pins differ).
  printf 'services:\n  db-backend:\n    image: postgres:18-alpine\n' \
    > "${T_COMPOSE_DIR}/docker-compose.yml"
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}"
  printf 'services:\n  db-backend:\n    image: postgres:19-alpine\n' \
    > "${bundle}/docker-compose.yml"
  write_marker "${MARKER}"
  # A prior non-force tick already gated this target and wrote the marker.
  echo "${DIG_BACKEND}|${DIG_FRONTEND}|${DIG_INGEST}|sha256:config-next|${DIG_KCSPI}" \
    > "${T_STATE_DIR}/config-blocked.marker"
  echo "services: {}" > "${T_STATE_DIR}/current-digest-pin.yml"
  mapfile -t fake < <(converged_env)
  run_deploy --force -- "${fake[@]}" \
    "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=sha256:config-next" "FAKE_UP_RC=1" || rc=$?
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

# ---------------------------------------------------------------------------
# Scenario 18: a promoted config bundle that smuggles in a secret-shaped file
# (here a *.pem — a pattern the CI assert catches but the host gate used to miss)
# must be rejected before anything is applied (widened assert_no_secrets).
# ---------------------------------------------------------------------------
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
    "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=sha256:config-next" || rc=$?
  assert_exit 1 "$rc" "a secret-carrying bundle aborts the deploy"
  assert_contains "forbidden secret-shaped file" "the widened secret gate rejects the .pem"
  assert_no_docker " up -d" "nothing is applied when the bundle carries a secret"
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
scenario_oneoff_ignored
scenario_starting_grace
scenario_monitoring_config_reload
scenario_monitoring_reload_no_drift
scenario_monitoring_reload_self_heals_on_noop
scenario_monitoring_compose_def_applied_on_noop
scenario_monitoring_reconcile_disabled_when_running
scenario_monitoring_flag_read_from_env_file
scenario_signature_verified_on_apply
scenario_signature_failure_aborts
scenario_break_glass_skips_verify
# ---------------------------------------------------------------------------
# Scenario 19: --check-only over a CONVERGED stack still runs the signature
# preflight (it does not take the plain no-op fast exit), reporting "no change"
# AND verifying — so `deploy.sh --check-only` is a repeatable signature check.
# ---------------------------------------------------------------------------
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
  assert_no_docker " up " "nothing is applied"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 20: --check-only with a signature that does NOT verify must exit
# non-zero and report the failure, but must NOT write a deploy-failure metric
# (a dry-run must not trip DeployFailed).
# ---------------------------------------------------------------------------
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
  assert_no_docker " up " "nothing is applied"
  if [[ ! -f "${T_STATE_DIR}/textfile/deploy.prom" ]] \
     || ! grep -q 'basetool_deploy_last_failure_timestamp [1-9]' \
            "${T_STATE_DIR}/textfile/deploy.prom" 2>/dev/null; then
    record 1 "no deploy-failure metric is written for a dry-run verification failure"
  else
    record 0 "no deploy-failure metric is written for a dry-run verification failure"
  fi
  rm -rf "${tmp}"
}

scenario_token_expiry_metric
scenario_forced_gated_rollback_keeps_marker
scenario_config_bundle_secret_rejected
scenario_check_only_noop_verifies
scenario_check_only_verify_fail

echo
if [[ "$tests_failed" -eq 0 ]]; then
  echo "All ${tests_run} deploy.sh tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} deploy.sh test(s) failed."
exit 1
