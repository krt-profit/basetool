# shellcheck shell=bash
# =============================================================================
# The container-runtime seam (ADR-0163, Phase 3 of docs/PODMAN_MIGRATION_PLAN.md).
#
# `deploy.sh`, `backup.sh` and `restore-drill.sh` were written against Docker
# Compose. The Podman migration does not swap a binary underneath them — it
# changes the orchestration model: there is no compose file, no project, and no
# `up --wait`. A stack is a set of systemd units that Quadlet generates from
# `.container` files, and "bring it up and wait for health" is
# `systemctl --user start`, which blocks because `Notify=healthy` makes each unit
# `Type=notify`.
#
# Both shapes have to work AT THE SAME TIME. Production serves on Docker until
# the cutover and the testing host serves on Podman now, so a hard rewrite would
# leave production without a deployer for the length of the migration — which
# §23 of the plan identifies as a larger data-loss risk than the migration night
# itself. So this is a seam, not a replacement: every runtime operation the three
# scripts perform is named once here and implemented twice.
#
# Usage:
#
#     . "$(dirname "$0")/lib/container-runtime.sh"
#     rt_detect                      # sets RT_BACKEND, and dies if neither exists
#     rt_apply backend frontend      # compose up --wait, or systemctl start
#
# Every function is prefixed `rt_`. Nothing here writes outside the paths its
# caller passes in, and nothing here reads a secret.
#
# TESTABILITY. `deploy.test.sh` stubs the CLI by putting a fake `docker` on PATH
# and asserting on the recorded invocations. That works unchanged here: the
# Podman backend calls `podman`, `skopeo` and `systemctl` by name, so the same
# harness stubs them the same way. `RT_BACKEND` can also be forced, which is what
# lets one test exercise both shapes on a machine that has neither.
# =============================================================================

#: Which runtime this host actually has. Set by rt_detect; never guessed.
RT_BACKEND="${RT_BACKEND:-}"

#: The container CLI, including any privilege prefix a rootless deployment needs.
RT_CLI="${RT_CLI:-}"

#: How to reach the rootless user's systemd instance, for the Podman backend.
RT_SYSTEMCTL="${RT_SYSTEMCTL:-}"

#: Where Quadlet reads unit files from, for the Podman backend.
RT_UNIT_DIR="${RT_UNIT_DIR:-}"

# Defers to the caller's own `fail` when it has one, so a sourcing script keeps
# its logging and its exit path instead of dying differently depending on which
# layer happened to notice.
rt_die() {
  if declare -F fail >/dev/null 2>&1; then
    fail "$*"
  fi
  printf 'container-runtime: %s\n' "$*" >&2
  exit 1
}

