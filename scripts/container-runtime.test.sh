#!/usr/bin/env bash
# =============================================================================
# Self-test for scripts/lib/container-runtime.sh — the seam Phase 3 puts between
# the operational scripts and the container runtime.
#
# No daemon, no containers, no network: `podman`, `skopeo` and `systemctl` are
# stubbed on PATH and record what they were called with, which is the same
# harness deploy.test.sh already uses.
#
# Rootless Podman only, since 2026-09-22 (OPS-SIMP-01): the Docker half of the
# seam, and with it every assertion this file made twice, was removed with the
# retired Docker host.
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

make_stub podman 'case "$*" in
  *"volume inspect"*) exit 0 ;;
  *"ps -aq"*) echo "cid-podman-1" ;;
  *"ps --format"*) echo "backend" ;;
  *"inspect --format"*) echo "running/healthy" ;;
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
# GitHub runner has a real, working /usr/bin/podman as well as a docker, so "a
# host with neither" detected one and the suite went red there while passing on a
# workstation. Green for the wrong reason, in the file whose whole subject is
# detection.
MINIMAL="${WORK}/coreutils"
mkdir -p "$MINIMAL"
for util in bash sh env id basename getent cut ls sudo sleep stat; do
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
# runner with /usr/bin/podman the scenarios below are meaningless unless the PATH
# they run under really cannot reach it. Checking the scenarios without checking
# this is how the suite went green on a workstation and red in CI.
if PATH="$MINIMAL" command -v podman >/dev/null 2>&1; then
  bad "the minimal PATH can still reach a podman -- the detection cases prove nothing"
else
  ok "the minimal PATH reaches no podman, so a host-installed one cannot leak in"
fi

ONLY_PODMAN="${WORK}/only-podman"; mkdir -p "$ONLY_PODMAN"; cp "${BIN}/podman" "$ONLY_PODMAN/"
NEITHER="${WORK}/neither"; mkdir -p "$NEITHER"
# A docker binary on its own is no runtime any more (OPS-SIMP-01): it must be refused, not used.
ONLY_DOCKER="${WORK}/only-docker"; mkdir -p "$ONLY_DOCKER"
printf '#!/usr/bin/env bash\nexit 0\n' > "${ONLY_DOCKER}/docker"; chmod +x "${ONLY_DOCKER}/docker"

got="$(detect_in "$ONLY_PODMAN")"
if [[ "$got" == podman ]]; then ok "a host with podman is podman"; else bad "expected podman, got '${got}'"; fi
got="$(detect_in "$NEITHER")"
if [[ -z "$got" ]]; then ok "a host with no podman refuses instead of guessing"; else bad "expected a refusal, got '${got}'"; fi
got="$(detect_in "$ONLY_DOCKER")"
if [[ -z "$got" ]]; then ok "a working docker alone is refused -- the Docker runtime is retired"; else bad "docker was accepted as '${got}'"; fi
got="$(RT_BACKEND=docker bash -c '. "$1"; rt_detect' _ "$LIB" 2>&1 || true)"
if [[ "$got" == *"retired"* ]]; then ok "a preset RT_BACKEND=docker is refused and says why"; else bad "RT_BACKEND=docker was not refused: '${got}'"; fi

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
say "== the boot race: a lingering user whose runtime is not up YET =="
# Measured on the production host 2026-09-22, the first reboot after the cutover: the backup,
# cleanup and drill timers carry Persistent=true, their catch-up runs fired at 16:26:02, eleven
# seconds after boot, and all three died in detection -- user@994.service became active at 16:26:03.
# They lost by one second, stayed `failed` and paged SystemdUnitFailed critical.
#
# And again on 2026-09-25, with the wait for the directory in place: all four jobs started in the
# same second as user@994.service and died at once, because the directory was there and podman
# still refused. Reproduced the same day in a systemd container (Rocky 10.2, systemd 257, podman
# 5.8.2), which is where the two shapes below come from:
#
#   * a sandboxed job started BEFORE the runtime tmpfs was mounted never sees the mount -- the
#     directory it finds is the bare, root-owned mount point, for the job's whole life;
#   * a job that does see the runtime (read-only, ProtectHome) cannot set up podman's user
#     namespace itself and can only JOIN the pause process the manager's first container creates:
#     until then "set sticky bit on: chmod /run/user/<uid>/libpod: read-only file system".
#
# These cases run detection through the SUDO BRIDGE, which is the path production takes: the jobs
# run as the deploy account, which does not linger, and reach the service user through sudo.
BRIDGE="${WORK}/bridge"; mkdir -p "$BRIDGE"
cp "${BIN}/podman" "$BRIDGE/"
REAL_ID="$(command -v id)"
ABS_BASH="$(command -v bash)"
# The service user's uid is the CURRENT user's by default, so a fixture directory this test creates
# is owned by "the service user" -- which is what a mounted runtime looks like from the job
# (rt_runtime_visible). STUB_SVC_UID set to anything else makes the same directory the bare mount
# point of a runtime this job cannot see. Every other form of id is the real one, because
# detection also asks `id -un` for the current user and that answer has to be true.
MY_UID="$("$REAL_ID" -u)"
# shellcheck disable=SC2016  # "$1", "$2", "$@" and the variables belong to the stub being written
printf '#!%s\nif [ "$1" = "-u" ] && [ "$2" = "svcuser" ]; then echo "${STUB_SVC_UID:-%s}"; exit 0; fi\nexec "%s" "$@"\n' \
  "$ABS_BASH" "$MY_UID" "$REAL_ID" > "${BRIDGE}/id"
