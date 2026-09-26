#!/usr/bin/env bash
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

USER_SLICE="${FAKE}/user.slice/user-1000.slice/user@1000.service"

make_container() {
  local dir=$1 memmax=$2 pidsmax=$3 oom=$4 throttled=$5 periods=${6:-4200}
  mkdir -p "$dir"
  cat > "${dir}/cpu.stat" <<EOF
usage_usec 2500000
user_usec 1500000
system_usec 1000000
nice_usec 0
nr_periods ${periods}
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
file_mapped 31457280
inactive_file 20971520
slab 1048576
EOF
  printf '%s\n' "134217728"  > "${dir}/memory.current"
  printf '%s\n' "${memmax}"  > "${dir}/memory.max"
  printf '%s\n' "42"         > "${dir}/pids.current"
  printf '%s\n' "${pidsmax}" > "${dir}/pids.max"
}

make_quadlet() {
  local unit=$1
  make_container "$unit" max 97670 0 0 0
  make_container "${unit}/runtime" max max 0 0 0
  make_container "${unit}/libpod-payload-$(printf 'a%.0s' {1..64})" "$2" "$3" "$4" "$5"
  printf '%s\n' "4242" > "${unit}/libpod-payload-$(printf 'a%.0s' {1..64})/cgroup.procs"
}

say "building a fake cgroup tree in ${FAKE}"
make_quadlet "${USER_SLICE}/backend.service" 268435456 100 0 0
make_quadlet "${USER_SLICE}/app.slice/edge.service" max max 0 0
make_quadlet "${USER_SLICE}/acme.service" 268435456 100 3 7
make_container "${USER_SLICE}/app.slice/podman-exporter.service" 67108864 max 0 0
make_container \
  "${USER_SLICE}/app.slice/$(printf 'e%.0s' {1..64})-623ad299e33d18f7.service" max max 0 0
make_container "${USER_SLICE}/app.slice/keycloak.service" max 97670 0 0 0
make_container "${USER_SLICE}/app.slice/keycloak.service/libpod-payload-$(printf '0%.0s' {1..64})" \
  111 max 0 0
make_container "${USER_SLICE}/app.slice/keycloak.service/libpod-payload-$(printf 'f%.0s' {1..64})" \
  2684354560 2048 0 0
printf '%s\n' "4343" \
  > "${USER_SLICE}/app.slice/keycloak.service/libpod-payload-$(printf 'f%.0s' {1..64})/cgroup.procs"
mkdir -p "${USER_SLICE}/ghost.service"
make_container "${FAKE}/system.slice/alloy.service" 536870912 max 1 0
make_container "${FAKE}/system.slice/prometheus-node-exporter.service" 67108864 max 0 0
make_container "${FAKE}/system.slice/sshd.service" max max 0 0

say ""
say "== it reads a rootless-Quadlet tree with the default pattern =="
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
assert_absent "a healthcheck's transient unit is not a container" 'name="[0-9a-f]{64}-'
assert_absent "neither is the payload or conmon's runtime cgroup" 'name="(libpod-payload-|runtime)'
assert_line "counts only the containers it emitted" '^basetool_container_metrics_containers 5$'
assert_line "stamps when it ran"              '^basetool_container_metrics_timestamp_seconds [0-9]'

say ""
say "== a Quadlet container is read from its payload cgroup, not from its unit =="
assert_line "the memory limit is the payload's" \
  '^basetool_container_memory_limit_bytes\{name="backend"\} 268435456$'
assert_line "the pids ceiling is the payload's" \
  '^basetool_container_pids_max\{name="backend"\} 100$'
assert_line "CFS periods are the payload's, where the quota is" \
  '^basetool_container_cpu_periods_total\{name="backend"\} 4200$'
assert_line "a unit without a payload is read from its own cgroup" \
  '^basetool_container_memory_limit_bytes\{name="podman-exporter"\} 67108864$'
