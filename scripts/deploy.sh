#!/bin/bash
# =============================================================================
# Profit Basetool — Server-side deploy script
#
# Pulls the production backend + frontend + ingest images, the host config bundle
# (basetool-config: the Quadlet units, the compose files they are generated from,
# the edge and monitoring configuration, the maintenance page and the Keycloak
# theme) AND the Keycloak provider-JAR bundle (basetool-keycloak-spi:
# keycloak-spi.jar) from GHCR (the version tag defaults to `:stable`, which is
# moved atomically by the `promote.yml` GitHub Actions workflow), resolves them to
# immutable digests, applies them through the service user's systemd (a unit
# start waits for health, because Notify=healthy), and rolls back to the previous
# digest set, the previous config tree AND the previous provider JAR if a unit
# does not come up healthy.
#
# The config bundle travels the SAME pull-only, digest-pinned, deliberately
# promoted GHCR channel as the app images (see docs/adr/0049-*), so a promoted
# unit change — e.g. a bumped redis/edge image pin — reaches the host and is
# applied automatically, with no hand-run `systemctl --user restart`. A postgres/Keycloak image change is the one carve-out:
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
#   /var/lib/iri/current-digest-pin.yml    the record of the live backend/
#                                          frontend/ingest image digests; the
#                                          `.container.d/10-digest-pin.conf`
#                                          drop-ins bind them, so a tag flip in
#                                          GHCR does NOT silently move the
#                                          running stack underneath us.
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
#   /var/lib/iri/config-apply.incomplete   present while the live config tree is
#                                          not known to be one release: written
#                                          before a config apply, removed when
#                                          it (or the restore after a failed
#                                          one) completed. While it exists,
#                                          config-previous/ is not re-snapshotted.
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

# The applied config tree must be readable by the SERVICE user, so the mode it lands with cannot be
# the caller's business. Rocky's hardened baseline sets `UMASK 027` in /etc/login.defs and
# /etc/profile, while a systemd service gets 0022 — so the same deploy produces a different tree
# depending on how it was started:
#
#   systemctl start iri-deploy.service   umask 0022 -> /var/iri/code/... 0755  works
#   sudo -u deploy deploy.sh             umask 0027 -> /var/iri/code/... 0750  every container dies
#
# and the second is the invocation this script's own usage block documents. Under Docker it never
# mattered: the root daemon resolved the bind mounts. Rootless Podman resolves them AS the service
# user, which is not in group `deploy`, so it cannot traverse a 0750 directory — measured on the
# testing host 2026-09-20, where keycloak and edge both exited 125 with
#
#   Error: statfs /var/iri/code/keycloak-theme/krt-theme: permission denied
#
# on a path that plainly exists. Worse than the cosign asymmetry, because it does not fail at apply
# time: the deploy reports the config applied and the failure surfaces later, as a health-check
# timeout and a rollback that fails the same way.
#
# 022 and not something tighter: this tree is the config BUNDLE, which carries no secret by
# construction — the bundle Dockerfile COPY is an allowlist, .dockerignore bars them from the context,
# release-images.yml asserts the built bundle carries none, and assert_no_secrets below re-asserts
# it on the host. The things that ARE secret set their own mode explicitly and are unaffected:
# ${DOCKER_CONFIG} is `install -d -m 0700`, and render-env-d.py writes env.d at 0640 into a
# setgid 2750 directory the role owns.
umask 022

# --- The container-runtime seam (ADR-0163, Phase 3) -------------------------
# Every runtime operation below goes through `rt_*` rather than naming a CLI;
# lib/container-runtime.sh is where rootless Podman is spoken. lib/common.sh
# carries log, fail, read_env and the atomic textfile write.
#
# Sourced by path relative to THIS script, so a host that has scripts/ has the
# libraries too. `rt_detect` runs in the pre-flight below, not here, so a usage
# error still reports before anything touches a registry.
IRI_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=lib/common.sh
# shellcheck disable=SC1091
# repo-lint.yml runs `shellcheck <files>` without -x, so it cannot follow a
# sourced file and reports SC1091 at info level, which fails the job. The
# libraries are linted on their own by the same sweep, so nothing goes unchecked.
. "${IRI_SCRIPT_DIR}/lib/common.sh"
# shellcheck source=lib/container-runtime.sh
# shellcheck disable=SC1091
. "${IRI_SCRIPT_DIR}/lib/container-runtime.sh"

# Out of the caller's working directory, before anything can reach the container runtime -- and
# AFTER IRI_SCRIPT_DIR above, which may have been derived from a relative $0. `sudo -u <service
# user>` keeps the caller's cwd, and the service user cannot traverse root's home or the deploy
# account's, so on a rootless host started by hand from /root every RT_CLI call -- rt_detect's
# `sudo -n -u iri podman ps` first of all -- dies with "cannot chdir to /root: Permission denied".
# rt_detect then concludes that no lingering user owns the containers and aborts, with an error that
# names neither the directory nor the cause. The timer was never affected (a systemd service starts
# in /), which is exactly why the hand-started path went unnoticed. container-cleanup.sh has done
# the same since the Podman preparation; deploy.sh did not until 2026-09-22. Every path this script
# uses from here on is absolute, and the apply below changes into COMPOSE_DIR itself.
cd /

# --- Defaults / paths -------------------------------------------------------
COMPOSE_DIR="${IRI_COMPOSE_DIR:-/var/iri/code}"
STATE_DIR="${IRI_STATE_DIR:-/var/lib/iri}"
LOCKFILE="${IRI_LOCKFILE:-/var/lock/iri-deploy.lock}"
TOKEN_FILE="${IRI_GHCR_TOKEN_FILE:-/etc/iri/ghcr-pull-token}"
HEALTH_TIMEOUT="${IRI_HEALTH_TIMEOUT:-180}"

# IRI_MONITORING_ENABLED gates reconcile_monitoring_reloads. It is read from the compose `.env` when
# the environment does not already carry it — because that is where an operator naturally puts it,
# next to every other IRI_* setting, and where putting it has NO effect otherwise: this script runs
# from `iri-deploy.service`, which deliberately declares no `EnvironmentFile=`, so a value set in
# `.env` is invisible to it. That mismatch produced a silent, expensive failure twice: the flag
# reads `true` on disk, the deploy logs "IRI_MONITORING_ENABLED != 'true'" every tick, and monitoring
# config changes are rsynced to disk but never reloaded into the running Prometheus — so fixed alert
# rules keep firing their old version (2026-07-13, and again 2026-08-03).
#
# An explicit environment value still wins, so a systemd `Environment=` drop-in keeps working and
# overrides the file. Only THIS key is read: sourcing `.env` would pull every production secret into
# this process's environment for no reason. The value is stripped of quotes and whitespace so
# `IRI_MONITORING_ENABLED="true"` behaves like the bare form (read_env, lib/common.sh).
if [[ -z "${IRI_MONITORING_ENABLED:-}" && -r "${COMPOSE_DIR}/.env" ]]; then
  IRI_MONITORING_ENABLED="$(read_env IRI_MONITORING_ENABLED)"
  export IRI_MONITORING_ENABLED
