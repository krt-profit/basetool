# shellcheck shell=bash
# =============================================================================
# The container-runtime seam (ADR-0163, Phase 3 of docs/archive/PODMAN_MIGRATION_PLAN.md).
#
# Every container operation the operational scripts perform -- deploy.sh,
# backup.sh, restore-drill.sh, container-cleanup.sh -- is named once here. A stack
# is a set of systemd units that Quadlet generates from `.container` files, and
# "bring it up and wait for health" is `systemctl --user start`, which blocks
# because `Notify=healthy` makes each unit `Type=notify`.
#
# ROOTLESS PODMAN ONLY, since 2026-09-22 (OPS-SIMP-01, ADR-0194 amended). This
# file was a seam with TWO implementations while production served on Docker and
# the testing host on Podman; each function carried a `docker)` arm beside the
# Podman one. The Docker host was retired at the cutover and is only kept, shut
# down, as a way back -- with its own copy of these scripts on its own disk, which
# a release never reaches. The migration plan's "no soak" ruling said the new host
# need not keep the old shape working, so the Docker arms were removed rather than
# left as nineteen untested branches that read as supported.
#
# Usage:
#
#     . "$(dirname "$0")/lib/container-runtime.sh"
#     rt_detect                      # finds the lingering service user, or dies
#     rt_apply backend frontend      # systemctl --user start, which waits for health
#
# Every function is prefixed `rt_`. Nothing here writes outside the paths its
# caller passes in, and nothing here reads a secret.
#
# TESTABILITY. `deploy.test.sh` and `container-runtime.test.sh` put fake `podman`,
# `skopeo` and `systemctl` binaries on PATH and assert on the recorded
# invocations. `RT_BACKEND=podman` can be preset, which skips the lingering-user
# probe on a machine that has no service user.
# =============================================================================

#: The runtime. Only `podman` exists; the variable stays because the scripts log it and a preset
#: value is how the self-tests skip the detection probe.
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

# A lingering user's $XDG_RUNTIME_DIR lives under here. A variable for the same reason as
# RT_LINGER_DIR: the self-test points it at a fixture. On a host it is always /run/user.
RT_RUNTIME_BASE="${RT_RUNTIME_BASE:-/run/user}"

# How long rt_detect waits for a lingering user's runtime to come up, and how long
# rt_wait_for_startup waits for that user's manager to finish starting. Seconds. See both
# functions for why they exist and why they are bounded.
RT_RUNTIME_WAIT="${RT_RUNTIME_WAIT:-120}"
RT_STARTUP_WAIT="${RT_STARTUP_WAIT:-600}"
RT_POLL_INTERVAL="${RT_POLL_INTERVAL:-2}"

# Seconds rt_detect actually spent waiting, so its refusal can say so. Not configuration.
RT_RUNTIME_WAITED=0

# Monitoring services that are HOST services under Podman rather than containers, so a reconcile has
# to restart the system unit instead of asking the service user's systemd about a unit it has never
# had.
#
# `generate-quadlet.py` translates node-exporter and alloy to "host-service": node-exporter mounts
# /run/systemd/private, which a rootless container cannot reach, and alloy carries group_add 4/473
# to read root:adm files, which a rootless container's NAMESPACE groups are not. Only alloy's
# CONFIGURATION rides the config bundle, so only alloy is ever reconciled -- the other two are
# configured by the Ansible role and never by a release.
#
# Measured on the testing host 2026-09-20: without this every deploy logged
#   monitoring: WARN recreate of alloy failed (non-gating; monitoring stack down?)
# and carried on. Non-gating, so nothing broke -- and an alloy config change therefore never
# reached the running alloy, on every single tick, behind a line that guessed "monitoring stack
# down?" about a host service that was running perfectly well.
RT_HOST_SERVICES="${RT_HOST_SERVICES:-alloy}"

