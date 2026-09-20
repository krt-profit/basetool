#!/usr/bin/env bash
# =============================================================================
# Self-test for scripts/lib/container-runtime.sh — the seam Phase 3 puts between
# the operational scripts and the container runtime.
#
# No daemon, no containers, no network: `docker`, `podman`, `skopeo` and
# `systemctl` are stubbed on PATH and record what they were called with, which is
# the same harness deploy.test.sh already uses.
#
# The point of the file is the SECOND backend. Production serves on Docker until
# the cutover and the testing host serves on Podman now, so both shapes are live
# at once and a change that only works on one of them is a change that breaks the
# other silently. Every behavioural assertion below is therefore made twice.
#
#   bash scripts/container-runtime.test.sh
# =============================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="${HERE}/lib/container-runtime.sh"
[[ -f "$LIB" ]] || { echo "FATAL: ${LIB} not found" >&2; exit 1; }

PASSED=0
FAILED=0
say() { printf '%s\n' "$*"; }
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/rt-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
BIN="${WORK}/bin"
LOG="${WORK}/calls.log"
mkdir -p "$BIN"

# A stub that records its argv and can be told to fail. `STUB_FAIL` holds a
# space-separated list of substrings; an invocation matching one exits 1.
make_stub() {
  local name="$1" extra="${2:-}"
  # An ABSOLUTE interpreter: `#!/usr/bin/env bash` makes env search PATH for
  # bash, and the detection scenarios deliberately run under a PATH that may not
  # contain it.
  local bash_path; bash_path="$(command -v bash)"
  cat > "${BIN}/${name}" <<STUB
#!${bash_path}
printf '%s %s\n' "${name}" "\$*" >> "${LOG}"
for pat in \${STUB_FAIL:-}; do
  case "\$*" in *"\${pat//_/ }"*) exit 1 ;; esac
done
${extra}
exit 0
STUB
  chmod +x "${BIN}/${name}"
}

make_stub docker 'case "$*" in
  *"buildx imagetools"*) echo "sha256:1111111111111111111111111111111111111111111111111111111111111111" ;;
  *"ps -aq"*) echo "cid-docker-1" ;;
  *"inspect --format"*) echo "False|running/healthy" ;;
  *create*) echo "created-cid" ;;
esac'
make_stub podman 'case "$*" in
  *"volume inspect"*) exit 0 ;;
  *"ps -aq"*) echo "cid-podman-1" ;;
  *"ps --format"*) echo "backend" ;;
  *"inspect --format"*) echo "false|running/healthy" ;;
  *create*) echo "created-cid" ;;
esac'
make_stub skopeo 'echo "{\"Digest\":\"sha256:2222222222222222222222222222222222222222222222222222222222222222\"}"'
make_stub systemctl
export PATH="${BIN}:${PATH}"

# Runs a snippet with the library loaded and a clean call log.
# $1 backend, $2 shell snippet. Prints the snippet's stdout; the log is in $LOG.
run_rt() {
  local backend="$1" snippet="$2"
  : > "$LOG"
  # shellcheck disable=SC2030,SC2031
  # The subshell IS the isolation: every case must start from an unset RT_*, or
  # one test's detection result leaks into the next and the suite passes by
  # accident. shellcheck warns that the change is local, which is the intent.
  (
    set +e
    export RT_BACKEND="$backend"
    export RT_CLI="" RT_SYSTEMCTL="" RT_UNIT_DIR="${WORK}/units"
    export RT_COMPOSE_FILE="${WORK}/docker-compose.yml" RT_PROFILE=prod
    # shellcheck disable=SC1090
    . "$LIB"
    rt_detect
    eval "$snippet"
  )
}

# $1 label, $2 backend, $3 snippet, $4 substring the call log must contain
expect_call() {
  local label="$1" backend="$2" snippet="$3" want="$4"
  run_rt "$backend" "$snippet" >/dev/null 2>&1
  if grep -qF -- "$want" "$LOG"; then ok "$label"; else
    bad "$label"
    printf '        wanted a call containing: %s\n' "$want"
    printf '        recorded:\n'; sed 's/^/          /' "$LOG"
  fi
}

