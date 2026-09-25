#!/bin/bash

set -euo pipefail

umask 022

IRI_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
. "${IRI_SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/container-runtime.sh
# shellcheck disable=SC1091
. "${IRI_SCRIPT_DIR}/lib/container-runtime.sh"

cd /

COMPOSE_DIR="${IRI_COMPOSE_DIR:-/var/iri/code}"
STATE_DIR="${IRI_STATE_DIR:-/var/lib/iri}"
LOCKFILE="${IRI_LOCKFILE:-/var/lock/iri-deploy.lock}"
TOKEN_FILE="${IRI_GHCR_TOKEN_FILE:-/etc/iri/ghcr-pull-token}"
HEALTH_TIMEOUT="${IRI_HEALTH_TIMEOUT:-180}"

if [[ -z "${IRI_MONITORING_ENABLED:-}" && -r "${COMPOSE_DIR}/.env" ]]; then
  IRI_MONITORING_ENABLED="$(read_env IRI_MONITORING_ENABLED)"
  export IRI_MONITORING_ENABLED
fi

REGISTRY="${IRI_REGISTRY:-ghcr.io}"
NAMESPACE="${IRI_IMAGE_NAMESPACE:-krt-profit}"
GHCR_USERNAME="${IRI_GHCR_USERNAME:-deploy-bot}"

COSIGN_VERIFY="${IRI_COSIGN_VERIFY:-true}"
COSIGN_REPO="${IRI_COSIGN_REPO:-krt-profit/basetool}"
COSIGN_IDENTITY_REGEXP="${IRI_COSIGN_IDENTITY_REGEXP:-^https://github\\.com/${COSIGN_REPO}/\\.github/workflows/release-images\\.yml@refs/(heads/main|tags/v[0-9]+\\.[0-9]+\\.[0-9]+)$}"
COSIGN_OIDC_ISSUER="${IRI_COSIGN_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"
COSIGN_VERIFY_ATTEMPTS="${IRI_COSIGN_VERIFY_ATTEMPTS:-3}"
COSIGN_VERIFY_DELAY="${IRI_COSIGN_VERIFY_DELAY:-5}"
COSIGN_SEARCH_PATH="${IRI_COSIGN_SEARCH_PATH:-/usr/local/bin:/usr/bin:/opt/cosign/bin}"
VERIFY_LAST_ERROR=""

TARGET_TAG=stable
CHECK_ONLY=false
FORCE=false

BACKOFF_BASE="${IRI_BACKOFF_BASE:-600}"
BACKOFF_MAX="${IRI_BACKOFF_MAX:-21600}"

HEALTH_RESTART_BASE="${IRI_HEALTH_RESTART_BASE:-300}"
HEALTH_RESTART_MAX="${IRI_HEALTH_RESTART_MAX:-3600}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      [[ -n "${2:-}" ]] || { echo "FATAL: --tag requires a value" >&2; exit 1; }
      TARGET_TAG="$2"
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
    -h|--help)
      cat <<'USAGE'
Usage: deploy.sh [--tag <ref>] [--check-only] [--force]

Options:
  --tag <ref>     Image tag/ref to deploy. Default: stable
                  Examples: stable, latest, 1.4.2, sha-abc1234
  --check-only    Resolve digests + cosign-verify them, but do not apply
                  (dry-run / signature preflight). Exits non-zero if a signature
                  does not verify; writes no deploy metric.
  --force         Bypass the bad-digest backoff and retry a previously failed
                  target now (e.g. after fixing an environmental cause).
  -h, --help      Show this help.