fi

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
# and the next timer tick would pull and run the unverified image as the service
# user. cosign reads the registry credential from $DOCKER_CONFIG/config.json,
# written by the login below; keyless verify additionally reaches the Sigstore
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
# suffix is pinned to `heads/main` (main-branch :edge/:sha builds) or a
# `tags/vMAJOR.MINOR.PATCH` release tag — NOT the broad `refs/.+`, so an image
# built by a workflow_dispatch run off an arbitrary feature branch is not trusted
# for prod.
#
# ANCHORED at both ends (`^…$`). cosign matches the regexp against the whole
# certificate SAN with Go's `regexp.MatchString`, which finds a match ANYWHERE in
# the string: the unanchored form this replaced also accepted
# `@refs/heads/main-x`, `@refs/heads/maintenance` and `@refs/tags/vfoo`. Every
# copy of this regexp (promote.yml, promote-testing.yml, release-images.yml's
# reuse gate, this line) is asserted identical and anchored by
# scripts/check-cosign-identity.py in repo-lint, and scripts/deploy.test.sh runs
# this default against accepted and refused subjects.
COSIGN_REPO="${IRI_COSIGN_REPO:-krt-profit/basetool}"
COSIGN_IDENTITY_REGEXP="${IRI_COSIGN_IDENTITY_REGEXP:-^https://github\\.com/${COSIGN_REPO}/\\.github/workflows/release-images\\.yml@refs/(heads/main|tags/v[0-9]+\\.[0-9]+\\.[0-9]+)$}"
COSIGN_OIDC_ISSUER="${IRI_COSIGN_OIDC_ISSUER:-https://token.actions.githubusercontent.com}"
# Verification is a network round-trip — it fetches the signature layer from GHCR
# and reaches the Sigstore roots — so it fails transiently for exactly the reasons
# release-images.yml already wraps `cosign sign` in a 5-attempt retry for. Without
# a retry here, a one-second registry blip is indistinguishable from a :stable tag
# moved to an untrusted digest and aborts the tick as a critical SECURITY alarm.
# That is the 2026-08-05 DeployFailed page: the identical digest verified cleanly
# by hand minutes later, and the very same tick also failed to resolve
# keycloak-spi:stable — one GHCR hiccup, two symptoms. Retrying costs at most
# DELAY+2*DELAY seconds on a genuinely bad signature, which still aborts.
COSIGN_VERIFY_ATTEMPTS="${IRI_COSIGN_VERIFY_ATTEMPTS:-3}"
COSIGN_VERIFY_DELAY="${IRI_COSIGN_VERIFY_DELAY:-5}"
# Where to look for cosign when it is not on PATH. The first entry is where the basetool_host role
# installs it (`basetool_host_cosign_path`); the others are where an operator would put it by hand.
# A variable rather than a literal only so the self-test can point the search at a fixture — on a
# host it is always these three, the same reasoning as container-runtime.sh's RT_LINGER_DIR.
COSIGN_SEARCH_PATH="${IRI_COSIGN_SEARCH_PATH:-/usr/local/bin:/usr/bin:/opt/cosign/bin}"
# stderr of the last failed `cosign verify`, so the abort can say *why* it failed
# instead of only that it did. Declared here because `set -u` is in force.
VERIFY_LAST_ERROR=""

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

# --- Helpers ----------------------------------------------------------------
# log and fail are lib/common.sh's.
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
#
# A failure RETURNS the tool's exit code after naming the path, and does not swallow it. Called as a
# plain command, the non-zero return is what errexit acts on, and the pre-gate guard
# (on_pre_gate_exit) turns that exit into a recorded failure. Until 2026-09-25 the rsync error was
# the last thing a tick printed: errexit ended the script inside this function, before any FATAL
# line, metric or backoff record — see on_pre_gate_exit for that incident.
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