# $1 label, $2 backend, $3 snippet, $4 substring the call log must NOT contain
expect_no_call() {
  local label="$1" backend="$2" snippet="$3" unwanted="$4"
  run_rt "$backend" "$snippet" >/dev/null 2>&1
  if grep -qF -- "$unwanted" "$LOG"; then
    bad "$label"; printf '        must not have called: %s\n' "$unwanted"
  else ok "$label"; fi
}

# $1 label, $2 backend, $3 snippet, $4 expected stdout substring
expect_out() {
  local label="$1" backend="$2" snippet="$3" want="$4" got
  got="$(run_rt "$backend" "$snippet" 2>/dev/null)"
  if [[ "$got" == *"$want"* ]]; then ok "$label"; else
    bad "$label"; printf '        wanted: %s\n        got   : %s\n' "$want" "$got"
  fi
}

say "== detecting the runtime, which is where this class of bug lives =="
# The equivalent code in check-conformance.py INFERRED the rootless user from a
# glob over a 0750 directory it could not read, silently fell back to the wrong
# user, and reported `no such object` for eight healthy containers. So detection
# is asserted here rather than assumed: it must pick what actually answers.
# The scenarios below must see ONLY the CLIs each one names, so the PATH they run
# under carries the scenario directory plus a hand-built set of coreutils -- and
# nothing else.
#
# `PATH="$1:/usr/bin:/bin"` was the first attempt and it is not isolation: a
# GitHub runner has a real, working /usr/bin/docker, so "a host with only podman"
# and "a host with neither" both detected docker and the suite went red there
# while passing on a workstation that happens to have no docker in /usr/bin.
# Green for the wrong reason, in the file whose whole subject is detection.
MINIMAL="${WORK}/coreutils"
mkdir -p "$MINIMAL"
for util in bash sh env id basename getent cut ls sudo; do
  util_path="$(command -v "$util" 2>/dev/null)" || continue
  ln -sf "$util_path" "${MINIMAL}/${util}" 2>/dev/null     || cp "$util_path" "${MINIMAL}/${util}" 2>/dev/null || true
done
# STAGED IS NOT THE SAME AS RUNNABLE, and the difference is not cosmetic. Where
# `ln -sf` copies instead of linking -- MSYS/Git Bash on Windows -- a copied
# binary cannot find its runtime library and exits 127 with "error while loading
# shared libraries". Detection calls `id -un`, so a broken `id` there made
# rt_detect take a branch on an EMPTY username, which is the exact condition the
# library now guards against. The stage is patched only when a utility genuinely
# fails to run, so on Linux nothing below happens at all.
if ! PATH="${MINIMAL}" id -un >/dev/null 2>&1; then
  minimal_srcdir="$(dirname "$(command -v id)")"
  for runtime_lib in "${minimal_srcdir}"/msys-*.dll "${minimal_srcdir}"/cyg*.dll; do
    [[ -e "${runtime_lib}" ]] || continue
    cp "${runtime_lib}" "${MINIMAL}/" 2>/dev/null || true
  done
fi
if [[ ! -x "${MINIMAL}/bash" ]]; then
  say "  FATAL: could not stage a minimal PATH (no bash found)"; exit 2
fi
# Asserted, not hoped for: every case below reads a username out of the staged
# PATH, and one that cannot produce one tests nothing.
if PATH="${MINIMAL}" id -un >/dev/null 2>&1; then
  ok "the staged PATH can actually run its utilities"
else
  bad "the staged PATH cannot run \`id\` — every detection case below is meaningless"
fi