# How to reach the SYSTEM manager for those. Set by rt_detect: a plain `systemctl` when this runs as
# root or as the service user, and a narrowly granted sudo for the deploy account -- see
# /etc/sudoers.d/basetool-deploy, which names that one unit with no wildcard.
RT_HOST_SYSTEMCTL="${RT_HOST_SYSTEMCTL:-}"

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
# Honours a pre-set RT_BACKEND=podman, which is how the self-tests skip the probe.
# -----------------------------------------------------------------------------
rt_detect() {
  if [[ -n "${RT_BACKEND}" ]]; then
    case "${RT_BACKEND}" in
      podman) RT_CLI="${RT_CLI:-podman}"; RT_SYSTEMCTL="${RT_SYSTEMCTL:-systemctl --user}" ;;
      *) rt_die "RT_BACKEND=${RT_BACKEND} is not podman -- the Docker runtime was retired on 2026-09-22" ;;
    esac
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
      RT_HOST_SYSTEMCTL="${RT_HOST_SYSTEMCTL:-systemctl}"
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
      rt_wait_for_user_runtime "${u}" "${uid}"
      if sudo -n -u "${u}" podman ps --format '{{.Names}}' >/dev/null 2>&1; then
        RT_CLI="sudo -n -u ${u} podman"
        RT_SYSTEMCTL="sudo -n -u ${u} XDG_RUNTIME_DIR=${RT_RUNTIME_BASE}/${uid} systemctl --user"
        # The SYSTEM manager, for RT_HOST_SERVICES. Running as root already has it; the deploy
        # account reaches it through one named sudoers entry per unit, and through nothing wider.
        RT_HOST_SYSTEMCTL="${RT_HOST_SYSTEMCTL:-sudo -n systemctl}"
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
    if (( RT_RUNTIME_WAITED > 0 )); then
      rt_die "podman is installed but no lingering user could be found that owns the containers (looked in ${RT_LINGER_DIR}, after waiting ${RT_RUNTIME_WAITED}s for a runtime directory under ${RT_RUNTIME_BASE} to appear)"
    fi
    rt_die "podman is installed but no lingering user could be found that owns the containers (looked in ${RT_LINGER_DIR})"
  fi

  rt_die "this host has no podman"
}

# -----------------------------------------------------------------------------
# rt_wait_for_user_runtime <user> <uid>
#
# Wait, bounded, for a lingering user's runtime to come up -- but ONLY when its
# runtime directory does not exist yet. That is the one shape of "podman ps
# failed" that is worth waiting for: the user has declared it runs services, and
# its manager simply has not started yet. Every other failure is a real answer
# and is returned at once, as before.
#
# WHY IT EXISTS. Measured on the production host 2026-09-22, the first reboot
# after the cutover:
#
#   16:25:51  boot
#   16:26:02  iri-backup, iri-container-cleanup and iri-restore-drill start --
#             their timers carry Persistent=true, so each catch-up run fires
#             at once -- and all three die here with "no lingering user could
#             be found"
#   16:26:03  user@994.service becomes active
#
# They lost the race by one second. A failed oneshot is not "one retry" -- it
# puts the unit into `failed`, SystemdUnitFailed pages CRITICAL, and it stays
# failed until the next scheduled run: the next night for the backup, the next
# WEEK for the other two. And the run that was lost is exactly the catch-up
# Persistent=true exists to provide.
#
# Only the directory is tested before sudo, because only the directory is
# visible to the account this runs as: /run/user is 0755, the directory under
# it is 0700 and owned by the service user, so the deploy account can stat it
# and cannot look inside.
#
# Returns 0 whatever happens. The caller's own `podman ps` decides.
# -----------------------------------------------------------------------------
rt_wait_for_user_runtime() {
  local u="$1" uid="$2" waited=0
  [[ -d "${RT_RUNTIME_BASE}/${uid}" ]] && return 0
  echo "container-runtime: ${u} lingers but ${RT_RUNTIME_BASE}/${uid} does not exist yet --" \
       "waiting up to ${RT_RUNTIME_WAIT}s for its manager to start" >&2
  while (( waited < RT_RUNTIME_WAIT )); do
    sleep "${RT_POLL_INTERVAL}"
    waited=$(( waited + RT_POLL_INTERVAL ))
    RT_RUNTIME_WAITED=${waited}
    if [[ -d "${RT_RUNTIME_BASE}/${uid}" ]] \
       && sudo -n -u "${u}" podman ps --format '{{.Names}}' >/dev/null 2>&1; then
      echo "container-runtime: ${u}'s runtime answered after ${waited}s" >&2
      return 0
    fi
  done
  return 0
}