# -----------------------------------------------------------------------------
# rt_detect
#
# Decide which runtime is in front of us, and how to reach it.
#
# It TRIES rather than infers. The first version of the equivalent code in
# check-conformance.py looked the rootless user up with a glob over
# `/home/*/.config/containers/systemd/*.container`; that directory is 0750 and
# the deploy account is not its owner, so the glob expanded to nothing, the
# fallback picked the current user, and a bare `podman` reported `no such object`
# for eight healthy containers. Every arm of the caller then read a dead stack.
#
# Honours a pre-set RT_BACKEND, which is how the self-test drives both shapes.
# -----------------------------------------------------------------------------
rt_detect() {
  if [[ -n "${RT_BACKEND}" ]]; then
    case "${RT_BACKEND}" in
      docker) RT_CLI="${RT_CLI:-docker}" ;;
      podman) RT_CLI="${RT_CLI:-podman}"; RT_SYSTEMCTL="${RT_SYSTEMCTL:-systemctl --user}" ;;
      *) rt_die "RT_BACKEND=${RT_BACKEND} is neither docker nor podman" ;;
    esac
    return 0
  fi

  if command -v docker >/dev/null 2>&1 && docker ps >/dev/null 2>&1; then
    RT_BACKEND=docker
    RT_CLI=docker
    return 0
  fi

  if command -v podman >/dev/null 2>&1; then
    RT_BACKEND=podman
    # Already the owning user?
    if podman ps --format '{{.Names}}' >/dev/null 2>&1; then
      RT_CLI=podman
      RT_SYSTEMCTL="systemctl --user"
      RT_UNIT_DIR="${RT_UNIT_DIR:-${HOME}/.config/containers/systemd}"
      return 0
    fi
    # Otherwise, ask each lingering user whether it can see containers. Lingering
    # is the declared intent — "this user runs services without a login session" —
    # and /var/lib/systemd/linger is world-readable, unlike the users' homes.
    # A glob, not `ls`: a username with a space would be split by word splitting,
    # and the `-e` guard is what makes an EMPTY directory iterate zero times
    # instead of once over the literal pattern.
    local lingerfile u uid
    for lingerfile in /var/lib/systemd/linger/*; do
      [[ -e "${lingerfile}" ]] || continue
      u="$(basename "${lingerfile}")"
      uid="$(id -u "${u}" 2>/dev/null)" || continue
      if sudo -n -u "${u}" XDG_RUNTIME_DIR="/run/user/${uid}" \
           podman ps --format '{{.Names}}' >/dev/null 2>&1; then
        RT_CLI="sudo -n -u ${u} XDG_RUNTIME_DIR=/run/user/${uid} podman"
        RT_SYSTEMCTL="sudo -n -u ${u} XDG_RUNTIME_DIR=/run/user/${uid} systemctl --user"
        RT_UNIT_DIR="$(getent passwd "${u}" | cut -d: -f6)/.config/containers/systemd"
        return 0
      fi
    done
    rt_die "podman is installed but no user could be found that owns the containers"
  fi

  rt_die "this host has neither a working docker nor a podman"
}

# -----------------------------------------------------------------------------
# rt_resolve_digest <image-reference>
#
# Resolve a tag to the immutable digest it currently points at, WITHOUT pulling.
#
# Docker uses `buildx imagetools inspect`. Podman has no equivalent subcommand;
# `skopeo inspect docker://…` is the registry-native answer and is what
# ansible/roles/basetool_host installs for this purpose. Both are read-only
# registry calls.
#
# Prints the digest (`sha256:…`) on stdout. Non-zero and silent on failure, so a
# caller can tell "the tag does not exist" from "the registry is unreachable" by
# its own retry policy rather than by parsing text.
# -----------------------------------------------------------------------------
rt_resolve_digest() {
  local ref="$1" out
  case "${RT_BACKEND}" in
    docker)
      # The manifest's own digest, which is what `@sha256:` addresses. Works for
      # a multi-arch list (returns the index digest) and a single manifest alike.
      # This exact form is what deploy.sh has been using and what its 159 tests
      # already pin, so the seam adopts it rather than introducing a second one.
      docker buildx imagetools inspect "${ref}" --format '{{.Manifest.Digest}}' 2>/dev/null
      ;;
    podman)
      command -v skopeo >/dev/null 2>&1 || rt_die "skopeo is not installed; it is how a tag is resolved without pulling"
      out="$(skopeo inspect --no-tags "docker://${ref}" 2>/dev/null)" || return 1
      printf '%s' "${out}" | sed -n 's/.*"Digest"[[:space:]]*:[[:space:]]*"\(sha256:[a-f0-9]\{64\}\)".*/\1/p' | head -1
      ;;
    *) rt_die "rt_resolve_digest before rt_detect" ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_login <registry> <username> <password-file>
#
# The password arrives via `--password-stdin` in both backends, so it never
# reaches a command line where `ps` could read it.
# -----------------------------------------------------------------------------
rt_login() {
  local registry="$1" user="$2" pwfile="$3"
  ${RT_CLI} login "${registry}" --username "${user}" --password-stdin < "${pwfile}"
}

