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
# rt_prune
#
# Reclaim dangling images and unused networks. Deliberately NOT `system prune`:
# that reaches volumes, and this stack's volumes hold the databases.
# -----------------------------------------------------------------------------
rt_prune() {
  ${RT_CLI} image prune -f >/dev/null 2>&1 || true
  ${RT_CLI} network prune -f >/dev/null 2>&1 || true
}