# A linger directory that does NOT name the current user, which is the state the
# deploy account is in: it can run podman, and it owns nothing.
NO_LINGER="${WORK}/linger-none"; mkdir -p "$NO_LINGER"
# ...and one that does.
SELF_LINGER="${WORK}/linger-self"; mkdir -p "$SELF_LINGER"
: > "${SELF_LINGER}/$(id -un)"

detect_in() { # $1 = directory holding the CLIs that exist; prints the backend
  # shellcheck disable=SC2030,SC2031
  # Same reason as run_rt: a leaked RT_BACKEND would make detection untestable.
  (
    set +e
    PATH="$1:${MINIMAL}"
    unset RT_BACKEND RT_CLI RT_SYSTEMCTL
    # shellcheck disable=SC2034
    # Read by the library sourced on the next line, which shellcheck does not follow.
    RT_LINGER_DIR="${2:-${SELF_LINGER}}"
    # shellcheck disable=SC1090
    . "$LIB"
    rt_detect 2>/dev/null && printf '%s' "${RT_BACKEND}"
  )
}

# Prints "<backend>|<cli>|<unit dir>" so a case can assert WHICH podman was
# chosen, not merely that podman was.
detect_detail() {
  # shellcheck disable=SC2030,SC2031,SC2034
  # The subshell IS the isolation, as in run_rt. RT_LINGER_DIR reads as unused because its only
  # consumer is the library sourced two lines below, which shellcheck does not follow; the three
  # RT_* names are read back out of that same subshell on purpose.
  (
    set +e
    PATH="$1:${MINIMAL}"
    unset RT_BACKEND RT_CLI RT_SYSTEMCTL RT_UNIT_DIR
    RT_LINGER_DIR="${2:-${SELF_LINGER}}"
    HOME="${WORK}/not-the-owner"
    # shellcheck disable=SC1090
    . "$LIB"
    rt_detect 2>/dev/null
    printf '%s|%s|%s' "${RT_BACKEND}" "${RT_CLI}" "${RT_UNIT_DIR}"
  )
}
# The isolation is itself asserted, because it is what silently failed: on a
# runner with /usr/bin/docker the scenarios below are meaningless unless the PATH
# they run under really cannot reach it. Checking the scenarios without checking
# this is how the suite went green on a workstation and red in CI.
if PATH="$MINIMAL" command -v docker >/dev/null 2>&1; then
  bad "the minimal PATH can still reach a docker -- the detection cases prove nothing"
else
  ok "the minimal PATH reaches no docker, so a host-installed one cannot leak in"
fi
if PATH="$MINIMAL" command -v podman >/dev/null 2>&1; then
  bad "the minimal PATH can still reach a podman"
else
  ok "...and no podman either"
fi

ONLY_DOCKER="${WORK}/only-docker"; mkdir -p "$ONLY_DOCKER"; cp "${BIN}/docker" "$ONLY_DOCKER/"
ONLY_PODMAN="${WORK}/only-podman"; mkdir -p "$ONLY_PODMAN"; cp "${BIN}/podman" "$ONLY_PODMAN/"
NEITHER="${WORK}/neither"; mkdir -p "$NEITHER"

got="$(detect_in "$ONLY_DOCKER")"
if [[ "$got" == docker ]]; then ok "a host with a working docker is docker"; else bad "expected docker, got '${got}'"; fi
got="$(detect_in "$ONLY_PODMAN")"
if [[ "$got" == podman ]]; then ok "a host with only podman is podman"; else bad "expected podman, got '${got}'"; fi
got="$(detect_in "$NEITHER")"
if [[ -z "$got" ]]; then ok "a host with neither refuses instead of guessing"; else bad "expected a refusal, got '${got}'"; fi
# The real production case: the docker BINARY exists but the daemon is not
# answering. Treating "installed" as "usable" would pick a backend that cannot
# run anything.
BROKEN="${WORK}/broken-docker"; mkdir -p "$BROKEN"
printf '#!/usr/bin/env bash\nexit 1\n' > "${BROKEN}/docker"; chmod +x "${BROKEN}/docker"
cp "${BIN}/podman" "${BROKEN}/"
got="$(detect_in "$BROKEN")"
if [[ "$got" == podman ]]; then ok "an installed-but-dead docker does not win over a working podman"; else bad "expected podman, got '${got}'"; fi