# -----------------------------------------------------------------------------
# rt_service_container_ids <service>
#
# The container ids belonging to one service, one per line, including stopped
# ones — the caller decides what a non-running container means.
#
# Docker asks compose, which knows the project. Podman has no project, so the
# label Quadlet stamps on every container it starts is the equivalent: each
# container carries PODMAN_SYSTEMD_UNIT=<service>.service. Matching on the NAME
# would also work today and would break the moment two units share a name prefix.
# -----------------------------------------------------------------------------
rt_service_container_ids() {
  local svc="$1"
  case "${RT_BACKEND}" in
    docker)
      docker compose -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        --profile "${RT_PROFILE:-prod}" ps -aq "${svc}" 2>/dev/null || true
      ;;
    podman)
      ${RT_CLI} ps -aq --filter "label=PODMAN_SYSTEMD_UNIT=${svc}.service" 2>/dev/null || true
      ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_container_probe <container-id>
#
# One line: `<is-one-off>|<state>/<health>`, the shape deploy.sh's drift check
# already parses.
#
# The one-off flag exists because `docker compose run` leaves debug containers
# alongside the service replica, and judging them flags drift on every tick for
# as long as they exist. Podman has no compose-run, so the flag is always false
# there — stated rather than left as an empty string that reads like a failure.
# -----------------------------------------------------------------------------
rt_container_probe() {
  local cid="$1" fmt
  case "${RT_BACKEND}" in
    docker)
      fmt='{{index .Config.Labels "com.docker.compose.oneoff"}}|{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}'
      ;;
    podman)
      fmt='false|{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}'
      ;;
  esac
  ${RT_CLI} inspect --format "${fmt}" "${cid}" 2>/dev/null || printf '|gone'
}

# -----------------------------------------------------------------------------
# rt_container_image_id <container-id>
# rt_image_repo_digests <image-id>
#
# The pair that answers "is this container running the image we targeted". Both
# formats were measured identical under podman 5.8.2 and docker 29 on
# 2026-09-18, which is why they are not branched.
# -----------------------------------------------------------------------------
rt_container_image_id() {
  ${RT_CLI} inspect --format '{{.Image}}' "$1" 2>/dev/null || true
}

rt_image_repo_digests() {
  ${RT_CLI} image inspect --format '{{join .RepoDigests " "}}' "$1" 2>/dev/null || true
}

# -----------------------------------------------------------------------------
# rt_apply <service>...
#
# Bring the named services to their target state and WAIT for them to be healthy.
# Returns non-zero when any of them does not get there, which is what the health
# gate and the rollback hang off.
#
# The wait is the interesting half. `docker compose up -d --wait` blocks on the
# healthchecks. Under Quadlet the same guarantee is structural rather than a
# flag: `Notify=healthy` makes each unit Type=notify, so `systemctl start` does
# not return until podman reports the container healthy — and the generator's
# `TimeoutStartSec=`, derived from the service's own health numbers, is what
# bounds it. Without that key systemd's 90s default would cap a keycloak start
# that its own configuration allows 330s for, and the unit would be killed
# mid-start and restarted forever (PR #1933, finding 2).
#
# A daemon-reload comes first because the unit files may have just been replaced.
# -----------------------------------------------------------------------------
rt_apply() {
  case "${RT_BACKEND}" in
    docker)
      docker compose -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        --profile "${RT_PROFILE:-prod}" up -d --wait "$@"
      ;;
    podman)
      ${RT_SYSTEMCTL} daemon-reload || return 1
      local svc rc=0
      for svc in "$@"; do
        ${RT_SYSTEMCTL} start "${svc}.service" || rc=1
      done
      return "${rc}"
      ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_pull_refs <image-reference>...