# The sudo bridge. Every call is logged, so a case can prove podman was NOT run.
#   `podman ps`             succeeds once STUB_READY_DIR exists -- on a host, once the manager's
#                           first container has created the pause process podman joins; otherwise
#                           it fails the way the sandbox makes it fail, on stderr
#   `is-system-running`     answers STUB_MANAGER_STATE (default running); "-" is no answer at all
# Any other sudo call succeeds.
cat > "${BRIDGE}/sudo" <<STUB
#!${ABS_BASH}
printf '%s\n' "\$*" >> "${WORK}/sudo.log"
case "\$*" in
  *"podman ps"*)
    [ -d "\${STUB_READY_DIR:-/nonexistent}" ] && exit 0
    echo 'Error: set sticky bit on: chmod /run/user/4242/libpod: read-only file system' >&2
    exit 1 ;;
  *"is-system-running"*)
    state="\${STUB_MANAGER_STATE:-running}"
    [ "\${state}" = "-" ] && exit 1
    printf '%s\n' "\${state}"
    [ "\${state}" = "running" ] ;;
esac
exit 0
STUB
chmod +x "${BRIDGE}/id" "${BRIDGE}/sudo"
LINGER_SVC="${WORK}/linger-svc"; mkdir -p "$LINGER_SVC"; : > "${LINGER_SVC}/svcuser"
RUNBASE="${WORK}/run-user"; mkdir -p "$RUNBASE"
RUNDIR="${RUNBASE}/${MY_UID}"

# Prints "<backend>|<cli>", or nothing when detection refused. $1 = RT_RUNTIME_WAIT.
detect_bridge() {
  # shellcheck disable=SC2030,SC2031,SC2034
  # The subshell IS the isolation, as in detect_detail; the RT_* names are read by the library.
  (
    set +e
    : > "${WORK}/sudo.log"
    PATH="${BRIDGE}:${MINIMAL}"
    unset RT_BACKEND RT_CLI RT_SYSTEMCTL RT_UNIT_DIR
    RT_LINGER_DIR="${LINGER_SVC}"
    RT_RUNTIME_BASE="${RUNBASE}"
    RT_POLL_INTERVAL=1
    RT_RUNTIME_WAIT="${1:-10}"
    HOME="${WORK}/not-the-owner"
    # shellcheck disable=SC1090
    . "$LIB"
    rt_detect 2>"${WORK}/detect.err"
    printf '%s|%s' "${RT_BACKEND}" "${RT_CLI}"
  )
}
detect_err() { tr '\n' ' ' < "${WORK}/detect.err"; }

rm -rf "${RUNDIR:?}"
export STUB_READY_DIR="${RUNDIR}"
( sleep 3; mkdir -p "${RUNDIR}" ) &
started=$SECONDS
got="$(detect_bridge 15)"
elapsed=$(( SECONDS - started ))
wait
if [[ "$got" == "podman|sudo -n -u svcuser podman" && $elapsed -ge 2 ]]; then
  ok "a runtime that comes up late is waited for, and then found (${elapsed}s)"
else
  bad "the boot race is not waited out: got '${got}' after ${elapsed}s -- $(detect_err)"
fi

# The bound matters as much as the wait. A runtime that never appears must still end in the
# refusal detection always gave -- later, and saying that it waited.
rm -rf "${RUNDIR:?}"
started=$SECONDS
got="$(detect_bridge 3)"
elapsed=$(( SECONDS - started ))
if [[ -z "$got" && $elapsed -ge 3 && $elapsed -lt 10 ]] && grep -q "after waiting" "${WORK}/detect.err" \
   && grep -q "never became visible" "${WORK}/detect.err"; then
  ok "...and the wait is bounded: it refuses after ${elapsed}s, says it waited and that the runtime never showed"
else
  bad "a runtime that never comes up was not refused within the bound: got '${got}' after ${elapsed}s -- $(detect_err)"
fi

# THE 2026-09-25 SHAPE, first half: the directory EXISTS but is not the service user's -- the bare
# mount point a job sees when its sandbox was built before the runtime was mounted. The old wait
# looked only for the directory, found it, and let podman refuse at once. Now it is not taken for a
# runtime: podman is never run against it (with no runtime to find, podman sets up a namespace of
# its own inside the sandbox, which once left a pause process that broke the stack), and the
# refusal names the missing ordering.
mkdir -p "${RUNBASE}/4242"       # exists, and is not owned by the (stubbed) service uid 4242
export STUB_READY_DIR="${RUNBASE}/4242"
started=$SECONDS
got="$(STUB_SVC_UID=4242 detect_bridge 3)"
elapsed=$(( SECONDS - started ))
rm -rf "${RUNBASE:?}/4242"
if [[ -z "$got" && $elapsed -ge 3 ]] && ! grep -q "podman" "${WORK}/sudo.log" \
   && grep -q "20-service-user.conf" "${WORK}/detect.err"; then
  ok "a runtime directory the service user does not own is not a runtime: no podman, and the refusal names the ordering"
else
  bad "the bare mount point was taken for a runtime: got '${got}' after ${elapsed}s, sudo calls: $(tr '\n' ';' < "${WORK}/sudo.log") -- $(detect_err)"
fi

