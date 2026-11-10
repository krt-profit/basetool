#!/bin/bash
# shellcheck source-path=SCRIPTDIR

set -euo pipefail

umask 022

BASETOOL_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
. "${BASETOOL_SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/container-runtime.sh
# shellcheck disable=SC1091
. "${BASETOOL_SCRIPT_DIR}/lib/container-runtime.sh"

# shellcheck source=lib/deploy-config.sh
# shellcheck disable=SC1091
. "${BASETOOL_SCRIPT_DIR}/lib/deploy-config.sh"

# shellcheck source=lib/deploy-drift.sh
# shellcheck disable=SC1091
. "${BASETOOL_SCRIPT_DIR}/lib/deploy-drift.sh"

# shellcheck source=lib/deploy-observability.sh
# shellcheck disable=SC1091
. "${BASETOOL_SCRIPT_DIR}/lib/deploy-observability.sh"

# shellcheck source=lib/deploy-release.sh
# shellcheck disable=SC1091
. "${BASETOOL_SCRIPT_DIR}/lib/deploy-release.sh"


cd /

COMPOSE_DIR="${BASETOOL_COMPOSE_DIR:-/opt/basetool}"
STATE_DIR="${BASETOOL_STATE_DIR:-/var/lib/basetool/deploy}"
LOCKFILE="${BASETOOL_LOCKFILE:-/run/lock/basetool-deploy.lock}"
TOKEN_FILE="${BASETOOL_GHCR_TOKEN_FILE:-/etc/basetool/deploy/ghcr-pull-token}"
HEALTH_TIMEOUT="${BASETOOL_HEALTH_TIMEOUT:-180}"

if [[ -z "${BASETOOL_MONITORING_ENABLED:-}" && -r "${ENV_FILE}" ]]; then
  BASETOOL_MONITORING_ENABLED="$(read_env BASETOOL_MONITORING_ENABLED)"
  export BASETOOL_MONITORING_ENABLED
fi

REGISTRY="${BASETOOL_REGISTRY:-ghcr.io}"
NAMESPACE="${BASETOOL_IMAGE_NAMESPACE:-krt-profit}"
GHCR_USERNAME="${BASETOOL_GHCR_USERNAME:-deploy-bot}"

COSIGN_VERIFY="${BASETOOL_COSIGN_VERIFY:-true}"
COSIGN_REPO="${BASETOOL_COSIGN_REPO:-krt-profit/basetool}"
COSIGN_IDENTITY_REGEXP="${BASETOOL_COSIGN_IDENTITY_REGEXP:-^https://github\\.com/${COSIGN_REPO}/\\.github/workflows/release-images\\.yml@refs/(heads/main|tags/v[0-9]+\\.[0-9]+\\.[0-9]+)$}"
COSIGN_OIDC_ISSUER="${BASETOOL_COSIGN_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"
COSIGN_VERIFY_ATTEMPTS="${BASETOOL_COSIGN_VERIFY_ATTEMPTS:-3}"
COSIGN_VERIFY_DELAY="${BASETOOL_COSIGN_VERIFY_DELAY:-5}"
REQUIRE_ONE_RELEASE="${BASETOOL_REQUIRE_ONE_RELEASE:-true}"
MIXED_RELEASE_GRACE="${BASETOOL_MIXED_RELEASE_GRACE:-900}"
COSIGN_SEARCH_PATH="${BASETOOL_COSIGN_SEARCH_PATH:-/usr/local/bin:/usr/bin:/opt/cosign/bin}"
VERIFY_LAST_ERROR=""

HOST_SCRIPTS_DIR="${BASETOOL_HOST_SCRIPTS_DIR:-${BASETOOL_SCRIPT_DIR}}"
REQUIRE_CURRENT_SCRIPTS="${BASETOOL_REQUIRE_CURRENT_SCRIPTS:-true}"
HOST_SCRIPTS_MANIFEST_NAME="host-scripts.sha256"
APPLY_PATH_SCRIPTS=(deploy.sh lib/common.sh lib/container-runtime.sh lib/deploy-config.sh lib/deploy-drift.sh lib/deploy-observability.sh lib/deploy-release.sh render-env-d.py env_file.py)
HS_DIFFERS=()
HS_MISSING=()
HS_CHECKED=0

TARGET_TAG=stable
TAG_GIVEN=false
CHECK_ONLY=false
FORCE=false
REAPPLY_REQUESTED=false

BACKOFF_BASE="${BASETOOL_BACKOFF_BASE:-600}"
BACKOFF_MAX="${BASETOOL_BACKOFF_MAX:-21600}"

HEALTH_RESTART_BASE="${BASETOOL_HEALTH_RESTART_BASE:-300}"
HEALTH_RESTART_MAX="${BASETOOL_HEALTH_RESTART_MAX:-3600}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      [[ -n "${2:-}" ]] || { echo "FATAL: --tag requires a value" >&2; exit 1; }
      TARGET_TAG="$2"
      TAG_GIVEN=true
      shift 2
      ;;
    --check-only)
      CHECK_ONLY=true
      shift
      ;;
    --force)
      FORCE=true
      shift
      ;;
    --reapply)
      REAPPLY_REQUESTED=true
      shift
      ;;
    -h|--help)
      cat <<'USAGE'
Usage: deploy.sh [--tag <ref> | --reapply] [--check-only] [--force]

Options:
  --tag <ref>     Image tag/ref to deploy. Default: stable
                  Examples: stable, latest, 1.4.2, sha-abc1234
  --reapply       Re-apply the release that is DEPLOYED — the one
                  /var/lib/basetool/deploy/last-deployed.digests records — instead of
                  resolving a tag: re-delivers its config bundle and units,
                  rewrites its digest pin, re-stages its provider JAR (swapped in
                  only if the live one differs) and runs the health gate. Rotates
                  no rollback anchor and, if it fails, rolls nothing back (the
                  failure is backed off like a failed drift re-apply). Bypasses
                  the re-apply backoff, never a rolled-back release's backoff.
                  Refused on a host with no deployed release, and with --tag.
                  Use it instead of deleting last-deployed.digests, which turns
                  the deployed release into a new one and rotates every anchor.
  --check-only    Resolve digests + cosign-verify them, but do not apply
                  (dry-run / signature preflight). Exits non-zero if a signature
                  does not verify; writes no deploy metric. With --reapply, the
                  deployed release's digests.
  --force         Bypass the bad-digest backoff (and the re-apply backoff) and
                  retry a previously failed target now (e.g. after fixing an
                  environmental cause); also applies an operator-gated
                  stateful-infra change.
  -h, --help      Show this help.