# The case that shipped broken. `podman ps` succeeds for EVERY account with a
# podman binary, against that account's own empty store -- so "can I run podman"
# answered yes for the deploy account, which owns nothing, and detection stopped
# there with a bare `podman` and a unit directory under deploy's HOME. Measured on
# the testing host 2026-09-18: the deployer aborted with "no Quadlet unit
# directory (/var/lib/iri/.config/containers/systemd)". Lingering is the signal
# that distinguishes them, and it is the one the sudo bridge already used.
got="$(detect_detail "$ONLY_PODMAN" "$NO_LINGER")"
if [[ "${got}" == "podman|podman|"* ]]; then
  bad "a user that merely HAS podman was taken for the owner (got '${got}')"
else
  ok "an account that can run podman but does not linger is not taken for the owner"
fi
got="$(detect_detail "$ONLY_PODMAN" "$SELF_LINGER")"
if [[ "${got}" == "podman|podman|"*"/.config/containers/systemd" ]]; then
  ok "...while a lingering user IS the owner, and its own unit directory is used"
else
  bad "expected the lingering user's own store, got '${got}'"
fi

say ""
say "== resolving a tag to a digest without pulling =="
expect_call "docker asks buildx imagetools" docker \
  'rt_resolve_digest ghcr.io/x/y:stable' 'buildx imagetools inspect ghcr.io/x/y:stable'
expect_out  "docker returns the manifest digest" docker \
  'rt_resolve_digest ghcr.io/x/y:stable' 'sha256:1111111111111111111111111111111111111111111111111111111111111111'
expect_call "podman asks skopeo, which is why the role installs it" podman \
  'rt_resolve_digest ghcr.io/x/y:stable' 'skopeo inspect --no-tags docker://ghcr.io/x/y:stable'
expect_out  "podman returns the registry digest" podman \
  'rt_resolve_digest ghcr.io/x/y:stable' 'sha256:2222222222222222222222222222222222222222222222222222222222222222'
expect_no_call "neither backend PULLS to resolve a tag" podman \
  'rt_resolve_digest ghcr.io/x/y:stable' 'podman pull'

say ""
say "== bringing the stack up, and WAITING for health =="
expect_call "docker waits with compose --wait" docker \
  'rt_apply backend frontend' 'up -d --wait backend frontend'
expect_call "podman reloads before starting, because units may have moved" podman \
  'rt_apply backend' 'systemctl --user daemon-reload'
expect_call "podman starts the unit, whose Type=notify IS the wait" podman \
  'rt_apply backend' 'systemctl --user start backend.service'
expect_out  "podman reports failure when a start does not reach healthy" podman \
  'STUB_FAIL="start_backend.service" rt_apply backend; echo "rc=$?"' 'rc=1'
expect_out  "...and success when it does" podman \
  'rt_apply backend; echo "rc=$?"' 'rc=0'
expect_out  "docker reports failure the same way" docker \
  'STUB_FAIL="up_-d" rt_apply backend; echo "rc=$?"' 'rc=1'

say ""
say "== pre-pulling the release, where a service name and a reference are NOT the same thing =="
# The regression this section exists for. rt_pull used to take REFERENCES while
# its only call site passed SERVICE NAMES, and both were correct under Docker:
# `docker compose pull backend` resolves the name through the compose file.
# Podman has no compose file, so the identical argument became `podman pull
# backend` -- a bare name resolved against the host's unqualified-search
# registries -- which fails and, at a call site running under `set -e`, aborted
# every Podman deploy at "pulling images". It now takes the pair.
PULL_PAIRS="'backend=ghcr.io/krt-profit/basetool-backend@sha256:3333333333333333333333333333333333333333333333333333333333333333' 'ingest=ghcr.io/krt-profit/basetool-ingest@sha256:4444444444444444444444444444444444444444444444444444444444444444'"
expect_call "docker hands compose the SERVICE names it can resolve" docker \
  "rt_pull ${PULL_PAIRS}" 'pull --quiet backend ingest'