# Second half, and the case that FAILED on the code before this: the runtime is visible and podman
# refuses because the manager has not run its first container yet. The manager says `starting`, so
# that refusal is waited out -- and ends the moment podman can join.
mkdir -p "${RUNDIR}"                # the runtime is up and visible from here on
export STUB_READY_DIR="${WORK}/first-container"
rm -rf "${STUB_READY_DIR:?}"
( sleep 3; mkdir -p "${WORK}/first-container" ) &
started=$SECONDS
got="$(STUB_MANAGER_STATE=starting detect_bridge 15)"
elapsed=$(( SECONDS - started ))
wait
if [[ "$got" == "podman|sudo -n -u svcuser podman" && $elapsed -ge 2 && $elapsed -lt 10 ]]; then
  ok "a visible runtime that refuses while its manager is STARTING is waited out, and then found (${elapsed}s)"
else
  bad "the 2026-09-25 boot shape is not waited out: got '${got}' after ${elapsed}s -- $(detect_err)"
fi
rm -rf "${WORK}/first-container"

# ...bounded as well, and this time the refusal carries podman's own words: the 2026-09-25 line
# named no cause, because podman's stderr was thrown away.
export STUB_READY_DIR="${WORK}/never-ready"
started=$SECONDS
got="$(STUB_MANAGER_STATE=- detect_bridge 3)"
elapsed=$(( SECONDS - started ))
if [[ -z "$got" && $elapsed -ge 3 && $elapsed -lt 10 ]] && grep -q "after waiting" "${WORK}/detect.err" \
   && grep -q "podman said: Error: set sticky bit on" "${WORK}/detect.err"; then
  ok "...bounded too, for a manager that never answers, and the refusal quotes podman"
else
  bad "a manager that never finishes coming up was not refused within the bound: got '${got}' after ${elapsed}s -- $(detect_err)"
fi

# The opposite case must NOT wait. A runtime whose manager says it is RUNNING (or degraded) is up,
# so a podman that refuses is a real answer -- waiting there would only make every genuine refusal
# two minutes slower.
for state in running degraded; do
  started=$SECONDS
  got="$(STUB_MANAGER_STATE=${state} detect_bridge 15)"
  elapsed=$(( SECONDS - started ))
  if [[ -z "$got" && $elapsed -lt 3 ]] && grep -q "podman said:" "${WORK}/detect.err"; then
    ok "a runtime whose manager is ${state} but refuses is answered at once, with podman's reason (${elapsed}s)"
  else
    bad "detection waited on a runtime that was already ${state}: got '${got}' after ${elapsed}s -- $(detect_err)"
  fi
done

# And the steady state -- every tick but the first after a boot -- costs nothing new: podman answers
# first time, so the manager is never asked and nothing waits.
export STUB_READY_DIR="${RUNDIR}"
got="$(detect_bridge 15)"
if [[ "$got" == "podman|sudo -n -u svcuser podman" ]] && ! grep -q "is-system-running" "${WORK}/sudo.log"; then
  ok "a runtime that answers is used at once, without asking the manager anything"
else
  bad "the steady-state probe changed: got '${got}', sudo calls: $(tr '\n' ';' < "${WORK}/sudo.log")"
fi
unset STUB_READY_DIR

