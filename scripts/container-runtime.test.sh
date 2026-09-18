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
  *"buildx imagetools"*) echo "{\"digest\":\"sha256:1111111111111111111111111111111111111111111111111111111111111111\"}" ;;
  *"ps -aq"*) echo "cid-docker-1" ;;
  *"inspect --format"*) echo "False|running/healthy" ;;
  *create*) echo "created-cid" ;;
esac'
make_stub podman 'case "$*" in
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
if [[ ! -x "${MINIMAL}/bash" ]]; then
  say "  FATAL: could not stage a minimal PATH (no bash found)"; exit 2
fi

detect_in() { # $1 = directory holding the CLIs that exist; prints the backend
  # shellcheck disable=SC2030,SC2031
  # Same reason as run_rt: a leaked RT_BACKEND would make detection untestable.
  (
    set +e
    PATH="$1:${MINIMAL}"
    unset RT_BACKEND RT_CLI RT_SYSTEMCTL
    # shellcheck disable=SC1090
    . "$LIB"
    rt_detect 2>/dev/null && printf '%s' "${RT_BACKEND}"
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
say "== lifting a file out of an image without running it =="
expect_call "podman creates, copies, and removes" podman \
  'rt_extract_from_image img:tag /a /b' 'cp created-cid:/a /b'
expect_call "the container is removed even when the copy FAILS" podman \
  'STUB_FAIL="cp_created-cid" rt_extract_from_image img:tag /a /b' 'rm -f created-cid'
expect_out  "...and the failure is still reported" podman \
  'STUB_FAIL="cp_created-cid" rt_extract_from_image img:tag /a /b; echo "rc=$?"' 'rc=1'

say ""
say "== pruning must never reach a volume, because they hold the databases =="
expect_no_call "no system prune" podman 'rt_prune' 'system prune'
expect_no_call "no volume prune" podman 'rt_prune' 'volume prune'
expect_call    "images and networks only" podman 'rt_prune' 'image prune -f'

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