expect_no_call "...and never a reference, which compose has no argument for" docker \
  "rt_pull ${PULL_PAIRS}" '@sha256:'
expect_call "podman pulls the REFERENCE, the only form it can resolve" podman \
  "rt_pull ${PULL_PAIRS}" \
  'pull --quiet ghcr.io/krt-profit/basetool-backend@sha256:3333333333333333333333333333333333333333333333333333333333333333'
expect_no_call "...and never the bare service name" podman \
  "rt_pull ${PULL_PAIRS}" 'pull --quiet backend'
expect_call "every image is attempted, so the journal names all of them" podman \
  "rt_pull ${PULL_PAIRS}" \
  'pull --quiet ghcr.io/krt-profit/basetool-ingest@sha256:4444444444444444444444444444444444444444444444444444444444444444'
expect_out "a failed pull is reported, because these three images ARE the release" podman \
  "STUB_FAIL='pull' rt_pull ${PULL_PAIRS}; echo \"rc=\$?\"" 'rc=1'
expect_out "docker reports it the same way" docker \
  "STUB_FAIL='pull_--quiet' rt_pull ${PULL_PAIRS}; echo \"rc=\$?\"" 'rc=1'
expect_out "...and success stays success" podman \
  "rt_pull ${PULL_PAIRS}; echo \"rc=\$?\"" 'rc=0'

say ""
say "== finding the containers that belong to a service =="
expect_call "docker asks compose, which knows the project" docker \
  'rt_service_container_ids backend' 'ps -aq backend'
expect_call "podman filters on the label Quadlet stamps, not on the name" podman \
  'rt_service_container_ids backend' 'label=PODMAN_SYSTEMD_UNIT=backend.service'
expect_out  "podman returns the id" podman 'rt_service_container_ids backend' 'cid-podman-1'

say ""
say "== judging one container =="
expect_out "docker carries the compose one-off flag" docker \
  'rt_container_probe cid-1' 'False|running/healthy'
expect_out "podman states the flag as false rather than leaving it empty" podman \
  'rt_container_probe cid-1' 'false|running/healthy'
expect_out "an unknown container reads as gone, not as an empty state" podman \
  'STUB_FAIL="inspect_--format" rt_container_probe cid-1' '|gone'

say ""
say "== the digest pin, which stops a tag flip moving the stack =="
run_rt podman 'rt_pin_write backend ghcr.io/x/backend@sha256:abc' >/dev/null 2>&1
PIN="${WORK}/units/backend.container.d/10-digest-pin.conf"
if [[ -f "$PIN" ]]; then ok "podman writes a drop-in beside the unit"; else bad "podman wrote no drop-in at ${PIN}"; fi
if grep -q '^\[Container\]$' "$PIN" 2>/dev/null; then ok "the drop-in carries a [Container] section"; else bad "the drop-in has no [Container] section"; fi
if grep -q '^Image=ghcr.io/x/backend@sha256:abc$' "$PIN" 2>/dev/null; then
  ok "the drop-in sets Image=, which MEASURABLY replaces the base unit's value"
else bad "the drop-in does not set Image="; fi
case "$(basename "$PIN")" in
  10-*) ok "numbered 10-, so an operator drop-in can still outrank it" ;;
  *)    bad "the pin is not numbered, so ordering against an operator drop-in is undefined" ;;
esac
run_rt podman 'rt_pin_write backend ghcr.io/x/backend@sha256:abc; rt_pin_clear backend' >/dev/null 2>&1
if [[ ! -f "$PIN" ]]; then ok "clearing the pin removes the file"; else bad "the pin survived rt_pin_clear"; fi