Environment overrides (all optional, sensible defaults shown):
  BASETOOL_COMPOSE_DIR=/opt/basetool   (the release tree)
  BASETOOL_CONFIG_DIR=/etc/basetool    (the host config: basetool.env and env.d)
  BASETOOL_ENV_FILE=<CONFIG_DIR>/basetool.env
  BASETOOL_ENV_D_DIR=<CONFIG_DIR>/env.d
  BASETOOL_STATE_DIR=/var/lib/basetool/deploy
  BASETOOL_LOCKFILE=/run/lock/basetool-deploy.lock
  BASETOOL_GHCR_TOKEN_FILE=/etc/basetool/deploy/ghcr-pull-token
  BASETOOL_HEALTH_TIMEOUT=180
  BASETOOL_BACKOFF_BASE=600     (first retry delay after a failed target, seconds)
  BASETOOL_BACKOFF_MAX=21600    (cap for the exponential backoff, seconds)
  BASETOOL_HEALTH_RESTART_BASE=300   (first delay before re-restarting an unhealthy
                                 at-target service — the runtime-health path —
                                 and before retrying a failed re-apply, seconds)
  BASETOOL_HEALTH_RESTART_MAX=3600   (cap for the health-restart and re-apply
                                 backoffs, seconds)
  BASETOOL_REGISTRY=ghcr.io
  BASETOOL_IMAGE_NAMESPACE=krt-profit
  BASETOOL_GHCR_USERNAME=deploy-bot
  BASETOOL_COSIGN_VERIFY=true    (host-side cosign signature gate; false = break-glass,
                            Sigstore-outage only)
  BASETOOL_COSIGN_REPO=krt-profit/basetool   (repo whose release-images.yml identity signs)
  BASETOOL_COSIGN_IDENTITY_REGEXP=...        (override the trusted signer identity regexp)
  BASETOOL_COSIGN_OIDC_ISSUER=https://token.actions.githubusercontent.com
  BASETOOL_COSIGN_VERIFY_ATTEMPTS=3   (verify retries; a registry/Sigstore blip must
                                  not read as an untrusted image)
  BASETOOL_COSIGN_VERIFY_DELAY=5      (first retry delay in seconds, then doubling)
  BASETOOL_REQUIRE_ONE_RELEASE=true   (refuse a target set whose images do not share one
                                  org.opencontainers.image.revision; false = break-glass)
  BASETOOL_MIXED_RELEASE_GRACE=900   (seconds a mixed target set is waited out as a
                                  promotion in progress before it fails the deploy)
  BASETOOL_REQUIRE_CURRENT_SCRIPTS=true   (refuse a release whose config bundle lists a
                                  deploy.sh, lib/ or render-env-d.py hash the host's
                                  copy does not have; false = break-glass, --force
                                  also bypasses it)
  BASETOOL_HOST_SCRIPTS_DIR=<dir of this script>   (the host scripts that are hashed)
  DOCKER_CONFIG=/var/lib/basetool/deploy/.docker   (the registry credential cosign reads
                                        and skopeo shares via
                                        REGISTRY_AUTH_FILE; under STATE_DIR
                                        because the deploy user has no \$HOME)
USAGE
      exit 0
      ;;
    *)
      echo "FATAL: unknown argument: $1 (try --help)" >&2
      exit 1
      ;;
  esac
done

if [[ "${REAPPLY_REQUESTED}" == "true" && "${TAG_GIVEN}" == "true" ]]; then
  echo "FATAL: --reapply re-applies the deployed release and cannot be combined with --tag (try --help)" >&2
  exit 1
fi

require_file() {
  [[ -f "$1" ]] || fail "required file missing: $1"
}

require_file "${ENV_FILE}"
require_file "${TOKEN_FILE}"

mkdir -p "${STATE_DIR}"

export DOCKER_CONFIG="${DOCKER_CONFIG:-${STATE_DIR}/.docker}"
install -d -m 0700 "${DOCKER_CONFIG}"
export REGISTRY_AUTH_FILE="${REGISTRY_AUTH_FILE:-${DOCKER_CONFIG}/config.json}"

export HOME="${BASETOOL_HOME:-${STATE_DIR}}"

ENV_RENDERER="${BASETOOL_ENV_RENDERER:-${BASETOOL_SCRIPT_DIR}/render-env-d.py}"

rt_detect
export RT_HEALTH_TIMEOUT="${HEALTH_TIMEOUT}"
export RT_STACK_SERVICES="db-backend db-keycloak redis keycloak edge backend ingest frontend acme"
log "container runtime: ${RT_BACKEND}"

command -v skopeo >/dev/null 2>&1 \
  || fail "skopeo not available; it is how a tag is resolved without pulling (ansible role: 10-packages.yml)"
QUADLET_BIN="${BASETOOL_QUADLET_BIN:-}"
if [[ -z "${QUADLET_BIN}" ]]; then
  for candidate in /usr/libexec/podman/quadlet /usr/lib/podman/quadlet; do
    [[ -x "${candidate}" ]] && { QUADLET_BIN="${candidate}"; break; }
  done
fi
[[ -n "${QUADLET_BIN}" && -x "${QUADLET_BIN}" ]] \
  || fail "the Quadlet generator is missing (looked in /usr/libexec/podman and /usr/lib/podman); this host cannot turn .container files into services"
[[ -n "${RT_UNIT_DIR}" && -d "${RT_UNIT_DIR}" ]] \
  || fail "no Quadlet unit directory (${RT_UNIT_DIR:-unset}) — run the basetool_host role first; it creates the directory this fills"

keystore_mount_sources() {
  local src=""
  src="$(grep -h -E '^Volume=[^:]+:(/run/secrets/[A-Za-z0-9._-]+\.p12|/etc/nginx/[A-Za-z0-9._-]+\.crt)(:|$)' "${RT_UNIT_DIR}"/*.container \
           2>/dev/null | sed -E 's/^Volume=([^:]+):.*/\1/' | sort -u || true)"
  if [[ -z "${src}" ]]; then
    src="$(read_env BASETOOL_KEYSTORE_HOST_PATH || true)"
  fi
  printf '%s\n' "${src:-/etc/basetool/secrets/keystore.p12}"
}
while IFS= read -r KEYSTORE_HOST_PATH; do
  if [[ -n "${KEYSTORE_HOST_PATH}" ]]; then
    require_file "${KEYSTORE_HOST_PATH}"
  fi
done < <(keystore_mount_sources)

if [[ "${COSIGN_VERIFY}" == "true" ]] && ! command -v cosign >/dev/null 2>&1; then
  IFS=':' read -r -a _cosign_dirs <<< "${COSIGN_SEARCH_PATH}"
  for _cosign_dir in "${_cosign_dirs[@]}"; do
    [[ -n "${_cosign_dir}" && -x "${_cosign_dir}/cosign" ]] || continue
    PATH="${_cosign_dir}:${PATH}"
    export PATH
    log "cosign found at ${_cosign_dir}/cosign but not on PATH (sudo secure_path?) — using it"
    break
  done
  unset _cosign_dir _cosign_dirs
fi
if [[ "${COSIGN_VERIFY}" == "true" ]] && ! command -v cosign >/dev/null 2>&1; then
  fail "cosign not found on PATH or under ${COSIGN_SEARCH_PATH}, but signature verification is enabled — install cosign (see docs/deployment.md → 'Signature verification (cosign)') or set BASETOOL_COSIGN_VERIFY=false ONLY to break glass during a Sigstore outage"
fi

