#!/usr/bin/env bash
#
# Regression tests for scripts/deploy.sh's decision logic — the idempotence
# fast-exit, the running-stack drift verification behind it, and their
# interplay with the bad-digest backoff and the rollback path.
#
# The script under test is run against STUBBED `podman`, `skopeo`, `systemctl`
# and `cosign` CLIs (and a no-op `flock`) placed first on PATH, so no container,
# no registry and no service user are needed — pure bash, runs in seconds.
#
# Rootless Podman only since 2026-09-22 (OPS-SIMP-01): every scenario that used
# to drive the Docker arm now drives the Podman one, asserting the same decision
# on the unit operations instead of on `docker compose` calls. The stub records
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

# A readable 64-hex digest: the tag, then zero padding. REAL-SHAPED, because it has
# to be: the resolver parses skopeo's JSON with a sed pattern that requires exactly
# 64 hex characters and silently yields nothing for anything shorter. A test that
# fed it `sha256:backend-current` would exercise the failure path while reading
# like the happy one.
hexdig() {
  local tag="$1" pad=""
  while (( ${#tag} + ${#pad} < 64 )); do pad="${pad}0"; done
  printf 'sha256:%s%s' "${tag}" "${pad}"
}

# The five digests the stub registry resolves `:stable` to, and the matching
# local RepoDigest entries of a converged stack.
DIG_BACKEND="$(hexdig beef)"
DIG_FRONTEND="$(hexdig face)"
DIG_INGEST="$(hexdig 1ce)"
DIG_CONFIG="$(hexdig c0ffee)"
DIG_KCSPI="$(hexdig 5b1)"
# A config digest the registry moves to, for the scenarios that stage a bundle.
DIG_CONFIG_NEXT="$(hexdig c0ff1e)"
MARKER="${DIG_BACKEND}|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"

REPO_BACKEND="ghcr.io/krt-profit/basetool-backend@${DIG_BACKEND}"
REPO_FRONTEND="ghcr.io/krt-profit/basetool-frontend@${DIG_FRONTEND}"
REPO_INGEST="ghcr.io/krt-profit/basetool-ingest@${DIG_INGEST}"

# Creates a throwaway temp directory and prints its absolute path. Each
# scenario gets its own so they cannot interfere with one another.
mktmp() {
  mktemp -d "${TMPDIR:-/tmp}/deploy-sh-test.XXXXXX"
}

# --- a PATH that genuinely cannot reach one command ------------------------
#
# Deleting the stub does NOT make a tool absent: a GitHub runner carries a real
# /usr/bin/skopeo, so the "host without skopeo" scenario ran against a working
# host and then died on a real registry call -- green on a workstation with no
# skopeo, red in CI, in the test whose entire subject is the tool being missing.
# The same mistake, in the same shape, as the detection cases in
# scripts/container-runtime.test.sh.
#
# The fix is a MIRROR of this host's PATH with one name left out: every command
# deploy.sh could reach, minus skopeo. Hand-picking a coreutils list instead was
# the obvious move and it is wrong -- a tool nobody thought of makes the script
# exit 127 with no output, which looks nothing like the refusal under test.
#
# It needs real symlinks. Under MSYS (Git Bash on Windows) `ln -s` COPIES, and a
# copied binary cannot find its DLLs, so the mirror is skipped there and the
# scenario falls back to the ambient PATH. That is safe because the assertion is
# made against whichever PATH is actually used: a host that has skopeo AND cannot
# mirror fails loudly rather than quietly testing nothing.
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
      # The one omission, and the entire point of the mirror.
      [[ "${t_name}" == "skopeo" ]] && continue
      [[ -e "${T_MINIMAL}/${t_name}" ]] && continue
      ln -s "${t_cmd}" "${T_MINIMAL}/${t_name}" 2>/dev/null || true
    done
  done
  # A mirror that cannot run a shell is not a mirror.
  if PATH="${T_MINIMAL}" env bash -c 'exit 0' >/dev/null 2>&1; then
    T_MINIMAL_OK=true
  fi
fi
rm -f "${T_MINIMAL}/.symprobe"
trap 'rm -rf "${T_MINIMAL}"' EXIT

# Builds a complete fake host layout under $1: config dir with .env, state dir,
# GHCR token, keystore, a Quadlet unit directory seeded with the application
# units, the stub binaries, and an empty invocation log. Exports the per-scenario
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
  echo "# dummy compose file — never parsed; the units are the deployment" \
    > "${T_COMPOSE_DIR}/docker-compose.yml"
  seed_units "${tmp}"
  printf 'IRI_KEYSTORE_HOST_PATH=%s/keystore.p12\n' "${tmp}" > "${T_COMPOSE_DIR}/.env"
  : > "${tmp}/keystore.p12"
  echo "fake-token" > "${T_TOKEN}"
  : > "${T_DOCKER_LOG}"

  # No-op flock so the test needs no util-linux (and the real one, where it
  # exists, is not exercised — locking is not under test here).
  printf '#!/usr/bin/env bash\nexit 0\n' > "${T_FAKE_BIN}/flock"
  chmod +x "${T_FAKE_BIN}/flock"

  # --- the runtime stubs -----------------------------------------------------
  #
  # podman, skopeo, systemctl and the Quadlet generator. They record into one log,
  # so an assertion is about the DECISION the deployer took. The log file keeps its
  # historical name (docker-invocations.log) because every scenario reads it.
  cat > "${T_FAKE_BIN}/podman" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'podman %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
# The working directory each call inherits. `sudo -u <service user> podman` keeps it, and the service
# user cannot enter /root -- so the cwd the deployer runs podman in is a precondition, asserted by
# scenario_podman_runs_podman_from_root_dir.
printf 'podman-cwd %s\n' "${PWD}" >> "${FAKE_DOCKER_LOG}"

lookup() { local var="$1_$2"; printf '%s' "${!var:-}"; }

case "${1:-}" in
  login) cat > /dev/null; exit 0 ;;
  ps)
    # Quadlet stamps PODMAN_SYSTEMD_UNIT on every container it starts, and the
    # deployer filters on that label rather than on the name.
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
      # A bare `ps --format {{.Names}}`: which containers run. FAKE_EDGE_PS models the edge being up
      # (reconcile_edge reads its certificates through it); FAKE_MON_PS a running monitoring unit.
      if [[ -n "${FAKE_EDGE_PS:-}" ]]; then printf '%s\n' "${FAKE_EDGE_PS}"; fi
      if [[ -n "${FAKE_MON_PS:-}" ]]; then printf '%s\n' "${FAKE_MON_PS}"; fi
    fi
    exit 0
    ;;
  exec)
    # `podman exec edge sh -c 'find … -exec sha256sum {} +'`. FAKE_EDGE_CERT_LINES holds the
    # sha256sum output verbatim; FAKE_EDGE_EXEC_RC=1 models an exec that fails, which must read as
    # "could not tell" rather than "no certificates".
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
    # podman cp <cid>:/config - -- a TAR ON STDOUT, which is what the seam asks for under podman
    # so that the extraction happens as the CALLER and not as the service user. The stub has to
    # produce a real archive: a stub that copied a directory instead would pass while the code it
    # is standing in for streams, and the difference is the whole reason the podman arm exists.
    if [[ -n "${FAKE_CONFIG_BUNDLE:-}" && "${3:-}" == "-" ]]; then
      tar -cf - -C "${FAKE_CONFIG_BUNDLE}" . 2>/dev/null
    fi
    exit 0
    ;;
  rm) exit 0 ;;
  pull)
    # What deploy.sh asks podman to pull has to be a REFERENCE. Under compose a
    # SERVICE name is enough, because compose maps it to that service's pinned
    # image; podman has no such mapping, so a bare `backend` is resolved against
    # the host's unqualified-search registries -- on Rocky that is
    # registry.access.redhat.com, registry.redhat.io and docker.io -- and either
    # fails or, worse, succeeds against a stranger's image.
    #
    # A stub that exited 0 for anything would let that ship: the deployer would
    # look tested and abort at "pulling images" on the real host.
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

  # skopeo replaces `docker buildx imagetools inspect`: it resolves a tag to a
  # digest WITHOUT pulling, which is the property the deployer depends on.
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

  # `systemctl --user start <unit>` IS the health gate under Quadlet: Notify=healthy
  # makes the unit Type=notify, so the call blocks until podman reports healthy and
  # returns non-zero when it does not. FAKE_UP_RC models exactly that.
  cat > "${T_FAKE_BIN}/systemctl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'systemctl %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