say ""
say "== the pin's TWO halves, and the rollback that has to restore both =="
# The trap this section exists for: under Quadlet the running stack is bound by
# the DROP-INS, not by the record. Restoring only the record on rollback leaves
# the new digests bound, so the "rollback" silently rolls FORWARD into the very
# release whose health check just failed.
PINDIR="${WORK}/pinstate"; mkdir -p "$PINDIR"
pin_env() {
  printf 'export RT_PIN_FILE=%q RT_PIN_FILE_PREVIOUS=%q;' \
    "${PINDIR}/current.yml" "${PINDIR}/previous.yml"
}
DROPIN="${WORK}/units/backend.container.d/10-digest-pin.conf"

rm -rf "${WORK}/units" "$PINDIR"; mkdir -p "$PINDIR"
run_rt podman "$(pin_env) rt_pin_apply backend=img@sha256:OLD ingest=img2@sha256:OLDI" >/dev/null 2>&1
if grep -q 'image: img@sha256:OLD' "${PINDIR}/current.yml" 2>/dev/null; then
  ok "the record names the pinned reference"
else bad "the record does not name the reference: $(cat "${PINDIR}/current.yml" 2>/dev/null)"; fi
if grep -q '^Image=img@sha256:OLD$' "$DROPIN" 2>/dev/null; then
  ok "...and the drop-in binds it"
else bad "the drop-in does not bind it"; fi

# Round-trip: the record must read back into exactly what was written, because
# that is the only place the previous digests survive an overwrite.
got="$(run_rt podman "$(pin_env) rt_pin_record_pairs \"${PINDIR}/current.yml\"" 2>/dev/null | tr '\n' ' ')"
if [[ "$got" == *"backend=img@sha256:OLD"* && "$got" == *"ingest=img2@sha256:OLDI"* ]]; then
  ok "the record round-trips into service=reference pairs"
else bad "the record did not round-trip: '${got}'"; fi

# Now the sequence a real deploy runs: save, apply a NEW pin, then roll back.
run_rt podman "$(pin_env) rt_pin_save; rt_pin_apply backend=img@sha256:NEW" >/dev/null 2>&1
if grep -q '^Image=img@sha256:NEW$' "$DROPIN" 2>/dev/null; then
  ok "applying a new pin rebinds the drop-in"
else bad "the drop-in was not rebound to the new digest"; fi
run_rt podman "$(pin_env) rt_pin_rollback" >/dev/null 2>&1
if grep -q 'image: img@sha256:OLD' "${PINDIR}/current.yml" 2>/dev/null; then
  ok "rollback restores the record"
else bad "rollback did not restore the record"; fi
if grep -q '^Image=img@sha256:OLD$' "$DROPIN" 2>/dev/null; then
  ok "rollback ALSO rebinds the drop-in -- it rolls back, not forward"
else
  bad "rollback left the drop-in on the failed release: $(grep '^Image=' "$DROPIN" 2>/dev/null)"
fi
# And a rollback with no anchor must refuse rather than pretend.
if run_rt podman "$(pin_env) rm -f \"${PINDIR}/previous.yml\"; rt_pin_rollback" >/dev/null 2>&1; then
  bad "rollback reported success with no previous pin to roll back to"
else ok "rollback with no anchor fails instead of silently doing nothing"; fi

say ""
say "== lifting a file out of an image without running it =="
# A TAR STREAM under podman, because RT_CLI is `sudo -u <svc> podman` there and a direct copy
# would have the service user write into the deploy account's directory ("mkdir /docker:
# permission denied"). The pipe is what crosses the account boundary.
expect_call "podman creates, streams a tar out, and removes" podman \
  'rt_extract_from_image img:tag /a /b' 'cp created-cid:/a -'
