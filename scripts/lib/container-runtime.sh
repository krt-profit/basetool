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

# Where systemd records which users may run services without a login session. A
# variable, not a literal, only so the self-test can point detection at a fixture
# -- on a host it is always this path.
RT_LINGER_DIR="${RT_LINGER_DIR:-/var/lib/systemd/linger}"

# The services whose DEFINITION this run changed -- a new digest pin, or a unit file the release
# replaced. Space separated, appended to by rt_pin_write and install_quadlet_units, and read by
# rt_apply_stack to decide `restart` instead of `start`.
#
# WHY IT HAS TO EXIST. `systemctl start` on a unit that is ALREADY ACTIVE is a no-op: it returns 0
# immediately and does not re-read anything. Measured on the testing host 2026-09-18 -- the pin was
# changed to a different image, daemon-reload run, `systemctl start backend.service` returned 0,
# and the container went on running the PREVIOUS image while the drop-in on disk named the new one.
# Every deploy after the first would have reported "deploy successful" and changed nothing: the
# 2026-07-02 incident's exact shape, produced by the deployer rather than by a manual `up`.
#
# Compose has no equivalent because `up -d` recreates a container whose definition changed and
# leaves the rest alone. Under Quadlet that comparison is ours to make.
RT_CHANGED_SERVICES="${RT_CHANGED_SERVICES:-}"

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
    #
    # "Can I run podman" is NOT "do I own the containers", and the first version of
    # this asked the wrong one. EVERY account with a podman binary can run
    # `podman ps` against its own empty store, including the deploy account that
    # runs this script. Measured on the testing host 2026-09-18: deploy passed the
    # test, detection stopped here, RT_CLI became a bare `podman` pointed at
    # deploy's own store, and RT_UNIT_DIR became
    # /var/lib/iri/.config/containers/systemd -- the deploy account's HOME -- so
    # the deployer went looking for the stack's units in a directory that has
    # never held one and aborted with "no Quadlet unit directory".
    #
    # LINGERING is the qualifying signal, and it is the same one the bridge below
    # already uses: "this user runs services without a login session" is exactly
    # the declaration that it owns a rootless stack. An account that merely has
    # podman does not make that claim. Checking it first also costs nothing on the
    # host where this branch IS right -- an operator running the script as the
    # service user, which lingers.
    #
    # The name is captured and CHECKED before it is used in a path. Inlining
    # `$(id -un)` looked tidier and was wrong: when id fails the test becomes
    # `[[ -e "${RT_LINGER_DIR}/" ]]`, which is TRUE for any directory that
    # exists -- so the branch this guard was added to prevent would fire on
    # every host where the name could not be read, which is precisely where
    # least is known. Found by the self-test, whose staged PATH cannot run id.
    local me
    me="$(id -un 2>/dev/null || true)"
    if [[ -n "${me}" && -e "${RT_LINGER_DIR}/${me}" ]] \
       && podman ps --format '{{.Names}}' >/dev/null 2>&1; then
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
    for lingerfile in "${RT_LINGER_DIR}"/*; do
      [[ -e "${lingerfile}" ]] || continue
      u="$(basename "${lingerfile}")"
      uid="$(id -u "${u}" 2>/dev/null)" || continue
      # podman is invoked with NO environment and systemctl WITH it, and the
      # asymmetry is measured rather than stylistic (2026-09-18, Rocky 10.2):
      # podman finds /run/user/<uid>/containers by itself, while systemctl --user
      # without XDG_RUNTIME_DIR reports "$DBUS_SESSION_BUS_ADDRESS and
      # $XDG_RUNTIME_DIR not defined" and never reaches the user manager.
      #
      # It matters because a correctly tightened sudoers refuses a command-line
      # variable unless the command carries SETENV. Passing it to podman made the
      # detection fail with "you are not allowed to set the following environment
      # variables" on the one host whose sudo rule was written properly -- so the
      # wider grant would have been needed for the command that does not need it.
      if sudo -n -u "${u}" podman ps --format '{{.Names}}' >/dev/null 2>&1; then
        RT_CLI="sudo -n -u ${u} podman"
        RT_SYSTEMCTL="sudo -n -u ${u} XDG_RUNTIME_DIR=/run/user/${uid} systemctl --user"
        # NOT the owning user's home. A deploy account cannot even TRAVERSE it:
        # measured on the testing host 2026-09-18, /home/iri is 0750 iri:iri, so
        # `[[ -d ~iri/.config/containers/systemd ]]` is false for the account that
        # has to install units there, and the pre-flight aborted with "no Quadlet
        # unit directory" pointing at a directory holding 39 units.
        #
        # podman-systemd.unit(5) as shipped by podman 5.8.2 lists four rootless
        # search paths, and one of them is exactly this case -- a system location
        # for one user's units:
        #
        #     $XDG_RUNTIME_DIR/containers/systemd/
        #     $XDG_CONFIG_HOME/containers/systemd/  or  ~/.config/containers/systemd/
        #     /etc/containers/systemd/users/$(UID)
        #     /etc/containers/systemd/users/
        #
        # The third is owned by the deploy account (ansible role, 30-directories)
        # and read by the service user's Quadlet generator on daemon-reload. It
        # needs no privilege beyond the account's own, and it keeps the deployer
        # out of the service user's home entirely.
        #
        # PRECEDENCE MATTERS AT CUTOVER: the home directory is searched FIRST, so a
        # unit of the same name left in ~/.config/containers/systemd SHADOWS the
        # delivered one, silently and permanently. A host that was brought up by
        # hand has to have those removed once delivery is in place.
        RT_UNIT_DIR="${RT_UNIT_DIR:-/etc/containers/systemd/users/${uid}}"
        return 0
      fi
    done
    rt_die "podman is installed but no lingering user could be found that owns the containers (looked in ${RT_LINGER_DIR})"
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
  ${RT_CLI} login "${registry}" --username "${user}" --password-stdin < "${pwfile}" || return 1

  # TWO identities, and that is not a duplicate call. Under Podman the images are pulled by the
  # SERVICE USER (RT_CLI is `sudo -u <svc> podman`), while the tag is resolved by `skopeo` and the
  # signature checked by `cosign` -- both of which run as the DEPLOY account, out of its own
  # credential store. Logging in only through RT_CLI leaves that store empty, and the run dies at
  #
  #     FATAL: cannot resolve ghcr.io/krt-profit/basetool-backend:stable (tag missing or no GHCR access)
  #
  # which reads exactly like an expired token. Measured on the testing host 2026-09-18, where the
  # same tag resolved perfectly as the service user and not at all as the deployer.
  #
  # REGISTRY_AUTH_FILE (honoured by the containers/image library that skopeo and podman share) is
  # pointed at the Docker-style config.json that cosign reads, so one file serves all three tools.
  # Under Docker RT_CLI is already `docker` and this second call is the same login again, which is
  # idempotent and costs one request.
  [[ "${RT_BACKEND}" == podman ]] || return 0
  podman login "${registry}" --username "${user}" --password-stdin < "${pwfile}"
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
# rt_pull <service>=<reference>...
#
# Pre-pull the images this release moves, named as `service=reference` pairs.
#
# Only the images this deploy moves: the third-party infra images are pinned by
# digest and change only on a deliberate config edit, and pulling them here would
# make every deploy hostage to a transient outage of a registry this project does
# not control — a quay.io 502 on the Keycloak manifest aborting the run before
# the apply ever gets to reuse the image that is already on disk.
#
# WHY A PAIR, when each runtime only needs one half of it. Because the two halves
# are not interchangeable and an earlier version of this function let them look
# as if they were: it took REFERENCES, its single call site passed SERVICE NAMES,
# and both were right for Docker. `docker compose pull backend` resolves the name
# through the compose file to that service's pinned image. Podman has no compose
# file and no project, so the same argument became `podman pull backend` — a bare
# name resolved against the host's unqualified-search registries (on Rocky:
# registry.access.redhat.com, registry.redhat.io, docker.io), which fails, or
# succeeds against a stranger's image of the same name. Measured 2026-09-18 in
# scripts/deploy.test.sh: the run aborted at "pulling images" and never reached
# the apply, because the call site runs under `set -e`. Taking the pair makes
# that mismatch unrepresentable — neither arm has to infer the other's half.
#
# A failed pull IS fatal, by way of that same `set -e` at the call site, and that
# is deliberate: these three images ARE the release. What must not be fatal is a
# third-party registry hiccup, and that is handled by not pulling infra here at
# all rather than by swallowing errors.
# -----------------------------------------------------------------------------
rt_pull() {
  local pair rc=0
  case "${RT_BACKEND}" in
    docker)
      local -a svcs=()
      for pair in "$@"; do svcs+=("${pair%%=*}"); done
      docker compose -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        ${RT_PIN_FILE:+-f "${RT_PIN_FILE}"} \
        --profile "${RT_PROFILE:-prod}" pull --quiet "${svcs[@]}" || rc=1
      ;;
    podman)
      # Every one is attempted even after the first failure, so the journal names
      # each image that could not be fetched instead of only the earliest.
      for pair in "$@"; do
        ${RT_CLI} pull --quiet "${pair#*=}" >/dev/null 2>&1 || rc=1
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
        # RESTART what this run re-defined, START what it did not. `start` on an already-active
        # unit returns 0 without re-reading anything, so a changed pin would never reach the
        # running container -- see RT_CHANGED_SERVICES at the top of this file for the measurement.
        # A restart IS a recreate here: the generated ExecStart carries --replace, so the old
        # container goes and a new one is created from the current unit.
        #
        # Everything else is left alone deliberately. Restarting the whole stack on every deploy
        # would take the databases down for a change that never touched them.
        case " ${RT_CHANGED_SERVICES} " in
          *" ${svc} "*) ${RT_SYSTEMCTL} restart "${svc}.service" || rc=1 ;;
          *)            ${RT_SYSTEMCTL} start   "${svc}.service" || rc=1 ;;
        esac
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
  case "${RT_BACKEND}" in
    docker)
      # The daemon runs as root and writes the destination itself, which is what the production
      # deployment has always done.
      ${RT_CLI} cp "${cid}:${src}" "${dst}" || rc=1
      ;;
    podman)
      # A TAR STREAM, not a direct copy, and the difference is the account doing the writing.
      # RT_CLI here is `sudo -u <service user> podman`, so a plain `cp` has the SERVICE USER write
      # into the DEPLOY account's staging directory, and podman says so at length:
      #
      #     copier: put: error creating "/docker": mkdir /docker: permission denied
      #
      # Measured on the testing host 2026-09-18, immediately after the signatures verified.
      # `cp <cid>:<src> -` writes a tar archive to stdout instead; the pipe crosses the account
      # boundary and the extraction happens as the caller, into its own directory. Same mechanism
      # rt_read_mount already uses to read a volume out for the backup.
      #
      # The destination is a DIRECTORY for the config bundle (`/config/.` -> a tree) and a FILE for
      # the Keycloak provider JAR (one member). A tar stream has to be unpacked differently for the
      # two, and treating the second as the first is how the first version of this failed:
      #
      #     tar: /var/lib/iri/keycloak-spi-stage.jar: Cannot open: No such file or directory
      #
      # `-O` writes the member to stdout instead of to a path, which is exactly the single-file
      # case. The trailing slash the caller already uses for a tree is what tells them apart, and
      # an existing directory is honoured too so a caller that omits it still works.
      if [[ "${dst}" == */ || -d "${dst}" ]]; then
        ${RT_CLI} cp "${cid}:${src}" - 2>/dev/null | tar -xf - -C "${dst%/}" || rc=1
      else
        ${RT_CLI} cp "${cid}:${src}" - 2>/dev/null | tar -xOf - > "${dst}" || rc=1
      fi
      ;;
  esac
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
  local svc="$1" ref="$2" path body
  path="$(rt_pin_path "${svc}")"
  mkdir -p "$(dirname "${path}")"
  body="$(printf '# Written by deploy.sh. The digest this release pinned; do not edit.\n[Container]\nImage=%s\n' "${ref}")"
  # Only a real change counts. Rewriting the identical file on every tick and then restarting the
  # service for it would turn an idempotent deploy into a rolling restart of the whole stack.
  if [[ ! -f "${path}" ]] || [[ "$(cat "${path}")" != "${body}" ]]; then
    printf '%s\n' "${body}" > "${path}"
    rt_note_changed "${svc}"
  fi
}

