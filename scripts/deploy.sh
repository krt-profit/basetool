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
# Every resolved digest is cosign-VERIFIED on the host against the release-images
# workflow's keyless signature before it is pulled, extracted or applied
# (REQ-OPS-015) — the host half of the supply-chain seam whose CI half is
# promote.yml. A `:stable` tag moved out-of-band to an untrusted digest is
# rejected here, so the blind `:stable` pull is safe. Requires cosign on the host
# (fail-closed); break-glass IRI_COSIGN_VERIFY=false disables it for a Sigstore
# outage only.
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
#   /var/lib/iri/health-restart.digests    backoff bookkeeping for the
#                                          runtime-health targeted restart: when
#                                          the running stack is already at the
#                                          target release but a container is
#                                          unhealthy (a runtime fault, not a
#                                          wrong release), deploy.sh restarts
#                                          only that service instead of rolling
#                                          the release back. This throttles the
#                                          restart so a container that will not
#                                          recover is not force-recreated every
#                                          tick. Cleared on a restart that
#                                          restores health or a successful
#                                          deploy. Drives the distinct
#                                          DeployHealthRestartFailing alert (via
#                                          deploy-health.prom), NEVER a false
#                                          DeployRolledBack (ADR-0083).
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

# --- Supply-chain signature verification (REQ-OPS-015) ----------------------
# Every resolved image digest is cosign-verified against the release-images
# workflow's keyless (Fulcio/OIDC) signature BEFORE it is pulled, extracted or
# applied — the HOST half of the supply-chain seam (the CI half is promote.yml's
# pre-flight verify). Without this the host trusts whatever `:stable` points at:
# a leaked `packages:write` credential retagging an arbitrary digest to :stable,
# or a registry-side tag manipulation, bypasses promote.yml's verify entirely,
# and the next timer tick would pull and run the unverified image (the deploy
# user is in the `docker` group, so that is code execution as root-equivalent).
# cosign reads the registry credential from the DOCKER_CONFIG written by the
# `docker login` below; keyless verify additionally reaches the Sigstore
# public-good Fulcio/Rekor roots over outbound HTTPS.
#
# Break-glass: IRI_COSIGN_VERIFY=false disables the gate for a tick — use ONLY
# to ride out a Sigstore public-good outage that is blocking every deploy. Each
# skipped verification is logged loudly (WARNING); the deploy still proceeds, so
# keep it a deliberate, temporary override and re-enable it the moment Sigstore
# recovers.
COSIGN_VERIFY="${IRI_COSIGN_VERIFY:-true}"
# The GitHub repository whose release-images.yml workflow identity signed the
# artifacts. Mirrors promote.yml's `--certificate-identity-regexp`. The `@refs/`
# suffix is pinned to `heads/main` (main-branch :edge/:sha builds) or `tags/v.+`
# (release builds) — NOT the broad `refs/.+`, so an image built by a
# workflow_dispatch run off an arbitrary feature branch is not trusted for prod.
COSIGN_REPO="${IRI_COSIGN_REPO:-krt-profit/basetool}"
COSIGN_IDENTITY_REGEXP="${IRI_COSIGN_IDENTITY_REGEXP:-https://github.com/${COSIGN_REPO}/\\.github/workflows/release-images\\.yml@refs/(heads/main|tags/v.+)}"
COSIGN_OIDC_ISSUER="${IRI_COSIGN_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"

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