# The bundle images declare no CMD and no ENTRYPOINT, so `create` refuses them
# without an argument -- one that is never executed, but has to be there.
# The two shapes the one helper serves, and the reason it has to tell them apart. The config
# bundle extracts a TREE into a directory; the Keycloak provider JAR is ONE FILE at a path. The
# first version unpacked both with `tar -x -C`, which for the second means "extract into a
# directory named keycloak-spi-stage.jar" -- and tar said so:
#   tar: /var/lib/iri/keycloak-spi-stage.jar: Cannot open: No such file or directory
expect_call "a trailing slash means a tree, unpacked in place" podman   'rt_extract_from_image img:tag /config/. /tmp/rt-extract-dir/' 'cp created-cid:/config/. -'
expect_no_call "...and a tree is never streamed to a single file" podman   'rt_extract_from_image img:tag /config/. /tmp/rt-extract-dir/' 'xOf'

expect_call "a command reaches create, for an image that declares none" podman \
  'rt_extract_from_image img:tag /a /b /bundle' 'create img:tag /bundle'
expect_call "the container is removed even when the copy FAILS" podman \
  'STUB_FAIL="cp_created-cid" rt_extract_from_image img:tag /a /b' 'rm -f created-cid'
expect_out  "...and the failure is still reported" podman \
  'STUB_FAIL="cp_created-cid" rt_extract_from_image img:tag /a /b; echo "rc=$?"' 'rc=1'

say ""
say "== pruning must never reach a volume, because they hold the databases =="
expect_no_call "no system prune" podman 'rt_prune' 'system prune'
expect_no_call "no volume prune" podman 'rt_prune' 'volume prune'
expect_call    "images and networks only" podman 'rt_prune' 'image prune --force'
# `until=` is what keeps the images this deploy just pulled -- the ones a rollback
# still needs. Pruning those would make the rollback re-pull from a registry that
# may be exactly what is broken.
expect_call    "the age filter that protects the rollback images" podman \
  'rt_prune_images 720h' 'image prune --force --filter until=720h'

say ""
say "== the monitoring plane, which is a project under one runtime and a name list under the other =="
mon() { printf 'export RT_PROJECT_DIR=%q RT_MONITORING_FILE=%q RT_MONITORING_SERVICES=%q;' \
  "$WORK" "${WORK}/docker-compose.monitoring.yml" "prometheus loki grafana"; }
: > "${WORK}/docker-compose.monitoring.yml"

expect_call "docker addresses it as its own project" docker \
  "$(mon) rt_monitoring_up" '-p iri-monitoring'
expect_call "podman starts each named unit" podman \
  "$(mon) rt_monitoring_up" 'systemctl --user start prometheus.service'
expect_call "...all of them, not just the first" podman \
  "$(mon) rt_monitoring_up" 'systemctl --user start grafana.service'
# `down` exists for exactly one reason: the monitoring project holds the shared
# data networks as `external`, and a bridge with an endpoint attached cannot be
# removed. Skipping it strands the topology change half-applied.
expect_call "docker takes the project down with its orphans" docker \
  "$(mon) rt_monitoring_down" 'down --remove-orphans'
expect_call "podman stops each named unit" podman \
  "$(mon) rt_monitoring_down" 'systemctl --user stop loki.service'
expect_call "docker asks the project label whether anything runs" docker \
  "$(mon) rt_monitoring_is_running" 'label=com.docker.compose.project=iri-monitoring'
expect_call "a monitoring recreate touches one service only" podman \
  "$(mon) rt_monitoring_recreate loki" 'systemctl --user restart loki.service'
expect_no_call "...and never the whole plane" podman \
  "$(mon) rt_monitoring_recreate loki" 'start prometheus.service'

say ""
say "== taking the app stack down, which only a topology change needs =="
expect_call "docker uses the app project's own file" docker \
  'rt_apply_stack >/dev/null; rt_stack_down' 'down --remove-orphans'