# Record a service whose definition moved, once.
rt_note_changed() {
  local svc="$1"
  case " ${RT_CHANGED_SERVICES} " in
    *" ${svc} "*) return 0 ;;
  esac
  RT_CHANGED_SERVICES="${RT_CHANGED_SERVICES}${RT_CHANGED_SERVICES:+ }${svc}"
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

# =============================================================================
# The monitoring plane
#
# Under Compose it is a SECOND project (`-p iri-monitoring`) with its own file,
# deliberately separate so the app stack can be recreated without taking the
# observability with it. Under Quadlet there are no projects: the nine
# monitoring units sit in the same directory as the eight application ones and
# are told apart by name, which RT_MONITORING_SERVICES holds.
#
# Every one of these is best-effort at the call site — the deploy is not gated on
# the monitoring plane, because an observability failure must not stop a release
# that is otherwise healthy.
# =============================================================================

rt_monitoring_configured() {
  case "${RT_BACKEND}" in
    docker)  [[ -f "${RT_MONITORING_FILE:-}" ]] ;;
    podman)  [[ -n "${RT_MONITORING_SERVICES:-}" ]] ;;
  esac
}

rt_monitoring_up() {
  case "${RT_BACKEND}" in
    docker)
      docker compose -p iri-monitoring --project-directory "${RT_PROJECT_DIR:?RT_PROJECT_DIR is unset}" \
        -f "${RT_MONITORING_FILE:?RT_MONITORING_FILE is unset}" up -d "$@"
      ;;
    podman)
      ${RT_SYSTEMCTL} daemon-reload || return 1
      local svc rc=0
      if [[ $# -eq 0 ]]; then
        local -a msvcs=()
        read -ra msvcs <<< "${RT_MONITORING_SERVICES:?RT_MONITORING_SERVICES is unset}"
        set -- "${msvcs[@]}"
      fi
      for svc in "$@"; do
        ${RT_SYSTEMCTL} start "${svc}.service" || rc=1
      done
      return "${rc}"
      ;;
  esac
}

# Take the monitoring plane down. Compose needs this before a network-topology
# recreate because the monitoring project holds the shared data networks as
# `external`, and a bridge with an endpoint still attached cannot be removed.
rt_monitoring_down() {
  case "${RT_BACKEND}" in
    docker)
      docker compose -p iri-monitoring --project-directory "${RT_PROJECT_DIR:?RT_PROJECT_DIR is unset}" \
        -f "${RT_MONITORING_FILE:?RT_MONITORING_FILE is unset}" down --remove-orphans
      ;;
    podman)
      local svc rc=0
      local -a msvcs=()
      read -ra msvcs <<< "${RT_MONITORING_SERVICES:?RT_MONITORING_SERVICES is unset}"
      for svc in "${msvcs[@]}"; do
        ${RT_SYSTEMCTL} stop "${svc}.service" || rc=1
      done
      return "${rc}"
      ;;
  esac
}