#
# Pre-pull specific images by reference. Deliberately by REFERENCE and not by
# service: the point is to pull only what this deploy moves. The third-party
# infra images are pinned by digest and change only on a deliberate config edit,
# and pulling them here would make every deploy hostage to a transient outage of
# a registry this project does not control — a quay.io 502 on the Keycloak
# manifest aborting the run before `up` ever gets to reuse the image that is
# already on disk.
#
# Compose can express that with a service list; podman takes the references
# straight. A failed pull is NOT fatal here for the same reason: the apply below
# pulls anything genuinely missing, so a transient failure costs a slower apply
# rather than a failed deploy.
# -----------------------------------------------------------------------------
rt_pull_refs() {
  local ref rc=0
  case "${RT_BACKEND}" in
    docker)
      docker compose -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        ${RT_PIN_FILE:+-f "${RT_PIN_FILE}"} \
        --profile "${RT_PROFILE:-prod}" pull --quiet "$@" || rc=1
      ;;
    podman)
      for ref in "$@"; do
        ${RT_CLI} pull --quiet "${ref}" >/dev/null 2>&1 || rc=1
      done
      ;;
  esac
  return "${rc}"
}

# -----------------------------------------------------------------------------
# rt_apply_stack [service]...
#
# The release apply: bring the stack to the pinned digests and WAIT, bounded by
# RT_HEALTH_TIMEOUT. With no arguments it applies the whole stack.
#
# The two shapes differ in where the pin lives, not in what is guaranteed.
# Compose gets the pin as a second `-f` override; under Quadlet the pin is
# already a drop-in on disk (see rt_pin_write), so the unit files ARE the pinned
# state and a daemon-reload is what picks them up.
#
# `--remove-orphans` has no Quadlet analogue and needs none: a retired service
# leaves no container behind once its unit is gone, because Quadlet only starts
# what has a unit file.
#
# The wait: compose blocks on `--wait --wait-timeout`; systemd blocks because
# `Notify=healthy` makes each unit Type=notify, bounded by the generated
# `TimeoutStartSec=`. Passing RT_HEALTH_TIMEOUT to systemd would fight that
# value, which is derived per service from its own health numbers, so it is
# deliberately not forwarded.
# -----------------------------------------------------------------------------
rt_apply_stack() {
  case "${RT_BACKEND}" in
    docker)
      docker compose \
        -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        ${RT_PIN_FILE:+-f "${RT_PIN_FILE}"} \
        --profile "${RT_PROFILE:-prod}" \
        up -d --no-build --remove-orphans \
           --wait --wait-timeout "${RT_HEALTH_TIMEOUT:-180}" "$@"
      ;;
    podman)
      ${RT_SYSTEMCTL} daemon-reload || return 1
      local svc rc=0
      if [[ $# -eq 0 ]]; then
        # "the whole stack" has to be named under Quadlet: there is no project to
        # ask, so the caller supplies the list. Split explicitly into an array
        # rather than relying on an unquoted expansion to do it.
        local -a svcs=()
        read -ra svcs <<< "${RT_STACK_SERVICES:?RT_STACK_SERVICES is unset and no services were named}"
        set -- "${svcs[@]}"
      fi
      for svc in "$@"; do
        ${RT_SYSTEMCTL} start "${svc}.service" || rc=1
      done
      return "${rc}"
      ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_recreate <service>
#
# Replace one service's container and wait for it to be healthy, without
# touching its dependencies. This is the Keycloak provider-JAR path: the JAR is
# staged on the host and only `kc.sh start` re-running the provider build picks
# it up, so the container has to be recreated rather than restarted in place.
#
# Under Quadlet a restart IS a recreate: the generated ExecStart carries
# `--replace --rm`, so the old container is removed and a new one is created from
# the current unit on every start.
# -----------------------------------------------------------------------------
rt_recreate() {
  case "${RT_BACKEND}" in
    docker)
      docker compose \
        -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        ${RT_PIN_FILE:+-f "${RT_PIN_FILE}"} \
        --profile "${RT_PROFILE:-prod}" \
        up -d --no-deps --force-recreate \
           --wait --wait-timeout "${RT_HEALTH_TIMEOUT:-180}" "$1"
      ;;
    podman)
      ${RT_SYSTEMCTL} daemon-reload || return 1
      ${RT_SYSTEMCTL} restart "$1.service"
      ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_restart <service>
#
# The targeted restart for runtime-health drift: the right release, a sick
# container. Never a release rollback (ADR-0083).
# -----------------------------------------------------------------------------
rt_restart() {
  case "${RT_BACKEND}" in
    docker)
      docker compose -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        --profile "${RT_PROFILE:-prod}" restart "$1"
      ;;
    podman)
      ${RT_SYSTEMCTL} restart "$1.service"
      ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_exec <container> <command>...