# Extract the promoted config bundle (/config inside the scratch basetool-config
# image) into a staging dir. The image has no entrypoint/command, so `podman
# create` needs a placeholder argument; the container is never started —
# `podman cp` reads straight from its filesystem layer.
extract_config_bundle() {
  local ref="$1" dest="$2"
  rm -rf "${dest}"
  install -d -m 0755 "${dest}"
  rt_extract_from_image "${ref}" "/config/." "${dest}/" /bundle \
    || fail "cannot extract /config from config image ${ref}"
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

# Emit the postgres + Keycloak image pins of a config tree, normalised and
# sorted. These are the stateful/choreographed images whose change must be
# operator-gated (PGDATA major migration; Keycloak provider+keystore dance);
# everything else (redis, the edge) is safe to auto-apply.
infra_image_pins() {
  # `|| true`: a tree with no match makes grep exit 1, which under `set -o pipefail` would abort the
  # surrounding command substitution. An empty result is the correct "no stateful-infra pins seen"
  # answer here.
  #
  # The DIGEST is deliberately stripped, so what is compared is the TAG — the version. The carve-out
  # exists for a stateful, choreographed upgrade (a PGDATA major migration, the Keycloak
  # provider+keystore dance), and those come with a tag change. A same-tag digest refresh is a
  # rebuilt base image, usually a security fix, and blocking it achieves the opposite of what this
  # gate is for. Comparing the full reference did exactly that on the testing host: Keycloak 26.7 was
  # rebuilt, and the deploy refused to apply anything for SEVEN DAYS (2026-09-05 to 2026-09-12).
  #
  # $1 is a TREE, and the pins are read from its Quadlet units -- `Image=` lines in db-backend,
  # db-keycloak and keycloak.container. Until 2026-09-22 this read the compose file as well and
  # matched the units with `^Image=postgres:`, which the generator never writes: it qualifies every
  # image (`Image=docker.io/postgres:18-alpine@…`). The postgres half of the gate therefore saw only
  # the compose file's copy; with the compose file no longer read, the pattern accepts the qualified
  # name and the output drops the registry, so a pin reads the same however it is spelled.
  local tree="$1"
  {
    [[ -d "${tree}/quadlet/systemd" ]] \
      && grep -Eho '^Image=((docker\.io/)?(library/)?postgres:[^[:space:]]+|quay\.io/keycloak/keycloak:[^[:space:]]+)' \
           "${tree}"/quadlet/systemd/*.container 2>/dev/null
  } | sed -E 's/^Image=//; s#^docker\.io/(library/)?##; s/@sha256:[0-9a-f]+$//' | sort -u || true
}

# Snapshot the live config tree (the allowlisted paths) into a directory so the
# rollback path can restore the exact units the previous digest pin expects.
snapshot_config_tree() {
  local dst="$1"
  rm -rf "${dst}"
  install -d -m 0755 "${dst}"
  [[ -f "${COMPOSE_DIR}/docker-compose.yml" ]] \
    && cp -a "${COMPOSE_DIR}/docker-compose.yml" "${dst}/docker-compose.yml"
  # Every docker/ subtree apply_config_tree mirrors, not only the maintenance page. The edge and acme
  # trees were added to the mirror list (2026-09-12, 2026-09-16) and not to this one, so a rollback
  # left the failed release's edge configuration and ACME loop in place — and a restore after an
  # apply that died halfway through could not have put them back at all.
  local sub
  for sub in maintenance edge acme; do
    if [[ -d "${COMPOSE_DIR}/docker/${sub}" ]]; then
      install -d "${dst}/docker"
      cp -a "${COMPOSE_DIR}/docker/${sub}" "${dst}/docker/${sub}"
    fi
  done
  [[ -d "${COMPOSE_DIR}/keycloak-theme" ]] \
    && cp -a "${COMPOSE_DIR}/keycloak-theme" "${dst}/keycloak-theme"
  # Monitoring compose + config tree (epic #936). Carries no secrets; snapshotted so a rollback
  # restores the exact monitoring config the previous release shipped.
  [[ -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" ]] \
    && cp -a "${COMPOSE_DIR}/docker-compose.monitoring.yml" "${dst}/docker-compose.monitoring.yml"
  [[ -d "${COMPOSE_DIR}/monitoring" ]] \
    && cp -a "${COMPOSE_DIR}/monitoring" "${dst}/monitoring"
  # The Quadlet deployment. Without this a rollback would restore the previous digest pin while
  # leaving the NEW units in place -- and the units are what the pin binds to, so the rollback
  # would put the old digests on the new definitions.
  [[ -d "${COMPOSE_DIR}/quadlet" ]] \
    && cp -a "${COMPOSE_DIR}/quadlet" "${dst}/quadlet"
  return 0
}

# Copy the allowlisted config paths from a source tree onto the host. The compose
# files -- still the source the units are generated from, and carried for the
# record -- are replaced atomically (write a temp, then rename → new inode); the
# asset trees are mirrored within their own subtree only.
#
# Must be called as a PLAIN command (or inside a `( set -e; … )` subshell that is itself a plain
# command). Under `if`, `!`, `&&` or `||` bash ignores errexit for the whole function body, so a
# failed mirror in the middle would be skipped over and the function would report the status of its
# last line only.
#
# The compose file is optional on the source side because the source is also a SNAPSHOT
# (restore_previous_config_tree), and a Quadlet host may never have had one. A staged bundle always
# carries it: the config block's require_file refuses one that does not.
apply_config_tree() {
  local src="$1" dst="$2"
  if [[ -f "${src}/docker-compose.yml" ]]; then
    install -m 0644 "${src}/docker-compose.yml" "${dst}/.docker-compose.yml.tmp"
    mv -f "${dst}/.docker-compose.yml.tmp" "${dst}/docker-compose.yml"
  fi
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
  # The edge proxy's configuration (ADR-0162). This list is EXPLICIT, not a
  # wildcard over the bundle, so a directory added to docker/config/Dockerfile
  # reaches the staging area and stops there — silently. That is exactly what
  # happened on 2026-09-12: the bundle carried docker/edge, the stage held a
  # correct copy, the host never got one, and the edge mounted an empty directory
  # and died with
  #   [emerg] open() "/etc/nginx/edge/nginx.conf" failed (2: No such file or directory)
  # through four applies, each of which rolled back cleanly and said nothing about
  # a missing mirror. Anything added to the bundle has to be added HERE too.
  if [[ -d "${src}/docker/edge" ]]; then
    install -d "${dst}/docker"
    mirror_dir "${src}/docker/edge" "${dst}/docker/edge"
  fi
  # The ACME renewal loop — the warning above, collected. docker/acme/publish-loop.sh moved out of
  # the compose file on 2026-09-16 and neither the bundle's COPY allowlist nor this list gained an
  # entry, so for six days a release shipped a compose service and a Quadlet unit that both mount a
  # directory nothing delivers. A host that already had the container never noticed; the Podman
  # cutover host, which did not, refused to start it at all and renewed no certificates.
  if [[ -d "${src}/docker/acme" ]]; then
    install -d "${dst}/docker"
    mirror_dir "${src}/docker/acme" "${dst}/docker/acme"
  fi
  if [[ -d "${src}/keycloak-theme" ]]; then
    mirror_dir "${src}/keycloak-theme" "${dst}/keycloak-theme"
  fi
  # The Quadlet units and their environment templates. Mirrored onto the host like any other
  # bundle payload; install_quadlet_units() below is what moves them from here into the unit
  # directory.
  if [[ -d "${src}/quadlet" ]]; then
    mirror_dir "${src}/quadlet" "${dst}/quadlet"
  fi
}

# ---------------------------------------------------------------------------
# install_quadlet_units
#
# Put the release's unit files where Quadlet reads them, and render the
# environment files they name.
#
# WHY THIS EXISTS AT ALL. Until 2026-09-18 nothing in this repository put a unit
# file on a host. The bundle did not carry them, the Ansible role states that it
# never ships them, and this script only checked that the directory was not empty
# and failed with "has the stack been installed?" if it was. The 39 units on the
# testing host had arrived by an operator action nobody wrote down. A release
# could change an image, a health command, a memory limit or an environment
# variable and have no path to production at all.
#
# THE THREE THINGS IT HAS TO GET RIGHT:
#
#   1. The drop-ins survive. `<unit>.container.d/10-digest-pin.conf` is written by
#      THIS script and is what binds the release's digest; a host-specific drop-in
#      may sit beside it at a higher number. A mirror with --delete over the unit
#      directory would remove both, so the sync is file-by-file over the bundle's
#      own names and the `.container.d` directories are never touched.
#   2. A retired unit is STOPPED before it is removed. Quadlet only generates a
#      service for a unit file that exists, so deleting the file makes systemd
#      forget the unit -- while its container keeps running, unmanaged and
#      invisible to every later reconcile.
#   3. env.d is rendered from the HOST's .env. The templates carry `KEY=${VAR:?}`
#      and no values; the secrets are on the host and never leave it. Rendering
#      before daemon-reload matters: a unit whose EnvironmentFile= does not exist
#      fails to start, and that failure reads as an application fault.
# ---------------------------------------------------------------------------
install_quadlet_units() {
  local src="${1}/quadlet" installed=0 removed=0

  if [[ ! -d "${src}/systemd" ]]; then
    # An older bundle, built before the units rode this channel. Not fatal: the
    # host keeps the units it has, which is what it has always done.
    log "config bundle carries no quadlet/systemd — units left as they are (pre-2026-09-18 bundle)"
    return 0
  fi

  local unit_dir="${RT_UNIT_DIR:?RT_UNIT_DIR is unset}"
  install -d "${unit_dir}"

  # Render the environment files the units name, BEFORE anything reloads.
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

  # Install every unit the bundle names.
  DEPLOY_STEP="install quadlet units into ${unit_dir}"
  local f base
  for f in "${src}"/systemd/*; do
    [[ -f "${f}" ]] || continue
    base="$(basename "${f}")"
    if [[ ! -f "${unit_dir}/${base}" ]] || ! cmp -s "${f}" "${unit_dir}/${base}"; then
      install -m 0644 "${f}" "${unit_dir}/${base}"
      installed=$(( installed + 1 ))
      # A replaced .container is a changed DEFINITION, exactly like a changed digest pin, and the
      # apply has to restart it rather than start it -- `systemctl start` on an active unit is a
      # no-op and would leave the old container running the old definition.
      [[ "${base}" == *.container ]] && rt_note_changed "${base%.container}"
    fi
  done

  # Retire what the bundle no longer names. Only files that LOOK like units are
  # considered, so a `.container.d` directory and anything an operator left beside
  # them are out of scope by construction rather than by a rule that can drift.
  local existing name
  for existing in "${unit_dir}"/*.container "${unit_dir}"/*.network "${unit_dir}"/*.volume; do
    [[ -f "${existing}" ]] || continue
    name="$(basename "${existing}")"
    [[ -f "${src}/systemd/${name}" ]] && continue
    if [[ "${name}" == *.container ]]; then
      # Stop it while systemd still knows about it. Best effort: a unit that was
      # never started is not an error, and a stop that fails must not block the
      # release -- it is reported and the file still goes.
      rt_service_stop "${name%.container}" >/dev/null 2>&1         || log "WARN: could not stop retired unit ${name%.container} before removing it"
    fi
    rm -f "${existing}"
    removed=$(( removed + 1 ))
  done

  log "quadlet units: ${installed} installed/updated, ${removed} retired (${unit_dir})"
  return 0
}

# ---------------------------------------------------------------------------
# assert_config_tree_writable <staged-tree>
#
# Refuse BEFORE anything is changed when the deploy account cannot write where the apply is about to
# write. The 2026-09-25 incident, v1.11.0: /var/iri/code/docker/acme was root-owned, and every tick
# from 12:25 to 12:35 mirrored monitoring/, the maintenance page and the edge configuration onto the
# host and then died inside the acme mirror with
#
#     rsync: [receiver] mkstemp "/var/iri/code/docker/acme/.publish-loop.sh.XXXX" failed: Permission denied (13)
#
# leaving a tree that was half the new release and half the old one. Checked here, the same host
# produces one line naming the directory and changes nothing.
#
# What is checked is what `rsync -rlpt --delete` actually needs: every DIRECTORY in a subtree it
# mirrors must be writable (it creates a temp file beside each target and renames it) and owned by
# this account (it sets the directory's mode and mtime, which only the owner may). A FILE owned by
# someone else is not a problem — rsync replaces it by rename rather than writing into it — so files
# are deliberately not checked; demanding more would refuse hosts that deploy fine. Beside the
# subtrees: the compose directory itself (the compose files are replaced through a temp file there),
# the unit directory, and env.d when it exists. A subtree that does not exist yet needs its nearest
# existing parent writable, because that is where rsync creates it.
#
# Only subtrees the staged bundle carries are checked: one the bundle does not carry is not mirrored.
# ---------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------
# restore_previous_config_tree
#
# Put config-previous/ back onto the host, and the previous units with it. Shared by the pre-gate
# abort (on_pre_gate_exit) and the health-gate rollback.
#
# Returns 0 when the previous tree and its units are back, 1 when the restore itself failed — the
# live tree is then a mix of two releases — and 2 when there is no previous DEFINITION to restore
# to: the first bundle on a host, whose snapshot holds no quadlet/systemd. Installing units from the
# live tree in that case would install the very release that just failed.
#
# The restore runs in a subshell with errexit ON, as a plain command between `set +e` and the
# caller's own setting. That is the only shape in which a failing step stops the restore: in an `if`
# or behind `||` bash ignores errexit inside the subshell, and a failed mirror would be skipped over
# with the restore reporting success. The price is that the subshell's rt_note_changed calls do not
# reach this shell. That loses nothing: the forward apply already noted every unit the restore
# changes back, and a unit it retired is stopped, which the following start covers.
# ---------------------------------------------------------------------------
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

# Idempotently reconcile ONE monitoring service's LOADED config against the on-disk config tree.
# `up -d` recreates a container only when its DEFINITION changes, and the config files are bind
# mounts, so a config-CONTENT edit alone leaves the running process on the old config. A SIGHUP does
# NOT fix it either: the units mount prometheus.yml (and the blackbox config) as SINGLE FILES, and `deploy.sh` writes the new config via mirror_dir/rsync, which
# replaces the file by temp+rename → a NEW INODE. A single-file bind mount is pinned to the inode it
# was created with, so the container keeps reading the OLD inode until it is RECREATED — a SIGHUP
# would merely re-read that stale inode (the trap behind the 2026-07-11 ingest TargetDown: the host
# prometheus.yml was already the new 11272 config, but the running Prometheus still saw the retired
# 11262 target through its pinned inode). So the reconcile FORCE-RECREATES the service, re-resolving
# the mount to the current host inode and loading the new config on startup (a brief scrape gap, only
# when the config actually changed — config edits are rare). The OLD logic acted only on the tick
# that swapped a bundle in AND reached the success block, so a recreate skipped, lost, or bypassed by
# a rollback was never retried. The baseline is now a PERSISTED per-service snapshot of the config
# subtree the process was last recreated for (${MON_RELOAD_STATE_DIR}/<svc>), refreshed ONLY after a
# successful recreate, and reconciled on EVERY healthy tick: a drift (content differs, or the snapshot
# is absent = never applied) recreates and re-snapshots; a failed recreate leaves the snapshot stale
# so the NEXT tick retries — the self-healing property. A genuinely stale running config (a recreate
# that never landed) still trips PrometheusConfigStale (meta.yml). `diff -rq` compares CONTENT, so
# rsync size/mtime quick-check quirks cannot mask a change. Best-effort: a stopped service or a failed
# recreate only logs and never gates the deploy.
reconcile_monitoring_reload() {
  local svc="$1" subpath="$2" src snap
  src="${COMPOSE_DIR}/monitoring/${subpath}"
  snap="${MON_RELOAD_STATE_DIR}/${svc}"
  # Nothing on disk for this service (a stripped-down monitoring tree) → nothing to reconcile.
  [[ -d "${src}" ]] || return 0
  if diff -rq "${snap}" "${src}" >/dev/null 2>&1; then
    return 0
  fi
  log "  monitoring: ${subpath} config differs from the last applied snapshot → recreating ${svc} (re-resolves the bind-mount inode)"
  if rt_monitoring_recreate "${svc}" >/dev/null 2>&1; then
    # Refresh the baseline ONLY on a successful recreate, so a failed one re-drifts next tick.
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

# Emit basetool_monitoring_config_applied_timestamp{component="prometheus"} = the Unix time deploy.sh
# last recreated Prometheus for a config change (the .applied stamp reconcile_monitoring_reload writes
# on a successful recreate). Paired in PrometheusConfigStale (meta.yml) with Prometheus's own
# prometheus_config_last_reload_success_timestamp_seconds: if this applied stamp stays NEWER than the
# last successful reload, the recreate was missed/lost and the running Prometheus is serving a stale
# config — the gap PrometheusConfigReloadFailed (== 0, only ATTEMPTED-and-FAILED reloads) cannot see.
# Written into the node_exporter textfile dir alongside deploy.prom. Best-effort; an absent stamp
# yields no series, so a host that never recreated Prometheus cannot false-fire the alert.
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

# Emit basetool_monitoring_reconcile_disabled{component="deploy"} = 0 (reconcile enabled/OK) or 1
# (the iri-monitoring stack is RUNNING but the reconcile is gated off). This gauge is written on its
# OWN path — independent of write_prometheus_config_applied_metric — precisely so the "running but
# gated off" state is catchable WITHOUT the applied-stamp series, which is itself produced only from
# inside the enabled reconcile. That coupling is what blinded PrometheusConfigStale on 2026-07-13: a
# host with IRI_MONITORING_ENABLED unset never recreated Prometheus, so no applied stamp was ever
# written and the stale-config alarm had no data to fire on. Best-effort atomic replace into the
# node_exporter textfile dir; never fails the deploy. $1 is 0 or 1.
write_monitoring_reconcile_state_metric() {
  {
    echo "# HELP basetool_monitoring_reconcile_disabled 1 when the iri-monitoring stack is running but deploy.sh's monitoring reconcile is gated off (IRI_MONITORING_ENABLED != true), so on-disk monitoring config changes are never reloaded into the running Prometheus/alloy/blackbox."
    echo "# TYPE basetool_monitoring_reconcile_disabled gauge"
    echo "basetool_monitoring_reconcile_disabled{component=\"deploy\"} ${1}"
  } | write_textfile monitoring-reconcile.prom || true
}

# Reconcile ALL bind-mounted monitoring components against on-disk, then refresh the config-applied
# metric. Called on every healthy tick — the success block AND the idempotence no-op fast-exit — so a
# missed apply self-heals even on a quiet host that never re-deploys. Cheap in steady state: a
# per-service content diff, and a force-recreate only on actual drift.
#
# When IRI_MONITORING_ENABLED != true the reconcile is gated off, but the config-bundle rsync still
# rewrites monitoring/** on disk every tick — so on a host that IS running the monitoring stack, the
# on-disk changes silently never reach the running Prometheus. That drift is invisible to
# PrometheusConfigStale (its applied-stamp series is written only from the enabled path below), so we
# make it loud: a per-tick WARN plus the self-standing basetool_monitoring_reconcile_disabled gauge
# (=1), which backs the MonitoringReconcileDisabled alert. A host with no monitoring stack running
# stays silent — nothing scrapes the textfile there anyway.
# Reconcile the EDGE proxy against what is on disk (ADR-0162).
#
# Two independent sources of drift, and neither one is caught by `up -d`:
#
#   1. Configuration. docker/edge/nginx.conf is a SINGLE-FILE bind mount, so it is
#      pinned to the inode it was created with: rsync writes a new inode and the
#      container keeps reading the old one until it is recreated. conf.d/ and
#      include/ are directory mounts and therefore immune to that, but nginx still
#      only reads them at start. Either way the answer is a recreate, never a
#      SIGHUP — the same reasoning as reconcile_monitoring_reload above.
#
#   2. Certificates. The acme container renews into the edge-certs volume and has
#      no way to signal the edge — deliberately, because signalling would mean
#      handing it the container runtime, and the whole point of the split is that
#      the internet-facing process and the credential-holding process share nothing.
#      So the renewal is applied here, by fingerprinting the certificates and
#      recreating when the fingerprint moves. A renewal lands within one tick.
#
# Best-effort and non-gating, exactly like the monitoring reconcile: a failed
# recreate logs and is retried on the next tick, and it never fails a deploy.
reconcile_edge() {
  local src="${COMPOSE_DIR}/docker/edge"
  local snap="${EDGE_STATE_DIR}/config"
  local fp_file="${EDGE_STATE_DIR}/certs.sha256"
  local drift="" fp_now="" fp_old="" fp_lines=""

  # Nothing on disk for the edge (an older bundle, or the rollback profile is in
  # use) -> nothing to reconcile.
  [[ -d "${src}" ]] || return 0

  diff -rq "${snap}" "${src}" >/dev/null 2>&1 || drift="config"

  # Read the certificates THROUGH the edge rather than from the volume's host
  # path. That path lives in the service user's container storage, which this
  # script, running as `deploy`, cannot open — an earlier `[[ -d "${vol_mp}" ]]`
  # (then under /var/lib/docker) was false on every single tick, this whole
  # branch was skipped in silence, and certs.sha256 was never written. A renewed certificate would therefore never have been
  # loaded, however correctly acme published it. Found on 2026-09-12, after acme
  # itself was fixed and the edge went on serving the material seeded from the
  # retired Nginx Proxy Manager at the ADR-0162 cutover.
  # The edge already mounts the volume read-only, so `exec` needs neither a new
  # container nor root.
  if rt_is_running edge; then
    fp_lines="$(rt_exec edge sh -c \
                  'find /etc/nginx/certs -name fullchain.pem -type f -exec sha256sum {} +' \
                  2>/dev/null || true)"
  fi
  # An empty read means "could not tell", never "no certificates": sha256sum of
  # nothing is itself a valid hash, and folding that in would force-recreate the
  # edge on every tick the exec happened to fail.
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
    # An explicit `if`, not `A && B || C`: with the `|| true` tail that idiom runs
    # C when A is merely false, which reads as an error path and is not one
    # (SC2015).
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
  # Apply monitoring UNIT-DEFINITION drift (an image pin, a memory limit, a mount) to the running
  # containers: rt_monitoring_up restarts the units this run re-defined and merely starts the rest, so
  # it is a fast no-op on the 5-min converged hot path (this function runs there too). It is
  # COMPLEMENTARY to the per-service recreate below: that catches inode-pinned config-FILE edits
  # (prometheus.yml / config.alloy) a unit comparison cannot see because the bind-mount path is
  # unchanged, whereas this catches a changed definition the subtree diff cannot see. Without it a
  # monitoring definition change only landed on a full app deploy — 2026-07-17: alloy ran a stale
  # 256M/230MiB definition for days while disk said 384M/300MiB. Best-effort, non-gating (a failed
  # apply logs and retries next tick; it never fails the deploy).
  if ! rt_monitoring_up >/dev/null 2>&1; then
    log "  monitoring: WARN definition reconcile failed — non-gating, retries next tick"
  fi
  reconcile_monitoring_reload prometheus prometheus
  reconcile_monitoring_reload alloy alloy
  reconcile_monitoring_reload blackbox-exporter blackbox
  write_prometheus_config_applied_metric
}

# Extract the promoted Keycloak provider JAR (/providers/keycloak-spi.jar inside
# the scratch basetool-keycloak-spi image) onto the host as the mounted provider
# JAR. Like the config bundle the image has no entrypoint/command, so `podman
# create` needs a placeholder argument; the container is never started. The JAR is
# installed 0644 (and its parent dir created) so the uid-1000 Keycloak runtime can
# read it through the providers bind mount.
extract_keycloak_spi_jar() {
  local ref="$1" dest_jar="$2" stage
  stage="${STATE_DIR}/keycloak-spi-stage.jar"
  rm -f "${stage}"
  rt_extract_from_image "${ref}" /providers/keycloak-spi.jar "${stage}" /bundle \
    || fail "cannot extract /providers/keycloak-spi.jar from ${ref}"
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
  local attempt delay err rc
  delay="${COSIGN_VERIFY_DELAY}"
  VERIFY_LAST_ERROR=""
  for (( attempt = 1; attempt <= COSIGN_VERIFY_ATTEMPTS; attempt++ )); do
    rc=0
    # `2>&1 >/dev/null` keeps stderr (the reason) and drops stdout (the payload
    # JSON). The old code discarded both, which is why a transient registry error
    # and a forged image produced byte-identical operator-facing output.
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
  fail "SECURITY: cosign signature verification failed for ${label} (${ref}) after ${COSIGN_VERIFY_ATTEMPTS} attempts — refusing to deploy an unverified/untrusted image (expected identity: ${COSIGN_IDENTITY_REGEXP}); last cosign error: ${VERIFY_LAST_ERROR:-<none>}"
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
  log "  ${label}: SIGNATURE VERIFICATION FAILED (${ref}) — last cosign error: ${VERIFY_LAST_ERROR:-<none>}"
  return 1
}

# --- Pre-flight -------------------------------------------------------------
# NOT docker-compose.yml: the unit files are the deployment, and a host before its first bundle has
# no compose file at all. Demanding one aborted the testing host's first deploy on 2026-09-18.
require_file "${COMPOSE_DIR}/.env"
require_file "${TOKEN_FILE}"

# The keystore pre-flight is further down, after rt_detect: the mount source is read from the units
# it finds there. See keystore_mount_source.

mkdir -p "${STATE_DIR}"

# Pin DOCKER_CONFIG before the first registry call. The name is the Docker CLI's, and it is kept
# because cosign (go-containerregistry) reads the registry credential from
# $DOCKER_CONFIG/config.json. The `deploy` user has no usable $HOME (its home is the state
# directory, and the unit's ProtectHome makes /home read-only), so the default under $HOME/.docker
# is not writable. STATE_DIR is in the unit's ReadWritePaths, persists the credential between
# ticks, and stays under the deploy user's exclusive 0700 ownership.
export DOCKER_CONFIG="${DOCKER_CONFIG:-${STATE_DIR}/.docker}"
install -d -m 0700 "${DOCKER_CONFIG}"
# One credential file for three tools. cosign (go-containerregistry) reads
# $DOCKER_CONFIG/config.json; skopeo and podman (containers/image) read $REGISTRY_AUTH_FILE
# before anything else. Pointing the second at the first means a single `login` serves the
# tag resolution, the signature check and the pull, instead of three tools each looking in a
# different place and two of them finding nothing.
export REGISTRY_AUTH_FILE="${REGISTRY_AUTH_FILE:-${DOCKER_CONFIG}/config.json}"

# cosign writes its Sigstore/TUF cache under $HOME/.sigstore. The deploy user has
# no usable $HOME (created with --no-create-home, and the systemd unit's
# ProtectHome=true makes /home an inaccessible tmpfs), so point $HOME at STATE_DIR
# — already writable in the unit's ReadWritePaths and 0700-owned by deploy —
# otherwise `cosign verify` (REQ-OPS-015) cannot initialise its trust root under
# the sandbox. The registry credential is resolved via DOCKER_CONFIG and REGISTRY_AUTH_FILE (set
# above), NOT $HOME, so this does not affect the registry login.
export HOME="${IRI_HOME:-${STATE_DIR}}"

# Where the units' EnvironmentFile= lines point, and the renderer that fills it. The templates
# ride the config bundle and carry only `KEY=${VAR:?}` placeholders; the VALUES are in the host's
# own .env and never leave it, which is the whole reason this is rendered here rather than baked
# into the bundle at build time.
ENV_D_DIR="${IRI_ENV_D_DIR:-${COMPOSE_DIR}/env.d}"
ENV_RENDERER="${IRI_ENV_RENDERER:-${IRI_SCRIPT_DIR}/render-env-d.py}"

# Find the service user that owns the containers before anything else touches them, and fail fast on
# a host that has none. `rt_detect` TRIES rather than infers.
rt_detect
# NOT rt_wait_for_startup, unlike backup, drill and cleanup: a stack stuck in `starting` because a
# unit will not come up may be exactly what this release exists to fix. See the library.
# The monitoring units are derived from the unit directory: every `.container` that is not in
# RT_STACK_SERVICES below (rt_monitoring_services, since 2026-09-22 -- they were a literal list here
# and in backup.sh).
export RT_HEALTH_TIMEOUT="${HEALTH_TIMEOUT}"
# There is no project to ask what "the whole stack" is, so the list is named here. It is the compose
# prod profile's services, in dependency order.
#
# `acme` was missing from it until 2026-09-22, and nothing said so: it is the one prod-profile service
# nothing else depends on, so no start of another unit pulls it in. A release that changed
# acme.container installed the unit and restarted nothing, and a host where the acme unit was not
# running was never brought back by a deploy. It goes last because it needs the edge's webroot to
# answer the HTTP-01 challenge, and it is gated like every other service here.
export RT_STACK_SERVICES="db-backend db-keycloak redis keycloak backend ingest frontend edge acme"
log "container runtime: ${RT_BACKEND}"

# skopeo is how a tag is resolved to a digest without pulling; Quadlet is what turns the unit files
# into services at all. A host missing either cannot deploy, and saying so here beats discovering it
# halfway through an apply.
command -v skopeo >/dev/null 2>&1 \
  || fail "skopeo not available; it is how a tag is resolved without pulling (ansible role: 10-packages.yml)"
# A LIST, not one path: Fedora/RHEL/Rocky ship the generator at /usr/libexec/podman/quadlet and Debian
# at /usr/lib/podman/quadlet. Hardcoding either one is a latent failure on the other, and this project
# has already changed platform twice. IRI_QUADLET_BIN overrides it outright.
QUADLET_BIN="${IRI_QUADLET_BIN:-}"
if [[ -z "${QUADLET_BIN}" ]]; then
  for candidate in /usr/libexec/podman/quadlet /usr/lib/podman/quadlet; do
    [[ -x "${candidate}" ]] && { QUADLET_BIN="${candidate}"; break; }
  done
fi
[[ -n "${QUADLET_BIN}" && -x "${QUADLET_BIN}" ]] \
  || fail "the Quadlet generator is missing (looked in /usr/libexec/podman and /usr/lib/podman); this host cannot turn .container files into services"
# The DIRECTORY, not the units in it. An empty one is the normal state of a freshly provisioned host:
# ansible/roles/basetool_host creates it, and the first deploy fills it from the config bundle.
# Demanding units here would make that first deploy impossible.
[[ -n "${RT_UNIT_DIR}" && -d "${RT_UNIT_DIR}" ]] \
  || fail "no Quadlet unit directory (${RT_UNIT_DIR:-unset}) — run the basetool_host role first; it creates the directory this fills"

# The keystore the application containers will actually mount -- checked here and not earlier,
# because the answer is in the units rt_detect located.
#
# scripts/generate-quadlet.py substitutes the fixed /var/iri/secrets/keystore.p12 into each unit's
# `Volume=` at GENERATION time, and nothing reads .env's IRI_KEYSTORE_HOST_PATH afterwards. Until
# 2026-09-22 this pre-flight still read .env on a Podman host, so it certified a file the stack does
# not mount: an .env naming an existing file passed while the unit's source was missing, and the
# release then died at the first `systemctl start` as an application fault.
#
# So the path is read out of the units that will be started, from the `Volume=` whose destination is
# /run/secrets/keystore.p12. A unit directory that names no keystore mount yet -- a freshly
# provisioned host before its first bundle -- falls back to .env, and the generator's constant is
# the last resort.
#
# EVERY PKCS#12 mounted under /run/secrets/, not the first one (REQ-SEC-070): since the
# per-service keystores each service mounts its own file plus the internal truststore, and one
# missing file among several is exactly the case `head -n1` could not see.
#
# And the edge's two trust anchors under /etc/nginx/*.crt (REQ-OBS-008): the internal CA and, since
# 2026-09-23, Grafana's own certificate. A single-file bind mount whose source is missing does not
# start the container at all, and for the edge that is the whole site.
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

# cosign is required for the host-side signature gate (REQ-OPS-015). Fail closed:
# a host that cannot verify signatures must not silently fall back to trusting an
# unverified :stable. Missing cosign with the gate ON is a bootstrap error, not a
# reason to skip verification — install cosign (docs/deployment.md) or, only to
# break glass during a Sigstore outage, run with IRI_COSIGN_VERIFY=false.
#
# "Not on PATH" and "not installed" are different facts, and on Rocky they come apart. The role
# installs cosign to /usr/local/bin (`basetool_host_cosign_path`); sudo's `secure_path` on Rocky
# 10.2 is `/sbin:/bin:/usr/sbin:/usr/bin` and does NOT contain it. So `sudo -u deploy deploy.sh` —
# the manual invocation this script's own usage block documents — aborted with "install cosign"
# against a host where cosign was sitting in /usr/local/bin, 141 MB of it, installed by the role two
# days earlier. Measured on the testing host 2026-09-20.
#
# The TIMER is unaffected and always was: a systemd service gets systemd's own PATH, which includes
# /usr/local/bin — so the cutover's `systemctl start iri-deploy.service` never saw this. That is
# exactly what makes it worth fixing rather than documenting: the failure is invisible on the path
# CI and the runbook exercise, and waiting for the operator who types the other one.
#
# Looking in the known locations is not a search of the filesystem: it is the one directory the
# role is configured to install into, plus the two an operator would use by hand.
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
# Per-service persisted snapshot of the monitoring config subtree each bind-mounted component
# (prometheus/alloy/blackbox) was last successfully applied. reconcile_monitoring_reload diffs the
# on-disk subtree against this baseline on EVERY healthy tick and force-recreates on drift, so a
# missed/lost/rolled-back config apply self-heals instead of leaving the running process on a stale
# config (a SIGHUP cannot: the single-file mounts are inode-pinned — see reconcile_monitoring_reload).
MON_RELOAD_STATE_DIR="${STATE_DIR}/monitoring-reload"
# Per-component applied state for the edge proxy (ADR-0162): a snapshot of the
# config subtree it was last recreated for, and the fingerprint of the
# certificates it was last started with.
EDGE_STATE_DIR="${STATE_DIR}/edge"
CONFIG_BLOCKED_FILE="${STATE_DIR}/config-blocked.marker"
# Present while the live config tree is not known to be one release: written before the first byte
# of a config apply, removed once the apply (or the restore after a failed one) completed. A tick that
# finds it does NOT re-snapshot config-previous/, because the live tree would be a mix of two releases
# and snapshotting it would overwrite the only consistent rollback anchor with it.
CONFIG_APPLY_INCOMPLETE_FILE="${STATE_DIR}/config-apply.incomplete"
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
# date. Best-effort and opt-in: no expiry recorded → NO METRIC, and the alert does
# not fire on absence (a non-expiring token is a valid, un-alerted state);
# never fails the deploy. Called on EVERY tick, including the idempotence no-op,
# so the gauge does not go stale.
#
# "No metric" has to mean REMOVING one that is already there, and that is a fix
# rather than a nicety (2026-09-20). This used to `return 0` on an absent file,
# which is correct only on a host that never recorded an expiry. On a host where
# one WAS recorded and was then correctly deleted -- the documented action when
# the PAT turns out to be non-expiring -- the old ghcr-token.prom stayed on disk,
# node_exporter kept serving the stale timestamp, and GhcrPullTokenExpired fired
# CRITICAL forever for doing exactly what the alert's own description asks.
#
# An UNPARSEABLE date removes it too. A typo'd date is not a reason to keep
# asserting the previous one: "no claim" is recoverable, a stale claim is a
# permanent critical that looks like a real one. The WARN is what says so.
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

# write_deploy_metric <success|rollback|failure|blocked>
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

# write_stack_health_metric <healthy|restart_failed>
# Maintains ${STACK_HEALTH_METRIC_FILE} with two gauges that drive the runtime
# DeployHealthRestartFailing alert WITHOUT overloading the promotion-outcome
# metrics: `healthy` stamps basetool_deploy_last_stack_healthy_timestamp (a
# freshness heartbeat written on every healthy tick), `restart_failed` stamps
# basetool_deploy_last_health_restart_failed_timestamp. The alert fires while the
# failed stamp is newer than the healthy one and self-clears on the next healthy
# tick. Each write preserves the other gauge. Best-effort; never gates a deploy.
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

# record_target_failure — count one more failure of EXPECTED_MARKER in ${FAILED_FILE}, which is what
# the bad-digest backoff reads. Sets FAIL_COUNT. The count restarts at 1 when the record belongs to a
# different target, so a freshly promoted release is never throttled by its predecessor's failures.
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

# --- The pre-gate guard -----------------------------------------------------
# Everything between the signature verification and the health gate changes the host — the config
# tree, the units, env.d, the digest pin — and until 2026-09-25 a failure anywhere in it was recorded
# NOWHERE. The only failure bookkeeping this script had was explicit code on three paths (the health
# gate, the provider-JAR recreate, the signature check); `fail` writes no metric, and a command that
# failed under `set -e` ended the process without even a FATAL line.
#
# That is how v1.11.0 sat undeployed for fifteen minutes on 2026-09-25. /var/iri/code/docker/acme was
# root-owned; every tick from 12:25 to 12:35 logged "config changed → staging", mirrored three
# subtrees, died on rsync exit 23 inside mirror_dir, and the next tick began. No FATAL line, no
# backoff record, no rollback, `basetool_deploy_last_failure_timestamp 0` — so DeployFailed could not
# fire, and a human noticed. It also destroyed both rollback anchors on the way: the second tick's pin
# save copied the first tick's NEW pin over previous-digest-pin.yml, and its snapshot captured the
# half-mirrored tree as config-previous/.
#
# The guard is an EXIT trap armed for exactly that window. Any non-zero exit inside it — a `fail`, a
# mirror's rsync, an errexit anywhere, a pull — lands here, and is recorded the way every other
# deploy failure is: a FATAL line naming the step and the exit code, the host tree and the pin put
# back if this run had changed them, a backoff record, and the failure metric that DeployFailed
# reads. A deliberate exit inside the window (the stateful-infra carve-out) disarms it first.
DEPLOY_STEP=""
PRE_GATE_GUARD=false
CONFIG_TREE_TOUCHED=false
PIN_TOUCHED=false
PIN_HAD_PREVIOUS=false

# shellcheck disable=SC2317  # runs indirectly via the EXIT trap armed before the config delivery
on_pre_gate_exit() {
  local rc=$?
  [[ "${PRE_GATE_GUARD}" == "true" ]] || return 0
  (( rc != 0 )) || return 0
  PRE_GATE_GUARD=false
  # Every step below is best-effort and must not end the handler early: the metric write at the end
  # is the part the alerting depends on.
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
      # There was no pin before this run, so there is nothing to go back to — only this run's to remove.
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

# --- Lock -------------------------------------------------------------------
exec 200>"${LOCKFILE}"
if ! flock -n 200; then
  log "another deploy is in progress (lock: ${LOCKFILE}); exiting"
  exit 0
fi

# --- Authenticate to GHCR ---------------------------------------------------
log "logging in to ${REGISTRY} as ${GHCR_USERNAME}"
if ! rt_login "${REGISTRY}" "${GHCR_USERNAME}" "${TOKEN_FILE}" >/dev/null 2>&1; then
  fail "${RT_BACKEND} login to ${REGISTRY} failed — check ${TOKEN_FILE} (scope: read:packages)"
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
  # Resolves a tag to its manifest digest WITHOUT pulling the image, through `skopeo inspect` —
  # which is why the Ansible role installs skopeo. Works for multi-arch lists (returns the index
  # digest) and for single-platform manifests alike.
  rt_resolve_digest "$1"
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

# A host with no unit files has not received this release's DEFINITION, whatever the
# marker claims about its digests -- and that is the normal state of a freshly provisioned host,
# because ansible/roles/basetool_host creates the unit directory and deliberately puts nothing in
# it. Treated as a config change so the bundle is staged and install_quadlet_units fills it.
#
# Decided HERE, beside the other config divergences, and not further down: below this point sits
# the idempotence fast exit, and a host whose marker happens to match would take it and report
# "no change" for a stack that does not exist.
if [[ "${CONFIG_CHANGED}" != "true" ]]; then
  shopt -s nullglob
  host_units=( "${RT_UNIT_DIR}"/*.container )
  shopt -u nullglob
  if (( ${#host_units[@]} == 0 )); then
    CONFIG_CHANGED=true
    log "no Quadlet units on this host — staging the config bundle to install them"
  fi
fi

# --- Idempotence check ------------------------------------------------------
# The marker only records what the last SUCCESSFUL deploy applied — trusting it
# alone is not enough. A hand-made container or a missing digest-pin drop-in
# resolves `:stable` from the LOCAL image cache, which this script never
# refreshes (it always pulls by digest), so it can silently start an outdated
# build; a crash loop or a half-down stack likewise leaves the marker
# untouched while prod serves the wrong version or none. Incident 2026-07-02:
# a pre-V199 backend started that way against a V201-migrated database was
# crash-looping while this script reported "no change" and exited 0. So the
# fast exit is taken only after verifying the RUNNING stack against the target.

# Emits one `service: reason` line per divergence between the running stack
# and the target digest set; emits nothing when every app service has a
# container that is running, healthy (a container without a healthcheck counts
# as healthy, as a unit without Notify=healthy starts) and created from an image whose RepoDigest
# equals the target digest. `ps -aq` (not `-q`) so stopped/created/restarting
# containers are judged by their state instead of being reported as absent.
running_stack_drift() {
  local entry svc image digest cids cid state img_id repo_digests img_ok
  # The unit files ARE the stack. None of them means nothing can be running, and the per-service
  # probe below would report three missing containers without ever saying why -- so the cause is
  # named once, first.
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
      # A wholly missing service is a STRUCTURAL divergence (half-down stack),
      # never a runtime-health blip: an `up` must (re)create the container.
      echo "structural ${svc}: no container"
      continue
    fi
    while IFS= read -r cid; do
      [[ -n "${cid}" ]] || continue
      state="$(rt_container_probe "${cid}")" || state="gone"
      # Whether this container runs the TARGET image is computed for every state
      # (not only the running-healthy ones) so an unhealthy container that IS on
      # the target image can be told apart from one on a wrong image. The former
      # is a runtime-health fault (targeted restart); the latter, like a missing
      # container, is a structural mismatch the apply/rollback path must correct.
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
        # running/starting (healthcheck still inside its start period, e.g. the
        # restart policies bringing the stack up after a host reboot while a tick
        # fires) counts as converged for THIS tick: a unit start would simply wait
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
      # Even on a converged no-op, reconcile the bind-mounted monitoring configs against on-disk:
      # a config apply a prior tick failed to land (or a rollback bypassed) would otherwise linger
      # on a quiet host until the next real deploy — the 2026-07-11 ingest TargetDown. Cheap and
      # non-gating: a per-service content diff, a force-recreate only on actual drift.
      reconcile_monitoring_reloads
      # Same reasoning one layer over: the edge's config is a bind mount and its
      # certificates are renewed out-of-band, so a quiet host would otherwise run
      # a stale edge until the next real deploy.
      reconcile_edge
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
# affected service(s) (a unit restart, which recreates the container), bounded by a short backoff
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
  # The targeted restart recreates ONLY the sick services, at the release they
  # are already on — no pull, no signature re-verify, no rollback (ADR-0083).
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
# apply. A :stable tag moved out-of-band to an unsigned or differently-signed
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

# --- Arm the pre-gate guard -------------------------------------------------
# From here to the health gate every failure is recorded by on_pre_gate_exit: FATAL line, restore,
# backoff record, failure metric. Disarmed before each deliberate exit and before the health gate,
# whose success and rollback paths record their own outcome.
export RT_PIN_FILE="${PIN_FILE_CURRENT}"
export RT_PIN_FILE_PREVIOUS="${PIN_FILE_PREVIOUS}"
trap on_pre_gate_exit EXIT
PRE_GATE_GUARD=true

# --- Deliver promoted host config -------------------------------------------
# The units and their sibling host config (the edge and monitoring configuration, the
# maintenance page, the Keycloak theme) ride the SAME promoted, digest-pinned GHCR channel as the
# app images.
# Only (re)stage them when the promoted config digest actually moved, so an
# app-only promotion stays byte-for-byte the legacy path.
if [[ "${CONFIG_CHANGED}" == "true" ]]; then
  log "config changed → staging ${CONFIG_IMAGE}@${CONFIG_DIGEST}"
  DEPLOY_STEP="extract the config bundle"
  extract_config_bundle "${CONFIG_IMAGE}@${CONFIG_DIGEST}" "${CONFIG_STAGE_DIR}"
  DEPLOY_STEP="check the staged config bundle"
  require_file "${CONFIG_STAGE_DIR}/docker-compose.yml"
  assert_no_secrets "${CONFIG_STAGE_DIR}"

  # Carve-out: a postgres / Keycloak IMAGE change is a stateful, choreographed
  # upgrade (PGDATA major migration; Keycloak provider+keystore dance) that a
  # blind restart would break and the health-gate would then roll back on a
  # 5-minute loop. Refuse to auto-apply it; record the target so we alert ONCE
  # and then skip quietly until a new promotion or an operator --force. The
  # operator runs the documented manual upgrade, then re-runs with --force.
  OLD_INFRA="$(infra_image_pins "${COMPOSE_DIR}")"
  NEW_INFRA="$(infra_image_pins "${CONFIG_STAGE_DIR}")"
  # A host that has never received a definition has nothing to compare, and "everything is new" is
  # not an upgrade: the bundle IS the first definition, so the carve-out saw an empty `old` against a
  # full `new` and refused the very first deploy as a stateful-infra upgrade -- measured on the
  # testing host 2026-09-18, twice. The test is whether units EXIST, not whether the pins are empty.
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
      # A deliberate refusal, recorded as `blocked` above — not a failure for the guard to record.
      PRE_GATE_GUARD=false
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

  # A changed `.network` unit is installed below and NOT applied: Quadlet creates networks with
  # `podman network create --ignore`, so an existing network keeps its settings until it is removed
  # and recreated -- a full-stack outage taken as a maintenance (docs/deployment.md → "Network
  # changes are installed, not applied"), never by an automatic tick. The #974 clean-slate recreate
  # that did this for Compose was removed with the Docker runtime on 2026-09-22.

  # Refuse before the first byte is written if the deploy account cannot write where the apply
  # writes. The incident this exists for is in assert_config_tree_writable.
  assert_config_tree_writable "${CONFIG_STAGE_DIR}"

  # Snapshot the live config tree as the rollback anchor, then swap in the new — unless an earlier
  # apply died halfway and could not be undone: the live tree is then a mix of two releases, and
  # snapshotting it would replace the last consistent anchor with that mix.
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
  # The unit files ARE the definition the apply below acts on, so they have to be in place before
  # the pin is written and long before anything is started.
  install_quadlet_units "${COMPOSE_DIR}"
  rm -f "${CONFIG_APPLY_INCOMPLETE_FILE}"
  log "config applied"
fi

# --- Save rollback anchor + write new pin -----------------------------------
# The pin has two halves and the seam owns both: the RECORD (this file, which the
# rollback reads back) and the `.container.d/` drop-ins that actually bind the digest.
# Restoring only the record on rollback would leave the new digests bound and
# roll silently FORWARD into the release whose health check just failed.
#
# AFTER the config delivery, not before it (moved 2026-09-25). A drop-in binds as soon as anything
# daemon-reloads — a reboot, another unit's restart — so a pin written before a config apply that
# then refused (the stateful-infra carve-out) or failed left the NEW app digests bound to the OLD
# units; and the next tick's rt_pin_save copied that new pin over previous-digest-pin.yml, so the
# rollback anchor pointed at the release it was meant to roll back from.
DEPLOY_STEP="write the digest pin"
PIN_HAD_PREVIOUS=false
[[ -f "${PIN_FILE_CURRENT}" ]] && PIN_HAD_PREVIOUS=true
PIN_TOUCHED=true
rt_pin_save
rt_pin_apply \
  "backend=${BACKEND_IMAGE}@${BACKEND_DIGEST}" \
  "frontend=${FRONTEND_IMAGE}@${FRONTEND_DIGEST}" \
  "ingest=${INGEST_IMAGE}@${INGEST_DIGEST}"

# --- Apply ------------------------------------------------------------------
DEPLOY_STEP="enter ${COMPOSE_DIR}"
cd "${COMPOSE_DIR}"

# Only pre-pull the images this deploy actually moves (backend + frontend +
# ingest from GHCR). The third-party infra images (keycloak/postgres/redis/edge)
# are pinned by digest and change only on a deliberate unit change; pulling them
# here would make every deploy hostage to a transient outage of a third-party
# registry (e.g. a quay.io 502/504 on the Keycloak manifest aborting the whole
# pull under `set -e`, before the apply ever runs). A unit start still pulls any
# infra image that is genuinely missing locally, so a real digest bump is rolled
# forward — an already-present pinned image is simply reused offline.
log "pulling images"
DEPLOY_STEP="pull the release images"
RT_PIN_FILE="${PIN_FILE_CURRENT}" rt_pull \
  "backend=${BACKEND_IMAGE}@${BACKEND_DIGEST}" \
  "frontend=${FRONTEND_IMAGE}@${FRONTEND_DIGEST}" \
  "ingest=${INGEST_IMAGE}@${INGEST_DIGEST}"

# The health gate records its own outcome — success, or the rollback below — so the guard ends here.
PRE_GATE_GUARD=false
log "applying (timeout ${HEALTH_TIMEOUT}s)"
if rt_apply_stack; then

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

    if ! rt_recreate keycloak; then
      log "keycloak did not become healthy with the new provider JAR — rolling back the JAR"
      if [[ "${KEYCLOAK_SPI_HAD_PREVIOUS}" == "true" ]]; then
        install -D -m 0644 "${KEYCLOAK_SPI_PREVIOUS_JAR}" "${KEYCLOAK_SPI_JAR}"
      else
        rm -f "${KEYCLOAK_SPI_JAR}"
      fi
      rt_recreate keycloak >/dev/null 2>&1 \
        || log "WARNING: keycloak did not return to health on the previous JAR — manual check needed"

      # Record the failure so the backoff throttles re-attempts of this exact
      # target, the same mechanism as a failed app deploy. The app images stay on
      # the new (healthy) version; only the provider JAR was reverted, so the
      # marker is deliberately NOT written and the next tick retries (backed off).
      record_target_failure
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
  # Reconcile the monitoring units AFTER the app stack is verified healthy — this NEVER gates the
  # app deploy. Monitoring config changes (new dashboards/alert rules) arrive via the config bundle,
  # so they land on this path; crashed monitoring containers recover on their own restart policy.
  # Any failure only logs — the app deploy stays successful. Gated on IRI_MONITORING_ENABLED=true
  # (unset on a host without the stack).
  if [[ "${IRI_MONITORING_ENABLED:-false}" == "true" ]] && rt_monitoring_configured; then
    log "applying monitoring stack (non-gating)"
    # Through a file and NOT a pipe into sed. A pipeline runs rt_monitoring_up in a subshell, so
    # the service it restarts is forgotten only there -- and reconcile_monitoring_reloads below calls
    # it again in THIS shell, where the service is still listed as changed, and would recreate it a
    # second time for the same release. The file is overwritten on every run and left in place, so
    # the last apply's output can still be read after the journal has rotated.
    MONITORING_APPLY_RC=0
    rt_monitoring_up > "${STATE_DIR}/monitoring-apply.log" 2>&1 || MONITORING_APPLY_RC=$?
    sed 's/^/  monitoring: /' "${STATE_DIR}/monitoring-apply.log" 2>/dev/null || true
    if [[ "${MONITORING_APPLY_RC}" -eq 0 ]]; then
      log "monitoring stack reconciled"
      # The apply restarts a service only when its DEFINITION changed; a bind-mounted config-file edit
      # (inode-pinned single-file mount) needs a recreate as well. Reconcile the applied config against
      # on-disk — self-healing: force-recreates on ANY drift from the last applied snapshot, not just
      # this tick's bundle swap.
      reconcile_monitoring_reloads
    else
      log "WARN: monitoring stack apply failed — app deploy stays successful (non-gating)"
    fi
  fi

  # OUTSIDE the monitoring gate on purpose: the edge belongs to the application
  # stack, so IRI_MONITORING_ENABLED must not decide whether its configuration
  # reaches the running container.
  reconcile_edge

  # Best-effort prune of dangling images older than 30 days. Restricted via
  # `until=720h` to avoid wiping the just-pulled images we may still need to
  # roll back to. `|| true` because a stuck container ref can transiently
  # block a prune and we should not fail the deploy over it.
  rt_prune_images 720h
  exit 0
fi

# --- Rollback on health failure --------------------------------------------
log "health check failed within ${HEALTH_TIMEOUT}s — rolling back"

# Record this failure so subsequent ticks back off this exact (broken) digest
# pair instead of re-applying it every 5 minutes (see the backoff block above).
record_target_failure
log "recorded health-check failure #${FAIL_COUNT} for this target; next retry backs off"

# Revert the host config too (if this deploy swapped it), so the rolled-back
# stack runs the exact units the previous digest pin expects. Done before the
# pin check so even the no-previous-pin exit leaves the config tree consistent.
# And the units with it: restoring the config tree and the digest pin while leaving the NEW unit
# files in place would put the previous release's digests on the failed release's definitions --
# a state neither release was ever tested in.
#
# A restore that fails does not end the rollback. Until 2026-09-25 it ran under errexit, so a failed
# mirror here ended the script before the pin went back and before the rollback metric was written
# -- the same silence as the forward apply's. Called as a plain command between `set +e` and `set -e`
# because restore_previous_config_tree needs errexit to be honoured inside it.
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

# Restores the record AND the drop-ins it names — see rt_pin_rollback.
rt_pin_rollback

if rt_apply_stack; then
  log "rolled back to previous digest pin successfully"
else
  log "rollback ALSO failed — one or more target digests broken or environment problem"
fi

# Either way, this run failed → non-zero exit so the systemd unit reports
# `failed` and journalctl flags it. Active alerting is via the textfile metric
# written just below, not a systemd OnFailure= hook: a rollback newer than the
# last success trips the DeployRolledBack Prometheus alert (a failure trips
# DeployFailed) — the "promoted release did not ship" signal (epic #936 alert
# catalog, monitoring/prometheus/alerts/ops-automation.yml).
write_deploy_metric rollback
exit 1