rt_monitoring_is_running() {
  case "${RT_BACKEND}" in
    docker)
      # The project label is how compose itself identifies its containers.
      [[ -n "$(docker ps --filter "label=com.docker.compose.project=iri-monitoring" \
                 --format '{{.Names}}' 2>/dev/null)" ]]
      ;;
    podman)
      local svc
      local -a msvcs=()
      read -ra msvcs <<< "${RT_MONITORING_SERVICES:?RT_MONITORING_SERVICES is unset}"
      for svc in "${msvcs[@]}"; do
        rt_is_running "${svc}" && return 0
      done
      return 1
      ;;
  esac
}

# Replace ONE monitoring container so it re-resolves its bind-mount inode and
# re-reads a changed config file. Not health-gated and never gating: a failed
# monitoring recreate re-drifts on the next tick rather than failing a release.
rt_monitoring_recreate() {
  case "${RT_BACKEND}" in
    docker)
      docker compose -p iri-monitoring --project-directory "${RT_PROJECT_DIR:?RT_PROJECT_DIR is unset}" \
        -f "${RT_MONITORING_FILE:?RT_MONITORING_FILE is unset}" \
        up -d --force-recreate --no-deps "$1"
      ;;
    podman)
      ${RT_SYSTEMCTL} restart "$1.service"
      ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_stack_down