# -----------------------------------------------------------------------------
# rt_wait_for_startup
#
# Wait, bounded, until the rootless user's systemd manager has FINISHED
# starting -- that is, until every container it brings up at boot is up, has
# failed, or has timed out. For a job that stops, dumps or prunes, "the runtime
# answers" is not enough: the manager answers within a second of boot and the
# stack is not up for another minute and a half.
#
# Measured on the same boot: user@994.service was ACTIVE at 16:26:03 -- its
# Type=notify-reload reports ready as soon as the manager runs -- while the
# manager itself logged "Startup finished in 1min 30.765s" at 16:27:33. Ordering
# the units after user@<uid>.service would therefore have fixed the detection and
# then let backup.sh QUIESCE the backend, frontend and ingest while they were
# still starting. That is why this is not a unit dependency: the signal that
# matters is the manager's own state, not whether its unit is up.
#
# `running` and `degraded` both mean startup is over -- degraded is merely that
# some unit failed, which podman's transient healthcheck units do routinely.
# `initializing`, `starting` and no answer at all mean wait. Anything else --
# `stopping` during a shutdown, `offline`, `maintenance` -- means this is not a
# moment to start a maintenance job, and it refuses.
#
# NOT called by deploy.sh, on purpose. A stack stuck in `starting` because a
# unit will not come up is exactly what a new release may be needed to fix, and
# a deployer that refused to act until startup finished could never deliver it.
# -----------------------------------------------------------------------------
rt_wait_for_startup() {
  local waited=0 state
  while :; do
    # `|| true` because a manager that is not answering yet makes systemctl exit
    # non-zero, and the callers run under `set -e`.
    state="$(${RT_SYSTEMCTL} is-system-running 2>/dev/null)" || true
    case "${state}" in
      running|degraded)
        (( waited > 0 )) && echo "container-runtime: the service user's manager is ${state} after ${waited}s" >&2
        return 0 ;;
      initializing|starting|"") ;;
      *) rt_die "the service user's manager reports '${state}' -- refusing to start a maintenance job now" ;;
    esac
    if (( waited >= RT_STARTUP_WAIT )); then
      rt_die "the service user's manager was still '${state:-not answering}' after ${RT_STARTUP_WAIT}s -- refusing to run against a stack that has not finished starting"
    fi
    (( waited == 0 )) && echo "container-runtime: the service user's manager is '${state:-not answering}' -- waiting up to ${RT_STARTUP_WAIT}s for its startup to finish" >&2
    sleep "${RT_POLL_INTERVAL}"
    waited=$(( waited + RT_POLL_INTERVAL ))
  done
}

# -----------------------------------------------------------------------------
# rt_resolve_digest <image-reference>
#
# Resolve a tag to the immutable digest it currently points at, WITHOUT pulling.
#
# Podman has no subcommand for this; `skopeo inspect docker://…` is the
# registry-native answer, a read-only registry call, and what
# ansible/roles/basetool_host installs for this purpose. The digest is the
# manifest's own -- the index digest for a multi-arch list -- which is what
# `@sha256:` addresses.
#
# Prints the digest (`sha256:…`) on stdout. Non-zero and silent on failure, so a
# caller can tell "the tag does not exist" from "the registry is unreachable" by
# its own retry policy rather than by parsing text.
# -----------------------------------------------------------------------------
rt_resolve_digest() {
  local ref="$1" out
  [[ -n "${RT_BACKEND}" ]] || rt_die "rt_resolve_digest before rt_detect"
  command -v skopeo >/dev/null 2>&1 || rt_die "skopeo is not installed; it is how a tag is resolved without pulling"
  out="$(skopeo inspect --no-tags "docker://${ref}" 2>/dev/null)" || return 1
  printf '%s' "${out}" | sed -n 's/.*"Digest"[[:space:]]*:[[:space:]]*"\(sha256:[a-f0-9]\{64\}\)".*/\1/p' | head -1
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

  # TWO identities, and that is not a duplicate call. The images are pulled by the SERVICE USER
  # (RT_CLI is `sudo -u <svc> podman`), while the tag is resolved by `skopeo` and the signature
  # checked by `cosign` -- both of which run as the DEPLOY account, out of its own credential store.
  # Logging in only through RT_CLI leaves that store empty, and the run dies at
  #
  #     FATAL: cannot resolve ghcr.io/krt-profit/basetool-backend:stable (tag missing or no GHCR access)
  #
  # which reads exactly like an expired token. Measured on the testing host 2026-09-18, where the
  # same tag resolved perfectly as the service user and not at all as the deployer.
  #
  # REGISTRY_AUTH_FILE (honoured by the containers/image library that skopeo and podman share) is
  # pointed at the config.json that cosign reads, so one file serves all three tools. When RT_CLI is
  # a bare `podman` (the self-tests, or the service user itself) this is the same login again,
  # which is idempotent and costs one request.
  podman login "${registry}" --username "${user}" --password-stdin < "${pwfile}"
}

