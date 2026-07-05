#!/bin/bash
# =============================================================================
# Profit Basetool — Server-side deploy script
#
# Pulls the production backend + frontend + ingest images, the host config bundle
# (basetool-config: docker-compose.yml + maintenance page + Keycloak theme) AND
# the Keycloak provider-JAR bundle (basetool-keycloak-spi: keycloak-spi.jar) from
# GHCR (the version tag defaults to `:stable`, which is moved atomically by the
# `promote.yml` GitHub Actions workflow), resolves them to immutable digests,
# applies them via docker-compose with `--wait`, and rolls back to the previous
# digest set, the previous config tree AND the previous provider JAR if the
# health-check fails within IRI_HEALTH_TIMEOUT seconds.
#
# The config bundle travels the SAME pull-only, digest-pinned, deliberately
# promoted GHCR channel as the app images (see docs/adr/0049-*), so a promoted
# compose change — e.g. a bumped redis/npm image pin — reaches the host and is
# applied automatically, with no manual `cp docker-compose.yml` or hand-run
# `docker compose up -d`. A postgres/Keycloak image change is the one carve-out:
# it is operator-gated (stateful migration / provider+keystore choreography),
# never auto-applied. deploy.sh and the systemd units are NOT part of the bundle
# (self-update hazard) and stay a manual bootstrap concern.
#
# The Keycloak provider JAR (basetool-keycloak-spi) rides the SAME channel as its
# own SEPARATE artifact — REQ-OPS-005 bars provider JARs from the config bundle,
# so it gets its own promotable, cosign-signed bundle (ADR-0055). When its digest
# moves, the JAR is staged into keycloak/providers and ONLY keycloak is recreated
# (health-gated; the JAR is rolled back on failure). A combined Keycloak-image +
# provider-JAR change stays operator-gated by the postgres/Keycloak carve-out
# above — the image change blocks the tick until the operator runs --force.
#
# Invoked periodically by the `iri-deploy.timer` systemd unit, or manually:
#   sudo -u deploy /var/iri/code/scripts/deploy.sh                  # apply :stable
#   sudo -u deploy /var/iri/code/scripts/deploy.sh --tag 1.4.2      # pin a specific version
#   sudo -u deploy /var/iri/code/scripts/deploy.sh --check-only     # dry-run
#   sudo -u deploy /var/iri/code/scripts/deploy.sh --force          # retry a backed-off target now
#
# State files (rewritten on every deploy):
#   /var/lib/iri/current-digest-pin.yml    compose override pinning the live
#                                          backend/frontend/ingest image digests;
#                                          used on every subsequent `up` so a tag
#                                          flip in GHCR does NOT silently move
#                                          the running stack underneath us.
#   /var/lib/iri/previous-digest-pin.yml   the prior pin, restored on rollback.
#   /var/lib/iri/last-deployed.digests     idempotence marker — a fixed 5-field
#                                          record backend|frontend|ingest|config|
#                                          keycloak-spi. When ALL target digests
#                                          match this file AND the running stack
#                                          is verified to actually match them
#                                          (containers present, running and
#                                          healthy, image RepoDigest equal to
#                                          the target), the script exits 0
#                                          without restarting; a matching marker
#                                          over a drifted or unhealthy stack
#                                          re-applies instead. The config and
#                                          keycloak-spi digests are part of the
#                                          marker so a config-only change (e.g. a
#                                          redis pin bump) or a provider-JAR-only
#                                          change is NOT skipped.
#   /var/lib/iri/failed.digests            a digest set whose health check
#                                          failed, plus a failure counter, so
#                                          the SAME broken target is retried
#                                          with exponential backoff instead of
#                                          on every tick. Cleared on a
#                                          successful deploy or when a new
#                                          digest is promoted to the tag.
#   /var/lib/iri/config-stage/             scratch dir the promoted config bundle
#                                          is extracted into before being copied
#                                          into /var/iri/code (never applied in
#                                          place).
#   /var/lib/iri/config-previous/          snapshot of the live config tree taken
#                                          before a config swap; restored on
#                                          rollback (the config analogue of
#                                          previous-digest-pin.yml).
#   /var/lib/iri/config-blocked.marker     a target whose config carries an
#                                          operator-gated postgres/Keycloak image
#                                          change. Recorded so the carve-out
#                                          alerts once, then skips subsequent
#                                          ticks quietly until a new promotion or
#                                          a --force run. Cleared on apply.
#   /var/lib/iri/keycloak-spi-previous.jar snapshot of the live provider JAR taken
#                                          before a provider-JAR swap; restored on
#                                          rollback if the keycloak recreate is
#                                          unhealthy (the provider-JAR analogue of
#                                          previous-digest-pin.yml).
#
# Locking: a single `flock` on /var/lock/iri-deploy.lock prevents the systemd
# timer and a manual invocation from racing each other.
# =============================================================================

set -euo pipefail

# --- Defaults / paths -------------------------------------------------------
COMPOSE_DIR="${IRI_COMPOSE_DIR:-/var/iri/code}"
STATE_DIR="${IRI_STATE_DIR:-/var/lib/iri}"
LOCKFILE="${IRI_LOCKFILE:-/var/lock/iri-deploy.lock}"
TOKEN_FILE="${IRI_GHCR_TOKEN_FILE:-/etc/iri/ghcr-pull-token}"
HEALTH_TIMEOUT="${IRI_HEALTH_TIMEOUT:-180}"

REGISTRY="${IRI_REGISTRY:-ghcr.io}"
NAMESPACE="${IRI_IMAGE_NAMESPACE:-krt-profit}"
GHCR_USERNAME="${IRI_GHCR_USERNAME:-deploy-bot}"

PROFILE=prod
TARGET_TAG=stable
CHECK_ONLY=false
FORCE=false