# Runtime-health-drift restart backoff. When the running stack is already at the
# target release but a container is unhealthy (a RUNTIME fault, not a wrong
# release), deploy.sh restarts only the affected service instead of rolling the
# stack back. This backoff throttles that targeted restart so a container that
# will not recover is not force-recreated every tick — shorter than the deploy
# backoff above, because a targeted restart is cheap and recovery is urgent.
HEALTH_RESTART_BASE="${IRI_HEALTH_RESTART_BASE:-300}"
HEALTH_RESTART_MAX="${IRI_HEALTH_RESTART_MAX:-3600}"

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
#
# The pattern set MIRRORS the CI-side assertion in release-images.yml (*.p12,
# *.jks, *.pem, *.key, .env, realm-export.json) so the host gate is not narrower
# than the build gate — an earlier version checked only the three literal names
# and would have let a stray `*.pem`/`*.key`/`*.jks` through this last barrier.
assert_no_secrets() {
  local dir="$1"
  # Glob-shaped secrets (any key/cert material), matched by name anywhere in the
  # staged tree. `-iname` is case-insensitive so a `.PEM` cannot slip past.
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

# Idempotently reconcile ONE monitoring service's LOADED config against the on-disk config tree.
# `up -d` recreates a container only when its DEFINITION changes; a bind-mounted config-file edit
# leaves the running process on the OLD config until an explicit SIGHUP — Prometheus (config +
# alert rules), Alloy (pipelines) and blackbox_exporter do NOT auto-reload (Grafana file-provisions
# its dashboards and the Loki ruler polls its rule dir, so both pick changes up on their own and are
# deliberately never signalled). The OLD logic signalled ONLY on the exact tick that swapped a new
# config bundle in AND reached the success block, so a SIGHUP that was skipped (monitoring down at
# that moment), lost, or bypassed by a rollback was NEVER retried — the on-disk config could
# permanently outrun the running process (the 2026-07-11 ingest TargetDown: ADR-0090 moved ingest's
# actuator 11262->11272 and prometheus.yml followed in the same release, but the running Prometheus
# kept scraping the retired 11262). The baseline is now a PERSISTED per-service snapshot of the
# config subtree the process was last SIGHUP'd for (${MON_RELOAD_STATE_DIR}/<svc>), refreshed ONLY
# after a successful signal, and reconciled on EVERY healthy tick: a drift (content differs, or the
# snapshot is absent = never signalled) reloads and re-snapshots; a lost/failed signal leaves the
# snapshot stale so the NEXT tick retries — the self-healing property. An invalid config is rejected
# by the target (the running one stays live) and trips the matching *ConfigReloadFailed alert; a
# genuinely stale running config (a reload that never landed) trips PrometheusConfigStale (meta.yml).
# `diff -rq` compares CONTENT, so rsync size/mtime quick-check quirks cannot mask a change.
# Best-effort: a stopped service or a failed signal only logs and never gates the deploy.
reconcile_monitoring_reload() {
  local svc="$1" subpath="$2" src snap
  src="${COMPOSE_DIR}/monitoring/${subpath}"
  snap="${MON_RELOAD_STATE_DIR}/${svc}"
  # Nothing on disk for this service (a stripped-down monitoring tree) → nothing to reconcile.
  [[ -d "${src}" ]] || return 0
  if diff -rq "${snap}" "${src}" >/dev/null 2>&1; then
    return 0
  fi
  log "  monitoring: ${subpath} config differs from the last reloaded snapshot → reloading ${svc} (SIGHUP)"
  if docker compose -p iri-monitoring --project-directory "${COMPOSE_DIR}" \
       -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" kill -s SIGHUP "${svc}" >/dev/null 2>&1; then
    # Refresh the baseline ONLY on a successful signal, so a failed/lost SIGHUP re-drifts next tick.
    install -d -m 0755 "${MON_RELOAD_STATE_DIR}" 2>/dev/null || true
    rm -rf "${snap}"
    if cp -R "${src}" "${snap}" 2>/dev/null; then
      date +%s > "${MON_RELOAD_STATE_DIR}/${svc}.applied" 2>/dev/null || true
    else
      log "  monitoring: WARN could not snapshot ${subpath} reload baseline (will re-signal next tick)"
    fi
  else
    log "  monitoring: WARN reload of ${svc} failed (non-gating; service not running?) — will retry next tick"
  fi
}

# Emit basetool_monitoring_config_applied_timestamp{component="prometheus"} = the Unix time deploy.sh
# last SIGHUP-signalled a Prometheus config change (the .applied stamp reconcile_monitoring_reload
# writes on a successful signal). Paired in PrometheusConfigStale (meta.yml) with Prometheus's own
# prometheus_config_last_reload_success_timestamp_seconds: if this applied stamp stays NEWER than the
# last successful reload, a reload was missed/lost and the running Prometheus is serving a stale
# config — the gap PrometheusConfigReloadFailed (== 0, only ATTEMPTED-and-FAILED reloads) cannot see.
# Written into the node_exporter textfile dir alongside deploy.prom. Best-effort; an absent stamp
# yields no series, so a host that never re-signalled Prometheus cannot false-fire the alert.
write_prometheus_config_applied_metric() {
  local applied_file applied tmp f
  applied_file="${MON_RELOAD_STATE_DIR}/prometheus.applied"
  [[ -f "${applied_file}" ]] || return 0
  applied="$(cat "${applied_file}" 2>/dev/null || true)"
  [[ "${applied}" =~ ^[0-9]+$ ]] || return 0
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  f="${TEXTFILE_DIR}/monitoring-config.prom"
  tmp="${f}.$$"
  if {
    echo "# HELP basetool_monitoring_config_applied_timestamp Unix time deploy.sh last SIGHUP-signalled a monitoring component's on-disk config change."
    echo "# TYPE basetool_monitoring_config_applied_timestamp gauge"
    echo "basetool_monitoring_config_applied_timestamp{component=\"prometheus\"} ${applied}"
  } > "${tmp}" 2>/dev/null; then
    mv -f "${tmp}" "${f}" 2>/dev/null || true
  else
    log "  monitoring: WARN could not write monitoring-config textfile metric (${TEXTFILE_DIR})"
    rm -f "${tmp}" 2>/dev/null || true
  fi
}

# Reconcile ALL SIGHUP-only monitoring components against on-disk, then refresh the config-applied
# metric. Gated on the monitoring stack being present/enabled on this host. Called on every healthy
# tick — the success block AND the idempotence no-op fast-exit — so a missed reload self-heals even
# on a quiet host that never re-deploys. Cheap: a per-service content diff, a SIGHUP only on drift.
reconcile_monitoring_reloads() {
  [[ "${IRI_MONITORING_ENABLED:-false}" == "true" && -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" ]] || return 0
  reconcile_monitoring_reload prometheus prometheus
  reconcile_monitoring_reload alloy alloy
  reconcile_monitoring_reload blackbox-exporter blackbox
  write_prometheus_config_applied_metric
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

# Cosign-verify one resolved `image@digest` against the release-images workflow's
# keyless signature (REQ-OPS-015). Returns 0 when the signature is trusted (or
# when the break-glass IRI_COSIGN_VERIFY=false is set — logged loudly), non-zero
# when verification fails. Never calls `fail` itself so the caller can attach a
# failure metric before aborting.
verify_signature() {
  local ref="$1"
  if [[ "${COSIGN_VERIFY}" != "true" ]]; then
    log "WARNING: signature verification DISABLED (IRI_COSIGN_VERIFY=false) — NOT verifying ${ref}"
    return 0
  fi
  cosign verify "${ref}" \
    --certificate-identity-regexp "${COSIGN_IDENTITY_REGEXP}" \
    --certificate-oidc-issuer "${COSIGN_OIDC_ISSUER}" \
    >/dev/null 2>&1
}

# Verify a resolved digest or abort the whole deploy. A verification failure is a
# supply-chain alarm (a :stable tag moved to an untrusted digest), so it records
# a deploy-failure metric — surfacing the existing DeployFailed alert — and then
# `fail`s the tick before anything is pulled, extracted or applied.
verify_digest_or_die() {
  local label="$1" ref="$2"
  if verify_signature "${ref}"; then
    log "  ${label}: signature OK"
    return 0
  fi
  write_deploy_metric failure
  fail "SECURITY: cosign signature verification failed for ${label} (${ref}) — refusing to deploy an unverified/untrusted image (expected identity: ${COSIGN_IDENTITY_REGEXP})"
}

# The --check-only variant of the verify: report per-artifact OK/FAIL and return
# non-zero on any failure, but WITHOUT `fail`ing the process or writing a
# deploy-failure metric (a dry-run must not trip DeployFailed). Lets an operator
# preflight the signature gate — `deploy.sh --check-only` — against the current
# :stable in the real deploy-user + sandbox context, without applying anything.
check_only_verify_one() {
  local label="$1" ref="$2"
  if verify_signature "${ref}"; then
    log "  ${label}: signature OK"
    return 0
  fi
  log "  ${label}: SIGNATURE VERIFICATION FAILED (${ref})"
  return 1
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

# cosign writes its Sigstore/TUF cache under $HOME/.sigstore. The deploy user has
# no usable $HOME (created with --no-create-home, and the systemd unit's
# ProtectHome=true makes /home an inaccessible tmpfs), so point $HOME at STATE_DIR
# — already writable in the unit's ReadWritePaths and 0700-owned by deploy —
# otherwise `cosign verify` (REQ-OPS-015) cannot initialise its trust root under
# the sandbox. docker resolves its credential via DOCKER_CONFIG (set above), NOT
# $HOME, so this does not affect the registry login.
export HOME="${IRI_HOME:-${STATE_DIR}}"

# Compose v2 ships with Docker Engine ≥ 20.10.13 as `docker compose`; the
# `--wait` flag landed in 2.1.0. Fail fast on older installs rather than
# discovering it during `up`.
if ! docker compose version --short >/dev/null 2>&1; then
  fail "docker compose v2 not available; install Docker Engine ≥ 23.x"
fi

# cosign is required for the host-side signature gate (REQ-OPS-015). Fail closed:
# a host that cannot verify signatures must not silently fall back to trusting an
# unverified :stable. Missing cosign with the gate ON is a bootstrap error, not a
# reason to skip verification — install cosign (docs/deployment.md) or, only to
# break glass during a Sigstore outage, run with IRI_COSIGN_VERIFY=false.
if [[ "${COSIGN_VERIFY}" == "true" ]] && ! command -v cosign >/dev/null 2>&1; then
  fail "cosign not found on PATH but signature verification is enabled — install cosign (see docs/deployment.md → 'Signature verification (cosign)') or set IRI_COSIGN_VERIFY=false ONLY to break glass during a Sigstore outage"
fi

PIN_FILE_CURRENT="${STATE_DIR}/current-digest-pin.yml"
PIN_FILE_PREVIOUS="${STATE_DIR}/previous-digest-pin.yml"
LAST_DEPLOYED_FILE="${STATE_DIR}/last-deployed.digests"
FAILED_FILE="${STATE_DIR}/failed.digests"
CONFIG_STAGE_DIR="${STATE_DIR}/config-stage"
CONFIG_PREVIOUS_DIR="${STATE_DIR}/config-previous"
# Per-service persisted snapshot of the monitoring config subtree each SIGHUP-only component
# (prometheus/alloy/blackbox) was last successfully reloaded for. reconcile_monitoring_reload diffs
# the on-disk subtree against this baseline on EVERY healthy tick and re-signals on drift, so a
# missed/lost/rolled-back reload self-heals instead of leaving the running process on a stale config.
MON_RELOAD_STATE_DIR="${STATE_DIR}/monitoring-reload"
# Set true when the promoted compose changes the `networks:` topology (a re-pinned
# subnet, a net added/removed); forces a clean down+up instead of an in-place `up`
# on apply AND on rollback, so the change never strands name resolution (#974).
NETWORK_TOPOLOGY_CHANGED=false
CONFIG_BLOCKED_FILE="${STATE_DIR}/config-blocked.marker"
# The live Keycloak provider JAR (mounted into the keycloak container) and the
# rollback snapshot of it taken before a provider-JAR swap.
KEYCLOAK_SPI_JAR="${COMPOSE_DIR}/keycloak/providers/keycloak-spi.jar"
KEYCLOAK_SPI_PREVIOUS_JAR="${STATE_DIR}/keycloak-spi-previous.jar"
# Backoff bookkeeping for the runtime-health targeted restart (see the
# HEALTH_RESTART_* constants): a fixed 3-field record `marker count epoch`,
# keyed to the target so a freshly promoted release clears it. Cleared on a
# successful targeted restart or a successful full deploy.
HEALTH_RESTART_FILE="${STATE_DIR}/health-restart.digests"

# --- Monitoring textfile metrics (epic #936, ADR-0072) ----------------------
# Per-outcome timestamps so the alert catalog can tell success / rollback / failure / blocked apart:
# a rollback or failure NEWER than the last success is CRITICAL (a promoted release did not ship).
# The four outcome timestamps persist across runs (each write updates only its own and preserves the
# others); config_blocked mirrors the presence of the config-blocked marker. Written to the
# node_exporter textfile dir (already inside the systemd unit's ReadWritePaths=/var/iri).
TEXTFILE_DIR="${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}"
DEPLOY_METRIC_FILE="${TEXTFILE_DIR}/deploy.prom"
# Separate textfile for the runtime-health-drift signal (a targeted restart of an
# unhealthy at-target service). Kept out of deploy.prom so it never entangles with
# the promotion-outcome timestamps — a runtime blip on the CURRENT release is not
# a deploy outcome and must not read as one (that is the 2026-07-09 lesson).
STACK_HEALTH_METRIC_FILE="${TEXTFILE_DIR}/deploy-health.prom"
START_EPOCH="$(date +%s)"

# GHCR pull token expiry (OPT-IN). A token that expires (a fine-grained PAT, which
# GitHub forces to expire) silently stops every deploy on the expiry day. There is
# no way to read a PAT's expiry from the token itself, so IF the token expires the
# operator records the date at rotation time in ${TOKEN_FILE}.expiry (an ISO-8601
# date the host `date` can parse, e.g. `2026-10-01`) and deploy.sh emits it as a
# gauge so the GhcrPullTokenExpiring alert warns ~2 weeks ahead. A token kept
# NON-expiring (a classic PAT, by deliberate choice) simply omits the file — no
# metric, no alert (docs/deployment.md → Token rotation).
TOKEN_EXPIRY_FILE="${IRI_GHCR_TOKEN_EXPIRY_FILE:-${TOKEN_FILE}.expiry}"
TOKEN_METRIC_FILE="${TEXTFILE_DIR}/ghcr-token.prom"

# Emit basetool_ghcr_token_expiry_timestamp from the operator-recorded expiry
# date. Best-effort and opt-in: absent file → no metric, and the alert does NOT
# fire on absence (a non-expiring token is a valid, un-alerted state);
# unparseable → a WARN; never fails the deploy. Called on EVERY tick, including
# the idempotence no-op, so the gauge does not go stale.
write_token_expiry_metric() {
  local raw epoch tmp
  [[ -f "${TOKEN_EXPIRY_FILE}" ]] || return 0
  raw="$(tr -d '[:space:]' < "${TOKEN_EXPIRY_FILE}" 2>/dev/null || true)"
  [[ -n "${raw}" ]] || return 0
  epoch="$(date -u -d "${raw}" +%s 2>/dev/null || true)"
  if ! [[ "${epoch}" =~ ^[0-9]+$ ]]; then
    log "WARN: could not parse GHCR token expiry '${raw}' from ${TOKEN_EXPIRY_FILE}"
    return 0
  fi
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  tmp="${TOKEN_METRIC_FILE}.$$"
  if {
    echo "# HELP basetool_ghcr_token_expiry_timestamp Unix time the GHCR pull token expires (operator-recorded in ${TOKEN_FILE}.expiry)."
    echo "# TYPE basetool_ghcr_token_expiry_timestamp gauge"
    echo "basetool_ghcr_token_expiry_timestamp ${epoch}"
  } > "${tmp}" 2>/dev/null; then
    mv -f "${tmp}" "${TOKEN_METRIC_FILE}" 2>/dev/null || true
  else
    log "WARN: could not write GHCR token expiry metric (${TEXTFILE_DIR})"
    rm -f "${tmp}" 2>/dev/null || true
  fi
}

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

# write_stack_health_metric <healthy|restart_failed>
# Maintains ${STACK_HEALTH_METRIC_FILE} with two gauges that drive the runtime
# DeployHealthRestartFailing alert WITHOUT overloading the promotion-outcome
# metrics: `healthy` stamps basetool_deploy_last_stack_healthy_timestamp (a
# freshness heartbeat written on every healthy tick), `restart_failed` stamps
# basetool_deploy_last_health_restart_failed_timestamp. The alert fires while the
# failed stamp is newer than the healthy one and self-clears on the next healthy
# tick. Each write preserves the other gauge. Best-effort; never gates a deploy.
write_stack_health_metric() {
  local outcome="$1" now f tmp prev_healthy prev_failed
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
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  tmp="${f}.$$"
  if {
    echo "# HELP basetool_deploy_last_stack_healthy_timestamp Unix time deploy.sh last observed the running app stack at target and healthy."
    echo "# TYPE basetool_deploy_last_stack_healthy_timestamp gauge"
    echo "basetool_deploy_last_stack_healthy_timestamp ${prev_healthy}"
    echo "# HELP basetool_deploy_last_health_restart_failed_timestamp Unix time a targeted restart of an unhealthy at-target service last failed to restore health."
    echo "# TYPE basetool_deploy_last_health_restart_failed_timestamp gauge"
    echo "basetool_deploy_last_health_restart_failed_timestamp ${prev_failed}"
  } > "${tmp}" 2>/dev/null; then
    mv -f "${tmp}" "${f}" 2>/dev/null || true
  else
    log "WARN: could not write stack-health textfile metric (${TEXTFILE_DIR})"
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

# Refresh the token-expiry gauge on every tick (incl. the no-op below), so the
# GhcrPullTokenExpiring alert can warn ahead of a lapse. Best-effort, never gates.
write_token_expiry_metric

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
  local entry svc image digest cids cid probe state img_id repo_digests img_ok
  for entry in \
    "backend|${BACKEND_IMAGE}|${BACKEND_DIGEST}" \
    "frontend|${FRONTEND_IMAGE}|${FRONTEND_DIGEST}" \
    "ingest|${INGEST_IMAGE}|${INGEST_DIGEST}"; do
    IFS='|' read -r svc image digest <<< "${entry}"
    cids="$(docker compose -f "${COMPOSE_DIR}/docker-compose.yml" \
              --profile "${PROFILE}" ps -aq "${svc}" 2>/dev/null)" || cids=""
    if [[ -z "${cids}" ]]; then
      # A wholly missing service is a STRUCTURAL divergence (half-down stack),
      # never a runtime-health blip: an `up` must (re)create the container.
      echo "structural ${svc}: no container"
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
      # Whether this container runs the TARGET image is computed for every state
      # (not only the running-healthy ones) so an unhealthy container that IS on
      # the target image can be told apart from one on a wrong image. The former
      # is a runtime-health fault (targeted restart); the latter, like a missing
      # container, is a structural mismatch the apply/rollback path must correct.
      img_id="$(docker inspect --format '{{.Image}}' "${cid}" 2>/dev/null)" || img_id=""
      repo_digests=""
      if [[ -n "${img_id}" ]]; then
        repo_digests="$(docker image inspect \
          --format '{{join .RepoDigests " "}}' "${img_id}" 2>/dev/null)" || repo_digests=""
      fi
      img_ok=false
      case " ${repo_digests} " in
        *" ${image}@${digest} "*) img_ok=true ;;
      esac
      case "${state}" in
        # running/starting (healthcheck still inside its start period, e.g. the
        # restart policies bringing the stack up after a host reboot while a tick
        # fires) counts as converged for THIS tick: `up --wait` would simply wait
        # on it, and re-applying could record a false backoff failure for a good
        # target. Only a wrong image still drifts here (structural).
        running/healthy | running/no-healthcheck | running/starting)
          if [[ "${img_ok}" != "true" ]]; then
            echo "structural ${svc}: running image [${repo_digests:-unknown}] does not match target ${digest}"
          fi
          ;;
        *)
          # Not running-healthy (unhealthy / restarting / exited / created / …).
          # On the target image → runtime-health drift (right release, sick
          # container) — a targeted restart, never a release rollback. On a wrong
          # or unknown image → structural (a re-apply must correct the release
          # before health can even be judged).
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
    # A converged no-op. Normally the fast exit; under --check-only we fall
    # through so the signature preflight below still runs (that is the point of
    # a dry-run signature check even when nothing needs applying).
    if [[ "${CHECK_ONLY}" != "true" ]]; then
      log "no change — already at target digests (running stack verified)"
      # Freshness heartbeat for the runtime-health signal: a healthy tick pushes
      # the last-stack-healthy timestamp past any earlier health-restart failure,
      # so the DeployHealthRestartFailing alert self-clears the moment recovery
      # is observed. Written on the hot no-op path so the gauge never goes stale.
      write_stack_health_metric healthy
      # Even on a converged no-op, reconcile the SIGHUP-only monitoring components against on-disk:
      # a config reload a prior tick failed to land (or a rollback bypassed) would otherwise linger
      # on a quiet host until the next real deploy — the 2026-07-11 ingest TargetDown. Cheap and
      # non-gating: a per-service content diff, a SIGHUP only on actual drift.
      reconcile_monitoring_reloads
      exit 0
    fi
    NOOP=true
  else
    # Log every divergence (class token stripped for the human line), then split
    # the report. A `structural` divergence (missing container / wrong image) is a
    # real release mismatch the full apply/rollback path must reconcile. A report
    # with ONLY `health` divergences means the deployed RELEASE is correct and
    # only the runtime is sick (right image, unhealthy container): that takes the
    # targeted-restart branch below — rolling the stack back to the same image
    # cannot fix a runtime fault and would fire a false DeployRolledBack.
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
  # Signature preflight (REQ-OPS-015): verify every resolved digest against the
  # release-images identity and report per artifact. Exits non-zero on any
  # failure WITHOUT writing a deploy metric — a dry-run must not trip DeployFailed.
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

# --- Runtime-health drift: targeted restart, NOT a release rollback ----------
# The running stack is at the target release (right image) but one or more app
# containers are unhealthy — a RUNTIME fault (e.g. the 2026-07-09 native-thread
# exhaustion), not a wrong release. Rolling the stack back to the same image
# cannot fix that and would fire a false DeployRolledBack, so restart ONLY the
# affected service(s) (`--no-deps --force-recreate`), bounded by a short backoff
# so a container that will not recover is not force-recreated every tick. A
# persistent failure is recorded as a distinct health-restart signal, never a
# deploy `rollback`, so the promotion-outcome alerts stay truthful. This path
# deliberately does NOT re-pull or re-verify signatures: the image is already
# present and running (it is the verified target), so there is no new supply-chain
# surface — only the local container is recreated.
if [[ "${HEALTH_DRIFT}" == "true" ]]; then
  UNHEALTHY_SVCS="$(grep '^health ' <<< "${DRIFT_REPORT}" \
    | sed -E 's/^health ([a-z]+):.*/\1/' | sort -u | tr '\n' ' ')"
  UNHEALTHY_SVCS="${UNHEALTHY_SVCS% }"

  if [[ -f "${HEALTH_RESTART_FILE}" ]]; then
    read -r HR_MARKER HR_COUNT HR_EPOCH _ < "${HEALTH_RESTART_FILE}" || true
    if [[ "${HR_MARKER:-}" != "${EXPECTED_MARKER}" ]] \
       || ! [[ "${HR_COUNT:-}" =~ ^[0-9]+$ ]] || ! [[ "${HR_EPOCH:-}" =~ ^[0-9]+$ ]]; then
      # Record belongs to a superseded target or is corrupt — drop it and restart.
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
  HR_COMPOSE_ARGS=(-f docker-compose.yml)
  if [[ -f "${PIN_FILE_CURRENT}" ]]; then
    HR_COMPOSE_ARGS+=(-f "${PIN_FILE_CURRENT}")
  fi
  # shellcheck disable=SC2086 # UNHEALTHY_SVCS is a deliberate space-split service list
  if docker compose "${HR_COMPOSE_ARGS[@]}" --profile "${PROFILE}" \
       up -d --no-deps --force-recreate --no-build \
          --wait --wait-timeout "${HEALTH_TIMEOUT}" ${UNHEALTHY_SVCS}; then
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

# --- Verify supply-chain signatures (host-side gate, REQ-OPS-015) -----------
# We are now committed to applying (past the idempotence no-op, the --check-only
# dry-run and the bad-digest backoff), so cosign-verify every resolved digest
# against the release-images keyless signature BEFORE the first pull / extract /
# up. A :stable tag moved out-of-band to an unsigned or differently-signed
# digest is rejected here — it is never pulled, the config bundle and provider
# JAR are never extracted onto the host, and the stack is never recreated on it.
# The config + keycloak-spi digests are verified only when resolved (best-effort
# absence leaves them empty, nothing to verify or stage that tick).
log "verifying image signatures (cosign keyless)"
verify_digest_or_die "backend"  "${BACKEND_IMAGE}@${BACKEND_DIGEST}"
verify_digest_or_die "frontend" "${FRONTEND_IMAGE}@${FRONTEND_DIGEST}"
verify_digest_or_die "ingest"   "${INGEST_IMAGE}@${INGEST_DIGEST}"
[[ -n "${CONFIG_DIGEST}" ]]       && verify_digest_or_die "config"       "${CONFIG_IMAGE}@${CONFIG_DIGEST}"
[[ -n "${KEYCLOAK_SPI_DIGEST}" ]] && verify_digest_or_die "keycloak-spi" "${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST}"

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
  if [[ "${OLD_INFRA}" != "${NEW_INFRA}" ]]; then
    if [[ "${FORCE}" != "true" ]]; then
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
    # --force through a gated stateful-infra change. Deliberately do NOT clear the
    # block marker here (pre-apply): if this forced apply fails its health gate and
    # rolls back, the gate must stay in place so the next automatic (non-force) tick
    # QUIETLY skips (marker still matches EXPECTED_MARKER) instead of re-firing the
    # CARVE-OUT alert + blocked metric as if it were the first encounter. The marker
    # is cleared only on a SUCCESSFUL apply (the success block below).
    log "stateful-infra upgrade forced (--force) — applying the gated change"
  else
    # No stateful-infra change for this target: clear any stale block marker (e.g.
    # left by a previous, now-superseded gated target) so a lingering marker cannot
    # keep the basetool_deploy_config_blocked metric stuck at 1.
    rm -f "${CONFIG_BLOCKED_FILE}"
  fi

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
  rm -f "${FAILED_FILE}" "${CONFIG_BLOCKED_FILE}" "${HEALTH_RESTART_FILE}"
  log "deploy successful"
  write_deploy_metric success
  # A fresh successful deploy is by definition a healthy stack — refresh the
  # runtime-health heartbeat so any prior health-restart-failed signal clears.
  write_stack_health_metric healthy

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
      # `up -d` recreates a service only when its DEFINITION changes; a bind-mounted config-file
      # edit needs an explicit SIGHUP. Reconcile the LOADED config against on-disk — self-healing:
      # re-signals on ANY drift from the last reloaded snapshot, not just this tick's bundle swap.
      reconcile_monitoring_reloads
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
# `failed` and journalctl flags it. Active alerting is via the textfile metric
# written just below, not a systemd OnFailure= hook: a rollback newer than the
# last success trips the DeployRolledBack Prometheus alert (a failure trips
# DeployFailed) — the "promoted release did not ship" signal (epic #936 alert
# catalog, monitoring/prometheus/alerts/ops-automation.yml).
write_deploy_metric rollback
exit 1