Environment overrides (all optional, sensible defaults shown):
  IRI_COMPOSE_DIR=/var/iri/code
  IRI_STATE_DIR=/var/lib/iri
  IRI_LOCKFILE=/var/lock/iri-deploy.lock
  IRI_GHCR_TOKEN_FILE=/etc/iri/ghcr-pull-token
  IRI_HEALTH_TIMEOUT=180
  IRI_BACKOFF_BASE=600     (first retry delay after a failed target, seconds)
  IRI_BACKOFF_MAX=21600    (cap for the exponential backoff, seconds)
  IRI_HEALTH_RESTART_BASE=300   (first delay before re-restarting an unhealthy
                                 at-target service — the runtime-health path — seconds)
  IRI_HEALTH_RESTART_MAX=3600   (cap for the health-restart backoff, seconds)
  IRI_REGISTRY=ghcr.io
  IRI_IMAGE_NAMESPACE=krt-profit
  IRI_GHCR_USERNAME=deploy-bot
  IRI_COSIGN_VERIFY=true    (host-side cosign signature gate; false = break-glass,
                            Sigstore-outage only)
  IRI_COSIGN_REPO=krt-profit/basetool   (repo whose release-images.yml identity signs)
  IRI_COSIGN_IDENTITY_REGEXP=...        (override the trusted signer identity regexp)
  IRI_COSIGN_OIDC_ISSUER=https://token.actions.githubusercontent.com
  IRI_COSIGN_VERIFY_ATTEMPTS=3   (verify retries; a registry/Sigstore blip must
                                  not read as an untrusted image)
  IRI_COSIGN_VERIFY_DELAY=5      (first retry delay in seconds, then doubling)
  DOCKER_CONFIG=/var/lib/iri/.docker   (the registry credential cosign reads
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

require_file() {
  [[ -f "$1" ]] || fail "required file missing: $1"
}

mirror_dir() {
  local src="$1" dst="$2" rc=0
  DEPLOY_STEP="mirror ${dst}"
  if command -v rsync >/dev/null 2>&1; then
    rsync -rlpt --delete "${src}/" "${dst}/" || rc=$?
  else
    { rm -rf "${dst}" && install -d "${dst%/*}" && cp -R "${src}" "${dst}"; } || rc=$?
  fi
  if (( rc != 0 )); then
    log "mirror of ${src} onto ${dst} failed (exit ${rc}) — the tool's own error is above"
    return "${rc}"
  fi
  return 0
}

extract_config_bundle() {
  local ref="$1" dest="$2"
  rm -rf "${dest}"
  install -d -m 0755 "${dest}"
  rt_extract_from_image "${ref}" "/config/." "${dest}/" /bundle \
    || fail "cannot extract /config from config image ${ref}"
}

assert_no_secrets() {
  local dir="$1"
  if find "${dir}" \( \
        -iname '.env' -o -iname '*.p12' -o -iname '*.jks' \
        -o -iname '*.pem' -o -iname '*.key' -o -iname 'realm-export.json' \
      \) -print -quit 2>/dev/null | grep -q .; then
    local hit
    hit="$(find "${dir}" \( \
        -iname '.env' -o -iname '*.p12' -o -iname '*.jks' \
        -o -iname '*.pem' -o -iname '*.key' -o -iname 'realm-export.json' \
      \) -print -quit 2>/dev/null)"
    fail "SECURITY: promoted config bundle contains a forbidden secret-shaped file '${hit}' — aborting before apply"
  fi
  if [[ -d "${dir}/keycloak/providers" ]]; then
    fail "SECURITY: promoted config bundle contains keycloak/providers — aborting before apply"
  fi
}

infra_image_pins() {
  local tree="$1"
  {
    [[ -d "${tree}/quadlet/systemd" ]] \
      && grep -Eho '^Image=((docker\.io/)?(library/)?postgres:[^[:space:]]+|quay\.io/keycloak/keycloak:[^[:space:]]+)' \
           "${tree}"/quadlet/systemd/*.container 2>/dev/null
  } | sed -E 's/^Image=//; s#^docker\.io/(library/)?##; s/@sha256:[0-9a-f]+$//' | sort -u || true
}

snapshot_config_tree() {
  local dst="$1"
  rm -rf "${dst}"
  install -d -m 0755 "${dst}"
  [[ -f "${COMPOSE_DIR}/docker-compose.yml" ]] \
    && cp -a "${COMPOSE_DIR}/docker-compose.yml" "${dst}/docker-compose.yml"
  local sub
  for sub in maintenance edge acme; do
    if [[ -d "${COMPOSE_DIR}/docker/${sub}" ]]; then
      install -d "${dst}/docker"
      cp -a "${COMPOSE_DIR}/docker/${sub}" "${dst}/docker/${sub}"
    fi
  done
  [[ -d "${COMPOSE_DIR}/keycloak-theme" ]] \
    && cp -a "${COMPOSE_DIR}/keycloak-theme" "${dst}/keycloak-theme"
  [[ -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" ]] \
    && cp -a "${COMPOSE_DIR}/docker-compose.monitoring.yml" "${dst}/docker-compose.monitoring.yml"
  [[ -d "${COMPOSE_DIR}/monitoring" ]] \
    && cp -a "${COMPOSE_DIR}/monitoring" "${dst}/monitoring"
  [[ -d "${COMPOSE_DIR}/quadlet" ]] \
    && cp -a "${COMPOSE_DIR}/quadlet" "${dst}/quadlet"
  return 0
}

apply_config_tree() {
  local src="$1" dst="$2"
  if [[ -f "${src}/docker-compose.yml" ]]; then
    install -m 0644 "${src}/docker-compose.yml" "${dst}/.docker-compose.yml.tmp"
    mv -f "${dst}/.docker-compose.yml.tmp" "${dst}/docker-compose.yml"
  fi
  if [[ -f "${src}/docker-compose.monitoring.yml" ]]; then
    install -m 0644 "${src}/docker-compose.monitoring.yml" "${dst}/.docker-compose.monitoring.yml.tmp"
    mv -f "${dst}/.docker-compose.monitoring.yml.tmp" "${dst}/docker-compose.monitoring.yml"
  fi
  if [[ -d "${src}/monitoring" ]]; then
    install -d "${dst}/monitoring"
    mirror_dir "${src}/monitoring" "${dst}/monitoring"
  fi
  if [[ -d "${src}/docker/maintenance" ]]; then
    install -d "${dst}/docker"
    mirror_dir "${src}/docker/maintenance" "${dst}/docker/maintenance"
  fi
  if [[ -d "${src}/docker/edge" ]]; then
    install -d "${dst}/docker"
    mirror_dir "${src}/docker/edge" "${dst}/docker/edge"
  fi
  if [[ -d "${src}/docker/acme" ]]; then
    install -d "${dst}/docker"
    mirror_dir "${src}/docker/acme" "${dst}/docker/acme"
  fi
  if [[ -d "${src}/keycloak-theme" ]]; then
    mirror_dir "${src}/keycloak-theme" "${dst}/keycloak-theme"
  fi
  if [[ -d "${src}/quadlet" ]]; then
    mirror_dir "${src}/quadlet" "${dst}/quadlet"
  fi
}

install_quadlet_units() {
  local src="${1}/quadlet" installed=0 removed=0

  if [[ ! -d "${src}/systemd" ]]; then
    log "config bundle carries no quadlet/systemd — units left as they are (pre-2026-09-18 bundle)"
    return 0
  fi

  local unit_dir="${RT_UNIT_DIR:?RT_UNIT_DIR is unset}"
  install -d "${unit_dir}"

  DEPLOY_STEP="render ${ENV_D_DIR}"
  if [[ -d "${src}/env.d" ]]; then
    if [[ -x "${ENV_RENDERER}" ]]; then
      if ! "${ENV_RENDERER}" --env "${COMPOSE_DIR}/.env"              --templates "${src}/env.d" --out "${ENV_D_DIR}" >/dev/null; then
        fail "rendering ${ENV_D_DIR} from the bundle's templates failed — a unit whose EnvironmentFile is missing does not start"
      fi
      log "rendered env.d from the bundle's templates"
    else
      fail "the bundle carries env.d templates but ${ENV_RENDERER} is not on this host (ansible role: 25-scripts.yml)"
    fi
  fi

  DEPLOY_STEP="install quadlet units into ${unit_dir}"
  local f base
  for f in "${src}"/systemd/*; do
    [[ -f "${f}" ]] || continue
    base="$(basename "${f}")"
    if [[ ! -f "${unit_dir}/${base}" ]] || ! cmp -s "${f}" "${unit_dir}/${base}"; then
      install -m 0644 "${f}" "${unit_dir}/${base}"
      installed=$(( installed + 1 ))
      [[ "${base}" == *.container ]] && rt_note_changed "${base%.container}"
    fi
  done

  local existing name
  for existing in "${unit_dir}"/*.container "${unit_dir}"/*.network "${unit_dir}"/*.volume; do
    [[ -f "${existing}" ]] || continue
    name="$(basename "${existing}")"
    [[ -f "${src}/systemd/${name}" ]] && continue
    if [[ "${name}" == *.container ]]; then
      rt_service_stop "${name%.container}" >/dev/null 2>&1         || log "WARN: could not stop retired unit ${name%.container} before removing it"
    fi
    rm -f "${existing}"
    removed=$(( removed + 1 ))
  done

  log "quadlet units: ${installed} installed/updated, ${removed} retired (${unit_dir})"
  return 0
}

assert_config_tree_writable() {
  local src="$1" me sub d parent bad count
  me="$(id -u)"
  DEPLOY_STEP="pre-flight: the config tree is writable"
  for d in "${COMPOSE_DIR}" "${RT_UNIT_DIR}"; do
    [[ -w "${d}" ]] \
      || fail "PRE-FLIGHT: ${d} is not writable by $(id -un) — nothing was changed; fix the owner (docs/deployment.md → Troubleshooting, 'deploy stuck on a config apply')"
  done
  if [[ -e "${ENV_D_DIR}" && ! -w "${ENV_D_DIR}" ]]; then
    fail "PRE-FLIGHT: ${ENV_D_DIR} is not writable by $(id -un) — nothing was changed; the role's --tags directories restores deploy:iri 2750"
  fi
  for sub in monitoring docker/maintenance docker/edge docker/acme keycloak-theme quadlet; do
    [[ -d "${src}/${sub}" ]] || continue
    d="${COMPOSE_DIR}/${sub}"
    if [[ -e "${d}" ]]; then
      bad="$(find "${d}" -type d \( ! -user "${me}" -o ! -writable \) -print 2>/dev/null || true)"
      if [[ -n "${bad}" ]]; then
        count="$(printf '%s\n' "${bad}" | wc -l | tr -d '[:space:]')"
        fail "PRE-FLIGHT: $(printf '%s\n' "${bad}" | head -n 1) is not owned or not writable by $(id -un) (${count} such directories under ${d}) — nothing was changed; fix with 'chown -R $(id -un):$(id -un) ${d}' or the role's --tags directories (docs/deployment.md → Troubleshooting)"
      fi
    else
      parent="${d%/*}"
      while [[ ! -e "${parent}" && "${parent}" == "${COMPOSE_DIR}"/* ]]; do
        parent="${parent%/*}"
      done
      [[ -w "${parent}" ]] \
        || fail "PRE-FLIGHT: ${parent} is not writable by $(id -un), so ${d} cannot be created — nothing was changed"
    fi
  done
  return 0
}

restore_previous_config_tree() {
  local rc=0 errexit_was_on=false
  if [[ ! -d "${CONFIG_PREVIOUS_DIR}/quadlet/systemd" ]]; then
    log "no previous definition in ${CONFIG_PREVIOUS_DIR} (the first bundle on this host) — nothing to restore to"
    return 2
  fi
  log "restoring previous host config from ${CONFIG_PREVIOUS_DIR}"
  [[ "$-" == *e* ]] && errexit_was_on=true
  set +e
  (
    set -e
    apply_config_tree "${CONFIG_PREVIOUS_DIR}" "${COMPOSE_DIR}"
    install_quadlet_units "${COMPOSE_DIR}"
  )
  rc=$?
  if [[ "${errexit_was_on}" == "true" ]]; then
    set -e
  fi
  if (( rc != 0 )); then
    log "restoring the previous host config FAILED (exit ${rc})"
    return 1
  fi
  log "previous host config restored"
  return 0
}

reconcile_monitoring_reload() {
  local svc="$1" subpath="$2" src snap
  src="${COMPOSE_DIR}/monitoring/${subpath}"
  snap="${MON_RELOAD_STATE_DIR}/${svc}"
  [[ -d "${src}" ]] || return 0
  if diff -rq "${snap}" "${src}" >/dev/null 2>&1; then
    return 0
  fi
  log "  monitoring: ${subpath} config differs from the last applied snapshot → recreating ${svc} (re-resolves the bind-mount inode)"
  if rt_monitoring_recreate "${svc}" >/dev/null 2>&1; then
    install -d -m 0755 "${MON_RELOAD_STATE_DIR}" 2>/dev/null || true
    rm -rf "${snap}"
    if cp -R "${src}" "${snap}" 2>/dev/null; then
      date +%s > "${MON_RELOAD_STATE_DIR}/${svc}.applied" 2>/dev/null || true
    else
      log "  monitoring: WARN could not snapshot ${subpath} baseline (will re-apply next tick)"
    fi
  else
    log "  monitoring: WARN recreate of ${svc} failed (non-gating; monitoring stack down?) — will retry next tick"
  fi
}

write_prometheus_config_applied_metric() {
  local applied_file applied
  applied_file="${MON_RELOAD_STATE_DIR}/prometheus.applied"
  [[ -f "${applied_file}" ]] || return 0
  applied="$(cat "${applied_file}" 2>/dev/null || true)"
  [[ "${applied}" =~ ^[0-9]+$ ]] || return 0
  {
    echo "# HELP basetool_monitoring_config_applied_timestamp Unix time deploy.sh last recreated a monitoring component for an on-disk config change."
    echo "# TYPE basetool_monitoring_config_applied_timestamp gauge"
    echo "basetool_monitoring_config_applied_timestamp{component=\"prometheus\"} ${applied}"
  } | write_textfile monitoring-config.prom || true
}

write_monitoring_reconcile_state_metric() {
  {
    echo "# HELP basetool_monitoring_reconcile_disabled 1 when the iri-monitoring stack is running but deploy.sh's monitoring reconcile is gated off (IRI_MONITORING_ENABLED != true), so on-disk monitoring config changes are never reloaded into the running Prometheus/alloy/blackbox."
    echo "# TYPE basetool_monitoring_reconcile_disabled gauge"
    echo "basetool_monitoring_reconcile_disabled{component=\"deploy\"} ${1}"
  } | write_textfile monitoring-reconcile.prom || true
}

reconcile_edge() {
  local src="${COMPOSE_DIR}/docker/edge"
  local snap="${EDGE_STATE_DIR}/config"
  local fp_file="${EDGE_STATE_DIR}/certs.sha256"
  local drift="" fp_now="" fp_old="" fp_lines=""

  [[ -d "${src}" ]] || return 0

  diff -rq "${snap}" "${src}" >/dev/null 2>&1 || drift="config"

  if rt_is_running edge; then
    fp_lines="$(rt_exec edge sh -c \
                  'find /etc/nginx/certs -name fullchain.pem -type f -exec sha256sum {} +' \
                  2>/dev/null || true)"
  fi
  if [[ -n "${fp_lines}" ]]; then
    fp_now="$(printf '%s\n' "${fp_lines}" | awk '{print $1}' | sort | sha256sum | cut -d' ' -f1)"
    fp_old="$(cat "${fp_file}" 2>/dev/null || true)"
    if [[ "${fp_now}" != "${fp_old}" ]]; then
      drift="${drift:+${drift} + }certificates"
    fi
  fi

  [[ -n "${drift}" ]] || return 0

  log "  edge: ${drift} differs from the last applied state -> recreating edge (re-resolves the bind-mount inode and re-reads the certificates)"
  if rt_recreate edge >/dev/null 2>&1; then
    install -d -m 0755 "${EDGE_STATE_DIR}" 2>/dev/null || true
    rm -rf "${snap}"
    if cp -R "${src}" "${snap}" 2>/dev/null; then
      date +%s > "${EDGE_STATE_DIR}/applied" 2>/dev/null || true
    else
      log "  edge: WARN could not snapshot the config baseline (will re-apply next tick)"
    fi
    if [[ -n "${fp_now}" ]]; then
      printf '%s\n' "${fp_now}" > "${fp_file}" 2>/dev/null || true
    fi
  else
    log "  edge: WARN recreate failed (non-gating) — will retry next tick"
  fi
}

reconcile_monitoring_reloads() {
  if [[ "${IRI_MONITORING_ENABLED:-false}" != "true" ]]; then
    if rt_monitoring_is_running; then
      log "  monitoring: WARN iri-monitoring is RUNNING but IRI_MONITORING_ENABLED != 'true' — on-disk monitoring config changes will NOT be reloaded into Prometheus/alloy/blackbox (set IRI_MONITORING_ENABLED=true in the iri-deploy service env)"
      write_monitoring_reconcile_state_metric 1
    fi
    return 0
  fi
  rt_monitoring_configured || return 0
  write_monitoring_reconcile_state_metric 0
  if ! rt_monitoring_up >/dev/null 2>&1; then
    log "  monitoring: WARN definition reconcile failed — non-gating, retries next tick"
  fi
  reconcile_monitoring_reload prometheus prometheus
  reconcile_monitoring_reload alloy alloy
  reconcile_monitoring_reload blackbox-exporter blackbox
  write_prometheus_config_applied_metric
}

extract_keycloak_spi_jar() {
  local ref="$1" dest_jar="$2" stage
  stage="${STATE_DIR}/keycloak-spi-stage.jar"
  rm -f "${stage}"
  rt_extract_from_image "${ref}" /providers/keycloak-spi.jar "${stage}" /bundle \
    || fail "cannot extract /providers/keycloak-spi.jar from ${ref}"
  install -D -m 0644 "${stage}" "${dest_jar}"
  rm -f "${stage}"
}

verify_signature() {
  local ref="$1"
  if [[ "${COSIGN_VERIFY}" != "true" ]]; then
    log "WARNING: signature verification DISABLED (IRI_COSIGN_VERIFY=false) — NOT verifying ${ref}"
    return 0
  fi
  local attempt delay err rc
  delay="${COSIGN_VERIFY_DELAY}"
  VERIFY_LAST_ERROR=""
  for (( attempt = 1; attempt <= COSIGN_VERIFY_ATTEMPTS; attempt++ )); do
    rc=0
    err="$(cosign verify "${ref}" \
      --certificate-identity-regexp "${COSIGN_IDENTITY_REGEXP}" \
      --certificate-oidc-issuer "${COSIGN_OIDC_ISSUER}" 2>&1 >/dev/null)" || rc=$?
    if (( rc == 0 )); then
      return 0
    fi
    VERIFY_LAST_ERROR="${err//$'\n'/ }"
    if (( attempt < COSIGN_VERIFY_ATTEMPTS )); then
      log "  ${ref}: verify attempt ${attempt}/${COSIGN_VERIFY_ATTEMPTS} failed (rc=${rc}), retrying in ${delay}s — ${VERIFY_LAST_ERROR}"
      sleep "${delay}"
      delay=$(( delay * 2 ))
    fi
  done
  return 1
}

verify_digest_or_die() {
  local label="$1" ref="$2"
  if verify_signature "${ref}"; then
    log "  ${label}: signature OK"
    return 0
  fi
  write_deploy_metric failure
  fail "SECURITY: cosign signature verification failed for ${label} (${ref}) after ${COSIGN_VERIFY_ATTEMPTS} attempts — refusing to deploy an unverified/untrusted image (expected identity: ${COSIGN_IDENTITY_REGEXP}); last cosign error: ${VERIFY_LAST_ERROR:-<none>}"
}

check_only_verify_one() {
  local label="$1" ref="$2"
  if verify_signature "${ref}"; then
    log "  ${label}: signature OK"
    return 0
  fi
  log "  ${label}: SIGNATURE VERIFICATION FAILED (${ref}) — last cosign error: ${VERIFY_LAST_ERROR:-<none>}"
  return 1
}

require_file "${COMPOSE_DIR}/.env"
require_file "${TOKEN_FILE}"

mkdir -p "${STATE_DIR}"

export DOCKER_CONFIG="${DOCKER_CONFIG:-${STATE_DIR}/.docker}"
install -d -m 0700 "${DOCKER_CONFIG}"
export REGISTRY_AUTH_FILE="${REGISTRY_AUTH_FILE:-${DOCKER_CONFIG}/config.json}"

export HOME="${IRI_HOME:-${STATE_DIR}}"

ENV_D_DIR="${IRI_ENV_D_DIR:-${COMPOSE_DIR}/env.d}"
ENV_RENDERER="${IRI_ENV_RENDERER:-${IRI_SCRIPT_DIR}/render-env-d.py}"

rt_detect
export RT_HEALTH_TIMEOUT="${HEALTH_TIMEOUT}"
export RT_STACK_SERVICES="db-backend db-keycloak redis keycloak backend ingest frontend edge acme"
log "container runtime: ${RT_BACKEND}"

command -v skopeo >/dev/null 2>&1 \
  || fail "skopeo not available; it is how a tag is resolved without pulling (ansible role: 10-packages.yml)"
QUADLET_BIN="${IRI_QUADLET_BIN:-}"
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
    src="$(read_env IRI_KEYSTORE_HOST_PATH || true)"
  fi
  printf '%s\n' "${src:-/var/iri/secrets/keystore.p12}"
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
  fail "cosign not found on PATH or under ${COSIGN_SEARCH_PATH}, but signature verification is enabled — install cosign (see docs/deployment.md → 'Signature verification (cosign)') or set IRI_COSIGN_VERIFY=false ONLY to break glass during a Sigstore outage"
fi

PIN_FILE_CURRENT="${STATE_DIR}/current-digest-pin.yml"
PIN_FILE_PREVIOUS="${STATE_DIR}/previous-digest-pin.yml"
LAST_DEPLOYED_FILE="${STATE_DIR}/last-deployed.digests"
FAILED_FILE="${STATE_DIR}/failed.digests"
CONFIG_STAGE_DIR="${STATE_DIR}/config-stage"
CONFIG_PREVIOUS_DIR="${STATE_DIR}/config-previous"
MON_RELOAD_STATE_DIR="${STATE_DIR}/monitoring-reload"
EDGE_STATE_DIR="${STATE_DIR}/edge"
CONFIG_BLOCKED_FILE="${STATE_DIR}/config-blocked.marker"
CONFIG_APPLY_INCOMPLETE_FILE="${STATE_DIR}/config-apply.incomplete"
KEYCLOAK_SPI_JAR="${COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar"
KEYCLOAK_SPI_PREVIOUS_JAR="${STATE_DIR}/keycloak-spi-previous.jar"
HEALTH_RESTART_FILE="${STATE_DIR}/health-restart.digests"

TEXTFILE_DIR="${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}"
DEPLOY_METRIC_FILE="${TEXTFILE_DIR}/deploy.prom"
STACK_HEALTH_METRIC_FILE="${TEXTFILE_DIR}/deploy-health.prom"
START_EPOCH="$(date +%s)"

TOKEN_EXPIRY_FILE="${IRI_GHCR_TOKEN_EXPIRY_FILE:-${TOKEN_FILE}.expiry}"
TOKEN_METRIC_FILE="${TEXTFILE_DIR}/ghcr-token.prom"

write_token_expiry_metric() {
  local raw epoch
  if [[ ! -f "${TOKEN_EXPIRY_FILE}" ]]; then
    rm -f "${TOKEN_METRIC_FILE}" 2>/dev/null || true
    return 0
  fi
  raw="$(tr -d '[:space:]' < "${TOKEN_EXPIRY_FILE}" 2>/dev/null || true)"
  if [[ -z "${raw}" ]]; then
    rm -f "${TOKEN_METRIC_FILE}" 2>/dev/null || true
    return 0
  fi
  epoch="$(date -u -d "${raw}" +%s 2>/dev/null || true)"
  if ! [[ "${epoch}" =~ ^[0-9]+$ ]]; then
    log "WARN: could not parse GHCR token expiry '${raw}' from ${TOKEN_EXPIRY_FILE}; removing the metric rather than leaving the previous value asserted"
    rm -f "${TOKEN_METRIC_FILE}" 2>/dev/null || true
    return 0
  fi
  {
    echo "# HELP basetool_ghcr_token_expiry_timestamp Unix time the GHCR pull token expires (operator-recorded in ${TOKEN_FILE}.expiry)."
    echo "# TYPE basetool_ghcr_token_expiry_timestamp gauge"
    echo "basetool_ghcr_token_expiry_timestamp ${epoch}"
  } | write_textfile "$(basename "${TOKEN_METRIC_FILE}")" || true
}

write_deploy_metric() {
  local outcome="$1" now dur f v blocked
  now="$(date +%s)"; dur=$(( now - START_EPOCH ))
  f="${DEPLOY_METRIC_FILE}"
  local prev_success=0 prev_rollback=0 prev_failure=0 prev_blocked=0
  if [[ -f "${f}" ]]; then
    prev_success="$(awk '/^basetool_deploy_last_success_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
    prev_rollback="$(awk '/^basetool_deploy_last_rollback_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
    prev_failure="$(awk '/^basetool_deploy_last_failure_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
    prev_blocked="$(awk '/^basetool_deploy_last_blocked_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
  fi
  for v in prev_success prev_rollback prev_failure prev_blocked; do
    [[ "${!v}" =~ ^[0-9]+$ ]] || printf -v "${v}" '%s' 0
  done
  case "${outcome}" in
    success)  prev_success="${now}" ;;
    rollback) prev_rollback="${now}" ;;
    failure)  prev_failure="${now}" ;;
    blocked)  prev_blocked="${now}" ;;
  esac
  blocked=0; [[ -f "${CONFIG_BLOCKED_FILE}" ]] && blocked=1
  {
    echo "# HELP basetool_deploy_last_success_timestamp Unix time of the last successful deploy."
    echo "# TYPE basetool_deploy_last_success_timestamp gauge"
    echo "basetool_deploy_last_success_timestamp ${prev_success}"
    echo "# HELP basetool_deploy_last_rollback_timestamp Unix time of the last deploy rollback (health gate reverted a release)."
    echo "# TYPE basetool_deploy_last_rollback_timestamp gauge"
    echo "basetool_deploy_last_rollback_timestamp ${prev_rollback}"
    echo "# HELP basetool_deploy_last_failure_timestamp Unix time of the last deploy failure."
    echo "# TYPE basetool_deploy_last_failure_timestamp gauge"
    echo "basetool_deploy_last_failure_timestamp ${prev_failure}"
    echo "# HELP basetool_deploy_last_blocked_timestamp Unix time of the last operator-gated (config-blocked) deploy."
    echo "# TYPE basetool_deploy_last_blocked_timestamp gauge"
    echo "basetool_deploy_last_blocked_timestamp ${prev_blocked}"
    echo "# HELP basetool_deploy_duration_seconds Runtime of the last deploy invocation in seconds."
    echo "# TYPE basetool_deploy_duration_seconds gauge"
    echo "basetool_deploy_duration_seconds ${dur}"
    echo "# HELP basetool_deploy_config_blocked Whether a postgres/Keycloak image change is operator-gated (1) or not (0)."
    echo "# TYPE basetool_deploy_config_blocked gauge"
    echo "basetool_deploy_config_blocked ${blocked}"
  } | write_textfile "$(basename "${f}")" || true
}

write_stack_health_metric() {
  local outcome="$1" now f prev_healthy prev_failed
  now="$(date +%s)"
  f="${STACK_HEALTH_METRIC_FILE}"
  prev_healthy=0
  prev_failed=0
  if [[ -f "${f}" ]]; then
    prev_healthy="$(awk '/^basetool_deploy_last_stack_healthy_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
    prev_failed="$(awk '/^basetool_deploy_last_health_restart_failed_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
  fi
  [[ "${prev_healthy}" =~ ^[0-9]+$ ]] || prev_healthy=0
  [[ "${prev_failed}" =~ ^[0-9]+$ ]] || prev_failed=0
  case "${outcome}" in
    healthy)        prev_healthy="${now}" ;;
    restart_failed) prev_failed="${now}" ;;
  esac
  {
    echo "# HELP basetool_deploy_last_stack_healthy_timestamp Unix time deploy.sh last observed the running app stack at target and healthy."
    echo "# TYPE basetool_deploy_last_stack_healthy_timestamp gauge"
    echo "basetool_deploy_last_stack_healthy_timestamp ${prev_healthy}"
    echo "# HELP basetool_deploy_last_health_restart_failed_timestamp Unix time a targeted restart of an unhealthy at-target service last failed to restore health."
    echo "# TYPE basetool_deploy_last_health_restart_failed_timestamp gauge"
    echo "basetool_deploy_last_health_restart_failed_timestamp ${prev_failed}"
  } | write_textfile "$(basename "${f}")" || true
}

record_target_failure() {
  local prev_marker="" prev_count=""
  FAIL_COUNT=1
  if [[ -f "${FAILED_FILE}" ]]; then
    read -r prev_marker prev_count _ < "${FAILED_FILE}" || true
    if [[ "${prev_marker}" == "${EXPECTED_MARKER}" ]] && [[ "${prev_count}" =~ ^[0-9]+$ ]]; then
      FAIL_COUNT=$(( 10#${prev_count} + 1 ))
    fi
  fi
  printf '%s %d %d\n' "${EXPECTED_MARKER}" "${FAIL_COUNT}" "$(date +%s)" > "${FAILED_FILE}"
}

DEPLOY_STEP=""
PRE_GATE_GUARD=false
CONFIG_TREE_TOUCHED=false
PIN_TOUCHED=false
PIN_HAD_PREVIOUS=false

# shellcheck disable=SC2317
on_pre_gate_exit() {
  local rc=$?
  [[ "${PRE_GATE_GUARD}" == "true" ]] || return 0
  (( rc != 0 )) || return 0
  PRE_GATE_GUARD=false
  set +e
  log "FATAL: deploy aborted before the health gate — step '${DEPLOY_STEP:-unknown}' failed (exit ${rc})"

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

log "resolving ${TARGET_TAG} → digest"
BACKEND_DIGEST="$(resolve_digest "${BACKEND_IMAGE}:${TARGET_TAG}")" \
  || fail "cannot resolve ${BACKEND_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"
FRONTEND_DIGEST="$(resolve_digest "${FRONTEND_IMAGE}:${TARGET_TAG}")" \
  || fail "cannot resolve ${FRONTEND_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"
INGEST_DIGEST="$(resolve_digest "${INGEST_IMAGE}:${TARGET_TAG}")" \
  || fail "cannot resolve ${INGEST_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"

CONFIG_DIGEST="$(resolve_digest "${CONFIG_IMAGE}:${TARGET_TAG}")" || CONFIG_DIGEST=""

KEYCLOAK_SPI_DIGEST="$(resolve_digest "${KEYCLOAK_SPI_IMAGE}:${TARGET_TAG}")" || KEYCLOAK_SPI_DIGEST=""

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

if [[ "${CONFIG_CHANGED}" != "true" ]]; then
  shopt -s nullglob
  host_units=( "${RT_UNIT_DIR}"/*.container )
  shopt -u nullglob
  if (( ${#host_units[@]} == 0 )); then
    CONFIG_CHANGED=true
    log "no Quadlet units on this host — staging the config bundle to install them"
  fi
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
if [[ -f "${LAST_DEPLOYED_FILE}" ]] \
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
      log "running stack does not match the last-deployed target — re-applying"
    else
      HEALTH_DRIFT=true
      log "running stack is at the target release but a container is unhealthy — targeted restart (not a release rollback)"
    fi
  fi
fi

if [[ "${CHECK_ONLY}" == "true" ]]; then
  if [[ "${NOOP}" == "true" ]]; then
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
  HR_RC=0
  for hr_svc in ${UNHEALTHY_SVCS}; do
    rt_recreate "${hr_svc}" || HR_RC=1
  done
  if [[ "${HR_RC}" -eq 0 ]]; then
    rm -f "${HEALTH_RESTART_FILE}"
    log "health drift resolved — service(s) [${UNHEALTHY_SVCS}] healthy again after targeted restart"
    write_stack_health_metric healthy
    exit 0
  fi

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

if [[ -f "${FAILED_FILE}" ]]; then
  read -r REC_MARKER REC_COUNT REC_EPOCH _ < "${FAILED_FILE}" || true
  if [[ "${REC_MARKER:-}" != "${EXPECTED_MARKER}" ]] \
     || ! [[ "${REC_COUNT:-}" =~ ^[0-9]+$ ]] \
     || ! [[ "${REC_EPOCH:-}" =~ ^[0-9]+$ ]]; then
    rm -f "${FAILED_FILE}"
  elif [[ "${FORCE}" == "true" ]]; then
    log "target previously failed ${REC_COUNT}x; --force given — retrying now"
  else
    if (( 10#${REC_COUNT} > 20 )); then
      backoff="${BACKOFF_MAX}"
    else
      backoff=$(( BACKOFF_BASE * (2 ** (10#${REC_COUNT} - 1)) ))
      if (( backoff > BACKOFF_MAX )); then
        backoff="${BACKOFF_MAX}"
      fi
    fi
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

export RT_PIN_FILE="${PIN_FILE_CURRENT}"
export RT_PIN_FILE_PREVIOUS="${PIN_FILE_PREVIOUS}"
trap on_pre_gate_exit EXIT
PRE_GATE_GUARD=true

if [[ "${CONFIG_CHANGED}" == "true" ]]; then
  log "config changed → staging ${CONFIG_IMAGE}@${CONFIG_DIGEST}"
  DEPLOY_STEP="extract the config bundle"
  extract_config_bundle "${CONFIG_IMAGE}@${CONFIG_DIGEST}" "${CONFIG_STAGE_DIR}"
  DEPLOY_STEP="check the staged config bundle"
  require_file "${CONFIG_STAGE_DIR}/docker-compose.yml"
  assert_no_secrets "${CONFIG_STAGE_DIR}"

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
        exit 0
      fi
      echo "${EXPECTED_MARKER}" > "${CONFIG_BLOCKED_FILE}"
      log "CARVE-OUT: postgres/Keycloak image pin changed — refusing to auto-apply a stateful-infra upgrade"
      log "  old: $(printf '%s' "${OLD_INFRA}" | tr '\n' ' ')"
      log "  new: $(printf '%s' "${NEW_INFRA}" | tr '\n' ' ')"
      log "  perform the documented manual upgrade (docs/deployment.md → Stateful-infra upgrades), then: deploy.sh --force"
      write_deploy_metric blocked
      PRE_GATE_GUARD=false
      exit 3
    fi
    log "stateful-infra upgrade forced (--force) — applying the gated change"
  else
    rm -f "${CONFIG_BLOCKED_FILE}"
  fi

  assert_config_tree_writable "${CONFIG_STAGE_DIR}"

  if [[ -f "${CONFIG_APPLY_INCOMPLETE_FILE}" && -d "${CONFIG_PREVIOUS_DIR}" ]]; then
    log "an earlier config apply did not complete and was not undone — keeping ${CONFIG_PREVIOUS_DIR} as the rollback anchor instead of snapshotting a half-applied tree"
  else
    DEPLOY_STEP="snapshot the live config tree into ${CONFIG_PREVIOUS_DIR}"
    snapshot_config_tree "${CONFIG_PREVIOUS_DIR}"
  fi
  DEPLOY_STEP="apply the config tree"
  echo "${EXPECTED_MARKER}" > "${CONFIG_APPLY_INCOMPLETE_FILE}"
  CONFIG_TREE_TOUCHED=true
  apply_config_tree "${CONFIG_STAGE_DIR}" "${COMPOSE_DIR}"
  DEPLOY_STEP="check ${COMPOSE_DIR}/.env after the swap"
  [[ -f "${COMPOSE_DIR}/.env" ]] \
    || fail "POST-APPLY: ${COMPOSE_DIR}/.env vanished after config swap — aborting before up"
  install_quadlet_units "${COMPOSE_DIR}"
  rm -f "${CONFIG_APPLY_INCOMPLETE_FILE}"
  log "config applied"
fi

DEPLOY_STEP="write the digest pin"
PIN_HAD_PREVIOUS=false
[[ -f "${PIN_FILE_CURRENT}" ]] && PIN_HAD_PREVIOUS=true
PIN_TOUCHED=true
rt_pin_save
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

PRE_GATE_GUARD=false
log "applying (timeout ${HEALTH_TIMEOUT}s)"
if rt_apply_stack; then

  if [[ "${KEYCLOAK_SPI_CHANGED}" == "true" ]]; then
    log "keycloak-spi changed → staging provider JAR + recreating keycloak (backend, ingest and frontend restart with it: Requires=)"
    if [[ -f "${KEYCLOAK_SPI_JAR}" ]]; then
      cp -a "${KEYCLOAK_SPI_JAR}" "${KEYCLOAK_SPI_PREVIOUS_JAR}"
      KEYCLOAK_SPI_HAD_PREVIOUS=true
    else
      rm -f "${KEYCLOAK_SPI_PREVIOUS_JAR}"
      KEYCLOAK_SPI_HAD_PREVIOUS=false
    fi
    extract_keycloak_spi_jar "${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST}" "${KEYCLOAK_SPI_JAR}"

    KEYCLOAK_SPI_FAILURE=""
    if ! rt_recreate keycloak; then
      KEYCLOAK_SPI_FAILURE="keycloak did not become healthy with the new provider JAR"
    else
      log "keycloak healthy on the new provider JAR — waiting for the application services systemd restarted with it"
      if ! rt_await_stack; then
        KEYCLOAK_SPI_FAILURE="keycloak is healthy with the new provider JAR, but the application stack it restarted did not return to health"
      fi
    fi

    if [[ -n "${KEYCLOAK_SPI_FAILURE}" ]]; then
      log "${KEYCLOAK_SPI_FAILURE} — rolling back the JAR"
      if [[ "${KEYCLOAK_SPI_HAD_PREVIOUS}" == "true" ]]; then
        install -D -m 0644 "${KEYCLOAK_SPI_PREVIOUS_JAR}" "${KEYCLOAK_SPI_JAR}"
      else
        rm -f "${KEYCLOAK_SPI_JAR}"
      fi
      KEYCLOAK_SPI_ROLLBACK_OK=true
      rt_recreate keycloak || KEYCLOAK_SPI_ROLLBACK_OK=false
      rt_await_stack || KEYCLOAK_SPI_ROLLBACK_OK=false
      if [[ "${KEYCLOAK_SPI_ROLLBACK_OK}" == "true" ]]; then
        log "keycloak and the application stack are healthy again on the previous provider JAR"
      else
        log "WARNING: the application stack did not return to health on the previous provider JAR — manual check needed"
      fi

      record_target_failure
      log "recorded keycloak-spi health-check failure #${FAIL_COUNT} for this target"
      write_deploy_metric failure
      exit 1
    fi
    log "keycloak-spi provider JAR applied — keycloak and the application stack are healthy"
  fi

  echo "${EXPECTED_MARKER}" > "${LAST_DEPLOYED_FILE}"
  rm -f "${FAILED_FILE}" "${CONFIG_BLOCKED_FILE}" "${HEALTH_RESTART_FILE}"
  log "deploy successful"
  write_deploy_metric success
  write_stack_health_metric healthy

  if [[ "${IRI_MONITORING_ENABLED:-false}" == "true" ]] && rt_monitoring_configured; then
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

log "health check failed within ${HEALTH_TIMEOUT}s — rolling back"

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

if [[ ! -f "${PIN_FILE_PREVIOUS}" ]]; then
  log "no previous pin available — manual intervention required"
  write_deploy_metric failure
  exit 2
fi

rt_pin_rollback

if rt_apply_stack; then
  log "rolled back to previous digest pin successfully"
else
  log "rollback ALSO failed — one or more target digests broken or environment problem"
fi

write_deploy_metric rollback
exit 1