case "$*" in
  *daemon-reload*) exit 0 ;;
  *start*|*restart*)
    # FAKE_UNHEALTHY_DIGEST models ONE broken release rather than a broken host:
    # the unit whose digest-pin drop-in binds that digest never reports healthy,
    # every other unit does. A flat FAKE_UP_RC=1 would fail the rollback's own
    # start too, and "rolled back" and "rollback also failed" would stop being
    # distinguishable -- which is exactly the pair a rollback test is about.
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

  # The Quadlet generator. Its PRESENCE is what the pre-flight asserts: a host
  # without it cannot turn .container files into services at all.
  printf '#!/usr/bin/env bash\nexit 0\n' > "${T_FAKE_BIN}/quadlet"
  chmod +x "${T_FAKE_BIN}/quadlet"

  # Stub cosign for the host-side signature gate (REQ-OPS-015). Records the
  # invocation and exits FAKE_COSIGN_RC (default 0 = signature trusted); a
  # scenario sets FAKE_COSIGN_RC=1 to model a verification failure (a :stable
  # tag moved to an untrusted digest). Placed first on PATH like the runtime stubs so the
  # real cosign (absent on the test runner) is never exercised.
  cat > "${T_FAKE_BIN}/cosign" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf 'cosign %s\n' "$*" >> "${FAKE_DOCKER_LOG}"
# FAKE_COSIGN_FAIL_TIMES models a *transient* registry/Sigstore error: the first
# N invocations for a given ref fail on stderr, every later one succeeds. Counted
# per ref (arg 2 of `cosign verify <ref> …`) so a multi-image tick exercises the
# retry once per image instead of spending the whole budget on the first.
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
  # A real mismatch also explains itself on stderr; the abort must quote it.
  echo "Error: no matching signatures" >&2
fi
# FAKE_COSIGN_SUBJECT models the certificate SAN the signature actually carries.
# When it is set, the stub does what real cosign does with it: match the
# `--certificate-identity-regexp` deploy.sh passed against that subject, and
# refuse the signature when it does not match. bash's ERE and Go's RE2 agree on
# everything the identity regexp uses (anchors, a group, `|`, `[0-9]+`, `\.`), and
# `=~` is unanchored exactly like Go's MatchString, so an unanchored regexp is
# as permissive here as it would be on the host.
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