# -----------------------------------------------------------------------------
# rt_service_container_ids <service>
#
# The container ids belonging to one service, one per line, including stopped
# ones — the caller decides what a non-running container means.
#
# There is no compose project to ask, so the label Quadlet stamps on every
# container it starts is the answer: each carries
# PODMAN_SYSTEMD_UNIT=<service>.service. Matching on the NAME would also work
# today and would break the moment two units share a name prefix.
# -----------------------------------------------------------------------------
rt_service_container_ids() {
  local svc="$1"
  ${RT_CLI} ps -aq --filter "label=PODMAN_SYSTEMD_UNIT=${svc}.service" 2>/dev/null || true
}

# -----------------------------------------------------------------------------
# rt_container_probe <container-id>
#
# One line: `<state>/<health>`, the shape deploy.sh's drift check parses, or
# `gone` when the container no longer exists. (It carried a leading
# `<is-one-off>|` field for Compose's `run` containers until 2026-09-22; Podman
# has no such thing.)
# -----------------------------------------------------------------------------
rt_container_probe() {
  local cid="$1"
  ${RT_CLI} inspect \
    --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' \
    "${cid}" 2>/dev/null || printf 'gone'
}

# -----------------------------------------------------------------------------
# rt_container_image_id <container-id>
# rt_image_repo_digests <image-id>
#
# The pair that answers "is this container running the image we targeted".
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
# The wait is structural rather than a flag: `Notify=healthy` makes each unit
# Type=notify, so `systemctl start` does not return until podman reports the
# container healthy — and the generator's `TimeoutStartSec=`, derived from the
# service's own health numbers, is what bounds it. Without that key systemd's 90s
# default would cap a keycloak start that its own configuration allows 330s for,
# and the unit would be killed mid-start and restarted forever (PR #1933,
# finding 2).
#
# A daemon-reload comes first because the unit files may have just been replaced.
# -----------------------------------------------------------------------------
rt_apply() {
  ${RT_SYSTEMCTL} daemon-reload || return 1
  local svc rc=0
  for svc in "$@"; do
    ${RT_SYSTEMCTL} start "${svc}.service" || rc=1
  done
  return "${rc}"
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
# WHY A PAIR, when only the reference is pulled. The service half is what the log
# and a reader name; the reference half is what podman needs, because it has no
# compose file to map `backend` to an image. A bare `podman pull backend` is
# resolved against the host's unqualified-search registries (on Rocky:
# registry.access.redhat.com, registry.redhat.io, docker.io) and fails, or
# succeeds against a stranger's image of the same name -- measured 2026-09-18 in
# scripts/deploy.test.sh, when this function took the service names the Docker
# arm wanted. The pair keeps that mistake unrepresentable.
#
# A failed pull IS fatal, by way of `set -e` at the call site, and that is
# deliberate: these three images ARE the release. Since 2026-09-25 deploy.sh's
# pre-gate guard records that exit as a deploy failure and puts the config tree
# and the pin back (on_pre_gate_exit). What must not be fatal is a
# third-party registry hiccup, and that is handled by not pulling infra here at
# all rather than by swallowing errors.
# -----------------------------------------------------------------------------
rt_pull() {
  local pair rc=0
  # Every one is attempted even after the first failure, so the journal names each image that could
  # not be fetched instead of only the earliest.
  for pair in "$@"; do
    ${RT_CLI} pull --quiet "${pair#*=}" >/dev/null 2>&1 || rc=1
  done
  return "${rc}"
}

# -----------------------------------------------------------------------------
# rt_apply_stack [service]...
#
# The release apply: bring the stack to the pinned digests and WAIT. With no
# arguments it applies the whole stack, RT_STACK_SERVICES.
#
# The pin is a drop-in on disk (see rt_pin_write), so the unit files ARE the
# pinned state and a daemon-reload is what picks them up. A retired service
# leaves no container behind once its unit is gone, because Quadlet only starts
# what has a unit file.
#
# The wait: systemd blocks because `Notify=healthy` makes each unit
# Type=notify, bounded by the generated `TimeoutStartSec=`. Passing
# RT_HEALTH_TIMEOUT to systemd would fight that value, which is derived per
# service from its own health numbers, so it is deliberately not forwarded.
# -----------------------------------------------------------------------------
rt_apply_stack() {
  ${RT_SYSTEMCTL} daemon-reload || return 1
  local svc rc=0
  if [[ $# -eq 0 ]]; then
    # "the whole stack" has to be named: there is no project to ask, so the caller supplies the
    # list. Split explicitly into an array rather than relying on an unquoted expansion to do it.
    local -a svcs=()
    read -ra svcs <<< "${RT_STACK_SERVICES:?RT_STACK_SERVICES is unset and no services were named}"
    set -- "${svcs[@]}"
  fi
  for svc in "$@"; do
    # RESTART what this run re-defined, START what it did not. `start` on an already-active unit
    # returns 0 without re-reading anything, so a changed pin would never reach the running
    # container -- see RT_CHANGED_SERVICES at the top of this file for the measurement. A restart IS
    # a recreate here: the generated ExecStart carries --replace, so the old container goes and a
    # new one is created from the current unit.
    #
    # Everything else is left alone deliberately. Restarting the whole stack on every deploy would
    # take the databases down for a change that never touched them.
    case " ${RT_CHANGED_SERVICES} " in
      *" ${svc} "*) ${RT_SYSTEMCTL} restart "${svc}.service" || rc=1 ;;
      *)            ${RT_SYSTEMCTL} start   "${svc}.service" || rc=1 ;;
    esac
  done
  return "${rc}"
}

# -----------------------------------------------------------------------------
# rt_recreate <service>
#
# Replace one service's container and wait for it to be healthy, without
# touching its dependencies. This is the Keycloak provider-JAR path: the JAR is
# staged on the host and only `kc.sh start` re-running the provider build picks
# it up, so the container has to be recreated rather than restarted in place.
#
# A restart IS a recreate: the generated ExecStart carries `--replace --rm`, so
# the old container is removed and a new one is created from the current unit on
# every start.
# -----------------------------------------------------------------------------
rt_recreate() {
  ${RT_SYSTEMCTL} daemon-reload || return 1
  ${RT_SYSTEMCTL} restart "$1.service"
}

# -----------------------------------------------------------------------------
# rt_restart <service>
#
# The targeted restart for runtime-health drift: the right release, a sick
# container. Never a release rollback (ADR-0083).
# -----------------------------------------------------------------------------
rt_restart() {
  ${RT_SYSTEMCTL} restart "$1.service"
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
# JAR and the config bundle are staged. The container is removed even when the
# copy fails, so a failed deploy does not leave a created-but-never-started
# container behind on every tick.
#
# The optional command matters even though it never runs: `create` refuses an
# image declaring neither CMD nor ENTRYPOINT with "no command specified", and the
# bundle images are exactly that — a filesystem with no process.
# -----------------------------------------------------------------------------
rt_extract_from_image() {
  local ref="$1" src="$2" dst="$3" cmd="${4:-}" cid rc=0
  if [[ -n "${cmd}" ]]; then
    cid="$(${RT_CLI} create "${ref}" "${cmd}" 2>/dev/null)" || return 1
  else
    cid="$(${RT_CLI} create "${ref}" 2>/dev/null)" || return 1
  fi
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
# The pin is a systemd DROP-IN beside the unit. Measured on podman 5.8.2 / Rocky 10, 2026-09-18: `Image=` in a
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

# Drop a service from RT_CHANGED_SERVICES once its new definition has reached the running container,
# so a later apply in the same run starts it instead of recreating it again. Only rt_monitoring_up
# calls it: it is the one apply that runs twice for a single change (see its comment).
rt_forget_changed() {
  local svc="$1" out="" s
  for s in ${RT_CHANGED_SERVICES}; do
    [[ "${s}" == "${svc}" ]] && continue
    out="${out}${out:+ }${s}"
  done
  RT_CHANGED_SERVICES="${out}"
}

rt_pin_clear() {
  rm -f "$(rt_pin_path "$1")"
}

# -----------------------------------------------------------------------------
# rt_pin_record_pairs <record-file>
#
# Read a pin RECORD back into `service=reference` lines.
#
# The record is the small YAML file this deployer has always written under the
# state directory, naming three services and their digests -- the compose-override
# shape it had under Docker, kept because the rollback reads it back. This function
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
# Write the pin: the record, and the drop-ins that actually bind it.
#
# Both halves matter and for different reasons. Quadlet reads unit files, so the
# record alone pins nothing; the drop-ins are the binding and the record is what
# makes a ROLLBACK possible, because it is the only place the previous digests
# survive once the drop-ins have been overwritten.
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
# overwritten; `rt_pin_rollback` puts it back — and re-materialises the drop-ins
# from it, which is the half that would otherwise be missed.
#
# Copying the record alone and calling it a rollback is the trap: the running
# stack is bound by the DROP-INS, so restoring only the record would
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

  local pair
  while IFS= read -r pair; do
    [[ -n "${pair}" ]] || continue
    rt_pin_write "${pair%%=*}" "${pair#*=}"
  done < <(rt_pin_record_pairs "${RT_PIN_FILE}")
}

# =============================================================================
# The monitoring plane
#
# There are no compose projects: the nine monitoring units sit in the same unit
# directory as the application ones and are told apart by name.
#
# Every one of these is best-effort at the call site — the deploy is not gated on
# the monitoring plane, because an observability failure must not stop a release
# that is otherwise healthy.
# =============================================================================

# -----------------------------------------------------------------------------
# rt_monitoring_services
#
# The monitoring units, one per line: every `.container` in the unit directory
# that is not an application service (RT_STACK_SERVICES). An explicit
# RT_MONITORING_SERVICES still wins, which is how the self-tests name a set.
#
# DERIVED, since 2026-09-22 (OPS-SIMP-04). deploy.sh and backup.sh each carried
# the nine names as a literal, and a list that has to be kept in step with the
# compose file by hand is how `acme` was missing from every list until the same
# day. The unit directory is what the release installed, so it is the answer.
# -----------------------------------------------------------------------------
rt_monitoring_services() {
  if [[ -n "${RT_MONITORING_SERVICES:-}" ]]; then
    local -a listed=()
    read -ra listed <<< "${RT_MONITORING_SERVICES}"
    printf '%s\n' "${listed[@]}"
    return 0
  fi
  local f name
  for f in "${RT_UNIT_DIR:-}"/*.container; do
    [[ -f "${f}" ]] || continue
    name="$(basename "${f}" .container)"
    case " ${RT_STACK_SERVICES:-} " in
      *" ${name} "*) continue ;;
    esac
    printf '%s\n' "${name}"
  done
}

rt_monitoring_configured() {
  [[ -n "$(rt_monitoring_services)" ]]
}

rt_monitoring_up() {
  ${RT_SYSTEMCTL} daemon-reload || return 1
  local svc rc=0
  if [[ $# -eq 0 ]]; then
    local -a msvcs=()
    mapfile -t msvcs < <(rt_monitoring_services)
    set -- "${msvcs[@]}"
  fi
  for svc in "$@"; do
    # RESTART what this run re-defined, START the rest -- the same rule, for the same measured
    # reason, as rt_apply_stack. Until 2026-09-22 this only ever said `start`, and `start` on an
    # active unit is a no-op: a release that changed prometheus.container (an image bump, a memory
    # limit, a new mount) installed the new unit, daemon-reloaded, and left the old container
    # running the old definition until something else happened to restart it.
    #
    # A restarted service is then forgotten, because this function runs TWICE on the success path --
    # once from the monitoring apply and once from reconcile_monitoring_reloads -- and the second
    # call must not recreate prometheus a second time for the same change. A restart that FAILED is
    # remembered, so the second call is its retry.
    case " ${RT_CHANGED_SERVICES} " in
      *" ${svc} "*)
        if ${RT_SYSTEMCTL} restart "${svc}.service"; then
          rt_forget_changed "${svc}"
        else
          rc=1
        fi
        ;;
      *) ${RT_SYSTEMCTL} start "${svc}.service" || rc=1 ;;
    esac
  done
  return "${rc}"
}

rt_monitoring_is_running() {
  local svc
  while IFS= read -r svc; do
    [[ -n "${svc}" ]] || continue
    rt_is_running "${svc}" && return 0
  done < <(rt_monitoring_services)
  return 1
}

# Replace ONE monitoring container so it re-resolves its bind-mount inode and
# re-reads a changed config file. Not health-gated and never gating: a failed
# monitoring recreate re-drifts on the next tick rather than failing a release.
rt_monitoring_recreate() {
  # A host service is not in the service user's systemd at all, so asking it to restart one fails
  # with "Unit alloy.service not found" -- which the caller reports as "monitoring stack down?"
  # about a unit that is up. Route it to the system manager.
  case " ${RT_HOST_SERVICES} " in
    *" $1 "*) ${RT_HOST_SYSTEMCTL:-systemctl} restart "$1.service" ;;
    *)        ${RT_SYSTEMCTL} restart "$1.service" ;;
  esac
}

# =============================================================================
# Reading state out, for the backup
# =============================================================================

# -----------------------------------------------------------------------------
# rt_read_mount <source> <helper-image> <command>...
#
# Run a throwaway helper with <source> mounted read-only at /src and stream its
# stdout. <source> may be a host path OR a named volume — podman accepts either
# in the same position, which is what lets one primitive serve both.
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

# -----------------------------------------------------------------------------
# rt_unit_image <service> [fallback-unit-directory]
#
# Print the image reference -- digest included -- that a Quadlet unit runs, read
# from its `Image=` line. The helper and drill containers use it to run EXACTLY
# the PostgreSQL image db-backend runs, instead of naming a tag of their own.
#
# Until 2026-09-22 backup.sh and restore-drill.sh each carried
# `docker.io/library/postgres:18-alpine` as a literal: a floating tag, resolved at
# pull time against whatever the registry said that night, and a second place to
# forget when Dependabot bumps the digest in docker-compose.yml. The generated unit
# is where that digest already lives, so it is read from there.
#
# The installed unit wins, because it is what actually runs; the copy in the
# config tree is the fallback for a host whose unit directory is not readable
# yet. Prints nothing and returns 1 when neither names an image, so the caller
# decides what a missing answer costs.
# -----------------------------------------------------------------------------
rt_unit_image() {
  local svc="$1" fallback="${2:-}" dir file ref
  for dir in "${RT_UNIT_DIR:-}" "${fallback}"; do
    [[ -n "${dir}" ]] || continue
    file="${dir}/${svc}.container"
    [[ -r "${file}" ]] || continue
    ref="$(sed -n 's/^Image=\([^[:space:]]\{1,\}\)[[:space:]]*$/\1/p' "${file}" | tail -n 1)"
    if [[ -n "${ref}" ]]; then
      printf '%s\n' "${ref}"
      return 0
    fi
  done
  return 1
}

# -----------------------------------------------------------------------------
# rt_prometheus_snapshot
#
# Ask Prometheus for a TSDB snapshot through its admin API (ADR-0072) and print
# the JSON answer, whose `data.name` is the snapshot directory under
# /prometheus/snapshots.
#
# It runs INSIDE the prometheus container, with the image's own BusyBox wget, and
# reads the web password there from the secret the container already mounts. Until
# 2026-09-22 backup.sh started a throwaway `curlimages/curl:8.11.1` container for
# this and handed it `-u grafana:<password>`, which failed twice over on Podman:
#
#   * a short image name, which podman refuses to resolve without a TTY
#     ("short-name resolution enforced but cannot prompt"), and an unpinned tag;
#   * the password on `podman run`'s argv, readable by every account on the host
#     through /proc/<pid>/cmdline for as long as the pull and the request took.
#
# Inside the container the password never leaves it: it goes from the mounted file
# into an Authorization header in one shell, the same way check-conformance.py's
# _promql reads Prometheus. The admin API needs no network hop, so nothing new is
# pulled and nothing joins net-monitoring-core.
# -----------------------------------------------------------------------------
rt_prometheus_snapshot() {
  # shellcheck disable=SC2016  # expanded by the shell INSIDE the container, from its own secret
  rt_exec prometheus sh -c \
    'p="$(cat /etc/prometheus/secrets/web_password)" && a="$(printf "grafana:%s" "${p}" | base64 -w0)" && wget -q -O- --post-data="" --header="Authorization: Basic ${a}" http://127.0.0.1:9090/api/v1/admin/tsdb/snapshot'
}

# rt_prometheus_snapshot_remove <name> -- delete one snapshot directory, from
# inside the container that owns it. The deploy account cannot: under rootless
# Podman the TSDB belongs to the container's `nobody`, i.e. host uid 165533, and a
# host-side `rm -rf` fails in silence, so every weekly snapshot used to stay in the
# 40 GB volume. The name is checked against the shape Prometheus gives it
# (`20260922T041500Z-<hex>`) before it is used in a path.
rt_prometheus_snapshot_remove() {
  local name="$1"
  [[ "${name}" =~ ^[0-9A-Za-z-]+$ ]] || return 1
  rt_exec prometheus rm -rf "/prometheus/snapshots/${name}"
}

# =============================================================================
# The throwaway container the restore drill proves recoverability in
# =============================================================================

# rt_rm_force <name> — remove a container whatever state it is in, quietly, TOGETHER WITH ITS
# ANONYMOUS VOLUME.
#
# `-v` removes only anonymous volumes ("Remove anonymous volumes associated with the container"),
# never named ones, so it cannot touch edge-certs or edge-acme-state. Without it the restore
# drill's throwaway Postgres left its data volume behind on every run: the `postgres` image
# declares `VOLUME /var/lib/postgresql/data`, and `rm` without `-v` keeps it. Measured on the
# migration target 2026-09-21 — one drill run, one orphaned 156 MB volume.
#
# Nobody noticed on the retired Docker host because its weekly `docker volume prune`
# (anonymous-only there) swept them up. Podman has no anonymous-only prune, so the leak is fixed
# where it is made instead (ADR-0194).
rt_rm_force() {
  ${RT_CLI} rm -f -v "$1" >/dev/null 2>&1 || true
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
  # STREAMED IN, not copied, and for the mirror image of the reason rt_extract_from_image
  # streams OUT. RT_CLI is `sudo -u <service user> podman`, so a plain `cp` has the service
  # user READ a file that belongs to the deploy account -- and the restore drill's working tree
  # is deploy-owned 0700 by design, because it holds restored database dumps:
  #
  #     Error: ".../krt_basetool.dump" could not be found on the host: ... permission denied
  #
  # Measured on the testing host 2026-09-20. The CALLER reads the file and the bytes cross the
  # account boundary through a pipe.
  #
  # `exec -i`, and NOT `podman cp -`, and that is a bug fix rather than a preference. `cp -`
  # stops reading the moment it has extracted the entry, so tar's trailing blocks land in a
  # closed pipe and podman reports its own failed write:
  #
  #     Error: 1 error occurred:
  #             * io: read/write on closed pipe
  #
  # It exits 125 -- and the file is COMPLETE in the container anyway. Measured on the production
  # host 2026-09-22, ten rounds each: `cp -` failed 10/10 while delivering the file correctly
  # 10/10; `exec -i` succeeded 10/10. Deterministic per file size rather than a race: the
  # restore drill's 12 MiB backend dump keeps podman reading to the end and passes, its 352 KiB
  # keycloak dump does not -- so the drill aborted on its second copy and reported four
  # artifacts unrestorable that it had never got as far as testing.
  #
  # This also drops the tar, and with it the rename that existed only because tar preserves the
  # SOURCE basename. `sh -c '…' sh "${dst}"` passes the destination as $1 rather than
  # interpolating it into the script, so a path with a quote in it cannot rewrite the command.
  local src="$1" ctr="$2" dst="$3" want got
  # shellcheck disable=SC2016  # `$1` belongs to the inner sh, not to this shell -- that is the point
  ${RT_CLI} exec -i "${ctr}" sh -c 'cat > "$1"' sh "${dst}" < "${src}" || return 1
  # Verify the bytes arrived instead of trusting the exit status -- which is precisely what the
  # mechanism this replaces got wrong, in the opposite direction.
  want="$(wc -c < "${src}" | tr -d '[:space:]')"
  got="$(${RT_CLI} exec "${ctr}" stat -c %s "${dst}" 2>/dev/null | tr -d '[:space:]')"
  if [[ "${want}" != "${got}" ]]; then
    echo "rt_cp_to: ${dst} is ${got:-0} bytes in ${ctr}, expected ${want}" >&2
    return 1
  fi
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
#
# The stop waits as long as the unit's StopTimeout= allows -- the service's own stop grace, 30 s for
# the application modules -- so a writer finishes its in-flight requests before the dump starts.
rt_service_stop() {
  local svc rc=0
  for svc in "$@"; do ${RT_SYSTEMCTL} stop "${svc}.service" || rc=1; done
  return "${rc}"
}

rt_service_start() {
  local svc rc=0
  for svc in "$@"; do ${RT_SYSTEMCTL} start "${svc}.service" || rc=1; done
  return "${rc}"
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