#
# Take the APPLICATION stack down. Only used for a network-topology change, which
# cannot be applied in place: the bridges have to be removed and recreated on the
# new subnets.
# -----------------------------------------------------------------------------
rt_stack_down() {
  case "${RT_BACKEND}" in
    docker)
      docker compose -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        --profile "${RT_PROFILE:-prod}" down --remove-orphans
      ;;
    podman)
      local svc rc=0
      local -a svcs=()
      read -ra svcs <<< "${RT_STACK_SERVICES:?RT_STACK_SERVICES is unset}"
      # Reverse order, so a dependent stops before what it depends on.
      local i
      for (( i=${#svcs[@]}-1; i>=0; i-- )); do
        svc="${svcs[i]}"
        ${RT_SYSTEMCTL} stop "${svc}.service" || rc=1
      done
      return "${rc}"
      ;;
  esac
}

# =============================================================================
# Reading state out, for the backup
# =============================================================================

# -----------------------------------------------------------------------------
# rt_read_mount <source> <helper-image> <command>...
#
# Run a throwaway helper with <source> mounted read-only at /src and stream its
# stdout. <source> may be a host path OR a named volume — both CLIs accept
# either in the same position, which is what lets one primitive serve both.
#
# The helper exists because the backup runs as an unprivileged user and most of
# what it must read is root-owned: the keystore is 0640 (REQ-OPS-016), and the
# edge's TLS material lives in named volumes whose contents that user cannot open
# directly. A plain `cp` EACCESes and, under `set -e`, takes the whole run with
# it — the 2026-07-06 regression, where tightening the keystore mode silently
# killed the off-site backup, database dumps included.
#
# Under rootless Podman a named volume belongs to the service user's own store,
# which RT_CLI already carries the privilege prefix for.
# -----------------------------------------------------------------------------
rt_read_mount() {
  local src="$1" image="$2"
  shift 2
  ${RT_CLI} run --rm -v "${src}:/src:ro" "${image}" "$@"
}

# -----------------------------------------------------------------------------
# rt_volume_exists <name>
#
# Whether a named volume exists at all. The backup uses it to tell "this
# deployment has no such volume" from "the volume is there and could not be
# read" — the second is a failure, the first is a host that legitimately does
# not run that service.
# -----------------------------------------------------------------------------
rt_volume_exists() {
  ${RT_CLI} volume inspect "$1" >/dev/null 2>&1
}

rt_network_exists() {
  ${RT_CLI} network inspect "$1" >/dev/null 2>&1
}

# -----------------------------------------------------------------------------
# rt_run_on_network <network> <image> <command>...
#
# Run a throwaway container attached to one internal network. The weekly
# Prometheus TSDB snapshot needs it: the admin API is reachable only from inside
# the monitoring plane, because the host publishes no Prometheus port by design.
# -----------------------------------------------------------------------------
rt_run_on_network() {
  local net="$1" image="$2"
  shift 2
  ${RT_CLI} run --rm --network "${net}" "${image}" "$@"
}

# =============================================================================
# The throwaway container the restore drill proves recoverability in
# =============================================================================

# rt_rm_force <name> — remove a container whatever state it is in, quietly.
rt_rm_force() {
  ${RT_CLI} rm -f "$1" >/dev/null 2>&1 || true
}

# rt_run_detached <name> <image> [--env K=V]... — start a detached container.
#
# Deliberately NOT on any of the deployment's networks: the drill must prove the
# dumps restore, and a throwaway Postgres that can reach the live stack is a
# throwaway Postgres that can be mistaken for it.
rt_run_detached() {
  local name="$1" image="$2"
  shift 2
  ${RT_CLI} run -d --name "${name}" "$@" "${image}" >/dev/null
}

# rt_cp_to <source-on-host> <container> <destination-in-container>
rt_cp_to() {
  case "${RT_BACKEND}" in
    docker)
      docker cp "$1" "$2:$3"
      ;;
    podman)
      # STREAMED IN, not copied, and for the mirror image of the reason rt_extract_from_image
      # streams OUT. RT_CLI is `sudo -u <service user> podman`, so a plain `cp` has the service
      # user READ a file that belongs to the deploy account -- and the restore drill's working tree
      # is deploy-owned 0700 by design, because it holds restored database dumps:
      #
      #     Error: ".../krt_basetool.dump" could not be found on the host: ... permission denied
      #
      # Measured on the testing host 2026-09-20. `cp -` reads a tar from stdin, so the CALLER reads
      # the file and the pipe crosses the account boundary. The archive is built here rather than
      # by the caller so the member lands at the requested name inside the container.
      local src="$1" ctr="$2" dst="$3"
      tar -C "$(dirname "${src}")" -cf - "$(basename "${src}")"         | ${RT_CLI} cp - "${ctr}:$(dirname "${dst}")" || return 1
      # tar preserves the SOURCE basename; rename inside the container when the caller asked for
      # a different one, so the contract stays "this file, at this path".
      if [[ "$(basename "${src}")" != "$(basename "${dst}")" ]]; then
        ${RT_CLI} exec "${ctr}" mv "$(dirname "${dst}")/$(basename "${src}")" "${dst}" || return 1
      fi
      ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_service_stop <service>...
# rt_service_start <service>...
#
# The backup quiesce: stop the writers for the DUMP only, then start them again
# before the slow upload, so the user-facing window is the dump and never the
# transfer (REQ-OPS-009).
#
# Deliberately NOT rt_apply_stack: that waits for health and applies the pin,
# which is a release operation. This is a pause, and it must come back exactly as
# it was.
# -----------------------------------------------------------------------------
rt_service_stop() {
  case "${RT_BACKEND}" in
    docker)
      docker compose -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        --profile "${RT_PROFILE:-prod}" stop -t "${RT_STOP_TIMEOUT:-30}" "$@"
      ;;
    podman)
      local svc rc=0
      for svc in "$@"; do ${RT_SYSTEMCTL} stop "${svc}.service" || rc=1; done
      return "${rc}"
      ;;
  esac
}