PIN_FILE_CURRENT="${STATE_DIR}/current-digest-pin.yml"
PIN_FILE_PREVIOUS="${STATE_DIR}/previous-digest-pin.yml"
LAST_DEPLOYED_FILE="${STATE_DIR}/last-deployed.digests"
FAILED_FILE="${STATE_DIR}/failed.digests"
REAPPLY_FAILED_FILE="${STATE_DIR}/reapply-failed.digests"
CONFIG_STAGE_DIR="${STATE_DIR}/config-stage"
CONFIG_PREVIOUS_DIR="${STATE_DIR}/config-previous"
MON_RELOAD_STATE_DIR="${STATE_DIR}/monitoring-reload"
EDGE_STATE_DIR="${STATE_DIR}/edge"
CONFIG_BLOCKED_FILE="${STATE_DIR}/config-blocked.marker"
CONFIG_APPLY_INCOMPLETE_FILE="${STATE_DIR}/config-apply.incomplete"
KEYCLOAK_SPI_JAR="${COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar"
KEYCLOAK_SPI_STAGE_JAR="${STATE_DIR}/keycloak-spi-stage.jar"
KEYCLOAK_SPI_PREVIOUS_JAR="${STATE_DIR}/keycloak-spi-previous.jar"
HEALTH_RESTART_FILE="${STATE_DIR}/health-restart.digests"
MIXED_RELEASE_FILE="${STATE_DIR}/mixed-release.since"

TEXTFILE_DIR="${BASETOOL_MONITORING_TEXTFILE_DIR:-/var/lib/basetool/textfile}"
DEPLOY_METRIC_FILE="${TEXTFILE_DIR}/deploy.prom"
STACK_HEALTH_METRIC_FILE="${TEXTFILE_DIR}/deploy-health.prom"
START_EPOCH="$(date +%s)"

TOKEN_EXPIRY_FILE="${BASETOOL_GHCR_TOKEN_EXPIRY_FILE:-${TOKEN_FILE}.expiry}"
TOKEN_METRIC_FILE="${TEXTFILE_DIR}/ghcr-token.prom"