# -----------------------------------------------------------------------------
rt_exec() {
  local c="$1"; shift
  ${RT_CLI} exec "${c}" "$@"
}

# -----------------------------------------------------------------------------
# rt_extract_from_image <image-ref> <path-in-image> <destination-on-host> [command]
#
# Lift one file out of an image without running it — how the Keycloak provider
# JAR and the config bundle are staged. create/cp/rm is identical in both CLIs;
# the container is removed even when the copy fails, so a failed deploy does not
# leave a created-but-never-started container behind on every tick.
#
# The optional command matters even though it never runs: `create` refuses an
# image declaring neither CMD nor ENTRYPOINT with "no command specified", and the
# bundle images are exactly that — a filesystem with no process. Both CLIs accept
# an argument they will never execute.
# -----------------------------------------------------------------------------
rt_extract_from_image() {
  local ref="$1" src="$2" dst="$3" cmd="${4:-}" cid rc=0
  if [[ -n "${cmd}" ]]; then
    cid="$(${RT_CLI} create "${ref}" "${cmd}" 2>/dev/null)" || return 1
  else
    cid="$(${RT_CLI} create "${ref}" 2>/dev/null)" || return 1
  fi
  ${RT_CLI} cp "${cid}:${src}" "${dst}" || rc=1
  ${RT_CLI} rm -f "${cid}" >/dev/null 2>&1 || true
  return "${rc}"
}

# -----------------------------------------------------------------------------
# rt_pin_path <service>
# rt_pin_write <service> <image-ref-with-digest>
# rt_pin_clear <service>
#
# The digest pin: what stops a tag flip in the registry from moving the running
# stack underneath us between ticks.
#
# Docker keeps it as a compose override file listing every service's image.
# Quadlet has no override file, so the analogue is a systemd DROP-IN beside the
# unit. Measured on podman 5.8.2 / Rocky 10, 2026-09-18: `Image=` in a
# `<unit>.container.d/*.conf` REPLACES the base unit's value — one reference
# reaches the generated ExecStart, with no warning — while list keys such as
# `AddHost=` append. That is exactly the semantics the pin needs, and it means
# the host-specific alias drop-ins already on the host keep working beside it.
#
# The file is numbered `10-` so an operator drop-in with a higher number can
# still win, which is the systemd convention and the escape hatch for a host that
# must pin something by hand.
# -----------------------------------------------------------------------------
rt_pin_path() {
  printf '%s/%s.container.d/10-digest-pin.conf' "${RT_UNIT_DIR:?RT_UNIT_DIR is unset}" "$1"
}

rt_pin_write() {
  local svc="$1" ref="$2" path
  path="$(rt_pin_path "${svc}")"
  mkdir -p "$(dirname "${path}")"
  printf '# Written by deploy.sh. The digest this release pinned; do not edit.\n[Container]\nImage=%s\n' \
    "${ref}" > "${path}"
}

rt_pin_clear() {
  rm -f "$(rt_pin_path "$1")"
}

# -----------------------------------------------------------------------------
# rt_pin_record_pairs <record-file>
#
# Read a pin RECORD back into `service=reference` lines.
#
# The record is the compose override this deployer has always written, and it
# stays the single source of truth under both runtimes: a small YAML file under
# the state directory naming three services and their digests. This function
# round-trips OUR OWN generated output — a fixed, machine-written shape — and is
# deliberately not a YAML parser.
# -----------------------------------------------------------------------------
rt_pin_record_pairs() {
  awk '
    /^  [a-z][a-z0-9-]*:[[:space:]]*$/ { svc = $1; sub(/:$/, "", svc); next }
    /^    image:[[:space:]]/          { if (svc != "") print svc "=" $2 }
  ' "$1" 2>/dev/null
}