# Reverse order: a dependent has to stop before the thing it depends on, or the
# database is pulled out from under a service still talking to it.
got="$(run_rt podman 'RT_STACK_SERVICES="db-backend keycloak frontend" rt_stack_down' 2>/dev/null; grep -o 'stop [a-z-]*\.service' "$LOG" | tr '\n' ' ')"
if [[ "$got" == *"stop frontend.service stop keycloak.service stop db-backend.service"* ]]; then
  ok "podman stops them in reverse dependency order"
else
  bad "stop order was '${got}', wanted frontend, keycloak, db-backend"
fi

say ""
say "== reading state out for the backup =="
# One primitive serves a host path and a named volume, because both CLIs accept
# either in the same position. That is what let the edge's TLS material -- which
# lives in volumes -- be captured by the same code that reads the keystore.
expect_call "a named volume is read through the helper" podman \
  'rt_read_mount edge-certs postgres:18-alpine tar -C /src -cz .' \
  'run --rm -v edge-certs:/src:ro postgres:18-alpine tar -C /src -cz .'
expect_call "...and a host path identically" docker \
  'rt_read_mount /var/iri/monitoring postgres:18-alpine cat /src/x' \
  'run --rm -v /var/iri/monitoring:/src:ro'
expect_call "the mount is read-only, so a backup cannot write to what it reads" podman \
  'rt_read_mount edge-certs img tar -C /src -cz .' ':/src:ro'
expect_out "a volume that does not exist is reported, not assumed present" podman \
  'STUB_FAIL="volume inspect" rt_volume_exists edge-certs; echo "rc=$?"' 'rc=1'
expect_out "...and one that does" podman 'rt_volume_exists edge-certs; echo "rc=$?"' 'rc=0'

say ""
say "== the quiesce: a pause, not a release =="
expect_call "docker stops the writers with the configured timeout" docker \
  'RT_STOP_TIMEOUT=30 rt_service_stop frontend backend ingest' 'stop -t 30 frontend backend ingest'
expect_call "podman stops each writer unit" podman \
  'rt_service_stop frontend backend' 'systemctl --user stop frontend.service'
expect_call "and starts them again" podman \
  'rt_service_start frontend' 'systemctl --user start frontend.service'
# The quiesce must NOT apply the pin or wait for health -- that is a release
# operation, and the backup is meant to put the stack back exactly as it was.
expect_no_call "the quiesce does not re-apply the digest pin" docker \
  'RT_PIN_FILE=/tmp/pin.yml rt_service_start frontend' '/tmp/pin.yml'

say ""
say "== the throwaway container the restore drill proves recoverability in =="
expect_call "it is started detached, by name" podman \
  'rt_run_detached iri-restore-drill postgres:18-alpine -e POSTGRES_USER=drill' \
  'run -d --name iri-restore-drill -e POSTGRES_USER=drill postgres:18-alpine'
# A drill container on the deployment's networks is a drill container that can be
# mistaken for the real thing.
expect_no_call "it joins none of the deployment's networks" podman \
  'rt_run_detached iri-restore-drill img -e A=b' '--network'
expect_call "a dump is copied into it" podman \
  'rt_cp_to /work/krt_basetool.dump iri-restore-drill /tmp/krt_basetool.dump' \
  'cp /work/krt_basetool.dump iri-restore-drill:/tmp/krt_basetool.dump'
expect_call "and it is removed whatever state it is in" podman \
  'rt_rm_force iri-restore-drill' 'rm -f iri-restore-drill'

say ""
say "== credentials never reach a command line =="
printf 'hunter2\n' > "${WORK}/token"
expect_call "the password is piped, not passed" podman \
  'rt_login ghcr.io someone '"${WORK}"'/token' '--password-stdin'
expect_no_call "the value itself is never in an argv" podman \
  'rt_login ghcr.io someone '"${WORK}"'/token' 'hunter2'

say ""
printf '%d passed, %d failed\n' "$PASSED" "$FAILED"
[[ $FAILED -eq 0 ]]