say ""
say "== waiting for the service user's manager to FINISH starting =="
# The second half of the same boot. user@994.service was active at 16:26:03 -- it reports ready as
# soon as the manager runs -- and the manager logged "Startup finished in 1min 30.765s" at 16:27:33.
# A job that only waited for the runtime would have quiesced the backend while it was still coming
# up. rt_wait_for_startup waits on the manager's own state instead.
STARTUP="${WORK}/startup"; mkdir -p "$STARTUP"
# Answers the next state from STUB_STATES on every call; the last one repeats. "-" stands for a
# manager that does not answer at all, which prints nothing.
printf '#!%s\n' "$ABS_BASH" > "${STARTUP}/systemctl"
cat >> "${STARTUP}/systemctl" <<'STUB'
read -r -a states <<< "${STUB_STATES}"
n="$(cat "${STUB_COUNTER}" 2>/dev/null || echo 0)"
i=$(( n < ${#states[@]} ? n : ${#states[@]} - 1 ))
echo $(( n + 1 )) > "${STUB_COUNTER}"
state="${states[$i]}"
[ "${state}" = "-" ] && exit 1
printf '%s\n' "${state}"
[ "${state}" = "running" ]
STUB
chmod +x "${STARTUP}/systemctl"

# Prints "rc=<n> calls=<n>". $1 backend (podman), $2 the states, $3 RT_STARTUP_WAIT.
startup_case() {
  # shellcheck disable=SC2030,SC2031,SC2034
  (
    set +e
    export STUB_STATES="$2" STUB_COUNTER="${WORK}/startup.count"
    rm -f "${STUB_COUNTER}"
    export RT_BACKEND="$1" RT_CLI="" RT_SYSTEMCTL="${STARTUP}/systemctl --user" RT_UNIT_DIR="${WORK}/units"
    RT_POLL_INTERVAL=1
    RT_STARTUP_WAIT="${3:-10}"
    # shellcheck disable=SC1090
    . "$LIB"
    rt_detect
    ( rt_wait_for_startup ) 2>"${WORK}/startup.err"
    rc=$?
    printf 'rc=%s calls=%s' "$rc" "$(cat "${STUB_COUNTER}" 2>/dev/null || echo 0)"
  )
}
expect_startup() { # label backend states wait want
  local got; got="$(startup_case "$2" "$3" "$4")"
  if [[ "$got" == "$5" ]]; then ok "$1"; else
    bad "$1 -- wanted '$5', got '${got}' ($(tr '\n' ' ' < "${WORK}/startup.err"))"
  fi
}
expect_startup "a manager that is running is not waited for"   podman "running"                  10 "rc=0 calls=1"
expect_startup "degraded is finished too -- a failed healthcheck unit is routine" \
                                                                podman "degraded"                 10 "rc=0 calls=1"
expect_startup "a manager still starting is waited out"        podman "starting starting running" 10 "rc=0 calls=3"
expect_startup "a manager not answering yet is waited out too" podman "- - degraded"             10 "rc=0 calls=3"
expect_startup "shutting down is not a moment to start a job, and it says so at once" \
                                                                podman "stopping"                 10 "rc=1 calls=1"
got="$(startup_case podman "starting" 3)"
if [[ "$got" == rc=1* ]] && grep -q "still 'starting' after 3s" "${WORK}/startup.err"; then
  ok "a startup that never finishes is refused after the bound, naming the state it was stuck in"
else
  bad "a stuck startup was not refused within the bound: got '${got}' ($(tr '\n' ' ' < "${WORK}/startup.err"))"
fi

# The function is only half of it; the other half is WHO calls it. Nothing else exercises the three
# maintenance scripts end to end, so a refactor that dropped one call would be invisible until the
# next reboot paged again. And deploy.sh must NOT call it: a stack stuck in `starting` may be exactly
# what the next release exists to fix, and a deployer that waits for startup could never deliver it.
for job in backup restore-drill container-cleanup; do
  if awk '/^rt_detect$/ { d = 1; next } d && /^rt_wait_for_startup$/ { found = 1 } END { exit !found }' \
       "${HERE}/${job}.sh"; then
    ok "${job}.sh waits for the manager's startup after detecting the runtime"
  else
    bad "${job}.sh no longer calls rt_wait_for_startup after rt_detect"
  fi
done
if grep -q '^[[:space:]]*rt_wait_for_startup' "${HERE}/deploy.sh"; then
  bad "deploy.sh waits for startup -- it must not; it may be the fix for a stuck one"
else
  ok "...and deploy.sh deliberately does not"
fi

say ""
say "== what starts the jobs at boot, and what they are ordered after =="
# Every iri-*.timer carried `Requires=<its service>` until 2026-09-25. That makes starting the TIMER
# start the service -- at every boot, when timers.target pulls the timers in -- without the timer
# elapsing and without LastTrigger moving. It is why all four deploy-account jobs ran one second
# into the 2026-09-25 reboot although none was due, and why iri-deploy's OnBootSec=5min never held.
# Reproduced in a systemd container the same day: the service started 3 ms after its timer.
# A directive in a comment does not count, so only lines that start with the key are read.
timers=0
for timer in "${HERE}"/iri-*.timer; do
  [[ -e "${timer}" ]] || continue
  timers=$((timers + 1))
  name="$(basename "${timer}")"
  if grep -Eq '^[[:space:]]*(Requires|Wants|BindsTo|Requisite|Upholds)[[:space:]]*=' "${timer}"; then
    bad "${name} pulls a unit in -- a timer only triggers, through Unit=; a pull-in runs the job at every boot"
  else
    ok "${name} pulls nothing in"
  fi
  if grep -Eq "^Unit=${name%.timer}\.service$" "${timer}"; then
    ok "${name} triggers ${name%.timer}.service through Unit="
  else
    bad "${name} does not name ${name%.timer}.service in Unit="
  fi
done
(( timers >= 6 )) || bad "expected the six iri-*.timer files beside this suite, found ${timers}"

# The ordering drop-in: the four deploy-account units wait for the service user's manager, so their
# sandbox is built after the runtime is mounted -- a sandbox built before it never sees it. After=
# only: a Wants= would make a tick start a manager an operator had stopped, and every container
# with it. The uid is the role's to fill in, so the template and the task are what is checked.
ROLE="${HERE}/../ansible/roles/basetool_host"
ORDER_TMPL="${ROLE}/templates/iri-deploy-account-order.conf.j2"
if grep -Eq '^After=.*systemd-logind\.service' "${ORDER_TMPL}" 2>/dev/null \
   && grep -Eq '^After=.*user@\{\{ basetool_host_service_uid \}\}\.service' "${ORDER_TMPL}"; then
  ok "the ordering drop-in orders after systemd-logind and user@<service uid>"
else
  bad "the ordering drop-in (${ORDER_TMPL}) does not order after systemd-logind.service and user@<uid>.service"
fi
if grep -Eq '^[[:space:]]*(Requires|Wants|BindsTo|Requisite|Upholds)[[:space:]]*=' "${ORDER_TMPL}" 2>/dev/null; then
  bad "the ordering drop-in pulls a unit in -- it must only order"
else
  ok "...and pulls nothing in"
fi
if awk '/src: iri-deploy-account-order\.conf\.j2/ { t = 1 }
        t && /dest: .*\{\{ item \}\}\.d\/20-service-user\.conf/ { d = 1 }
        t && d && /loop: "\{\{ basetool_host_deploy_account_units \}\}"/ { found = 1; exit }
        END { exit !found }' "${ROLE}/tasks/25-scripts.yml"; then
  ok "the role installs it beside every deploy-account unit"
else
  bad "tasks/25-scripts.yml no longer installs 20-service-user.conf beside basetool_host_deploy_account_units"
fi

say ""
say "== resolving a tag to a digest without pulling =="
expect_call "podman asks skopeo, which is why the role installs it" podman \
  'rt_resolve_digest ghcr.io/x/y:stable' 'skopeo inspect --no-tags docker://ghcr.io/x/y:stable'
expect_out  "podman returns the registry digest" podman \
  'rt_resolve_digest ghcr.io/x/y:stable' 'sha256:2222222222222222222222222222222222222222222222222222222222222222'
expect_no_call "resolving a tag never PULLS it" podman \
  'rt_resolve_digest ghcr.io/x/y:stable' 'podman pull'

say ""
say "== bringing the stack up, and WAITING for health =="
expect_call "podman reloads before starting, because units may have moved" podman \
  'rt_apply backend' 'systemctl --user daemon-reload'
expect_call "podman starts the unit, whose Type=notify IS the wait" podman \
  'rt_apply backend' 'systemctl --user start backend.service'
expect_out  "podman reports failure when a start does not reach healthy" podman \
  'STUB_FAIL="start_backend.service" rt_apply backend; echo "rc=$?"' 'rc=1'
expect_out  "...and success when it does" podman \
  'rt_apply backend; echo "rc=$?"' 'rc=0'

say ""
say "== pre-pulling the release, where a service name and a reference are NOT the same thing =="
# The regression this section exists for. rt_pull once took SERVICE NAMES, which
# `docker compose pull` could resolve through the compose file and podman cannot:
# `podman pull backend` is a bare name resolved against the host's
# unqualified-search registries, which fails and, at a call site running under
# `set -e`, aborted every Podman deploy at "pulling images". It takes the pair.
PULL_PAIRS="'backend=ghcr.io/krt-profit/basetool-backend@sha256:3333333333333333333333333333333333333333333333333333333333333333' 'ingest=ghcr.io/krt-profit/basetool-ingest@sha256:4444444444444444444444444444444444444444444444444444444444444444'"
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
expect_out "...and success stays success" podman \
  "rt_pull ${PULL_PAIRS}; echo \"rc=\$?\"" 'rc=0'

say ""
say "== finding the containers that belong to a service =="
expect_call "podman filters on the label Quadlet stamps, not on the name" podman \
  'rt_service_container_ids backend' 'label=PODMAN_SYSTEMD_UNIT=backend.service'
expect_out  "podman returns the id" podman 'rt_service_container_ids backend' 'cid-podman-1'

say ""
say "== judging one container =="
expect_out "a container reads as <state>/<health>" podman \
  'rt_container_probe cid-1' 'running/healthy'
expect_out "an unknown container reads as gone, not as an empty state" podman \
  'STUB_FAIL="inspect_--format" rt_container_probe cid-1' 'gone'

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
say "== the monitoring plane, a set of units told apart from the stack by name =="
mon() { printf 'export RT_MONITORING_SERVICES=%q;' "prometheus loki grafana"; }

# shellcheck disable=SC2016
# The snippets below are passed to `eval` inside run_rt, so `${WORK}` is expanded THERE, in the
# subshell that has the library loaded. Expanding it here would be the bug, not the fix.
#
# The defect that would have shipped. `systemctl start` on an ALREADY ACTIVE unit returns 0 and
# re-reads nothing, so a changed digest pin never reaches the running container: measured on the
# testing host, the drop-in named a new image, `start` returned 0, and the container went on
# running the old one while the deploy reported success.
# shellcheck disable=SC2016  # expanded by eval inside run_rt, not here
expect_call "a service this run re-pinned is RESTARTED, not started" podman   'RT_PIN_FILE="${WORK}/pin.yml" RT_UNIT_DIR="${WORK}/units" rt_pin_apply "backend=ghcr.io/x/backend@sha256:aaaa"; RT_STACK_SERVICES="backend db-backend" rt_apply_stack'   'restart backend.service'
# shellcheck disable=SC2016  # expanded by eval inside run_rt, not here
expect_call "...and one it did not is merely started, so the databases stay up" podman   'RT_PIN_FILE="${WORK}/pin.yml" RT_UNIT_DIR="${WORK}/units" rt_pin_apply "backend=ghcr.io/x/backend@sha256:bbbb"; RT_STACK_SERVICES="backend db-backend" rt_apply_stack'   'start db-backend.service'
# shellcheck disable=SC2016  # expanded by eval inside run_rt, not here
expect_no_call "...and the database is never restarted for somebody else's change" podman   'RT_PIN_FILE="${WORK}/pin.yml" RT_UNIT_DIR="${WORK}/units" rt_pin_apply "backend=ghcr.io/x/backend@sha256:cccc"; RT_STACK_SERVICES="backend db-backend" rt_apply_stack'   'restart db-backend.service'
# Idempotence: rewriting the identical pin must NOT count as a change, or every tick becomes a
# rolling restart of the whole stack.
# shellcheck disable=SC2016  # expanded by eval inside run_rt, not here
expect_no_call "an unchanged pin does not restart anything" podman   'RT_PIN_FILE="${WORK}/pin2.yml" RT_UNIT_DIR="${WORK}/units2" rt_pin_apply "backend=ghcr.io/x/backend@sha256:dddd"; RT_CHANGED_SERVICES=""; rt_pin_apply "backend=ghcr.io/x/backend@sha256:dddd"; RT_STACK_SERVICES="backend" rt_apply_stack'   'restart backend.service'

expect_call "podman starts each named unit" podman \
  "$(mon) rt_monitoring_up" 'systemctl --user start prometheus.service'
expect_call "...all of them, not just the first" podman \
  "$(mon) rt_monitoring_up" 'systemctl --user start grafana.service'
# The same trap as the digest pin above, on the monitoring half, and it shipped: until 2026-09-22
# this arm only ever said `start`, so a release that changed prometheus.container installed the unit
# and left the old container running.
expect_call "podman RESTARTS a monitoring unit this run re-defined" podman \
  "$(mon) RT_CHANGED_SERVICES='prometheus'; rt_monitoring_up" 'systemctl --user restart prometheus.service'
expect_no_call "...and leaves the others alone" podman \
  "$(mon) RT_CHANGED_SERVICES='prometheus'; rt_monitoring_up" 'restart loki.service'
# It runs twice on a deploy's success path. The second call must not recreate prometheus again.
expect_out "...once: a second apply in the same run only starts it" podman \
  "$(mon) RT_CHANGED_SERVICES='prometheus'; rt_monitoring_up; : > \"\${LOG}\"; rt_monitoring_up; grep -c 'restart prometheus' \"\${LOG}\" || true" '0'
# A restart that failed is kept, so the second call is its retry rather than a silent give-up. The
# stub fails `restart` ONLY: a plain `false` would fail the daemon-reload first, return before the
# loop, and leave the list untouched for the wrong reason -- a case that passes whatever the code does.
expect_out "...but a FAILED restart stays pending for the retry" podman \
  "$(mon) RT_CHANGED_SERVICES='prometheus'; norestart() { [[ \"\$1\" != restart ]]; }; RT_SYSTEMCTL=norestart; rt_monitoring_up; printf '[%s]' \"\${RT_CHANGED_SERVICES}\"" '[prometheus]'
expect_out "...and a successful one is not" podman \
  "$(mon) RT_CHANGED_SERVICES='prometheus'; rt_monitoring_up; printf '[%s]' \"\${RT_CHANGED_SERVICES}\"" '[]'
expect_call "a monitoring recreate touches one service only" podman \
  "$(mon) rt_monitoring_recreate loki" 'systemctl --user restart loki.service'
expect_no_call "...and never the whole plane" podman \
  "$(mon) rt_monitoring_recreate loki" 'start prometheus.service'

# alloy is a HOST service under Podman -- generate-quadlet.py translates it to one, because it
# carries group_add 4/473 to read root:adm files and a rootless container's namespace groups are
# not host groups. Its CONFIGURATION still rides the config bundle, so a release can change it and
# the reconcile has to restart something. Asking the SERVICE user's systemd about it fails with
# "Unit alloy.service not found", which deploy.sh reported as "monitoring stack down?" about a unit
# that was up -- on every tick, non-gating, while the new configuration never arrived.
expect_call "a host service is restarted through the SYSTEM manager" podman \
  "$(mon) RT_HOST_SYSTEMCTL=systemctl rt_monitoring_recreate alloy" 'systemctl restart alloy.service'
expect_no_call "...and never through the service user's, which has no such unit" podman \
  "$(mon) RT_HOST_SYSTEMCTL=systemctl rt_monitoring_recreate alloy" 'systemctl --user restart alloy.service'
# The routing must stay narrow: everything that IS a container still goes the ordinary way, or one
# host service turns the whole monitoring plane into system units nobody granted access to.
expect_call "a containerised one still goes to the service user" podman \
  "$(mon) RT_HOST_SYSTEMCTL=systemctl rt_monitoring_recreate prometheus" 'systemctl --user restart prometheus.service'

# The monitoring set is DERIVED from the unit directory (OPS-SIMP-04): every `.container` that is not
# an application service. deploy.sh and backup.sh each carried the nine names as a literal, and a
# list kept in step with compose by hand is how acme went missing from every list until 2026-09-22.
MONDIR="${WORK}/mon-units"; mkdir -p "$MONDIR"
for u in backend db-backend prometheus loki acme; do printf '[Container]\n' > "${MONDIR}/${u}.container"; done
got="$(run_rt podman "RT_MONITORING_SERVICES=''; RT_UNIT_DIR='${MONDIR}'; RT_STACK_SERVICES='db-backend backend acme'; rt_monitoring_services" 2>/dev/null | sort | tr '\n' ' ')"
if [[ "$got" == "loki prometheus " ]]; then
  ok "the monitoring units are the unit directory minus the stack"
else
  bad "derived monitoring set was '${got}', wanted 'loki prometheus'"
fi
expect_call "...and rt_monitoring_up starts exactly those" podman \
  "RT_MONITORING_SERVICES=''; RT_UNIT_DIR='${MONDIR}'; RT_STACK_SERVICES='db-backend backend acme'; rt_monitoring_up" \
  'systemctl --user start loki.service'
expect_no_call "...never an application unit" podman \
  "RT_MONITORING_SERVICES=''; RT_UNIT_DIR='${MONDIR}'; RT_STACK_SERVICES='db-backend backend acme'; rt_monitoring_up" \
  'start backend.service'
expect_out "an empty unit directory is 'not configured', not an error" podman \
  "RT_MONITORING_SERVICES=''; RT_UNIT_DIR='${WORK}/nothing-here'; rt_monitoring_configured; echo \"rc=\$?\"" 'rc=1'

say ""
say "== reading state out for the backup =="
# One primitive serves a host path and a named volume, because podman accepts
# either in the same position. That is what let the edge's TLS material -- which
# lives in volumes -- be captured by the same code that reads the keystore.
expect_call "a named volume is read through the helper" podman \
  'rt_read_mount edge-certs postgres:18-alpine tar -C /src -cz .' \
  'run --rm -v edge-certs:/src:ro postgres:18-alpine tar -C /src -cz .'
expect_call "...and a host path identically" podman \
  'rt_read_mount /var/iri/monitoring postgres:18-alpine cat /src/x' \
  'run --rm -v /var/iri/monitoring:/src:ro'
expect_call "the mount is read-only, so a backup cannot write to what it reads" podman \
  'rt_read_mount edge-certs img tar -C /src -cz .' ':/src:ro'
expect_out "a volume that does not exist is reported, not assumed present" podman \
  'STUB_FAIL="volume inspect" rt_volume_exists edge-certs; echo "rc=$?"' 'rc=1'
expect_out "...and one that does" podman 'rt_volume_exists edge-certs; echo "rc=$?"' 'rc=0'

say ""
say "== the helper and drill image is db-backend's own digest pin (OPS-SEC-03) =="
# backup.sh and restore-drill.sh named `docker.io/library/postgres:18-alpine` by TAG: resolved at
# pull time, and a second pin Dependabot never touches. The unit already carries the digest.
mkdir -p "${WORK}/units" "${WORK}/bundle"
printf '[Container]\nImage=docker.io/postgres:18-alpine@sha256:%s\nContainerName=db-backend\n' \
  "$(printf 'd%.0s' $(seq 1 64))" > "${WORK}/units/db-backend.container"
printf '[Container]\nImage=docker.io/postgres:18-alpine@sha256:%s\n' \
  "$(printf 'b%.0s' $(seq 1 64))" > "${WORK}/bundle/db-backend.container"
expect_out "the installed unit's Image= is read, digest and all" podman \
  'rt_unit_image db-backend '"${WORK}"'/bundle' \
  'docker.io/postgres:18-alpine@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
expect_out "the bundle copy is the fallback when the installed unit is not there" podman \
  'RT_UNIT_DIR='"${WORK}"'/nowhere rt_unit_image db-backend '"${WORK}"'/bundle' \
  'sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
expect_out "no unit anywhere is a non-zero answer, not an empty image name" podman \
  'RT_UNIT_DIR='"${WORK}"'/nowhere rt_unit_image db-backend '"${WORK}"'/nowhere; echo "rc=$?"' \
  'rc=1'
# The real unit, so the helper keeps working the day the generator changes the line's shape.
expect_out "the generated db-backend unit carries a digest the backup can use" podman \
  'RT_UNIT_DIR='"${HERE}"'/../quadlet/systemd rt_unit_image db-backend' \
  '@sha256:'

say ""
say "== the weekly TSDB snapshot is asked from inside prometheus (OPS-SEC-02) =="
# It used to be `podman run curlimages/curl:8.11.1 -u grafana:<password> ...`: a short image name
# rootless podman refuses without a TTY, and the password in the argv of a process every account on
# the host can read through /proc.
expect_call "the snapshot is requested through exec into prometheus" podman \
  'rt_prometheus_snapshot' 'exec prometheus sh -c'
expect_call "...reading the password from the container's own mounted secret" podman \
  'rt_prometheus_snapshot' '/etc/prometheus/secrets/web_password'
expect_call "...and POSTing to the admin API on loopback" podman \
  'rt_prometheus_snapshot' 'http://127.0.0.1:9090/api/v1/admin/tsdb/snapshot'
expect_no_call "no helper container is started for it" podman \
  'rt_prometheus_snapshot' 'run --rm'
expect_no_call "...and no curl image is named at all" podman \
  'rt_prometheus_snapshot' 'curlimages'
expect_call "the snapshot is removed inside the container that owns the TSDB" podman \
  'rt_prometheus_snapshot_remove 20260927T041500Z-7a1b2c3d4e5f' \
  'exec prometheus rm -rf /prometheus/snapshots/20260927T041500Z-7a1b2c3d4e5f'
expect_out "a name that could leave the snapshot directory is refused before it reaches rm" podman \
  'rt_prometheus_snapshot_remove "../../etc"; echo "rc=$?"' 'rc=1'
expect_no_call "...and nothing is run for it" podman \
  'rt_prometheus_snapshot_remove "../x"' 'rm -rf'

say ""
say "== the quiesce: a pause, not a release =="
expect_call "podman stops each writer unit" podman \
  'rt_service_stop frontend backend' 'systemctl --user stop frontend.service'
expect_call "and starts them again" podman \
  'rt_service_start frontend' 'systemctl --user start frontend.service'
# The quiesce must NOT apply the pin or wait for health -- that is a release
# operation, and the backup is meant to put the stack back exactly as it was.
expect_no_call "the quiesce does not re-apply the digest pin" podman \
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
# STREAMED in under podman: RT_CLI is `sudo -u <svc> podman`, so a plain `cp` would have the
# service user read a file in the deploy account's 0700 working tree. `exec -i` has the CALLER do
# the reading, and the bytes cross the boundary on stdin.
#
# NOT `podman cp -`, which is what this used to be: it stops reading once it has extracted the
# entry, so tar's trailing blocks land in a closed pipe and it exits 125 -- with the file complete
# in the container. Measured on the production host 2026-09-22, ten rounds each: `cp -` failed
# 10/10 while delivering correctly 10/10, `exec -i` passed 10/10. The restore drill aborted on its
# second copy and reported four artifacts unrestorable that it had never got as far as testing.
printf 'a dump\n' > "${WORK}/krt_basetool.dump"
expect_call "a dump is streamed in on stdin, not read by the service user" podman \
  'rt_cp_to '"${WORK}"'/krt_basetool.dump iri-restore-drill /tmp/krt_basetool.dump' \
  'exec -i iri-restore-drill sh -c'
expect_no_call "...and never through podman cp -, which exits 125 on a completed copy" podman \
  'rt_cp_to '"${WORK}"'/krt_basetool.dump iri-restore-drill /tmp/krt_basetool.dump' \
  'cp -'
expect_no_call "...so the service user never opens the deploy account's file" podman \
  'rt_cp_to '"${WORK}"'/krt_basetool.dump iri-restore-drill /tmp/krt_basetool.dump' \
  "cp ${WORK}/krt_basetool.dump"
expect_call "and it is removed whatever state it is in" podman \
  'rt_rm_force iri-restore-drill' 'rm -f -v iri-restore-drill'

say ""
say "== credentials never reach a command line =="
printf 'hunter2\n' > "${WORK}/token"
expect_call "the password is piped, not passed" podman \
  'rt_login ghcr.io someone '"${WORK}"'/token' '--password-stdin'
expect_no_call "the value itself is never in an argv" podman \
  'rt_login ghcr.io someone '"${WORK}"'/token' 'hunter2'

say ""
say "== lib/common.sh: the helpers the four operational scripts share (OPS-SIMP-04) =="
COMMON="${HERE}/lib/common.sh"
CENV="${WORK}/common-env"; mkdir -p "${CENV}/textfile"
printf 'A=1\nIRI_MONITORING_ENABLED = "true"\nKEY=first\nKEY=last\nQ=\x27single\x27\n' > "${CENV}/.env"
common() { # $1 snippet -- runs it with lib/common.sh loaded against the fixture
  ( set +e; export COMPOSE_DIR="${CENV}" TEXTFILE_DIR="${CENV}/textfile"
    # shellcheck disable=SC1090
    . "$COMMON"; eval "$1" )
}
got="$(common 'read_env IRI_MONITORING_ENABLED')"
if [[ "$got" == "true" ]]; then ok "read_env strips whitespace and quotes"; else bad "read_env gave '${got}'"; fi
got="$(common 'read_env KEY')"
if [[ "$got" == "last" ]]; then ok "the last assignment wins, as for compose"; else bad "read_env gave '${got}'"; fi
got="$(common 'read_env Q')"
if [[ "$got" == "single" ]]; then ok "single quotes are stripped too"; else bad "read_env gave '${got}'"; fi
got="$(common 'read_env MISSING; echo "[$?]"')"
if [[ "$got" == "[0]" ]]; then ok "a missing key is empty, not an error"; else bad "read_env gave '${got}'"; fi
got="$(common 'read_env "A;rm -rf x"; echo "rc=$?"')"
if [[ "$got" == "rc=1" ]]; then ok "a key that is not a variable name is refused before it reaches sed"; else bad "read_env gave '${got}'"; fi
common 'printf "m 1\n" | write_textfile t.prom' >/dev/null
if [[ "$(cat "${CENV}/textfile/t.prom" 2>/dev/null)" == "m 1" ]]; then ok "write_textfile writes stdin to the named file"; else bad "write_textfile wrote nothing"; fi
leftovers="$(find "${CENV}/textfile" -name '.t.prom.*' | wc -l | tr -d ' ')"
if [[ "$leftovers" == 0 ]]; then ok "...through a temporary file that does not survive"; else bad "${leftovers} temp file(s) left"; fi
got="$(common 'printf "x\n" | write_textfile "../evil.prom"; echo "rc=$?"')"
if [[ "$got" == *"rc=1" ]]; then ok "a name that leaves the directory is refused"; else bad "write_textfile accepted ../evil.prom: '${got}'"; fi
got="$(common 'TEXTFILE_DIR=/nonexistent/x/y; printf "x\n" | write_textfile u.prom; echo "rc=$?"')"
if [[ "$got" == *"WARN"*"rc=1" ]]; then ok "an unwritable directory is a WARN and a non-zero return, never a crash"; else bad "write_textfile on an unwritable dir: '${got}'"; fi
got="$(common 'fail "boom"; echo survived' 2>&1)"
if [[ "$got" == *"FATAL: boom"* && "$got" != *survived* ]]; then ok "fail logs FATAL and exits"; else bad "fail gave '${got}'"; fi
for job in deploy backup restore-drill container-cleanup; do
  # shellcheck disable=SC2016 # a literal `${...SCRIPT_DIR}` is what the pattern looks for
  if grep -q '^\. "\${[A-Z_]*SCRIPT_DIR}/lib/common.sh"' "${HERE}/${job}.sh" \
     && ! grep -qE '^(log|fail|read_env)\(\)' "${HERE}/${job}.sh"; then
    ok "${job}.sh uses lib/common.sh and keeps no copy of its helpers"
  else
    bad "${job}.sh does not source lib/common.sh, or still defines its own log/fail/read_env"
  fi
done

say ""
printf '%d passed, %d failed\n' "$PASSED" "$FAILED"
[[ $FAILED -eq 0 ]]