# -----------------------------------------------------------------------------
# rt_pin_apply <service>=<reference>...
#
# Write the pin: the record, and — under Quadlet — the drop-ins that actually
# bind it.
#
# Both halves matter and for different reasons. Compose reads the record
# directly as a second `-f` override, so under Docker the record IS the pin.
# Quadlet reads unit files, so the record alone would pin nothing; the drop-ins
# are the binding and the record is what makes a ROLLBACK possible, because it
# is the only place the previous digests survive once the drop-ins have been
# overwritten.
# -----------------------------------------------------------------------------
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

  [[ "${RT_BACKEND}" == podman ]] || return 0
  for pair in "$@"; do
    svc="${pair%%=*}"; ref="${pair#*=}"
    rt_pin_write "${svc}" "${ref}"
  done
}

# -----------------------------------------------------------------------------
# rt_pin_save
# rt_pin_rollback
#
# The rollback anchor. `rt_pin_save` snapshots the live record before it is
# overwritten; `rt_pin_rollback` puts it back — and under Quadlet re-materialises
# the drop-ins from it, which is the half that would otherwise be missed.
#
# Copying the record alone and calling it a rollback is the trap: under Podman
# the running stack is bound by the DROP-INS, so restoring only the record would
# leave the new digests in place and the "rollback" would silently roll forward
# into the very release whose health check had just failed.
# -----------------------------------------------------------------------------
rt_pin_save() {
  [[ -f "${RT_PIN_FILE:?RT_PIN_FILE is unset}" ]] || return 0
  cp "${RT_PIN_FILE}" "${RT_PIN_FILE_PREVIOUS:?RT_PIN_FILE_PREVIOUS is unset}"
}

rt_pin_rollback() {
  [[ -f "${RT_PIN_FILE_PREVIOUS:?RT_PIN_FILE_PREVIOUS is unset}" ]] || return 1
  cp "${RT_PIN_FILE_PREVIOUS}" "${RT_PIN_FILE:?RT_PIN_FILE is unset}"

  [[ "${RT_BACKEND}" == podman ]] || return 0
  local pair
  while IFS= read -r pair; do
    [[ -n "${pair}" ]] || continue
    rt_pin_write "${pair%%=*}" "${pair#*=}"
  done < <(rt_pin_record_pairs "${RT_PIN_FILE}")
}

# -----------------------------------------------------------------------------
# rt_prune
#
# Reclaim dangling images and unused networks. Deliberately NOT `system prune`:
# that reaches volumes, and this stack's volumes hold the databases.
# -----------------------------------------------------------------------------
rt_prune_images() {
  # `until=` keeps the images this deploy just pulled, which are the ones a
  # rollback still needs. Best-effort: a stuck container reference can block a
  # prune transiently, and that must not fail a deploy.
  ${RT_CLI} image prune --force --filter "until=${1:-720h}" >/dev/null 2>&1 || true
}

rt_prune_networks() {
  ${RT_CLI} network prune -f >/dev/null 2>&1 || true
}

rt_prune() {
  rt_prune_images
  rt_prune_networks
}

# -----------------------------------------------------------------------------
# rt_is_running <container-name>
#
# Whether a container of that exact name is running. Exact, not a substring: the
# session that wrote this had `keystore.p1` match inside `keystore.p12` once, and
# a prefix match here would report a stopped `edge` as running because
# `edge-acme` was up.
# -----------------------------------------------------------------------------
rt_is_running() {
  # Single quotes on purpose: the braces are a Go template the CLI expands, not a
  # shell expression. Double quotes would let the shell eat it and send an empty
  # format string, which prints every container's id and makes the exact match
  # below never fire.
  ${RT_CLI} ps --format '{{.Names}}' 2>/dev/null | grep -qx -- "$1"
}
