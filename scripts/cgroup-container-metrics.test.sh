#!/usr/bin/env bash
# =============================================================================================
# Self-test for scripts/cgroup-container-metrics.py
#
# The collector reads cgroup v2 files and writes them where node_exporter's textfile collector
# will serve them. Two things make it worth testing hermetically rather than against a live host:
#
#   - the values it produces feed alerts that replace the cAdvisor ones, so a silent parsing bug
#     is an alert that never fires, which looks exactly like a healthy system;
#   - the cases that matter most are the ones a healthy host never shows -- a prior OOM kill, a
#     throttled cgroup, an unlimited one -- and waiting for production to produce them is not a
#     test strategy.
#
# So it builds a fake cgroup tree with those states baked in and asserts the output. No kernel, no
# containers, no privileges. The layout mirrors rootless Quadlet exactly, so the collector's
# DEFAULT pattern is what gets exercised rather than a convenient one.
#
# If docker is available it also runs `promtool check metrics` over the result, because "I read
# the output and it looked like exposition format" is not the same claim as "Prometheus accepts
# it". Skipped, not failed, where docker is absent.
#
# Requires: bash, python3. Optional: docker (for the promtool leg).
#
#   bash scripts/cgroup-container-metrics.test.sh
# =============================================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLLECTOR="${HERE}/cgroup-container-metrics.py"
PY="${PYTHON:-python}"
command -v "$PY" >/dev/null 2>&1 || PY=python3

WORK="$(mktemp -d)"
FAKE="${WORK}/cgroup"
OUT="${WORK}/containers.prom"

PASSED=0
FAILED=0

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

say() { printf '%s\n' "$*"; }
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

# assert_line <label> <extended-regex>
assert_line() {
  local label=$1 pattern=$2
  if grep -Eq -- "$pattern" "$OUT"; then
    ok "$label"
  else
    bad "$label - no line matching /$pattern/"
  fi
}

assert_absent() {
  local label=$1 pattern=$2
  if grep -Eq -- "$pattern" "$OUT"; then
    bad "$label - unexpectedly found /$pattern/"
  else
    ok "$label"
  fi
}

# ---------------------------------------------------------------------------------------------
# A fake cgroup tree, shaped exactly like rootless Quadlet's so the DEFAULT pattern is tested.
# ---------------------------------------------------------------------------------------------
USER_SLICE="${FAKE}/user.slice/user-1000.slice/user@1000.service"

make_container() { # dir  memory.max  pids.max  oom_kill  nr_throttled
  local dir=$1 memmax=$2 pidsmax=$3 oom=$4 throttled=$5
  mkdir -p "$dir"
  cat > "${dir}/cpu.stat" <<EOF
usage_usec 2500000
user_usec 1500000
system_usec 1000000
nice_usec 0
nr_periods 4200
nr_throttled ${throttled}
throttled_usec 1250000
nr_bursts 0
burst_usec 0
EOF
  cat > "${dir}/memory.events" <<EOF
low 0
high 0
max 0
oom 0
oom_kill ${oom}
oom_group_kill 0
EOF
  cat > "${dir}/memory.stat" <<EOF
anon 104857600
file 52428800
inactive_file 20971520
slab 1048576
EOF
  printf '%s\n' "134217728"  > "${dir}/memory.current"
  printf '%s\n' "${memmax}"  > "${dir}/memory.max"
  printf '%s\n' "42"         > "${dir}/pids.current"
  printf '%s\n' "${pidsmax}" > "${dir}/pids.max"
}

say "building a fake cgroup tree in ${FAKE}"
# limits set, quiet
make_container "${USER_SLICE}/backend.service" 268435456 100 0 0
# unlimited -- both ceilings read the literal string "max"
make_container "${USER_SLICE}/app.slice/edge.service" max max 0 0
# has been OOM-killed before and is being throttled now
make_container "${USER_SLICE}/acme.service" 268435456 100 3 7
# a directory that matches the pattern but publishes nothing: a container that exited between
# the walk and the read. It must be dropped, not emitted with zeros.
mkdir -p "${USER_SLICE}/ghost.service"

# =============================================================================================
say ""
say "== it reads a rootless-Quadlet tree with the default pattern =="
# =============================================================================================
if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" --output "$OUT" >/dev/null 2>&1; then
  ok "exits 0 and writes the file"
else
  bad "exits 0 and writes the file"
  say "  (cannot continue without output)"; say "${PASSED} passed, ${FAILED} failed"; exit 1
fi

assert_line "finds backend by its unit name"  '^basetool_container_pids\{name="backend"\} 42$'
assert_line "finds edge below app.slice"      '^basetool_container_pids\{name="edge"\} 42$'
assert_line "finds acme"                      '^basetool_container_pids\{name="acme"\} 42$'
assert_absent "drops a cgroup that publishes nothing" 'name="ghost"'
assert_line "counts only the containers it emitted" '^basetool_container_metrics_containers 3$'
assert_line "stamps when it ran"              '^basetool_container_metrics_timestamp_seconds [0-9]'