rt_service_start() {
  case "${RT_BACKEND}" in
    docker)
      docker compose -f "${RT_COMPOSE_FILE:?RT_COMPOSE_FILE is unset}" \
        --profile "${RT_PROFILE:-prod}" start "$@"
      ;;
    podman)
      local svc rc=0
      for svc in "$@"; do ${RT_SYSTEMCTL} start "${svc}.service" || rc=1; done
      return "${rc}"
      ;;
  esac
}

# -----------------------------------------------------------------------------
# rt_prune
#
# Reclaim dangling images and unused networks. Deliberately NOT `system prune`:
# that reaches volumes, and this stack's volumes hold the databases.
# -----------------------------------------------------------------------------
# rt_prune_images <age>   e.g. `rt_prune_images 720h`
#
# The age is REQUIRED rather than defaulted, so every call site states how much
# history it is willing to lose. `until=` is what keeps the images this deploy
# just pulled — the ones a rollback still needs — and a default would let a new
# call site drop that protection without saying so.
#
# Best-effort: a stuck container reference can block a prune transiently, and
# that must not fail a deploy.
rt_prune_images() {
  ${RT_CLI} image prune --force --filter "until=$1" >/dev/null 2>&1 || true
}

rt_prune_networks() {
  ${RT_CLI} network prune -f >/dev/null 2>&1 || true
}

rt_prune() {
  rt_prune_images 720h
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