# Seeds the Quadlet unit directory with the three application units a deployed host has, the
# backend's carrying the keystore mount (the pre-flight reads the path from HERE, not from .env).
# A host with an EMPTY unit directory has no stack at all and the deployer stages the bundle to fill
# it, so a scenario that left it empty would model a host that cannot exist.
seed_units() {
  T_UNIT_DIR="${1}/units"
  mkdir -p "${T_UNIT_DIR}"
  local svc
  for svc in backend frontend ingest; do
    printf '[Container]\nContainerName=%s\nImage=placeholder\n' "${svc}" > "${T_UNIT_DIR}/${svc}.container"
  done
  printf 'Volume=%s/keystore.p12:/run/secrets/keystore.p12:ro\n' "$1" >> "${T_UNIT_DIR}/backend.container"
}

# Adds the monitoring units a monitored host has. The deployer derives the monitoring set from the
# unit directory (every unit not in RT_STACK_SERVICES), so without these a host has no monitoring
# plane to reconcile -- which is a real host shape, just not the one these scenarios are about.
seed_monitoring_units() {
  local svc
  for svc in prometheus loki blackbox-exporter; do
    printf '[Container]\nContainerName=%s\nImage=placeholder\n' "${svc}" > "${T_UNIT_DIR}/${svc}.container"
  done
}

# What "the stack was applied" looks like: the gated apply starts every stack unit it did not
# re-pin, and db-backend is never re-pinned. What "nothing was applied" looks like: no unit start or
# restart at all.
APPLIED="systemctl --user start db-backend.service"

# assert_no_apply <description> -- fails if any unit was started or restarted.
assert_no_apply() {
  local desc="$1"
  if ! grep -qE 'systemctl (--user )?(start|restart) ' "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (a unit was started or restarted)"
  fi
}

# assert_no_monitoring_recreate <description> -- fails if a monitoring component was recreated.
assert_no_monitoring_recreate() {
  local desc="$1"
  if ! grep -qE 'restart (prometheus|blackbox-exporter|alloy)\.service' "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (a monitoring component was recreated)"
  fi
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
  # RUN_DEPLOY_MINIMAL_PATH=1 swaps the inherited PATH for the staged one, which
  # is the only way a "this tool is not installed" scenario can mean anything on
  # a runner that has the tool.
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
    echo "----- recorded invocations -----"
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
  # `--` before the pattern: a needle that starts with a dash (any compose flag,
  # e.g. --force-recreate) is otherwise read by grep as an option, and the test
  # fails with "unknown option" while the invocation it looks for is right there.
  if grep -qF -- "$needle" "${T_DOCKER_LOG}"; then
    record 1 "$desc"
  else
    record 0 "$desc (no docker invocation matching: '${needle}')"
  fi
}