# =============================================================================================
say ""
say "== the values are the ones the alerts will read =="
# =============================================================================================
assert_line "memory limit from memory.max" \
  '^basetool_container_memory_limit_bytes\{name="backend"\} 268435456$'
assert_line "pids ceiling from pids.max" \
  '^basetool_container_pids_max\{name="backend"\} 100$'
assert_line "anon memory is the RSS analogue" \
  '^basetool_container_memory_anon_bytes\{name="backend"\} 104857600$'
# 134217728 - 20971520 = 113246208
assert_line "working set is current minus reclaimable page cache" \
  '^basetool_container_memory_working_set_bytes\{name="backend"\} 113246208$'
assert_line "cpu seconds scaled from usec" \
  '^basetool_container_cpu_usage_seconds_total\{name="backend"\} 2\.5$'
assert_line "throttled seconds scaled from usec" \
  '^basetool_container_cpu_throttled_seconds_total\{name="backend"\} 1\.25$'

# The three signals prometheus-podman-exporter does not have at all. If any of these regresses,
# ContainerOomKilled, ContainerCpuThrottledHigh and half of ContainerPidsHigh go blind.
assert_line "OOM kills survive from memory.events" \
  '^basetool_container_oom_kills_total\{name="acme"\} 3$'
assert_line "throttled periods survive from cpu.stat" \
  '^basetool_container_cpu_throttled_periods_total\{name="acme"\} 7$'
assert_line "a quiet container reports zero OOM kills, not nothing" \
  '^basetool_container_oom_kills_total\{name="backend"\} 0$'

# =============================================================================================
say ""
say "== an unlimited cgroup becomes +Inf, not a sentinel =="
# =============================================================================================
# A ratio against +Inf is zero, so `used / limit > 0.9` simply never fires for an unlimited
# container. A 0 there would divide by zero and a -1 would make it fire forever.
assert_line "memory.max=max renders +Inf" \
  '^basetool_container_memory_limit_bytes\{name="edge"\} \+Inf$'
assert_line "pids.max=max renders +Inf" \
  '^basetool_container_pids_max\{name="edge"\} \+Inf$'

# =============================================================================================
say ""
say "== the file is well-formed and written atomically =="
# =============================================================================================
# node_exporter refuses a textfile that declares the same metric's type twice, which is what a
# per-container layout produces and which parses fine by eye.
DUPES="$(grep -E '^# TYPE ' "$OUT" | awk '{print $3}' | sort | uniq -d)"
if [ -z "$DUPES" ]; then
  ok "each metric declares its TYPE exactly once"
else
  bad "duplicate TYPE declarations: $(printf '%s' "$DUPES" | tr '\n' ' ')"
fi

if [ -z "$(find "${WORK}" -maxdepth 1 -name '.*.tmp' -print -quit)" ]; then
  ok "leaves no temporary file behind"
else
  bad "leaves no temporary file behind"
fi

if [ "$(tail -c 1 "$OUT" | od -An -c | tr -d ' ')" = "\\n" ]; then
  ok "ends with a newline"
else
  bad "ends with a newline"
fi

# =============================================================================================
say ""
say "== it refuses to produce a misleading file =="
# =============================================================================================
# An empty metrics file and a broken pattern look identical to Prometheus, and only one of them
# is benign -- so finding nothing is an error rather than a file with nothing in it.
if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" --pattern 'nothing-(?P<name>x)$' --dry-run >/dev/null 2>&1; then
  bad "a pattern that matches nothing is an error"
else
  ok "a pattern that matches nothing is an error"
fi
if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" --pattern '[^/]+$' --dry-run >/dev/null 2>&1; then
  bad "a pattern without a name group is rejected"
else
  ok "a pattern without a name group is rejected"
fi
if "$PY" "$COLLECTOR" --cgroup-root "${WORK}/does-not-exist" --dry-run >/dev/null 2>&1; then
  bad "a missing cgroup root is an error"
else
  ok "a missing cgroup root is an error"
fi
if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" >/dev/null 2>&1; then
  bad "--output is required without --dry-run"
else
  ok "--output is required without --dry-run"
fi
if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" --output /does/not/exist/x.prom >/dev/null 2>&1; then
  bad "an unwritable output is an error"
else
  ok "an unwritable output is an error"
fi

# =============================================================================================
say ""
say "== Prometheus itself accepts the output =="
# =============================================================================================
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  if docker run --rm -i --entrypoint promtool prom/prometheus:v3.14.0 check metrics < "$OUT" \
       >/dev/null 2>&1; then
    ok "promtool check metrics accepts the file"
  else
    bad "promtool check metrics accepts the file"
    docker run --rm -i --entrypoint promtool prom/prometheus:v3.14.0 check metrics < "$OUT" 2>&1 \
      | sed 's/^/      /' | head -10
  fi
else
  say "  skip  promtool leg - docker is not available here"
fi

# =============================================================================================
say ""
say "-------------------------------------------------------------"
say "${PASSED} passed, ${FAILED} failed"
[ "$FAILED" -eq 0 ] || exit 1