# Bad-digest backoff: after a health-check failure the SAME target digest pair
# is retried with an exponential backoff (BACKOFF_BASE seconds, doubling per
# consecutive failure, capped at BACKOFF_MAX) instead of on every timer tick.
# Keyed to the digest pair, so a freshly promoted (fixed) image still deploys
# immediately; `--force` bypasses the wait.
BACKOFF_BASE="${IRI_BACKOFF_BASE:-600}"
BACKOFF_MAX="${IRI_BACKOFF_MAX:-21600}"

# --- CLI args ---------------------------------------------------------------
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
  --check-only    Resolve digests but do not apply (dry-run).
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
  IRI_REGISTRY=ghcr.io
  IRI_IMAGE_NAMESPACE=krt-profit
  IRI_GHCR_USERNAME=deploy-bot
  DOCKER_CONFIG=/var/lib/iri/.docker   (where `docker login` writes its
                                        credentials.json; defaults to a
                                        per-script location under STATE_DIR
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

# --- Helpers ----------------------------------------------------------------
log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

fail() {
  log "FATAL: $*"
  exit 1
}

require_file() {
  [[ -f "$1" ]] || fail "required file missing: $1"
}

# Mirror a source directory onto a destination, propagating deletions WITHIN the
# subtree only. Used to apply the bundled maintenance-page / Keycloak-theme trees
# without ever touching anything outside them. Prefers rsync; falls back to a
# clean re-copy when rsync is absent (it is not a hard host dependency).
#
# Runs as the unprivileged `deploy` user, so it deliberately does NOT preserve
# owner/group. `rsync -a` (= -rlptgoD) and `cp -a` (= --preserve=all) both try to
# `chgrp`/`chown` every entry, which fails ("Operation not permitted") the moment
# any file in the live tree is owned by someone else — e.g. a root-owned bootstrap
# copy of keycloak-theme. `rsync -rlpt` / `cp -R` keep recursion, symlinks, perms
# and times (all the maintenance/theme assets need) without ever touching
# ownership, so the mirror succeeds as long as the destination dirs are writable.
mirror_dir() {
  local src="$1" dst="$2"
  if command -v rsync >/dev/null 2>&1; then
    rsync -rlpt --delete "${src}/" "${dst}/"
  else
    rm -rf "${dst}"
    install -d "${dst%/*}"
    cp -R "${src}" "${dst}"
  fi
}

# Extract the promoted config bundle (/config inside the scratch basetool-config
# image) into a staging dir. The image has no entrypoint/command, so `docker
# create` needs a placeholder argument; the container is never started —
# `docker cp` reads straight from its filesystem layer.
extract_config_bundle() {
  local ref="$1" dest="$2" cid
  rm -rf "${dest}"
  install -d -m 0755 "${dest}"
  cid="$(docker create "${ref}" /bundle 2>/dev/null)" \
    || fail "cannot create container from config image ${ref}"
  if ! docker cp "${cid}:/config/." "${dest}/" >/dev/null 2>&1; then
    docker rm -f "${cid}" >/dev/null 2>&1 || true
    fail "cannot extract /config from config image ${ref}"
  fi
  docker rm -f "${cid}" >/dev/null 2>&1 || true
}

# Fail loudly if a staged config bundle smuggled in a host secret. The bundle is
# built from an explicit COPY allowlist and .dockerignore bars secrets from the
# build context, but this is the last gate before the tree is copied onto the
# host — defence in depth against a future Dockerfile edit widening the COPY.
assert_no_secrets() {
  local dir="$1" name
  for name in .env keystore.p12 realm-export.json; do
    if find "${dir}" -name "${name}" -print -quit 2>/dev/null | grep -q .; then
      fail "SECURITY: promoted config bundle contains forbidden file '${name}' — aborting before apply"
    fi
  done
  if [[ -d "${dir}/keycloak/providers" ]]; then
    fail "SECURITY: promoted config bundle contains keycloak/providers — aborting before apply"
  fi
}

# Emit the postgres + Keycloak image pins of a compose file, normalised and
# sorted. These are the stateful/choreographed images whose change must be
# operator-gated (PGDATA major migration; Keycloak provider+keystore dance);
# everything else (redis, npm) is safe to auto-apply via a declarative `up -d`.
infra_image_pins() {
  # `|| true`: a compose file with no match makes grep exit 1, which under
  # `set -o pipefail` would abort the surrounding command substitution. An empty
  # result is the correct "no stateful-infra pins seen" answer here.
  grep -Eo 'image:[[:space:]]*(postgres:[^[:space:]]+|quay\.io/keycloak/keycloak:[^[:space:]]+)' "$1" \
    | sed -E 's/image:[[:space:]]*//' | sort -u || true
}

# Emit the compose top-level `networks:` block, comment- and blank-stripped, so a
# deploy can tell whether the network TOPOLOGY changed (a network added/removed, a
# subnet/gateway re-pinned). Such a change cannot be applied by an in-place `up -d`:
# Docker can neither move a running container onto a differently-addressed bridge
# nor recreate a bridge that still has endpoints, so an in-place apply silently
# STRANDS container name resolution (keycloak<->backend, keycloak<->db-keycloak —
# the 2026-07 incident, #974). It must instead force a clean down+up. A
# comment-only edit inside the block does not count (comments are stripped here).
network_block() {
  awk '
    /^networks:[[:space:]]*$/ { inblock = 1; print; next }
    inblock && /^[^[:space:]#]/ { inblock = 0 }
    inblock {
      line = $0
      sub(/[[:space:]]*#.*$/, "", line)
      if (line ~ /[^[:space:]]/) print line
    }
  ' "$1"
}

# Bring the WHOLE stack fully down — the app project AND the monitoring project that
# references the shared data nets as `external` — drop the stale bridges, so the
# following `up` recreates them on the compose's (new) pinned subnets. Only used on
# the rare deploy that changes the compose `networks:` block: an in-place `up` there
# strands name resolution (#974). This is a brief FULL-STACK outage, which is why it
# is gated on an actual topology change, never taken on an ordinary config swap.
# Because the pinning fixes each subnet, the recreated bridges keep the same
# addresses/gateways, so the NPM SSH-tunnel admin allow-list stays valid.
clean_slate_recreate() {
  log "network topology changed -> clean recreate (brief full-stack downtime)"
  if [[ "${IRI_MONITORING_ENABLED:-false}" == "true" && -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" ]]; then
    log "  monitoring down (it holds the shared data nets as external)"
    docker compose -p iri-monitoring --project-directory "${COMPOSE_DIR}" \
      -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" down --remove-orphans >/dev/null 2>&1 \
      || log "  WARN: monitoring 'down' reported an error (continuing)"
  fi
  log "  app down"
  docker compose -f "${COMPOSE_DIR}/docker-compose.yml" --profile "${PROFILE}" \
    down --remove-orphans >/dev/null 2>&1 \
    || log "  WARN: app 'down' reported an error (continuing to prune + up)"
  # Belt-and-braces: a stray endpoint can block `down` from removing a bridge; drop
  # any now-unused network so `up` cannot reuse a stale-subnet one (single-purpose host).
  docker network prune -f >/dev/null 2>&1 || true
}

# Snapshot the live config tree (the allowlisted paths) into a directory so the
# rollback path can restore the exact compose the previous digest pin expects.
snapshot_config_tree() {
  local dst="$1"
  rm -rf "${dst}"
  install -d -m 0755 "${dst}"
  [[ -f "${COMPOSE_DIR}/docker-compose.yml" ]] \
    && cp -a "${COMPOSE_DIR}/docker-compose.yml" "${dst}/docker-compose.yml"
  if [[ -d "${COMPOSE_DIR}/docker/maintenance" ]]; then
    install -d "${dst}/docker"
    cp -a "${COMPOSE_DIR}/docker/maintenance" "${dst}/docker/maintenance"
  fi
  [[ -d "${COMPOSE_DIR}/keycloak-theme" ]] \
    && cp -a "${COMPOSE_DIR}/keycloak-theme" "${dst}/keycloak-theme"
  # Monitoring compose + config tree (epic #936). Carries no secrets; snapshotted so a rollback
  # restores the exact monitoring config the previous release shipped.
  [[ -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" ]] \
    && cp -a "${COMPOSE_DIR}/docker-compose.monitoring.yml" "${dst}/docker-compose.monitoring.yml"
  [[ -d "${COMPOSE_DIR}/monitoring" ]] \
    && cp -a "${COMPOSE_DIR}/monitoring" "${dst}/monitoring"
  return 0
}

# Copy the allowlisted config paths from a source tree onto the host. The compose
# file is replaced atomically (write a temp, then rename → new inode) so a
# concurrent reader never sees a half-written file; the asset trees are mirrored
# within their own subtree only.
apply_config_tree() {
  local src="$1" dst="$2"
  install -m 0644 "${src}/docker-compose.yml" "${dst}/.docker-compose.yml.tmp"
  mv -f "${dst}/.docker-compose.yml.tmp" "${dst}/docker-compose.yml"
  # Monitoring compose (atomic replace) + config tree (epic #936). Only present in bundles built
  # after Phase 2; the guards keep an older bundle (no monitoring/) applying cleanly.
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
  if [[ -d "${src}/keycloak-theme" ]]; then
    mirror_dir "${src}/keycloak-theme" "${dst}/keycloak-theme"
  fi
}

# In-place reload of a monitoring service whose slice of the config tree changed this run.
# `up -d` recreates a container only when its DEFINITION changes; a config-file-only edit would
# otherwise leave the process on the old config until its next natural recreate. Prometheus
# (config + alert rules), Alloy (pipelines) and blackbox_exporter do NOT auto-reload, so we send
# them SIGHUP — an in-place reload with no restart, no scrape gap, no state loss (an invalid
# config is rejected and the running one stays live). Grafana file-provisions its dashboards and
# the Loki ruler polls its rule dir, so both pick changes up on their own and are deliberately not
# signalled here. `${CONFIG_PREVIOUS_DIR}/monitoring` is the pre-apply snapshot; `diff -rq` is
# non-zero when the subtree changed OR the snapshot is absent (first apply) — both mean "reload".
# Best-effort: a stopped service or a failed signal only logs and never gates the deploy.
reload_monitoring_on_config_change() {
  local svc="$1" subpath="$2"
  if diff -rq "${CONFIG_PREVIOUS_DIR}/monitoring/${subpath}" \
       "${COMPOSE_DIR}/monitoring/${subpath}" >/dev/null 2>&1; then
    return 0
  fi
  log "  monitoring: ${subpath} config changed → reloading ${svc} (SIGHUP)"
  docker compose -p iri-monitoring --project-directory "${COMPOSE_DIR}" \
    -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" kill -s SIGHUP "${svc}" >/dev/null 2>&1 \
    || log "  monitoring: WARN reload of ${svc} failed (non-gating; service not running?)"
}

# Extract the promoted Keycloak provider JAR (/providers/keycloak-spi.jar inside
# the scratch basetool-keycloak-spi image) onto the host as the mounted provider
# JAR. Like the config bundle the image has no entrypoint/command, so `docker
# create` needs a placeholder argument; the container is never started. The JAR is
# installed 0644 (and its parent dir created) so the uid-1000 Keycloak runtime can
# read it through the providers bind mount.
extract_keycloak_spi_jar() {
  local ref="$1" dest_jar="$2" cid stage
  stage="${STATE_DIR}/keycloak-spi-stage.jar"
  rm -f "${stage}"
  cid="$(docker create "${ref}" /bundle 2>/dev/null)" \
    || fail "cannot create container from keycloak-spi image ${ref}"
  if ! docker cp "${cid}:/providers/keycloak-spi.jar" "${stage}" >/dev/null 2>&1; then
    docker rm -f "${cid}" >/dev/null 2>&1 || true
    fail "cannot extract /providers/keycloak-spi.jar from ${ref}"
  fi
  docker rm -f "${cid}" >/dev/null 2>&1 || true
  install -D -m 0644 "${stage}" "${dest_jar}"
  rm -f "${stage}"
}

# --- Pre-flight -------------------------------------------------------------
require_file "${COMPOSE_DIR}/docker-compose.yml"
require_file "${COMPOSE_DIR}/.env"
require_file "${TOKEN_FILE}"

# Pull the host-side keystore path from .env so the pre-flight check covers
# the actual mount source, not just our hard-coded default. The fall-back
# matches the default in docker-compose.yml's volume entry.
KEYSTORE_HOST_PATH="$(grep -E '^IRI_KEYSTORE_HOST_PATH=' "${COMPOSE_DIR}/.env" 2>/dev/null \
                       | tail -n1 | cut -d= -f2- | tr -d '"' || true)"
KEYSTORE_HOST_PATH="${KEYSTORE_HOST_PATH:-/var/iri/secrets/keystore.p12}"
require_file "${KEYSTORE_HOST_PATH}"

mkdir -p "${STATE_DIR}"

# Pin DOCKER_CONFIG BEFORE the first docker invocation. The `deploy` user is
# created with `useradd --no-create-home`, so $HOME=/home/deploy does not
# exist on disk; under the systemd unit's `ProtectHome=true` the directory
# is additionally an inaccessible empty tmpfs mount. Either situation makes
# Docker CLI's default config-discovery path under $HOME/.docker fail —
# `docker login` would error out with `mkdir /home/deploy: permission
# denied`, and even the cheaper `docker compose version --short` pre-flight
# probe below exits non-zero because the compose plugin tries to read its
# config from the unreachable $HOME on startup.
#
# Pinning DOCKER_CONFIG into STATE_DIR sidesteps both problems: the path is
# already in the systemd unit's ReadWritePaths set, persists the credential
# between timer ticks, and stays under the deploy user's exclusive 0700
# ownership. Must come BEFORE the docker compose version check below — the
# order is load-bearing.
export DOCKER_CONFIG="${DOCKER_CONFIG:-${STATE_DIR}/.docker}"
install -d -m 0700 "${DOCKER_CONFIG}"

# Compose v2 ships with Docker Engine ≥ 20.10.13 as `docker compose`; the
# `--wait` flag landed in 2.1.0. Fail fast on older installs rather than
# discovering it during `up`.
if ! docker compose version --short >/dev/null 2>&1; then
  fail "docker compose v2 not available; install Docker Engine ≥ 23.x"
fi

PIN_FILE_CURRENT="${STATE_DIR}/current-digest-pin.yml"
PIN_FILE_PREVIOUS="${STATE_DIR}/previous-digest-pin.yml"
LAST_DEPLOYED_FILE="${STATE_DIR}/last-deployed.digests"
FAILED_FILE="${STATE_DIR}/failed.digests"
CONFIG_STAGE_DIR="${STATE_DIR}/config-stage"
CONFIG_PREVIOUS_DIR="${STATE_DIR}/config-previous"
# Set true once a new config tree is swapped in this run; gates the post-apply monitoring
# in-place reload so a config-unchanged tick signals nothing.
CONFIG_APPLIED=false
# Set true when the promoted compose changes the `networks:` topology (a re-pinned
# subnet, a net added/removed); forces a clean down+up instead of an in-place `up`
# on apply AND on rollback, so the change never strands name resolution (#974).
NETWORK_TOPOLOGY_CHANGED=false
CONFIG_BLOCKED_FILE="${STATE_DIR}/config-blocked.marker"
# The live Keycloak provider JAR (mounted into the keycloak container) and the
# rollback snapshot of it taken before a provider-JAR swap.
KEYCLOAK_SPI_JAR="${COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar"
KEYCLOAK_SPI_PREVIOUS_JAR="${STATE_DIR}/keycloak-spi-previous.jar"

# --- Monitoring textfile metrics (epic #936, ADR-0072) ----------------------
# Per-outcome timestamps so the alert catalog can tell success / rollback / failure / blocked apart:
# a rollback or failure NEWER than the last success is CRITICAL (a promoted release did not ship).
# The four outcome timestamps persist across runs (each write updates only its own and preserves the
# others); config_blocked mirrors the presence of the config-blocked marker. Written to the
# node_exporter textfile dir (already inside the systemd unit's ReadWritePaths=/var/iri).
TEXTFILE_DIR="${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}"
DEPLOY_METRIC_FILE="${TEXTFILE_DIR}/deploy.prom"
START_EPOCH="$(date +%s)"

# write_deploy_metric <success|rollback|failure|blocked>
write_deploy_metric() {
  local outcome="$1" now dur f v blocked tmp
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
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  tmp="${f}.$$"
  if {
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
  } > "${tmp}" 2>/dev/null; then
    mv -f "${tmp}" "${f}" 2>/dev/null || true
  else
    log "WARN: could not write deploy textfile metric (${TEXTFILE_DIR})"
    rm -f "${tmp}" 2>/dev/null || true
  fi
}

# --- Lock -------------------------------------------------------------------
exec 200>"${LOCKFILE}"
if ! flock -n 200; then
  log "another deploy is in progress (lock: ${LOCKFILE}); exiting"
  exit 0
fi

# --- Authenticate to GHCR ---------------------------------------------------
log "logging in to ${REGISTRY} as ${GHCR_USERNAME}"
if ! docker login "${REGISTRY}" \
       --username "${GHCR_USERNAME}" \
       --password-stdin < "${TOKEN_FILE}" >/dev/null 2>&1; then
  fail "docker login to ${REGISTRY} failed — check ${TOKEN_FILE} (scope: read:packages)"
fi

# --- Resolve target digests -------------------------------------------------
BACKEND_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-backend"
FRONTEND_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-frontend"
INGEST_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-ingest"
CONFIG_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-config"
KEYCLOAK_SPI_IMAGE="${REGISTRY}/${NAMESPACE}/basetool-keycloak-spi"

resolve_digest() {
  # buildx imagetools resolves a tag to its manifest digest without pulling
  # the image. Works for multi-arch lists (returns the index digest) and for
  # single-platform manifests alike.
  local ref="$1"
  docker buildx imagetools inspect "${ref}" --format '{{.Manifest.Digest}}' 2>/dev/null
}

log "resolving ${TARGET_TAG} → digest"
BACKEND_DIGEST="$(resolve_digest "${BACKEND_IMAGE}:${TARGET_TAG}")" \
  || fail "cannot resolve ${BACKEND_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"
FRONTEND_DIGEST="$(resolve_digest "${FRONTEND_IMAGE}:${TARGET_TAG}")" \
  || fail "cannot resolve ${FRONTEND_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"
INGEST_DIGEST="$(resolve_digest "${INGEST_IMAGE}:${TARGET_TAG}")" \
  || fail "cannot resolve ${INGEST_IMAGE}:${TARGET_TAG} (tag missing or no GHCR access)"

# Config artifact is resolved BEST-EFFORT: a host running this script before the
# first config:stable promotion (or a transient hiccup on just this one tag) must
# not brick the app deploy loop. When absent, fall back to the legacy app-only
# behaviour for this tick (3-field marker, no config staging).
CONFIG_DIGEST="$(resolve_digest "${CONFIG_IMAGE}:${TARGET_TAG}")" || CONFIG_DIGEST=""

# The Keycloak provider-JAR artifact is resolved BEST-EFFORT too (same rationale
# as the config bundle): a host running before the first keycloak-spi:stable
# promotion, or a transient hiccup on just this tag, must not brick the deploy
# loop. When absent, this tick simply makes no provider-JAR change.
KEYCLOAK_SPI_DIGEST="$(resolve_digest "${KEYCLOAK_SPI_IMAGE}:${TARGET_TAG}")" || KEYCLOAK_SPI_DIGEST=""

log "target backend  ${BACKEND_DIGEST}"
log "target frontend ${FRONTEND_DIGEST}"
log "target ingest   ${INGEST_DIGEST}"

# The idempotence marker is a single whitespace-free token (digests carry no
# spaces), so the failed.digests `read -r REC_MARKER REC_COUNT REC_EPOCH` parsing
# below stays a single field. It is a FIXED 5-field positional record —
#   backend|frontend|ingest|config|keycloak-spi
# so a change to ANY component (incl. a config-only or provider-JAR-only change)
# moves the marker, and a stale app-only deploy never silently drops a promoted
# compose or provider JAR. The config + keycloak-spi digests are resolved
# best-effort: an unavailable one is an empty field (nothing to (re)stage for that
# component this tick — the legacy app-only behaviour).
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

# Decide whether the config and/or keycloak-spi components changed since the last
# successful deploy — used to skip re-staging an unchanged component. Parse the
# last marker positionally; a shorter legacy marker (pre-config or
# pre-keycloak-spi) leaves the missing fields empty, so a present-but-unrecorded
# digest reads as changed and is staged on the first tick that sees it.
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

# --- Idempotence check ------------------------------------------------------
# The marker only records what the last SUCCESSFUL deploy applied — trusting it
# alone is not enough. A manual `docker compose up` without the digest-pin
# overlay resolves `:stable` from the LOCAL image cache, which this script
# never refreshes (it always pulls by digest), so it can silently start an
# outdated build; a crash loop or a half-down stack likewise leaves the marker
# untouched while prod serves the wrong version or none. Incident 2026-07-02:
# a pre-V199 backend started that way against a V201-migrated database was
# crash-looping while this script reported "no change" and exited 0. So the
# fast exit is taken only after verifying the RUNNING stack against the target.

# Emits one `service: reason` line per divergence between the running stack
# and the target digest set; emits nothing when every app service has a
# container that is running, healthy (a container without a healthcheck counts
# as healthy, mirroring `up --wait`) and created from an image whose RepoDigest
# equals the target digest. `ps -aq` (not `-q`) so stopped/created/restarting
# containers are judged by their state instead of being reported as absent.
running_stack_drift() {
  local entry svc image digest cids cid probe state img_id repo_digests
  for entry in \
    "backend|${BACKEND_IMAGE}|${BACKEND_DIGEST}" \
    "frontend|${FRONTEND_IMAGE}|${FRONTEND_DIGEST}" \
    "ingest|${INGEST_IMAGE}|${INGEST_DIGEST}"; do
    IFS='|' read -r svc image digest <<< "${entry}"
    cids="$(docker compose -f "${COMPOSE_DIR}/docker-compose.yml" \
              --profile "${PROFILE}" ps -aq "${svc}" 2>/dev/null)" || cids=""
    if [[ -z "${cids}" ]]; then
      echo "${svc}: no container"
      continue
    fi
    while IFS= read -r cid; do
      [[ -n "${cid}" ]] || continue
      probe="$(docker inspect --format \
        '{{index .Config.Labels "com.docker.compose.oneoff"}}|{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' \
        "${cid}" 2>/dev/null)" || probe="|gone"
      # One-off `docker compose run` containers (debug shells, ad-hoc jobs) are
      # listed by `ps -aq` alongside the service replica but are not part of
      # the deployed stack — judging them would flag drift on every tick for
      # as long as they exist.
      if [[ "${probe%%|*}" == "True" ]]; then
        continue
      fi
      state="${probe#*|}"
      case "${state}" in
        # running/starting (healthcheck still inside its start period, e.g.
        # the restart policies bringing the stack up after a host reboot while
        # a tick fires) counts as converged for THIS tick: `up --wait` would
        # simply wait on it, and re-applying here could record a false backoff
        # failure for a perfectly good target. A genuinely broken container
        # surfaces as unhealthy/restarting on a later tick.
        running/healthy | running/no-healthcheck | running/starting) ;;
        *)
          echo "${svc}: container state ${state}"
          continue
          ;;
      esac
      img_id="$(docker inspect --format '{{.Image}}' "${cid}" 2>/dev/null)" || img_id=""
      repo_digests=""
      if [[ -n "${img_id}" ]]; then
        repo_digests="$(docker image inspect \
          --format '{{join .RepoDigests " "}}' "${img_id}" 2>/dev/null)" || repo_digests=""
      fi
      case " ${repo_digests} " in
        *" ${image}@${digest} "*) ;;
        *)
          echo "${svc}: running image [${repo_digests:-unknown}] does not match target ${digest}"
          ;;
      esac
    done <<< "${cids}"
  done
  return 0
}

DRIFTED=false
if [[ -f "${LAST_DEPLOYED_FILE}" ]] \
   && grep -qFx "${EXPECTED_MARKER}" "${LAST_DEPLOYED_FILE}"; then
  DRIFT_REPORT="$(running_stack_drift)"
  if [[ -z "${DRIFT_REPORT}" ]]; then
    log "no change — already at target digests (running stack verified)"
    exit 0
  fi
  DRIFTED=true
  while IFS= read -r drift_line; do
    log "drift: ${drift_line}"
  done <<< "${DRIFT_REPORT}"
  log "running stack does not match the last-deployed target — re-applying"
fi

if [[ "${CHECK_ONLY}" == "true" ]]; then
  if [[ "${DRIFTED}" == "true" ]]; then
    log "check-only: would re-apply (running stack drifted from target digests)"
  else
    log "check-only: would deploy"
  fi
  exit 0
fi

# --- Bad-digest backoff -----------------------------------------------------
# A target whose health check failed is NOT retried on every 5-minute tick:
# without this, a broken `:stable` (or a transient failure) re-applies and rolls
# back forever, each cycle taking the stack offline for the HEALTH_TIMEOUT
# window. We back off exponentially per consecutive failure of the SAME digest
# pair; promoting a new (fixed) image changes EXPECTED_MARKER, clears the record
# and deploys at once, so only re-attempts of the known-bad pair are throttled.
if [[ -f "${FAILED_FILE}" ]]; then
  read -r REC_MARKER REC_COUNT REC_EPOCH _ < "${FAILED_FILE}" || true
  if [[ "${REC_MARKER:-}" != "${EXPECTED_MARKER}" ]] \
     || ! [[ "${REC_COUNT:-}" =~ ^[0-9]+$ ]] \
     || ! [[ "${REC_EPOCH:-}" =~ ^[0-9]+$ ]]; then
    # Target moved to a new promotion, or the record is stale/corrupt: drop it
    # and deploy normally.
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

# --- Save rollback anchor + write new pin -----------------------------------
[[ -f "${PIN_FILE_CURRENT}" ]] && cp "${PIN_FILE_CURRENT}" "${PIN_FILE_PREVIOUS}"

cat > "${PIN_FILE_CURRENT}" <<EOF
# Auto-generated by scripts/deploy.sh. Do not edit by hand — it is rewritten
# on every deploy. Pinning to the exact image digests makes a subsequent
# \`:stable\` tag flip in GHCR a no-op until the next deploy.sh run.
services:
  backend:
    image: ${BACKEND_IMAGE}@${BACKEND_DIGEST}
  frontend:
    image: ${FRONTEND_IMAGE}@${FRONTEND_DIGEST}
  ingest:
    image: ${INGEST_IMAGE}@${INGEST_DIGEST}
EOF

# --- Deliver promoted host config -------------------------------------------
# The compose file and its sibling host config (NPM maintenance page, Keycloak
# theme) ride the SAME promoted, digest-pinned GHCR channel as the app images.
# Only (re)stage them when the promoted config digest actually moved, so an
# app-only promotion stays byte-for-byte the legacy path.
if [[ "${CONFIG_CHANGED}" == "true" ]]; then
  log "config changed → staging ${CONFIG_IMAGE}@${CONFIG_DIGEST}"
  extract_config_bundle "${CONFIG_IMAGE}@${CONFIG_DIGEST}" "${CONFIG_STAGE_DIR}"
  require_file "${CONFIG_STAGE_DIR}/docker-compose.yml"
  assert_no_secrets "${CONFIG_STAGE_DIR}"

  # Carve-out: a postgres / Keycloak IMAGE change is a stateful, choreographed
  # upgrade (PGDATA major migration; Keycloak provider+keystore dance) that a
  # blind `up -d` would break and the health-gate would then roll back on a
  # 5-minute loop. Refuse to auto-apply it; record the target so we alert ONCE
  # and then skip quietly until a new promotion or an operator --force. The
  # operator runs the documented manual upgrade, then re-runs with --force.
  OLD_INFRA="$(infra_image_pins "${COMPOSE_DIR}/docker-compose.yml")"
  NEW_INFRA="$(infra_image_pins "${CONFIG_STAGE_DIR}/docker-compose.yml")"
  if [[ "${OLD_INFRA}" != "${NEW_INFRA}" ]] && [[ "${FORCE}" != "true" ]]; then
    if [[ -f "${CONFIG_BLOCKED_FILE}" ]] && grep -qFx "${EXPECTED_MARKER}" "${CONFIG_BLOCKED_FILE}"; then
      log "stateful-infra upgrade still operator-gated for this target; skipping tick (run the manual upgrade then --force)"
      exit 0
    fi
    echo "${EXPECTED_MARKER}" > "${CONFIG_BLOCKED_FILE}"
    log "CARVE-OUT: postgres/Keycloak image pin changed — refusing to auto-apply a stateful-infra upgrade"
    log "  old: $(printf '%s' "${OLD_INFRA}" | tr '\n' ' ')"
    log "  new: $(printf '%s' "${NEW_INFRA}" | tr '\n' ' ')"
    log "  perform the documented manual upgrade (docs/deployment.md → Stateful-infra upgrades), then: deploy.sh --force"
    write_deploy_metric blocked
    exit 3
  fi
  rm -f "${CONFIG_BLOCKED_FILE}"

  # A change to the compose `networks:` block cannot be applied by an in-place
  # `up -d` (it strands container name resolution, #974). Detect it now, while the
  # LIVE compose is still on disk, so the apply below forces a clean down+up.
  if [[ "$(network_block "${COMPOSE_DIR}/docker-compose.yml")" \
        != "$(network_block "${CONFIG_STAGE_DIR}/docker-compose.yml")" ]]; then
    NETWORK_TOPOLOGY_CHANGED=true
    log "network topology changed in the promoted compose -> clean recreate on apply (#974)"
  fi

  # Snapshot the live config tree as the rollback anchor, then swap in the new.
  snapshot_config_tree "${CONFIG_PREVIOUS_DIR}"
  apply_config_tree "${CONFIG_STAGE_DIR}" "${COMPOSE_DIR}"
  [[ -f "${COMPOSE_DIR}/.env" ]] \
    || fail "POST-APPLY: ${COMPOSE_DIR}/.env vanished after config swap — aborting before up"
  log "config applied"
  CONFIG_APPLIED=true
fi

# --- Apply ------------------------------------------------------------------
cd "${COMPOSE_DIR}"

# Only pre-pull the images this deploy actually moves (backend + frontend +
# ingest from GHCR). The third-party infra images (keycloak/postgres/redis/npm)
# are pinned by digest and change only on a deliberate compose edit; pulling
# them here would make every deploy hostage to a transient outage of a
# third-party registry (e.g. a quay.io 502/504 on the Keycloak manifest aborting
# the whole `pull` under `set -e`, before `up` ever runs). The `up -d` below
# still pulls any infra image that is genuinely missing locally, so a real
# digest bump is rolled forward — an already-present pinned image is simply
# reused offline.
log "pulling images"
docker compose \
  -f docker-compose.yml \
  -f "${PIN_FILE_CURRENT}" \
  --profile "${PROFILE}" \
  pull --quiet backend frontend ingest

# A network-topology change (detected above) cannot be applied in place — take the
# whole stack down first so the `up` below recreates the bridges on the compose's
# pinned subnets (#974). Ordinary config/app changes keep the fast rolling in-place
# `up`; only a `networks:` edit pays the brief full-stack outage.
if [[ "${NETWORK_TOPOLOGY_CHANGED}" == "true" ]]; then
  clean_slate_recreate
fi

log "applying (timeout ${HEALTH_TIMEOUT}s)"
if docker compose \
     -f docker-compose.yml \
     -f "${PIN_FILE_CURRENT}" \
     --profile "${PROFILE}" \
     up -d \
        --no-build \
        --remove-orphans \
        --wait \
        --wait-timeout "${HEALTH_TIMEOUT}"; then

  # The app stack is healthy. If the promoted provider JAR moved, swap it in and
  # recreate ONLY keycloak so its `start` re-runs the provider build and loads the
  # new JAR — health-gated, with a JAR rollback on failure. A Keycloak IMAGE pin
  # change is NOT handled here: that arrives via the config bundle and is already
  # operator-gated by the infra_image_pins carve-out above, so a combined
  # image+JAR change never reaches this auto-apply path without --force.
  if [[ "${KEYCLOAK_SPI_CHANGED}" == "true" ]]; then
    log "keycloak-spi changed → staging provider JAR + recreating keycloak"
    if [[ -f "${KEYCLOAK_SPI_JAR}" ]]; then
      cp -a "${KEYCLOAK_SPI_JAR}" "${KEYCLOAK_SPI_PREVIOUS_JAR}"
      KEYCLOAK_SPI_HAD_PREVIOUS=true
    else
      rm -f "${KEYCLOAK_SPI_PREVIOUS_JAR}"
      KEYCLOAK_SPI_HAD_PREVIOUS=false
    fi
    extract_keycloak_spi_jar "${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST}" "${KEYCLOAK_SPI_JAR}"

    if ! docker compose \
           -f docker-compose.yml \
           -f "${PIN_FILE_CURRENT}" \
           --profile "${PROFILE}" \
           up -d --no-deps --force-recreate \
              --wait --wait-timeout "${HEALTH_TIMEOUT}" keycloak; then
      log "keycloak did not become healthy with the new provider JAR — rolling back the JAR"
      if [[ "${KEYCLOAK_SPI_HAD_PREVIOUS}" == "true" ]]; then
        install -D -m 0644 "${KEYCLOAK_SPI_PREVIOUS_JAR}" "${KEYCLOAK_SPI_JAR}"
      else
        rm -f "${KEYCLOAK_SPI_JAR}"
      fi
      docker compose \
        -f docker-compose.yml \
        -f "${PIN_FILE_CURRENT}" \
        --profile "${PROFILE}" \
        up -d --no-deps --force-recreate \
           --wait --wait-timeout "${HEALTH_TIMEOUT}" keycloak >/dev/null 2>&1 \
        || log "WARNING: keycloak did not return to health on the previous JAR — manual check needed"

      # Record the failure so the backoff throttles re-attempts of this exact
      # target, the same mechanism as a failed app deploy. The app images stay on
      # the new (healthy) version; only the provider JAR was reverted, so the
      # marker is deliberately NOT written and the next tick retries (backed off).
      FAIL_COUNT=1
      if [[ -f "${FAILED_FILE}" ]]; then
        read -r PREV_MARKER PREV_COUNT _ < "${FAILED_FILE}" || true
        if [[ "${PREV_MARKER:-}" == "${EXPECTED_MARKER}" ]] && [[ "${PREV_COUNT:-}" =~ ^[0-9]+$ ]]; then
          FAIL_COUNT=$(( 10#${PREV_COUNT} + 1 ))
        fi
      fi
      printf '%s %d %d\n' "${EXPECTED_MARKER}" "${FAIL_COUNT}" "$(date +%s)" > "${FAILED_FILE}"
      log "recorded keycloak-spi health-check failure #${FAIL_COUNT} for this target"
      write_deploy_metric failure
      exit 1
    fi
    log "keycloak-spi provider JAR applied"
  fi

  echo "${EXPECTED_MARKER}" > "${LAST_DEPLOYED_FILE}"
  rm -f "${FAILED_FILE}" "${CONFIG_BLOCKED_FILE}"
  log "deploy successful"
  write_deploy_metric success

  # --- Non-gating monitoring apply (epic #936, ADR-0072) ---------------------
  # Reconcile the SEPARATE monitoring project AFTER the app stack is verified healthy — this NEVER
  # gates the app deploy. The app `up` above already (re)created the shared net-monitoring-scrape and
  # the data nets the monitoring project references as external, so they resolve here. Monitoring
  # config changes (new dashboards/alert rules) arrive via the config bundle, so they land on this
  # path; crashed monitoring containers recover on their own restart policy. Any failure only logs —
  # the app deploy stays successful. Gated on IRI_MONITORING_ENABLED=true (unset on a host without
  # the stack). pipefail makes the `if` observe compose's real exit through the sed pipe.
  if [[ "${IRI_MONITORING_ENABLED:-false}" == "true" && -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" ]]; then
    log "applying monitoring stack (non-gating)"
    if docker compose -p iri-monitoring --project-directory "${COMPOSE_DIR}" \
         -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" up -d 2>&1 | sed 's/^/  monitoring: /'; then
      log "monitoring stack reconciled"
      # `up -d` already reloaded any service whose DEFINITION changed; for a config-file-only
      # change, SIGHUP the components that do not auto-reload — only when this tick swapped in a
      # new config tree, and only the services whose own slice actually changed.
      if [[ "${CONFIG_APPLIED}" == "true" ]]; then
        reload_monitoring_on_config_change prometheus prometheus
        reload_monitoring_on_config_change alloy alloy
        reload_monitoring_on_config_change blackbox-exporter blackbox
      fi
    else
      log "WARN: monitoring stack apply failed — app deploy stays successful (non-gating)"
    fi
  fi

  # Best-effort prune of dangling images older than 30 days. Restricted via
  # `until=720h` to avoid wiping the just-pulled images we may still need to
  # roll back to. `|| true` because a stuck container ref can transiently
  # block a prune and we should not fail the deploy over it.
  docker image prune --force --filter "until=720h" >/dev/null 2>&1 || true
  exit 0
fi

# --- Rollback on health failure --------------------------------------------
log "health check failed within ${HEALTH_TIMEOUT}s — rolling back"

# Record this failure so subsequent ticks back off this exact (broken) digest
# pair instead of re-applying it every 5 minutes (see the backoff block above).
FAIL_COUNT=1
if [[ -f "${FAILED_FILE}" ]]; then
  read -r PREV_MARKER PREV_COUNT _ < "${FAILED_FILE}" || true
  if [[ "${PREV_MARKER:-}" == "${EXPECTED_MARKER}" ]] && [[ "${PREV_COUNT:-}" =~ ^[0-9]+$ ]]; then
    FAIL_COUNT=$(( 10#${PREV_COUNT} + 1 ))
  fi
fi
printf '%s %d %d\n' "${EXPECTED_MARKER}" "${FAIL_COUNT}" "$(date +%s)" > "${FAILED_FILE}"
log "recorded health-check failure #${FAIL_COUNT} for this target; next retry backs off"

# Revert the host config too (if this deploy swapped it), so the rolled-back
# stack reads the exact compose the previous digest pin expects. Done before the
# pin check so even the no-previous-pin exit leaves the config tree consistent.
if [[ "${CONFIG_CHANGED}" == "true" ]] && [[ -d "${CONFIG_PREVIOUS_DIR}" ]]; then
  log "restoring previous host config"
  apply_config_tree "${CONFIG_PREVIOUS_DIR}" "${COMPOSE_DIR}"
fi

if [[ ! -f "${PIN_FILE_PREVIOUS}" ]]; then
  log "no previous pin available — manual intervention required"
  write_deploy_metric failure
  exit 2
fi

cp "${PIN_FILE_PREVIOUS}" "${PIN_FILE_CURRENT}"

# If this deploy crossed a network-topology change, the previous compose's subnets
# differ from the just-recreated ones, so the rollback `up` is itself a topology
# change — recreate cleanly again rather than stranding on the way back (#974).
if [[ "${NETWORK_TOPOLOGY_CHANGED}" == "true" ]]; then
  log "rolling back across the network topology change -> clean recreate again"
  clean_slate_recreate
fi

if docker compose \
     -f docker-compose.yml \
     -f "${PIN_FILE_CURRENT}" \
     --profile "${PROFILE}" \
     up -d \
        --no-build \
        --remove-orphans \
        --wait \
        --wait-timeout "${HEALTH_TIMEOUT}"; then
  log "rolled back to previous digest pin successfully"
else
  log "rollback ALSO failed — one or more target digests broken or environment problem"
fi

# clean_slate_recreate() took the monitoring project down to release the shared nets;
# the success path's monitoring reconcile never runs on a rollback, so bring it back
# best-effort here rather than leaving it down until the next successful deploy.
if [[ "${NETWORK_TOPOLOGY_CHANGED}" == "true" \
      && "${IRI_MONITORING_ENABLED:-false}" == "true" \
      && -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" ]]; then
  log "restoring the monitoring project after the rollback recreate (best-effort)"
  docker compose -p iri-monitoring --project-directory "${COMPOSE_DIR}" \
    -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" up -d >/dev/null 2>&1 \
    || log "WARN: monitoring restore failed — it returns on the next successful deploy"
fi

# Either way, this run failed → non-zero exit so the systemd unit reports
# `failed`, journalctl flags it, and any OnFailure= hook fires. A rollback newer than the last
# success is the "promoted release did not ship" signal (epic #936 alert catalog).
write_deploy_metric rollback
exit 1