# assert_no_docker <substring> <description> — fails if the stub recorded one.
assert_no_docker() {
  local needle="$1" desc="$2"
  if ! grep -qF -- "$needle" "${T_DOCKER_LOG}"; then
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
  assert_no_docker "podman pull" "nothing is pulled"
  assert_no_apply "nothing is restarted"
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
  assert_docker "${APPLIED}" "the stack is re-applied through its units"
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
  assert_docker "systemctl --user restart backend.service" "only the affected service is recreated"
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
  assert_no_apply "nothing is restarted during the health-restart backoff window"
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
  assert_docker "${APPLIED}" "the stack is re-applied"
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
  assert_docker "${APPLIED}" "the stack is applied"
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
  assert_no_docker "podman pull" "check-only pulls nothing"
  assert_no_apply "check-only restarts nothing"
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
  assert_no_apply "nothing is restarted during the backoff window"
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

# (Scenario 9, a leftover `docker compose run` one-off container, went with the Docker runtime on
# 2026-09-22: Podman has no compose `run`, and a container not started by a unit carries no
# PODMAN_SYSTEMD_UNIT label, so it is never listed as a service's container in the first place.)

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
  seed_monitoring_units
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
    "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" || rc=$?
  assert_exit 0 "$rc" "config-change deploy with a monitoring recreate succeeds"
  assert_docker "systemctl --user start loki.service" "the monitoring stack is reconciled"
  assert_docker "systemctl --user restart prometheus.service" "prometheus is recreated (its slice changed)"
  assert_no_docker "restart alloy.service" "alloy is left alone (its slice is unchanged)"
  assert_no_docker "restart blackbox-exporter.service" "blackbox is left alone (its slice is unchanged)"
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
  seed_monitoring_units
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
  seed_monitoring_units
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
  seed_monitoring_units
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
  assert_no_docker "podman pull" "the app stack is not pulled on the fast exit"
  assert_no_docker "${APPLIED}" "the app stack is not re-applied on the fast exit"
  assert_docker "systemctl --user start loki.service" "the monitoring unit reconcile runs on the converged no-op"
  assert_no_monitoring_recreate "no service is recreated when the config subtree is converged"
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
  seed_monitoring_units
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  # No IRI_MONITORING_ENABLED (defaults to false); FAKE_MON_PS models a running monitoring unit.
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
  # A host whose monitoring plane is running necessarily HAS its units — deploy.sh only writes the
  # armed gauge after finding them, so leaving them out models a state that cannot occur and
  # silently skips the very assertion this scenario exists for.
  seed_monitoring_units
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
  assert_docker "${APPLIED}" "the stack is applied after verification"
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

# ---------------------------------------------------------------------------
# Scenario 14b: a *transient* verification error (a registry / Sigstore blip) is
# retried, not escalated. This is the 2026-08-05 DeployFailed page: one GHCR
# hiccup aborted the tick as a critical SECURITY alarm — "refusing to deploy an
# unverified/untrusted image" — while the very same digest verified cleanly by
# hand minutes later, and the same tick had also failed to resolve
# keycloak-spi:stable. A blip must cost a retry, not an out-of-hours page.
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# Scenario 14c: the DEFAULT signer identity is anchored (audit item CI-SEC-01).
# cosign matches `--certificate-identity-regexp` anywhere in the certificate SAN,
# so the unanchored `…@refs/(heads/main|tags/v.+)` it replaced also trusted a
# signature minted on `refs/heads/main-x`, `refs/heads/maintenance` or
# `refs/tags/vfoo`. The stub evaluates the regexp deploy.sh really passes against
# the subject the signature claims, so this runs the shipped default rather than a
# copy of it.
# ---------------------------------------------------------------------------
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
  # Each refused subject differs from an accepted one only by what the unanchored
  # regexp let through: a suffix after `main`, a longer branch that starts with
  # `main`, and a `v` tag that is not a version. The last one is a different
  # workflow file in the same directory, which the `release-images\.yml@` part
  # already refused and must keep refusing.
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
  assert_docker "${APPLIED}" "the stack is still applied under break-glass"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 15b: cosign installed but not on PATH. "Not on PATH" and "not
# installed" are different facts and on Rocky they come apart: the role installs
# cosign to /usr/local/bin, and sudo's `secure_path` there is
# `/sbin:/bin:/usr/sbin:/usr/bin`. So `sudo -u deploy deploy.sh` — the manual
# invocation this script's own usage block documents — aborted with "install
# cosign" on a host that had it. Measured on the testing host 2026-09-20.
#
# The TIMER never saw it: a systemd service gets systemd's PATH, which includes
# /usr/local/bin. That asymmetry is exactly why it needs a test — the failure is
# invisible on the path CI and the runbook exercise, and waits for the operator
# who types the other one.
# ---------------------------------------------------------------------------
scenario_cosign_off_path() {
  echo "Scenario: cosign is installed but not on PATH (sudo secure_path)"
  local tmp rc=0 alt
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "sha256:backend-old|${DIG_FRONTEND}|${DIG_INGEST}|${DIG_CONFIG}|${DIG_KCSPI}"

  # Move the stub OUT of the staged PATH and into a directory that stands in for
  # /usr/local/bin, then point the search there. Nothing else changes.
  alt="${tmp}/not-on-path"
  mkdir -p "${alt}"
  mv "${T_FAKE_BIN}/cosign" "${alt}/cosign"

  mapfile -t fake < <(converged_env)
  RUN_DEPLOY_MINIMAL_PATH=1 run_deploy -- "${fake[@]}" \
    "IRI_COSIGN_SEARCH_PATH=${alt}" || rc=$?
  assert_exit 0 "$rc" "the deploy succeeds with cosign off PATH but findable"
  assert_contains "but not on PATH" "it says where it found cosign, rather than aborting"
  assert_docker "cosign verify" "and it still verifies every signature"

  # ...and with nothing to find, it must still fail closed and name where it looked.
  #
  # The stub log is truncated first: it ACCUMULATES across run_deploy calls, so without this the
  # `assert_no_docker` below would read the first run's perfectly legitimate `cosign verify` and go
  # red for the wrong reason. It did, on the first run of this scenario.
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
# Scenario 16b: deleting the expiry file is the DOCUMENTED action when the PAT
# turns out not to expire, and it has to actually silence the gauge. Until
# 2026-09-20 write_token_expiry_metric returned early on an absent file without
# removing ghcr-token.prom, so node_exporter kept serving the last recorded
# timestamp and GhcrPullTokenExpired fired critical forever -- for following the
# alert's own instructions. Both no-expiry shapes are covered: the file removed,
# and the file emptied.
# ---------------------------------------------------------------------------
scenario_token_expiry_removed_clears_the_gauge() {
  echo "Scenario: removing the expiry file removes the gauge"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  write_marker "${MARKER}"

  # First a tick WITH an expiry, so there is something to clear.
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

  # Now the operator learns the PAT does not expire and deletes the sidecar.
  rm -f "${T_TOKEN}.expiry"
  rc=0
  run_deploy -- "${fake[@]}" || rc=$?
  assert_exit 0 "$rc" "the tick after removing the expiry file exits 0"
  if [[ -e "${T_STATE_DIR}/textfile/ghcr-token.prom" ]]; then
    record 0 "the gauge file is gone once no expiry is recorded"
  else
    record 1 "the gauge file is gone once no expiry is recorded"
  fi

  # An EMPTY file is the same statement and must behave the same way.
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
  # The live units carry a postgres pin; the promoted bundle bumps it → a gated stateful-infra change
  # (infra_image_pins differ). Written the way the generator writes it: fully qualified.
  mkdir -p "${T_COMPOSE_DIR}/quadlet/systemd"
  printf '[Container]\nImage=docker.io/postgres:18-alpine\n' > "${T_COMPOSE_DIR}/quadlet/systemd/db-backend.container"
  local bundle="${tmp}/bundle"
  mkdir -p "${bundle}/quadlet/systemd"
  echo "# promoted compose" > "${bundle}/docker-compose.yml"
  printf '[Container]\nImage=docker.io/postgres:19-alpine\n' > "${bundle}/quadlet/systemd/db-backend.container"
  write_marker "${MARKER}"
  # A prior non-force tick already gated this target and wrote the marker.
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
  assert_no_apply "nothing is applied"
  rm -rf "${tmp}"
}

# ---------------------------------------------------------------------------
# Scenario 20: --check-only with a signature that does NOT verify must exit
# non-zero and report the failure, but must NOT write a deploy-failure metric
# (a dry-run must not trip DeployFailed).
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# The config apply mirrors an EXPLICIT list of directories, not the whole
# bundle. On 2026-09-12 docker/edge was added to the bundle image and not to
# that list, so the edge proxy's whole configuration reached the staging area
# and stopped there — the host never got it, the container mounted an empty
# directory and died with "open() /etc/nginx/edge/nginx.conf failed", and four
# applies rolled back cleanly without once naming the missing mirror.
#
# This asserts the mirror, not the bundle: a directory that ships but is never
# copied is the failure this scenario exists to catch.
#
# It happened a second time, and that is why every subtree under docker/ is now
# asserted rather than only the one that failed first. docker/acme was added to
# the bundle on 2026-09-16 when the ACME loop moved out of the compose file's
# block scalar; neither the COPY allowlist nor the mirror list gained an entry.
# A host that already had the acme container kept running the command baked into
# it and showed nothing. The Podman cutover host, 2026-09-22, had no such
# container and refused to start one:
#   Error: statfs /var/iri/code/docker/acme: no such file or directory
# — and renewed no certificates at all.
# ---------------------------------------------------------------------------
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
  # The unit mounts this directory and runs the script out of it; a bundle that
  # ships it and a deployer that never copies it is a container that cannot start.
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

# ---------------------------------------------------------------------------
# The mode the mirrored tree lands with must not depend on WHO started the
# deploy. Rocky's hardened baseline sets `UMASK 027` in /etc/login.defs and
# /etc/profile; a systemd service gets 0022. So the same deploy produced
# /var/iri/code/... at 0755 under the timer and 0750 under
# `sudo -u deploy deploy.sh` — the invocation deploy.sh's own usage block
# documents — and rootless Podman resolves bind mounts AS the service user,
# which is not in group `deploy` and cannot traverse a 0750 directory.
#
# Measured on the testing host 2026-09-20: keycloak and edge both exited 125
# with `statfs /var/iri/code/keycloak-theme/krt-theme: permission denied`, on a
# path that plainly existed. It does not fail at apply time — the deploy reports
# the config applied, and the failure arrives later as a health-check timeout
# and a rollback that fails the same way.
#
# Asserted as an INVARIANT rather than against a literal 0755: the two runs must
# agree. That is the property that matters and it holds on any platform, which
# a hardcoded mode would not.
# ---------------------------------------------------------------------------
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

# Writes a config tree whose units carry the stateful-infra image pins the carve-out reads, spelled
# the way generate-quadlet.py spells them (`docker.io/postgres:…`, which the carve-out's pattern did
# not match until 2026-09-22). $1 = the tree, $2 = keycloak tag, $3 = keycloak digest.
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

  # 1. Keycloak 26.7 rebuilt — same tag, new digest. A security refresh, and the
  #    exact shape that froze the testing host for seven days.
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

  # 2. Keycloak 26.7 -> 26.8. A version change, which IS the choreographed
  #    upgrade this gate exists for.
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

  # 3. A postgres MAJOR change, spelled as the generator spells it. Until 2026-09-22 the unit
  #    pattern was `^Image=postgres:`, which `Image=docker.io/postgres:` never matches, so the postgres
  #    half of this gate only ever saw the compose file's copy.
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
  # An edge config that is already in sync, so the ONLY thing that can produce
  # drift here is the certificate fingerprint. Without this the config diff would
  # recreate the edge on its own and the assertions below would prove nothing.
  mkdir -p "${T_COMPOSE_DIR}/docker/edge/conf.d"
  echo "worker_processes auto;" > "${T_COMPOSE_DIR}/docker/edge/nginx.conf"
  echo "# vhost" > "${T_COMPOSE_DIR}/docker/edge/conf.d/10-frontend.conf"
  mkdir -p "${T_STATE_DIR}/edge"
  cp -R "${T_COMPOSE_DIR}/docker/edge" "${T_STATE_DIR}/edge/config"
  write_marker "${MARKER}"
  mapfile -t fake < <(converged_env)
  local seeded="aaaa1111  /etc/nginx/certs/profit-base.online/fullchain.pem"
  local renewed="bbbb2222  /etc/nginx/certs/profit-base.online/fullchain.pem"

  # 1. Nothing recorded yet — the material on the edge is new to us, so load it.
  #    This is the assertion the old code could not pass: it read the certificates
  #    from the volume's host path under /var/lib/docker, which the `deploy` user
  #    cannot enter, so the fingerprint was never computed and never stored.
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

  # 2. Same certificates on the next tick — recreating the edge every five minutes
  #    would be an outage on a schedule.
  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_EDGE_PS=edge" "FAKE_EDGE_CERT_LINES=${seeded}" || rc=$?
  assert_exit 0 "$rc" "an unchanged tick succeeds"
  assert_no_docker "systemctl --user restart edge.service" "an unchanged certificate recreates nothing"

  # 3. acme renewed it.
  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_EDGE_PS=edge" "FAKE_EDGE_CERT_LINES=${renewed}" || rc=$?
  assert_exit 0 "$rc" "the renewal tick succeeds"
  assert_docker "systemctl --user restart edge.service" "a renewed certificate is loaded"

  # 4. The exec failed. "Could not tell" must not read as "no certificates" —
  #    sha256sum of an empty input is a valid hash, and folding it in would
  #    force-recreate the edge on every tick the read happened to fail.
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

# ---------------------------------------------------------------------------
# The Podman-specific paths: resolution through skopeo, the drop-in pin, the unit
# payload, the pre-flight's tools. (The scenarios above drive the same arm; these
# were written when it was the second of two.)
# ---------------------------------------------------------------------------

PDIG_BACKEND="${DIG_BACKEND}"
PDIG_FRONTEND="${DIG_FRONTEND}"
PDIG_INGEST="${DIG_INGEST}"
PDIG_CONFIG="${DIG_CONFIG}"
PDIG_KCSPI="${DIG_KCSPI}"
PDIG_BACKEND_NEW="$(hexdig dead)"
PMARKER="${PDIG_BACKEND}|${PDIG_FRONTEND}|${PDIG_INGEST}|${PDIG_CONFIG}|${PDIG_KCSPI}"
# The marker of a host still on the OLD backend: config and keycloak-spi match the
# registry, so a deploy from here moves the app images only and never enters the
# config-bundle extraction path.
PMARKER_OLD="$(hexdig ba5e)|${PDIG_FRONTEND}|${PDIG_INGEST}|${PDIG_CONFIG}|${PDIG_KCSPI}"

# Everything a scenario needs to drive the Podman arm: the backend forced, the
# Quadlet generator pointed at the stub, and a unit directory that exists --
# the pre-flight refuses a host where it does not.
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

# A converged Podman host: three running containers whose images carry the
# digests the registry is serving.
podman_converged_env() {
  printf '%s\n' \
    "FAKE_PS_backend=cid-backend" \
    "FAKE_PS_frontend=cid-frontend" \
    "FAKE_PS_ingest=cid-ingest" \
    "FAKE_REPODIGESTS_backend=ghcr.io/krt-profit/basetool-backend@${PDIG_BACKEND}" \
    "FAKE_REPODIGESTS_frontend=ghcr.io/krt-profit/basetool-frontend@${PDIG_FRONTEND}" \
    "FAKE_REPODIGESTS_ingest=ghcr.io/krt-profit/basetool-ingest@${PDIG_INGEST}"
}

# Creates the Quadlet unit directory the pre-flight insists on and exports its
# path, and seeds it with the units a host that has been deployed to actually
# has. Called after setup_host, before podman_env is expanded.
#
# The seeding is not decoration. A Podman host with an EMPTY unit directory has
# no stack at all, so the deployer treats it as a config divergence and stages
# the bundle to fill it -- which means a scenario that left the directory empty
# was modelling a host that cannot exist, and testing the wrong path.
# podman_units_empty() is for the one scenario that wants that state on purpose.
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
  # The keystore mount, as generate-quadlet.py writes it -- pointed at this scenario's keystore rather
  # than /var/iri/secrets, because under Podman the pre-flight reads the path from HERE and not from
  # .env (keystore_mount_source in deploy.sh).
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
  # There is no --wait to pass: Notify=healthy makes each unit Type=notify, so
  # `systemctl start` does not return until podman reports the container healthy.
  assert_docker "systemctl --user daemon-reload" "podman: the units are re-read before anything starts"
  # RESTART, not start. The scenario moves the backend's digest, so the deployer re-pins it -- and
  # `systemctl start` on an already-active unit returns 0 without re-reading anything, which would
  # leave the old container running the old image. Asserting `start` here is what let that ship.
  assert_docker "systemctl --user restart backend.service" "podman: a re-pinned service is restarted, so the new image actually lands"
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
  # The record is written too -- it is the only place the PREVIOUS digests
  # survive once the drop-ins have been overwritten, so a rollback needs it.
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
  # A first deploy that succeeds, so a previous pin exists to roll back TO.
  run_deploy -- "${pod[@]}" "${conv[@]}" >/dev/null 2>&1 || true
  local pin="${T_UNIT_DIR}/backend.container.d/10-digest-pin.conf"

  # Now a new backend digest whose unit never reaches healthy. The stub refuses
  # to start exactly the unit whose drop-in binds the broken digest -- which is
  # what a failed `Notify=healthy` start is -- so the rollback's own start can
  # succeed and the two outcomes stay distinguishable.
  : > "${T_DOCKER_LOG}"
  run_deploy -- "${pod[@]}" "${conv[@]}" \
    "FAKE_REMOTE_BACKEND=${PDIG_BACKEND_NEW}" \
    "FAKE_UNHEALTHY_DIGEST=${PDIG_BACKEND_NEW}" || rc=$?
  assert_exit 1 "$rc" "podman: a failed health gate exits non-zero"
  assert_contains "rolling back" "podman: the failed release is rolled back"
  assert_contains "rolled back to previous digest pin successfully" \
    "podman: the rollback's own apply reaches health"
  # The rollback is only real if the DROP-IN went back with the record. Under
  # Quadlet the record binds nothing; restoring it alone would leave the failed
  # digest bound and roll silently FORWARD into the release that just failed.
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
  # The isolation is asserted against the PATH this scenario ACTUALLY uses, not
  # assumed. Without this it passed on a workstation and, on a runner carrying
  # /usr/bin/skopeo, tested a host that had the tool all along.
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
  # Point the override at a path that does not exist. Without the generator a
  # .container file is an inert text file and nothing can ever start.
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

# --- the Quadlet units as a release payload --------------------------------
#
# Until 2026-09-18 nothing put a unit file on a host: the config bundle did not
# carry them, the Ansible role states it never ships them, and deploy.sh only
# checked the directory was not empty. The units on the testing host had arrived
# by an operator action nobody recorded, and a release could change an image, a
# health command or a memory limit with no path to production at all.

# A bundle fixture: a compose file (the bundle still carries the source), the
# units, and an env.d template. $1 is the directory to build it in.
write_bundle() {
  local b="$1"
  mkdir -p "${b}/quadlet/systemd" "${b}/quadlet/env.d"
  echo "# promoted compose" > "${b}/docker-compose.yml"
  printf '[Container]\nContainerName=backend\nImage=placeholder\n'  > "${b}/quadlet/systemd/backend.container"
  printf '[Container]\nContainerName=frontend\nImage=placeholder\n' > "${b}/quadlet/systemd/frontend.container"
  printf '[Network]\nNetworkName=net-app\n'                          > "${b}/quadlet/systemd/net-app.network"
  # shellcheck disable=SC2016
  # Literal on purpose: an env.d TEMPLATE carries the placeholder, and render-env-d.py is what
  # substitutes it on the host. Expanding it here would bake this machine's value into the fixture.
  printf 'BACKEND_TOKEN=${IRI_KEYSTORE_HOST_PATH:?}\n'                > "${b}/quadlet/env.d/backend.env.tmpl"
}

# A stub renderer, so the scenario tests what DEPLOY.SH does rather than
# re-testing render-env-d.py, which has its own suite.
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

  # The host as it is before the release: one stale unit, one that this release
  # retires, and a digest pin drop-in that must survive both.
  printf '[Container]\nContainerName=backend\nImage=OLD\n' > "${T_UNIT_DIR}/backend.container"
  printf '[Container]\nContainerName=retired\n'             > "${T_UNIT_DIR}/retired.container"
  mkdir -p "${T_UNIT_DIR}/backend.container.d"
  echo "# an operator drop-in" > "${T_UNIT_DIR}/backend.container.d/20-host-alias.conf"

  # A marker whose CONFIG digest differs from the registry's, so the bundle is staged.
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
  # The drop-in is what BINDS the digest this release pinned. A mirror with
  # --delete over the unit directory would take it with the retired unit, and the
  # stack would silently fall back to whatever the base unit's Image= says.
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
  # Quadlet only generates a service for a unit file that EXISTS. Delete the file
  # first and systemd forgets the unit while its container keeps running --
  # unmanaged, and invisible to every later reconcile.
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
  # The marker matches the registry exactly, so nothing is "changed" -- and the unit
  # directory is empty, which is what the Ansible role leaves behind. Without the
  # empty-directory path this deploy would resolve, verify, pin and start nothing,
  # and report success.
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
  # The state a real Quadlet host is in: the units are the deployment, and there is
  # no docker-compose.yml anywhere. The pre-flight used to require one BEFORE
  # rt_detect ran, so it aborted with "required file missing" before it could
  # discover it was on a runtime that has no compose at all.
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

# --- what a release re-defines has to reach the running container ------------
#
# Found by the documentation audit on 2026-09-22, the day production moved to
# Quadlet: rt_monitoring_up only ever said `systemctl start`, which is a no-op on
# an active unit, and acme was in neither service list at all. So a release that
# changed a monitoring unit or acme.container installed the new file,
# daemon-reloaded, and left the old container running the old definition.

scenario_podman_changed_monitoring_and_acme_units_are_restarted() {
  echo "Scenario: a release that changes a monitoring unit or acme restarts them -- once -- and only them"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_env_renderer
  local bundle="${tmp}/bundle"
  write_bundle "${bundle}"
  # The release: prometheus and acme move, loki does not.
  printf '[Container]\nContainerName=prometheus\nImage=NEW\n' > "${bundle}/quadlet/systemd/prometheus.container"
  printf '[Container]\nContainerName=loki\nImage=same\n'      > "${bundle}/quadlet/systemd/loki.container"
  printf '[Container]\nContainerName=acme\nImage=NEW\n'       > "${bundle}/quadlet/systemd/acme.container"
  # The host before it.
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
  # Once and not twice: the monitoring apply and reconcile_monitoring_reloads both call
  # rt_monitoring_up, and a pipe into sed used to run the first in a subshell that forgot nothing.
  assert_docker "systemctl --user start loki.service" "podman: ...an unchanged one is only started"
  assert_no_docker "systemctl --user restart loki.service" "podman: ...and never recreated for somebody else's change"
  assert_docker "systemctl --user restart acme.service" "podman: a changed acme unit is restarted with the stack"
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
  rm -rf "${tmp}"
}

# --- the keystore pre-flight checks the file the units mount ------------------

scenario_podman_keystore_checked_where_the_unit_mounts_it() {
  echo "Scenario: under Quadlet the keystore pre-flight reads the unit's Volume=, not .env"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  # .env names a file that does not exist. Quadlet never reads it, so it must not decide anything.
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
  # REQ-SEC-070: after the per-service rollout the backend's file exists and the ingest unit
  # names its OWN keystore -- plus an internal truststore -- which this host lacks.
  printf 'Volume=%s/tls/ingest.p12:/run/secrets/keystore.p12:ro\n' "${tmp}" >> "${T_UNIT_DIR}/ingest.container"
  printf 'Volume=%s/keystore.p12:/run/secrets/internal-truststore.p12:ro\n' "${tmp}" >> "${T_UNIT_DIR}/ingest.container"
  mapfile -t pod < <(podman_env)
  mapfile -t conv < <(podman_converged_env)
  run_deploy -- "${pod[@]}" "${conv[@]}" || rc=$?
  assert_exit 1 "$rc" "podman: a missing per-service keystore refuses before anything is applied"
  assert_contains "required file missing: ${tmp}/tls/ingest.p12" "podman: ...and names that service's file"
  rm -rf "${tmp}"
}

# --- the working directory podman inherits -----------------------------------

scenario_podman_missing_edge_trust_anchor_refuses() {
  echo "Scenario: ...and so is the edge's Grafana trust anchor, whose absence would keep the edge down"
  local tmp rc=0
  tmp="$(mktmp)"
  setup_host "${tmp}"
  podman_units "${tmp}"
  write_marker "${PMARKER}"
  # REQ-OBS-008: the edge mounts Grafana's certificate. On a host that never minted it the edge
  # container could not start, so the pre-flight refuses before anything is applied.
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
  # From a directory that is neither / nor the compose dir, the way an operator's shell in /root is.
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

# --- a failure between the signature check and the health gate is recorded ----
#
# 2026-09-25, v1.11.0: /var/iri/code/docker/acme was root-owned. Every tick from 12:25 to 12:35
# logged "config changed → staging", mirrored monitoring/, the maintenance page and the edge onto
# the host, and died inside the acme mirror on rsync exit 23. Errexit ended the script right there:
# no FATAL line, no backoff record, no restore, `basetool_deploy_last_failure_timestamp 0` — so
# DeployFailed never fired, and production stayed on the old release until a human noticed. The
# second tick then snapshotted the half-mirrored tree as config-previous/ and copied the first tick's
# NEW pin over the rollback anchor.

# An rsync stand-in that fails the way the host's did, for the mirrors a scenario names. The rules are
# `<source-substring>=><destination-substring>` pairs, space-separated, in FAKE_RSYNC_FAIL_RULES, so a
# scenario can fail the forward apply and let the restore through (or fail both). Every other mirror
# is carried out: the destination is replaced by the source, which is what `--delete` amounts to.
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

# The host's live config tree as the previous release left it — every file says OLD — with the units
# it delivered, identical to what is installed, so a restore that works changes nothing in the unit
# directory. $1 is the tree.
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

# The release being deployed: the same subtrees, every file NEW, and a changed backend unit. $1 is
# the bundle directory.
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

# assert_file_says <file> <expected-first-line> <description>
assert_file_says() {
  local file="$1" want="$2" desc="$3" got
  got="$(head -n 1 "${file}" 2>/dev/null || echo "<missing>")"
  if [[ "${got}" == "${want}" ]]; then
    record 1 "${desc}"
  else
    record 0 "${desc} (${file} says '${got}', expected '${want}')"
  fi
}

# assert_failure_metric <description> -- the textfile DeployFailed reads carries a failure stamp.
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

  # Tick 1: the acme mirror fails exactly as it did on the host, after three subtrees went through.
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
  # The mirrors that went through before the failure are undone, so the host is the previous
  # release again rather than half of each.
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
  # The pin is written after the config apply, so a failed apply never touched it.
  if [[ ! -e "${T_UNIT_DIR}/backend.container.d/10-digest-pin.conf" ]] \
    && grep -qx '# the previous release.s pin record' "${T_STATE_DIR}/current-digest-pin.yml"; then
    record 1 "the digest pin is untouched by a failed config apply"
  else
    record 0 "the digest pin is untouched by a failed config apply"
  fi
  assert_no_apply "nothing is started or restarted"

  # Tick 2, five minutes later: the backoff holds the same target instead of failing every tick.
  : > "${T_DOCKER_LOG}"
  rc=0
  run_deploy -- "${fake[@]}" "FAKE_CONFIG_BUNDLE=${bundle}" "FAKE_REMOTE_CONFIG=${DIG_CONFIG_NEXT}" \
    "FAKE_RSYNC_FAIL_RULES=config-stage=>/docker/acme" || rc=$?
  assert_exit 0 "$rc" "the next tick inside the backoff window is a quiet skip"
  assert_contains "in backoff window" "and says it is backing off"
  assert_excludes "config changed" "no config is staged while backing off"

  # The operator fixes the owner and retries now.
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

  # The forward apply gets as far as the units and dies there; the restore dies on its first mirror,
  # so the host is left with the new edge and acme beside the old units.
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
  # The previous release's pin: the record and the drop-in that binds it.
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
  # Mode bits do not bind root, and an MSYS/NTFS mount ignores them: on such a runner the directory
  # stays writable and there is nothing to refuse. Said out loud rather than passed — CI's
  # ubuntu-latest runner is neither, and runs it for real.
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
  # A backend digest that moves with the gated config: before 2026-09-25 its drop-in was written
  # first and stayed behind, bound to the old units.
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