assert_line "mid-recreate, the payload that holds a process wins" \
  '^basetool_container_memory_limit_bytes\{name="keycloak"\} 2684354560$'

say ""
say "== the two host services are read too, under the names they had as containers =="
assert_line "alloy is read from system.slice/alloy.service" \
  '^basetool_container_memory_limit_bytes\{name="alloy"\} 536870912$'
assert_line "...under its container name, so ContainerOomKilled keeps its meaning" \
  '^basetool_container_oom_kills_total\{name="alloy"\} 1$'
assert_line "node-exporter is read from its package's unit name" \
  '^basetool_container_memory_limit_bytes\{name="node-exporter"\} 67108864$'
assert_absent "an unnamed system unit stays invisible" 'name="sshd'
assert_line "host services are counted on their own" '^basetool_container_metrics_host_services 2$'
assert_line "...and not as containers" '^basetool_container_metrics_containers 5$'

if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" --no-host-services --dry-run 2>/dev/null \
     | grep -q 'name="alloy"'; then
  bad "--no-host-services reads containers only"
else
  ok "--no-host-services reads containers only"
fi
if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" --host-service 'alloy.service' --dry-run >/dev/null 2>&1; then
  bad "a --host-service without =NAME is rejected"
else
  ok "a --host-service without =NAME is rejected"
fi
if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" --pattern 'nothing-(?P<name>x)$' --dry-run 2>/dev/null \
     | grep -q '^basetool_container_metrics_containers 0$'; then
  ok "no containers but running host services writes containers 0"
else
  bad "no containers but running host services writes containers 0"
fi

say ""
say "== the values are the ones the alerts will read =="
assert_line "memory limit from memory.max" \
  '^basetool_container_memory_limit_bytes\{name="backend"\} 268435456$'
assert_line "pids ceiling from pids.max" \
  '^basetool_container_pids_max\{name="backend"\} 100$'
assert_line "anon memory is the RSS analogue" \
  '^basetool_container_memory_anon_bytes\{name="backend"\} 104857600$'
assert_line "working set is current minus reclaimable page cache" \
  '^basetool_container_memory_working_set_bytes\{name="backend"\} 113246208$'
assert_line "mapped file pages, the third series of the memory breakdown" \
  '^basetool_container_memory_mapped_file_bytes\{name="backend"\} 31457280$'
assert_line "cpu seconds scaled from usec" \
  '^basetool_container_cpu_usage_seconds_total\{name="backend"\} 2\.5$'
assert_line "throttled seconds scaled from usec" \
  '^basetool_container_cpu_throttled_seconds_total\{name="backend"\} 1\.25$'

assert_line "OOM kills survive from memory.events" \
  '^basetool_container_oom_kills_total\{name="acme"\} 3$'
assert_line "throttled periods survive from cpu.stat" \
  '^basetool_container_cpu_throttled_periods_total\{name="acme"\} 7$'
assert_line "a quiet container reports zero OOM kills, not nothing" \
  '^basetool_container_oom_kills_total\{name="backend"\} 0$'

say ""
say "== an unlimited cgroup becomes +Inf, not a sentinel =="
assert_line "memory.max=max renders +Inf" \
  '^basetool_container_memory_limit_bytes\{name="edge"\} \+Inf$'
assert_line "pids.max=max renders +Inf" \
  '^basetool_container_pids_max\{name="edge"\} \+Inf$'

say ""
say "== the file is well-formed and written atomically =="
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

say ""
say "== it refuses to produce a misleading file =="
if "$PY" "$COLLECTOR" --cgroup-root "$FAKE" --no-host-services --pattern 'nothing-(?P<name>x)$' \
     --dry-run >/dev/null 2>&1; then
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

say ""
say "== Prometheus itself accepts the output =="
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

say ""
say "-------------------------------------------------------------"
say "${PASSED} passed, ${FAILED} failed"
[ "$FAILED" -eq 0 ] || exit 1