record_target_failure() {
  local file="${1:-${FAILED_FILE}}" prev_marker="" prev_count=""
  FAIL_COUNT=1
  if [[ -f "${file}" ]]; then
    read -r prev_marker prev_count _ < "${file}" || true
    if [[ "${prev_marker}" == "${EXPECTED_MARKER}" ]] && [[ "${prev_count}" =~ ^[0-9]+$ ]]; then
      FAIL_COUNT=$(( 10#${prev_count} + 1 ))
    fi
  fi
  printf '%s %d %d\n' "${EXPECTED_MARKER}" "${FAIL_COUNT}" "$(date +%s)" > "${file}"
}

backoff_seconds() {
  local base="$1" max="$2" count="$3" b
  if (( 10#${count} > 20 )); then
    echo "${max}"
    return 0
  fi
  b=$(( base * (2 ** (10#${count} - 1)) ))
  (( b > max )) && b="${max}"
  echo "${b}"
}

release_parts() {
  local config=no jar=no
  [[ "${CONFIG_CHANGED}" == "true" ]] && config=yes
  [[ "${KEYCLOAK_SPI_CHANGED}" == "true" ]] && jar=yes
  printf 'app images [%s] · unit definitions [%s] · config bundle: %s · provider JAR: %s' \
    "${APP_IMAGES_CHANGED:-none}" "${UNITS_REDEFINED:-none}" "${config}" "${jar}"
}

explain_gate_failure() {
  local first="${RT_FAILED_SERVICES%% *}" img=no kc_redefined=false
  case " ${UNITS_REDEFINED} " in *" keycloak "*) kc_redefined=true ;; esac
  log "health gate: did not come up, in start order: [${RT_FAILED_SERVICES:-none reported}]"
  log "health gate: this release changed: $(release_parts)"
  if [[ -z "${first}" ]]; then
    log "health gate: every unit started, so it was the stop of the re-defined units that failed (above) — their new definition may not have reached the containers"
    return 0
  fi
  if [[ "${first}" == "keycloak" ]]; then
    if [[ "${KEYCLOAK_SPI_CHANGED}" == "true" && "${kc_redefined}" != "true" ]]; then
      if [[ -z "${APP_IMAGES_CHANGED}${UNITS_REDEFINED}" && "${CONFIG_CHANGED}" != "true" ]]; then
        log "health gate: KEYCLOAK did not come up, and the provider JAR is the ONLY change in this release — the JAR is the cause"
      else
        log "health gate: KEYCLOAK did not come up, and the provider JAR is the only part of this release that changed keycloak — the JAR is the likely cause; what failed after keycloak requires it"
      fi
    elif [[ "${KEYCLOAK_SPI_CHANGED}" == "true" ]]; then
      log "health gate: KEYCLOAK did not come up; this release changed both its unit and its provider JAR — either can be the cause"
    else
      log "health gate: KEYCLOAK did not come up; its provider JAR did not change in this release"
    fi
    return 0
  fi
  case " ${APP_IMAGES_CHANGED} " in *" ${first} "*) img=yes ;; esac
  if [[ "${KEYCLOAK_SPI_CHANGED}" == "true" && " ${RT_FAILED_SERVICES} " != *" keycloak "* ]]; then
    log "health gate: keycloak is up on the new provider JAR; the first unit that did not come up is ${first} (its image changed: ${img}). A new app image and a new JAR fail the same gate, so this cannot tell which of them it was (ADR-0213) — both are rolled back"
  else
    log "health gate: the first unit that did not come up is ${first} (its image changed: ${img})"
  fi
}

DEPLOY_STEP=""
PRE_GATE_GUARD=false
CONFIG_TREE_TOUCHED=false
PIN_TOUCHED=false
PIN_HAD_PREVIOUS=false
KEYCLOAK_SPI_TOUCHED=false
KEYCLOAK_SPI_HAD_PREVIOUS=false
UNITS_REDEFINED=""
APP_IMAGES_CHANGED=""

REAPPLY=false

record_reapply_failure() {
  local what="$1"
  record_target_failure "${REAPPLY_FAILED_FILE}"
  log "${what} — re-apply of the deployed release failed (#${FAIL_COUNT}); nothing is rolled back: the target IS the deployed release, and the rollback anchors keep naming the release before it"
  log "recorded re-apply failure #${FAIL_COUNT} for the deployed target; the next automatic attempt backs off $(backoff_seconds "${HEALTH_RESTART_BASE}" "${HEALTH_RESTART_MAX}" "${FAIL_COUNT}")s (deploy.sh --reapply or --force retries now)"
  write_stack_health_metric restart_failed
}

# shellcheck disable=SC2317,SC2329
on_pre_gate_exit() {
  local rc=$?
  [[ "${PRE_GATE_GUARD}" == "true" ]] || return 0
  (( rc != 0 )) || return 0
  PRE_GATE_GUARD=false
  set +e
  log "FATAL: deploy aborted before the health gate — step '${DEPLOY_STEP:-unknown}' failed (exit ${rc})"

  if [[ "${REAPPLY}" == "true" ]]; then
    record_reapply_failure "step '${DEPLOY_STEP:-unknown}' failed"
    exit "${rc}"
  fi

  if [[ "${CONFIG_TREE_TOUCHED}" == "true" ]]; then
    restore_previous_config_tree
    case $? in
      0) rm -f "${CONFIG_APPLY_INCOMPLETE_FILE}" ;;
      2) log "no previous definition to go back to — what was applied of the first bundle stays, and the next attempt applies it again" ;;
      *) log "FATAL: the host config tree under ${COMPOSE_DIR} is INCONSISTENT — part of it is the failed release; ${CONFIG_PREVIOUS_DIR} is kept as the rollback anchor (${CONFIG_APPLY_INCOMPLETE_FILE}). Fix the cause, then: deploy.sh --force" ;;
    esac
  fi

  if [[ "${PIN_TOUCHED}" == "true" ]]; then
    if [[ "${PIN_HAD_PREVIOUS}" == "true" ]] && rt_pin_rollback; then
      log "digest pin restored to the previous release"
    elif [[ "${PIN_HAD_PREVIOUS}" != "true" ]]; then
      rm -f "${PIN_FILE_CURRENT}"
      rt_pin_clear backend; rt_pin_clear frontend; rt_pin_clear ingest
      log "digest pin written by this run removed (there was none before it)"
    else
      log "WARNING: could not restore the previous digest pin from ${PIN_FILE_PREVIOUS}"
    fi
  fi

  if [[ "${KEYCLOAK_SPI_TOUCHED}" == "true" ]]; then
    if restore_previous_keycloak_spi_jar; then
      log "provider JAR restored to the previous one"
    else
      log "WARNING: could not restore the previous provider JAR from ${KEYCLOAK_SPI_PREVIOUS_JAR}"
    fi
  fi

  record_target_failure
  log "recorded pre-gate failure #${FAIL_COUNT} for this target; the next attempt backs off (--force retries now)"
  write_deploy_metric failure
  exit "${rc}"
}

exec 200>"${LOCKFILE}"
if ! flock -n 200; then
  log "another deploy is in progress (lock: ${LOCKFILE}); exiting"
  exit 0
fi

REAPPLY_BACKEND="" REAPPLY_FRONTEND="" REAPPLY_INGEST="" REAPPLY_CONFIG="" REAPPLY_KCSPI=""
if [[ "${REAPPLY_REQUESTED}" == "true" ]]; then
  if [[ ! -s "${LAST_DEPLOYED_FILE}" ]]; then
    fail "--reapply: no deployed release on this host — ${LAST_DEPLOYED_FILE} is missing or empty, so there is nothing to re-apply. Deploy a release instead: deploy.sh (or deploy.sh --tag <ref>)"
  fi
  reapply_extra=""
  IFS='|' read -r REAPPLY_BACKEND REAPPLY_FRONTEND REAPPLY_INGEST REAPPLY_CONFIG REAPPLY_KCSPI reapply_extra \
    < "${LAST_DEPLOYED_FILE}" || true
  for reapply_field in REAPPLY_BACKEND REAPPLY_FRONTEND REAPPLY_INGEST REAPPLY_CONFIG REAPPLY_KCSPI; do
    case "${reapply_field}" in
      REAPPLY_BACKEND | REAPPLY_FRONTEND | REAPPLY_INGEST) reapply_optional=false ;;
      *) reapply_optional=true ;;
    esac
    if [[ -z "${!reapply_field}" && "${reapply_optional}" == "true" ]]; then
      continue
    fi
    [[ "${!reapply_field}" =~ ^sha256:[0-9a-f]{64}$ ]] \
      || fail "--reapply: ${LAST_DEPLOYED_FILE} is not a deployed-release record (field ${reapply_field#REAPPLY_} is '${!reapply_field}') — refusing to guess the deployed release"
  done
  [[ -z "${reapply_extra}" ]] \
    || fail "--reapply: ${LAST_DEPLOYED_FILE} has more than five fields — refusing to guess the deployed release"
  if [[ -f "${PIN_FILE_CURRENT}" ]]; then
    for reapply_pair in "backend=${REAPPLY_BACKEND}" "frontend=${REAPPLY_FRONTEND}" "ingest=${REAPPLY_INGEST}"; do
      reapply_pinned="$(grep -oE "/basetool-${reapply_pair%%=*}@sha256:[0-9a-f]{64}" "${PIN_FILE_CURRENT}" | head -n 1 || true)"
      if [[ -n "${reapply_pinned}" && "${reapply_pinned#*@}" != "${reapply_pair#*=}" ]]; then
        fail "--reapply: ${PIN_FILE_CURRENT} pins ${reapply_pair%%=*} to ${reapply_pinned#*@}, but ${LAST_DEPLOYED_FILE} records ${reapply_pair#*=} — the host disagrees about what is deployed; refusing to guess. Check both, then deploy a release (deploy.sh --tag <ref>)"
      fi
    done
  fi
  unset reapply_extra reapply_field reapply_optional reapply_pair reapply_pinned
fi

log "logging in to ${REGISTRY} as ${GHCR_USERNAME}"
if ! rt_login "${REGISTRY}" "${GHCR_USERNAME}" "${TOKEN_FILE}" >/dev/null 2>&1; then
  fail "${RT_BACKEND} login to ${REGISTRY} failed — check ${TOKEN_FILE} (scope: read:packages)"
fi

write_token_expiry_metric

BACKEND_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-backend"
FRONTEND_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-frontend"
INGEST_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-ingest"
CONFIG_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-config"
KEYCLOAK_SPI_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-keycloak-spi"

resolve_digest() {
  rt_resolve_digest "$1"
}

if [[ "${REAPPLY_REQUESTED}" == "true" ]]; then
  log "--reapply: target is the deployed release recorded in ${LAST_DEPLOYED_FILE} (no tag is resolved)"
  BACKEND_DIGEST="${REAPPLY_BACKEND}"
  FRONTEND_DIGEST="${REAPPLY_FRONTEND}"
  INGEST_DIGEST="${REAPPLY_INGEST}"
  CONFIG_DIGEST="${REAPPLY_CONFIG}"
  KEYCLOAK_SPI_DIGEST="${REAPPLY_KCSPI}"
else
  log "resolving ${TARGET_TAG} → digest"
  BACKEND_DIGEST="$(resolve_digest "${BACKEND_IMAGE}:${TARGET_TAG}")" \
    || fail "cannot resolve ${BACKEND_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"
  FRONTEND_DIGEST="$(resolve_digest "${FRONTEND_IMAGE}:${TARGET_TAG}")" \
    || fail "cannot resolve ${FRONTEND_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"
  INGEST_DIGEST="$(resolve_digest "${INGEST_IMAGE}:${TARGET_TAG}")" \
    || fail "cannot resolve ${INGEST_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"

  CONFIG_DIGEST="$(resolve_digest "${CONFIG_IMAGE}:${TARGET_TAG}")" || CONFIG_DIGEST=""

  KEYCLOAK_SPI_DIGEST="$(resolve_digest "${KEYCLOAK_SPI_IMAGE}:${TARGET_TAG}")" || KEYCLOAK_SPI_DIGEST=""
fi

log "target backend  ${BACKEND_DIGEST}"
log "target frontend ${FRONTEND_DIGEST}"
log "target ingest   ${INGEST_DIGEST}"

if [[ -n "${CONFIG_DIGEST}" ]]; then
  log "target config   ${CONFIG_DIGEST}"
else
  log "target config   unavailable (${CONFIG_IMAGE}:${TARGET_TAG} not resolvable) — no config change this tick"
fi
if [[ -n "${KEYCLOAK_SPI_DIGEST}" ]]; then
  log "target kc-spi   ${KEYCLOAK_SPI_DIGEST}"
else
  log "target kc-spi   unavailable (${KEYCLOAK_SPI_IMAGE}:${TARGET_TAG} not resolvable) — no provider-JAR change this tick"
fi
EXPECTED_MARKER="${BACKEND_DIGEST}|${FRONTEND_DIGEST}|${INGEST_DIGEST}|${CONFIG_DIGEST}|${KEYCLOAK_SPI_DIGEST}"

LAST_CONFIG_DIGEST=""
LAST_KEYCLOAK_SPI_DIGEST=""
if [[ -f "${LAST_DEPLOYED_FILE}" ]]; then
  IFS='|' read -r _ _ _ LAST_CONFIG_DIGEST LAST_KEYCLOAK_SPI_DIGEST < "${LAST_DEPLOYED_FILE}" || true
fi
CONFIG_CHANGED=false
if [[ -n "${CONFIG_DIGEST}" ]] && [[ "${CONFIG_DIGEST}" != "${LAST_CONFIG_DIGEST}" ]]; then
  CONFIG_CHANGED=true
fi
KEYCLOAK_SPI_CHANGED=false
if [[ -n "${KEYCLOAK_SPI_DIGEST}" ]] && [[ "${KEYCLOAK_SPI_DIGEST}" != "${LAST_KEYCLOAK_SPI_DIGEST}" ]]; then
  KEYCLOAK_SPI_CHANGED=true
fi
if [[ "${REAPPLY_REQUESTED}" == "true" && -n "${CONFIG_DIGEST}" ]]; then
  CONFIG_CHANGED=true
fi
if [[ "${REAPPLY_REQUESTED}" == "true" && -n "${KEYCLOAK_SPI_DIGEST}" ]]; then
  KEYCLOAK_SPI_CHANGED=true
fi

if [[ "${CONFIG_CHANGED}" != "true" ]]; then
  shopt -s nullglob
  host_units=( "${RT_UNIT_DIR}"/*.container )
  shopt -u nullglob
  if (( ${#host_units[@]} == 0 )); then
    CONFIG_CHANGED=true
    log "no Quadlet units on this host — staging the config bundle to install them"
  fi
fi

if [[ "${CONFIG_CHANGED}" != "true" ]]; then
  check_host_scripts_applied
fi

running_stack_drift() {
  local entry svc image digest cids cid state img_id repo_digests img_ok
  local -a units=()
  shopt -s nullglob
  units=( "${RT_UNIT_DIR}"/*.container )
  shopt -u nullglob
  if (( ${#units[@]} == 0 )); then
    echo "structural quadlet: no unit files in ${RT_UNIT_DIR}"
  fi
  for entry in \
    "backend|${BACKEND_IMAGE}|${BACKEND_DIGEST}" \
    "frontend|${FRONTEND_IMAGE}|${FRONTEND_DIGEST}" \
    "ingest|${INGEST_IMAGE}|${INGEST_DIGEST}"; do
    IFS='|' read -r svc image digest <<< "${entry}"
    cids="$(rt_service_container_ids "${svc}")" || cids=""
    if [[ -z "${cids}" ]]; then
      echo "structural ${svc}: no container"
      continue
    fi
    while IFS= read -r cid; do
      [[ -n "${cid}" ]] || continue
      state="$(rt_container_probe "${cid}")" || state="gone"
      img_id="$(rt_container_image_id "${cid}")" || img_id=""
      repo_digests=""
      if [[ -n "${img_id}" ]]; then
        repo_digests="$(rt_image_repo_digests "${img_id}")" || repo_digests=""
      fi
      img_ok=false
      case " ${repo_digests} " in
        *" ${image}@${digest} "*) img_ok=true ;;
      esac
      case "${state}" in
        running/healthy | running/no-healthcheck | running/starting)
          if [[ "${img_ok}" != "true" ]]; then
            echo "structural ${svc}: running image [${repo_digests:-unknown}] does not match target ${digest}"
          fi
          ;;
        *)
          if [[ "${img_ok}" == "true" ]]; then
            echo "health ${svc}: container state ${state}"
          else
            echo "structural ${svc}: container state ${state}, image [${repo_digests:-unknown}] not at target ${digest}"
          fi
          ;;
      esac
    done <<< "${cids}"
  done
  return 0
}

DRIFTED=false
NOOP=false
HEALTH_DRIFT=false
HEALTH_LEFT_OVER=""
if [[ "${REAPPLY_REQUESTED}" == "true" ]]; then
  DRIFT_REPORT="$(running_stack_drift)"
  if [[ -n "${DRIFT_REPORT}" ]]; then
    while IFS= read -r drift_line; do
      log "drift: ${drift_line#* }"
    done <<< "${DRIFT_REPORT}"
  else
    log "--reapply: the running stack matches the deployed release — re-applying it anyway, as asked"
  fi
  DRIFTED=true
  REAPPLY=true
  log "--reapply: re-applying the deployed release — the rollback anchors keep naming the release before it"
  HEALTH_LEFT_OVER="$(grep '^health ' <<< "${DRIFT_REPORT}" \
    | sed -E 's/^health ([a-z]+):.*/\1/' | sort -u | tr '\n' ' ' || true)"
  HEALTH_LEFT_OVER="${HEALTH_LEFT_OVER% }"
  if [[ -n "${HEALTH_LEFT_OVER}" ]]; then
    log "drift: [${HEALTH_LEFT_OVER}] unhealthy on the deployed image — not recreated by this re-apply, left for the targeted heal"
  fi
elif [[ -f "${LAST_DEPLOYED_FILE}" ]] \
   && grep -qFx "${EXPECTED_MARKER}" "${LAST_DEPLOYED_FILE}"; then
  DRIFT_REPORT="$(running_stack_drift)"
  if [[ -z "${DRIFT_REPORT}" ]]; then
    if [[ "${CHECK_ONLY}" != "true" ]]; then
      log "no change — already at target digests (running stack verified)"
      write_stack_health_metric healthy
      reconcile_monitoring_reloads
      reconcile_edge
      exit 0
    fi
    NOOP=true
  else
    while IFS= read -r drift_line; do
      log "drift: ${drift_line#* }"
    done <<< "${DRIFT_REPORT}"
    if grep -q '^structural ' <<< "${DRIFT_REPORT}"; then
      DRIFTED=true
      REAPPLY=true
      log "running stack does not match the last-deployed target — re-applying"
      HEALTH_LEFT_OVER="$(grep '^health ' <<< "${DRIFT_REPORT}" \
        | sed -E 's/^health ([a-z]+):.*/\1/' | sort -u | tr '\n' ' ' || true)"
      HEALTH_LEFT_OVER="${HEALTH_LEFT_OVER% }"
      if [[ -n "${HEALTH_LEFT_OVER}" ]]; then
        log "drift: [${HEALTH_LEFT_OVER}] unhealthy on the target image — not part of this re-apply, left for the targeted heal of the next tick"
      fi
    else
      HEALTH_DRIFT=true
      log "running stack is at the target release but a container is unhealthy — targeted restart (not a release rollback)"
    fi
  fi
fi

if [[ "${CHECK_ONLY}" == "true" ]]; then
  if [[ "${REAPPLY_REQUESTED}" == "true" ]]; then
    log "check-only: would re-apply the deployed release (--reapply; no anchor rotates, no rollback)"
  elif [[ "${NOOP}" == "true" ]]; then
    log "check-only: no change (already at target digests, running stack verified)"
  elif [[ "${HEALTH_DRIFT}" == "true" ]]; then
    log "check-only: would restart unhealthy at-target service(s) (runtime-health drift, not a release rollback)"
  elif [[ "${DRIFTED}" == "true" ]]; then
    log "check-only: would re-apply (running stack drifted from target digests)"
  else
    log "check-only: would deploy"
  fi
  log "check-only: verifying image signatures (cosign keyless)"
  co_rc=0
  check_only_verify_one "backend"  "${BACKEND_IMAGE}@${BACKEND_DIGEST}"  || co_rc=1
  check_only_verify_one "frontend" "${FRONTEND_IMAGE}@${FRONTEND_DIGEST}" || co_rc=1
  check_only_verify_one "ingest"   "${INGEST_IMAGE}@${INGEST_DIGEST}"     || co_rc=1
  if [[ -n "${CONFIG_DIGEST}" ]]; then
    check_only_verify_one "config" "${CONFIG_IMAGE}@${CONFIG_DIGEST}" || co_rc=1
  fi
  if [[ -n "${KEYCLOAK_SPI_DIGEST}" ]]; then
    check_only_verify_one "keycloak-spi" "${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST}" || co_rc=1
  fi
  if [[ "${co_rc}" -eq 0 && "${REAPPLY_REQUESTED}" != "true" && "${REQUIRE_ONE_RELEASE}" == "true" ]]; then
    rev_problem="$(release_revision_problem)" || true
    if [[ -n "${rev_problem}" ]]; then
      log "check-only: the ${TARGET_TAG} set is not one release: ${rev_problem}"
      co_rc=1
    fi
  fi
  if [[ "${co_rc}" -eq 0 ]]; then
    log "check-only: all signatures verified OK"
  else
    log "check-only: SIGNATURE VERIFICATION FAILED for one or more artifacts"
  fi
  exit "${co_rc}"
fi

if [[ "${HEALTH_DRIFT}" == "true" ]]; then
  UNHEALTHY_SVCS="$(grep '^health ' <<< "${DRIFT_REPORT}" \
    | sed -E 's/^health ([a-z]+):.*/\1/' | sort -u | tr '\n' ' ')"
  UNHEALTHY_SVCS="${UNHEALTHY_SVCS% }"

  if [[ -f "${HEALTH_RESTART_FILE}" ]]; then
    read -r HR_MARKER HR_COUNT HR_EPOCH _ < "${HEALTH_RESTART_FILE}" || true
    if [[ "${HR_MARKER:-}" != "${EXPECTED_MARKER}" ]] \
       || ! [[ "${HR_COUNT:-}" =~ ^[0-9]+$ ]] || ! [[ "${HR_EPOCH:-}" =~ ^[0-9]+$ ]]; then
      rm -f "${HEALTH_RESTART_FILE}"
    elif [[ "${FORCE}" == "true" ]]; then
      log "health drift on [${UNHEALTHY_SVCS}]: ${HR_COUNT} prior restart(s) failed; --force — restarting now"
    else
      hr_backoff=$(( HEALTH_RESTART_BASE * (2 ** (10#${HR_COUNT} - 1)) ))
      if (( hr_backoff > HEALTH_RESTART_MAX )); then
        hr_backoff="${HEALTH_RESTART_MAX}"
      fi
      hr_elapsed=$(( $(date +%s) - 10#${HR_EPOCH} ))
      if (( hr_elapsed < hr_backoff )); then
        log "health drift on [${UNHEALTHY_SVCS}]: targeted restart failed ${HR_COUNT}x; in backoff (${hr_elapsed}s/${hr_backoff}s) — skipping tick (--force to retry now)"
        exit 1
      fi
      log "health drift on [${UNHEALTHY_SVCS}]: restart backoff ${hr_backoff}s elapsed — retrying"
    fi
  fi

  cd "${COMPOSE_DIR}"
  log "health drift: restarting unhealthy service(s) [${UNHEALTHY_SVCS}] (targeted; no pull, no signature re-verify, no release rollback)"
  log "health drift: one restart window — stopping [${UNHEALTHY_SVCS}] and what requires them, then starting the stack in order"
  HR_RC=0
  # shellcheck disable=SC2086
  rt_heal_stack ${UNHEALTHY_SVCS} || HR_RC=1
  if [[ "${HR_RC}" -eq 0 ]]; then
    rm -f "${HEALTH_RESTART_FILE}"
    log "health drift resolved — service(s) [${UNHEALTHY_SVCS}] healthy again after targeted restart, and every unit that requires them is up"
    write_stack_health_metric healthy
    exit 0
  fi
  log "health drift: did not come up, in start order: [${RT_FAILED_SERVICES:-none reported}]"

  HR_COUNT=1
  if [[ -f "${HEALTH_RESTART_FILE}" ]]; then
    read -r PREV_HR_MARKER PREV_HR_COUNT _ < "${HEALTH_RESTART_FILE}" || true
    if [[ "${PREV_HR_MARKER:-}" == "${EXPECTED_MARKER}" ]] && [[ "${PREV_HR_COUNT:-}" =~ ^[0-9]+$ ]]; then
      HR_COUNT=$(( 10#${PREV_HR_COUNT} + 1 ))
    fi
  fi
  printf '%s %d %d\n' "${EXPECTED_MARKER}" "${HR_COUNT}" "$(date +%s)" > "${HEALTH_RESTART_FILE}"
  log "targeted restart of [${UNHEALTHY_SVCS}] did NOT restore health (attempt #${HR_COUNT}) — runtime fault on the deployed release; the release is left in place (no rollback)"
  write_stack_health_metric restart_failed
  exit 1
fi

if [[ "${REAPPLY}" == "true" ]]; then
  if [[ -f "${REAPPLY_FAILED_FILE}" ]]; then
    read -r REC_MARKER REC_COUNT REC_EPOCH _ < "${REAPPLY_FAILED_FILE}" || true
    if [[ "${REC_MARKER:-}" != "${EXPECTED_MARKER}" ]] \
       || ! [[ "${REC_COUNT:-}" =~ ^[0-9]+$ ]] \
       || ! [[ "${REC_EPOCH:-}" =~ ^[0-9]+$ ]]; then
      rm -f "${REAPPLY_FAILED_FILE}"
    elif [[ "${FORCE}" == "true" ]]; then
      log "re-apply of the deployed release previously failed ${REC_COUNT}x; --force given — retrying now"
    elif [[ "${REAPPLY_REQUESTED}" == "true" ]]; then
      log "re-apply of the deployed release previously failed ${REC_COUNT}x; --reapply asked for it — retrying now"
    else
      backoff="$(backoff_seconds "${HEALTH_RESTART_BASE}" "${HEALTH_RESTART_MAX}" "${REC_COUNT}")"
      elapsed=$(( $(date +%s) - 10#${REC_EPOCH} ))
      if (( elapsed < backoff )); then
        log "re-apply of the deployed release failed ${REC_COUNT}x; in backoff window (${elapsed}s/${backoff}s) — skipping this tick (deploy.sh --reapply or --force retries now)"
        exit 0
      fi
      log "re-apply of the deployed release failed ${REC_COUNT}x; backoff of ${backoff}s elapsed — retrying"
    fi
  fi
elif [[ -f "${FAILED_FILE}" ]]; then
  read -r REC_MARKER REC_COUNT REC_EPOCH _ < "${FAILED_FILE}" || true
  if [[ "${REC_MARKER:-}" != "${EXPECTED_MARKER}" ]] \
     || ! [[ "${REC_COUNT:-}" =~ ^[0-9]+$ ]] \
     || ! [[ "${REC_EPOCH:-}" =~ ^[0-9]+$ ]]; then
    rm -f "${FAILED_FILE}"
  elif [[ "${FORCE}" == "true" ]]; then
    log "target previously failed ${REC_COUNT}x; --force given — retrying now"
  else
    backoff="$(backoff_seconds "${BACKOFF_BASE}" "${BACKOFF_MAX}" "${REC_COUNT}")"
    elapsed=$(( $(date +%s) - 10#${REC_EPOCH} ))
    if (( elapsed < backoff )); then
      log "target failed ${REC_COUNT}x; in backoff window (${elapsed}s/${backoff}s) — skipping this tick (promote a fixed image or pass --force)"
      exit 0
    fi
    log "target failed ${REC_COUNT}x; backoff of ${backoff}s elapsed — retrying"
  fi
fi

log "verifying image signatures (cosign keyless)"
verify_digest_or_die "backend"  "${BACKEND_IMAGE}@${BACKEND_DIGEST}"
verify_digest_or_die "frontend" "${FRONTEND_IMAGE}@${FRONTEND_DIGEST}"
verify_digest_or_die "ingest"   "${INGEST_IMAGE}@${INGEST_DIGEST}"
[[ -n "${CONFIG_DIGEST}" ]]       && verify_digest_or_die "config"       "${CONFIG_IMAGE}@${CONFIG_DIGEST}"
[[ -n "${KEYCLOAK_SPI_DIGEST}" ]] && verify_digest_or_die "keycloak-spi" "${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST}"

if [[ "${REAPPLY_REQUESTED}" != "true" ]]; then
  assert_one_release_or_die
fi

export RT_PIN_FILE="${PIN_FILE_CURRENT}"
export RT_PIN_FILE_PREVIOUS="${PIN_FILE_PREVIOUS}"
trap on_pre_gate_exit EXIT
PRE_GATE_GUARD=true

if [[ "${KEYCLOAK_SPI_CHANGED}" == "true" ]]; then
  if [[ "${REAPPLY_REQUESTED}" == "true" ]]; then
    log "--reapply: re-staging the deployed provider JAR ${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST} (swapped in only if the live one differs)"
  else
    log "keycloak-spi changed → staging ${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST} (applied with this release, one restart)"
  fi
  DEPLOY_STEP="extract the keycloak-spi provider JAR"
  stage_keycloak_spi_jar "${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST}"
fi

if [[ "${CONFIG_CHANGED}" == "true" ]]; then
  if [[ "${REAPPLY_REQUESTED}" == "true" ]]; then
    log "--reapply: re-delivering the deployed config bundle ${CONFIG_IMAGE}@${CONFIG_DIGEST}"
  else
    log "config changed → staging ${CONFIG_IMAGE}@${CONFIG_DIGEST}"
  fi
  DEPLOY_STEP="extract the config bundle"
  extract_config_bundle "${CONFIG_IMAGE}@${CONFIG_DIGEST}" "${CONFIG_STAGE_DIR}"
  DEPLOY_STEP="check the staged config bundle"
  require_file "${CONFIG_STAGE_DIR}/docker-compose.yml"
  assert_no_secrets "${CONFIG_STAGE_DIR}"
  assert_host_scripts_current_or_die "${CONFIG_STAGE_DIR}/${HOST_SCRIPTS_MANIFEST_NAME}"

  OLD_INFRA="$(infra_image_pins "${COMPOSE_DIR}")"
  NEW_INFRA="$(infra_image_pins "${CONFIG_STAGE_DIR}")"
  HAS_OLD_DEFINITION=false
  if [[ -d "${COMPOSE_DIR}/quadlet/systemd" ]]; then
    HAS_OLD_DEFINITION=true
  fi
  if [[ "${HAS_OLD_DEFINITION}" != "true" ]]; then
    log "first config bundle on this host — no previous definition to compare, so the stateful-infra gate does not apply"
  elif [[ "${OLD_INFRA}" != "${NEW_INFRA}" ]]; then
    if [[ "${FORCE}" != "true" ]]; then
      if [[ -f "${CONFIG_BLOCKED_FILE}" ]] && grep -qFx "${EXPECTED_MARKER}" "${CONFIG_BLOCKED_FILE}"; then
        log "stateful-infra upgrade still operator-gated for this target; skipping tick (run the manual upgrade then --force)"
        PRE_GATE_GUARD=false
        rm -f "${KEYCLOAK_SPI_STAGE_JAR}"
        exit 0
      fi
      echo "${EXPECTED_MARKER}" > "${CONFIG_BLOCKED_FILE}"
      log "CARVE-OUT: postgres/Keycloak image pin changed — refusing to auto-apply a stateful-infra upgrade"
      log "  old: $(printf '%s' "${OLD_INFRA}" | tr '\n' ' ')"
      log "  new: $(printf '%s' "${NEW_INFRA}" | tr '\n' ' ')"
      log "  perform the documented manual upgrade (docs/deployment.md → Stateful-infra upgrades), then: deploy.sh --force"
      write_deploy_metric blocked
      PRE_GATE_GUARD=false
      rm -f "${KEYCLOAK_SPI_STAGE_JAR}"
      exit 3
    fi
    log "stateful-infra upgrade forced (--force) — applying the gated change"
  elif [[ "${REAPPLY}" != "true" ]]; then
    rm -f "${CONFIG_BLOCKED_FILE}"
  fi

  assert_config_tree_writable "${CONFIG_STAGE_DIR}"

  if [[ "${REAPPLY}" == "true" ]]; then
    log "re-applying the deployed release's config — ${CONFIG_PREVIOUS_DIR} keeps the previous release as the rollback anchor"
  elif [[ -f "${CONFIG_APPLY_INCOMPLETE_FILE}" && -d "${CONFIG_PREVIOUS_DIR}" ]]; then
    log "an earlier config apply did not complete and was not undone — keeping ${CONFIG_PREVIOUS_DIR} as the rollback anchor instead of snapshotting a half-applied tree"
  else
    DEPLOY_STEP="snapshot the live config tree into ${CONFIG_PREVIOUS_DIR}"
    snapshot_config_tree "${CONFIG_PREVIOUS_DIR}"
  fi
  DEPLOY_STEP="apply the config tree"
  if [[ "${REAPPLY}" != "true" ]]; then
    echo "${EXPECTED_MARKER}" > "${CONFIG_APPLY_INCOMPLETE_FILE}"
  fi
  CONFIG_TREE_TOUCHED=true
  apply_config_tree "${CONFIG_STAGE_DIR}" "${COMPOSE_DIR}"
  DEPLOY_STEP="check ${ENV_FILE} after the swap"
  [[ -f "${ENV_FILE}" ]] \
    || fail "POST-APPLY: ${ENV_FILE} vanished after config swap — aborting before up"
  install_quadlet_units "${COMPOSE_DIR}"
  rm -f "${CONFIG_APPLY_INCOMPLETE_FILE}"
  log "config applied"
fi

UNITS_REDEFINED="${RT_CHANGED_SERVICES}"
APP_IMAGES_CHANGED=""
for img_pair in "backend=${BACKEND_IMAGE}@${BACKEND_DIGEST}" \
                "frontend=${FRONTEND_IMAGE}@${FRONTEND_DIGEST}" \
                "ingest=${INGEST_IMAGE}@${INGEST_DIGEST}"; do
  img_bound="$(sed -n 's/^Image=//p' "$(rt_pin_path "${img_pair%%=*}")" 2>/dev/null || true)"
  if [[ "${img_bound}" != "${img_pair#*=}" ]]; then
    APP_IMAGES_CHANGED="${APP_IMAGES_CHANGED}${APP_IMAGES_CHANGED:+ }${img_pair%%=*}"
  fi
done
unset img_pair img_bound
DEPLOY_STEP="write the digest pin"
PIN_HAD_PREVIOUS=false
[[ -f "${PIN_FILE_CURRENT}" ]] && PIN_HAD_PREVIOUS=true
PIN_TOUCHED=true
if [[ "${REAPPLY}" == "true" ]]; then
  log "re-applying the deployed release's digest pin — ${PIN_FILE_PREVIOUS} keeps the previous release as the rollback anchor"
else
  rt_pin_save
fi
rt_pin_apply \
  "backend=${BACKEND_IMAGE}@${BACKEND_DIGEST}" \
  "frontend=${FRONTEND_IMAGE}@${FRONTEND_DIGEST}" \
  "ingest=${INGEST_IMAGE}@${INGEST_DIGEST}"

DEPLOY_STEP="enter ${COMPOSE_DIR}"
cd "${COMPOSE_DIR}"

log "pulling images"
DEPLOY_STEP="pull the release images"
RT_PIN_FILE="${PIN_FILE_CURRENT}" rt_pull \
  "backend=${BACKEND_IMAGE}@${BACKEND_DIGEST}" \
  "frontend=${FRONTEND_IMAGE}@${FRONTEND_DIGEST}" \
  "ingest=${INGEST_IMAGE}@${INGEST_DIGEST}"

if [[ "${KEYCLOAK_SPI_CHANGED}" == "true" && "${REAPPLY}" == "true" && -f "${KEYCLOAK_SPI_JAR}" ]] \
   && [[ "$(sha256sum < "${KEYCLOAK_SPI_STAGE_JAR}")" == "$(sha256sum < "${KEYCLOAK_SPI_JAR}")" ]]; then
  rm -f "${KEYCLOAK_SPI_STAGE_JAR}"
  KEYCLOAK_SPI_CHANGED=false
  log "--reapply: the live provider JAR is the deployed release's — not swapped, keycloak is not restarted for it"
fi
if [[ "${KEYCLOAK_SPI_CHANGED}" == "true" ]]; then
  DEPLOY_STEP="swap in the keycloak-spi provider JAR"
  swap_in_keycloak_spi_jar
  if [[ "${REAPPLY}" == "true" ]]; then
    log "--reapply: the live provider JAR differed from the deployed release's — put back, keycloak restarts on it (${KEYCLOAK_SPI_PREVIOUS_JAR} is left alone)"
  else
    log "provider JAR swapped in — keycloak restarts on it with this release"
  fi
fi

log "release parts: $(release_parts)"
if [[ -n "${RT_CHANGED_SERVICES}" ]]; then
  log "one restart window: stopping [${RT_CHANGED_SERVICES}] and what requires them, then starting the stack in order"
fi

PRE_GATE_GUARD=false
log "applying (timeout ${HEALTH_TIMEOUT}s)"
if rt_apply_stack; then
  if [[ "${KEYCLOAK_SPI_CHANGED}" == "true" ]]; then
    log "keycloak-spi provider JAR applied with the release — keycloak and the application stack are healthy on it"
  fi

  echo "${EXPECTED_MARKER}" > "${LAST_DEPLOYED_FILE}"
  if [[ "${REAPPLY}" == "true" ]]; then
    rm -f "${REAPPLY_FAILED_FILE}"
    if [[ -f "${FAILED_FILE}" ]] && grep -qF "${EXPECTED_MARKER} " "${FAILED_FILE}"; then
      rm -f "${FAILED_FILE}"
    fi
    log "deploy successful — the deployed release is re-applied (the promotion-outcome metrics are left as they were)"
  else
    rm -f "${FAILED_FILE}" "${CONFIG_BLOCKED_FILE}" "${REAPPLY_FAILED_FILE}"
    log "deploy successful"
    write_deploy_metric success
  fi
  if [[ -z "${HEALTH_LEFT_OVER}" ]]; then
    rm -f "${HEALTH_RESTART_FILE}"
    write_stack_health_metric healthy
  else
    log "stack-health heartbeat NOT stamped: [${HEALTH_LEFT_OVER}] was unhealthy before this re-apply and was not recreated by it"
  fi

  if [[ "${BASETOOL_MONITORING_ENABLED:-false}" == "true" ]] && rt_monitoring_configured; then
    log "applying monitoring stack (non-gating)"
    MONITORING_APPLY_RC=0
    rt_monitoring_up > "${STATE_DIR}/monitoring-apply.log" 2>&1 || MONITORING_APPLY_RC=$?
    sed 's/^/  monitoring: /' "${STATE_DIR}/monitoring-apply.log" 2>/dev/null || true
    if [[ "${MONITORING_APPLY_RC}" -eq 0 ]]; then
      log "monitoring stack reconciled"
      reconcile_monitoring_reloads
    else
      log "WARN: monitoring stack apply failed — app deploy stays successful (non-gating)"
    fi
  fi

  reconcile_edge

  rt_prune_images 720h
  exit 0
fi

if [[ "${REAPPLY}" == "true" ]]; then
  log "health check failed within ${HEALTH_TIMEOUT}s on a re-apply of the deployed release — not rolling back"
  log "health gate: did not come up, in start order: [${RT_FAILED_SERVICES:-none reported}]"
  record_reapply_failure "the running release could not be restored"
  exit 1
fi

log "health check failed within ${HEALTH_TIMEOUT}s — rolling back"
explain_gate_failure
FORWARD_FAILED_SERVICES="${RT_FAILED_SERVICES}"

record_target_failure
log "recorded health-check failure #${FAIL_COUNT} for this target; next retry backs off"

if [[ "${CONFIG_CHANGED}" == "true" ]]; then
  set +e
  restore_previous_config_tree
  RESTORE_RC=$?
  set -e
  if (( RESTORE_RC == 1 )); then
    echo "${EXPECTED_MARKER}" > "${CONFIG_APPLY_INCOMPLETE_FILE}" || true
    log "FATAL: the host config tree under ${COMPOSE_DIR} is INCONSISTENT after the rollback — ${CONFIG_PREVIOUS_DIR} is kept as the anchor; the pin is rolled back regardless"
  fi
fi

ROLLBACK_PARTS="app digests"
if [[ "${CONFIG_CHANGED}" == "true" ]]; then
  ROLLBACK_PARTS="${ROLLBACK_PARTS} + config tree and units"
fi
if [[ "${KEYCLOAK_SPI_TOUCHED}" == "true" ]]; then
  if restore_previous_keycloak_spi_jar; then
    ROLLBACK_PARTS="${ROLLBACK_PARTS} + provider JAR"
    log "provider JAR restored to the previous one"
  else
    log "WARNING: could not restore the previous provider JAR from ${KEYCLOAK_SPI_PREVIOUS_JAR} — keycloak comes back on the failed release's JAR"
  fi
fi

if [[ ! -f "${PIN_FILE_PREVIOUS}" ]]; then
  log "no previous pin available — manual intervention required"
  write_deploy_metric failure
  exit 2
fi

rt_pin_rollback

if rt_apply_stack; then
  log "rolled back to previous digest pin successfully — the previous ${ROLLBACK_PARTS} are live again and the stack is healthy"
else
  log "rollback ALSO failed — did not come up: [${RT_FAILED_SERVICES:-none reported}] (the forward apply: [${FORWARD_FAILED_SERVICES:-none reported}]); one or more target digests broken or environment problem"
fi

write_deploy_metric rollback
exit 1
