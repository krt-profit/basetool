#!/usr/bin/env bash
#
# Regression tests for scripts/generate-quadlet.py's compose-to-Quadlet translation.
#
# Pure python against synthetic service mappings -- no host, no containers, no
# network, runs in under a second.
#
# Usage:
#   scripts/generate-quadlet.test.sh
#
# Why this file exists. The generator had a drift check from the start, and the
# drift check compares the generator against its own output -- so a translation
# that is uniformly wrong is uniformly consistent and reports clean. Four defects
# reached the tree behind that green check (PR #1933 review, 2026-09-18):
#
#   * an exec-form `CMD` healthcheck re-joined into an unquoted shell string, so
#     a password containing a space broke the probe forever and one containing
#     `;` ran whatever followed, inside the container, every five seconds;
#   * `Notify=healthy` with no `TimeoutStartSec=`, capping four services at
#     systemd's 90s default -- keycloak's own numbers allow 330s -- which with
#     Restart=always is a restart loop that never reports healthy;
#   * raw systemd `%` specifiers copied into `Exec=`, measured LIVE on the
#     testing host: both databases were running with log_line_prefix expanded to
#     the machine id, the unit name and the architecture;
#   * a prefix-unsafe substring replace in the health-variable rewrite.
#
# Every one of them is a property of the translation, not of the output, which is
# the level this file tests at.

# shellcheck disable=SC2016
# The single quotes are the subject of the tests, not an oversight: the fixtures
# feed the translator LITERAL ${...} and `%` text, and a double-quoted string
# would let bash expand it before the translator ever saw it.

set -uo pipefail

# The shell on a Windows workstation rewrites any argument that looks like a POSIX path list --
# `./a:/b` reaches python as a Windows path list -- and the volume fixtures below are exactly
# that shape. Only arguments starting with `print(` -- the python bodies -- are exempted, so the
# generator's own path argument is still translated for a native python. Inert on Linux and in CI.
export MSYS2_ARG_CONV_EXCL='print('

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GENERATOR="${SCRIPT_DIR}/generate-quadlet.py"
PY="${PYTHON:-python3}"

if [[ ! -f "$GENERATOR" ]]; then
  echo "FATAL: generator not found at ${GENERATOR}" >&2
  exit 1
fi

PASSED=0
FAILED=0
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

# Runs a python expression against the loaded generator module and prints the
# result. $1 is the python body; it may use `g` for the module.
run_py() {
  "$PY" - "$GENERATOR" <<'PYEOF' "$1"
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("genquadlet", sys.argv[1])
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)

try:
    exec(sys.argv[2], {"g": g, "print": print})
except g.Refusal as exc:
    print("REFUSAL: " + str(exc).replace("\n", " "))
PYEOF
}

# $1 label, $2 python body, $3 expected substring
expect() {
  local label="$1" body="$2" want="$3" got
  got="$(run_py "$body" 2>&1)"
  if [[ "$got" == *"$want"* ]]; then
    ok "$label"
  else
    bad "$label"
    printf '        want substring: %s\n' "$want"
    printf '        got           : %s\n' "$got"
  fi
}

# $1 label, $2 python body, $3 substring that must NOT appear
expect_not() {
  local label="$1" body="$2" unwanted="$3" got
  got="$(run_py "$body" 2>&1)"
  if [[ "$got" != *"$unwanted"* ]]; then
    ok "$label"
  else
    bad "$label"
    printf '        must not contain: %s\n' "$unwanted"
    printf '        got             : %s\n' "$got"
  fi
}

echo "== finding 3: an exec-form CMD keeps its argument boundaries =="

expect "a value with a space stays ONE argument" \
  'print(g._health({"test": ["CMD", "redis-cli", "-a", "pass word", "ping"]}, {}, "redis")[0][0])' \
  "HealthCmd=redis-cli -a 'pass word' ping"

expect "a value with a semicolon cannot start a second command" \
  'print(g._health({"test": ["CMD", "redis-cli", "-a", "x; touch /tmp/pwned", "ping"]}, {}, "redis")[0][0])' \
  "'x; touch /tmp/pwned'"

expect "a value with a command substitution is inert" \
  'print(g._health({"test": ["CMD", "sh", "-c", "echo $(id -u)"]}, {}, "x")[0][0])' \
  "'echo \$(id -u)'"

expect "an embedded single quote is closed and reopened, not terminated" \
  'print(g._sh_quote("it'"'"'s"))' \
  "'it'\\''s'"

expect "a DELIBERATE \${VAR} keeps double quotes, so it still expands" \
  'print(g._health({"test": ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD:?}", "ping"]}, {"REDIS_PASSWORD": "x"}, "redis")[0][0])' \
  'HealthCmd=redis-cli -a "${REDIS_PASSWORD:?}" ping'

expect "a CMD-SHELL test is left exactly as the author wrote it" \
  'print(g._health({"test": ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:?} -p 15432"]}, {"POSTGRES_USER": "x"}, "db")[0][0])' \
  'HealthCmd=pg_isready -U ${POSTGRES_USER:?} -p 15432'

expect "an unrecognised test: keyword is refused rather than guessed" \
  'print(g._health({"test": ["SHELL", "true"]}, {}, "x"))' \
  "REFUSAL: x: its healthcheck \`test:\` is a list starting with 'SHELL'"

echo
echo "== finding 2: Notify=healthy comes with a start budget =="

expect "keycloak's own numbers, not systemd's 90s default" \
  'print(g._health({"test": ["CMD", "true"], "interval": "10s", "timeout": "10s", "retries": 15, "start_period": "30s"}, {}, "keycloak")[1][0])' \
  "TimeoutStartSec=390"

expect "Notify=healthy is still emitted" \
  'print(g._health({"test": ["CMD", "true"]}, {}, "x")[0])' \
  "Notify=healthy"

expect "a healthcheck-less service gets neither" \
  'print(g._health({}, {}, "x"))' \
  "([], [])"

expect "podman's own defaults are used when compose states none" \
  'print(g._health({"test": ["CMD", "true"]}, {}, "x")[1][0])' \
  "TimeoutStartSec=240"

expect "a compound duration parses" \
  'print(g._seconds("1m30s", "x"))' \
  "90"

expect "a sub-second duration rounds UP, because this is a budget" \
  'print(g._seconds("1500ms", "x"))' \
  "2"

expect "an unreadable duration is refused, not silently zero" \
  'print(g._seconds("soon", "x.healthcheck.interval"))' \
  "REFUSAL: x.healthcheck.interval: 'soon' is not a duration"

echo
echo "== finding 4: systemd specifiers cannot reach the running process =="

expect "postgres keeps its log_line_prefix literal" \
  'print(g._exec("db", {"command": "postgres -c log_line_prefix=%m [%p] %q%u@%d/%a"})[0])' \
  "Exec=postgres -c log_line_prefix=%%m [%%p] %%q%%u@%%d/%%a"

expect "an entrypoint is escaped too" \
  'print(g._exec("x", {"entrypoint": "/bin/sh -c echo %H"})[0])' \
  "Entrypoint=/bin/sh -c echo %%H"

expect "a percent in a health command is escaped" \
  'print(g._health({"test": ["CMD-SHELL", "df --output=pcent | grep 90%"]}, {}, "x")[0][0])' \
  "grep 90%%"

expect "nothing already doubled is left half-escaped" \
  'print(g._escape_percent("%"))' \
  "%%"

echo
echo "== finding 13: the health-variable rewrite respects name boundaries =="

expect "a longer name that starts with a shorter one survives" \
  'print(g._health({"test": ["CMD-SHELL", "check -U ${POSTGRES_USER} -x ${POSTGRES_USER_EXTRA}"]}, {"PGUSER": "${POSTGRES_USER}", "EXTRA": "${POSTGRES_USER_EXTRA}"}, "db")[0][0])' \
  'HealthCmd=check -U ${PGUSER} -x ${EXTRA}'

expect_not "and is NOT corrupted into a name the container never receives" \
  'print(g._health({"test": ["CMD-SHELL", "check -U ${POSTGRES_USER} -x ${POSTGRES_USER_EXTRA}"]}, {"PGUSER": "${POSTGRES_USER}", "EXTRA": "${POSTGRES_USER_EXTRA}"}, "db")[0][0])' \
  'PGUSER_EXTRA'

expect "a name the container is handed under no name at all is still refused" \
  'print(g._health({"test": ["CMD-SHELL", "check -U ${NOWHERE}"]}, {"A": "b"}, "db"))' \
  "REFUSAL: db: the health command names \${NOWHERE}"

echo
echo "== the host aliases that exist only under Quadlet =="
# node-exporter and alloy become HOST services here, so on a Podman host nothing answers to
# those names on the container network and prometheus.yml's targets go permanently down --
# measured on the testing host, two of its five down targets. The alias lives in the UNIT
# rather than in prometheus.yml, because that file rides the bundle and one bundle serves the
# Docker host too, where the same names must keep resolving to real containers.
expect "prometheus gets a host alias for alloy" \
  'print(" ".join(sorted(g.PODMAN_HOST_ALIASES["prometheus"])))' \
  'alloy'
expect "...and for node-exporter" \
  'print(" ".join(sorted(g.PODMAN_HOST_ALIASES["prometheus"])))' \
  'node-exporter'
# Each alias must name a service the translation actually made a host service, or the alias
# points at a host that is not serving and the target stays down with a new explanation.
expect "every alias names a service the generator made a host service" \
  'print(all(g.DISPOSITION.get(a, ("",))[0] == "host-service" for v in g.PODMAN_HOST_ALIASES.values() for a in v))' \
  'True'

# The push direction of the same boundary. The apps send spans TO alloy, and when it became a
# host service `alloy` stopped resolving inside them: measured on the testing host, both
# otelcol_receiver_accepted_spans_total and tempo_distributor_spans_received_total were ABSENT
# -- the trace pipeline had never carried a span and nothing reported it, because the drop
# happens in each app's own exporter, where no alert looks.
for svc in backend frontend ingest keycloak; do
  expect "${svc} can resolve alloy, which it pushes spans to" \
    "print('alloy' in g.PODMAN_HOST_ALIASES.get('${svc}', ()))" \
    'True'
done

echo "== every address the front end can present the PROXY header from =="
# The peer the edge presents to haproxy is the container's OWN address, and podman chooses WHICH of
# its networks that address comes from. Measured on the production host 2026-09-22, three recreates
# with nothing else changed: net-proxy-frontend, then net-proxy-grafana, then net-proxy-api. So the
# question is not which network to pin -- it is that ALL of them are pinned, which is the only thing
# that makes the candidate set finite.
#
# While only the ingress address was pinned: set_real_ip_from never matched, nginx discarded the
# PROXY header and the edge logged 2340 requests in ten minutes from one bridge address -- one
# rate-limit bucket for the whole internet, the 2026-07-20 outage reached by another road, with a
# valid configuration throughout.
expect "the ingress address is pinned" \
  'print(g.FRONT_END["edge"]["pins"]["net-edge-ingress"])' \
  '172.28.15.10'
expect "every network the edge joins is pinned, and no pin names a network it does not join" \
  'import io, yaml;
d = yaml.safe_load(io.open(g.COMPOSE_APP, encoding="utf-8"));
nets = d["services"]["edge"]["networks"];
print(sorted(nets) == sorted(g.FRONT_END["edge"]["pins"]))' \
  'True'
# High on purpose, except on the ingress network the edge has to itself: netavark allocates from
# the low end and these networks are shared with the upstream they front, whose container drifts
# upward on every recreate. A low pin is a collision waiting to happen.
expect "every shared-network pin is out of the allocator's way" \
  'print(all(int(a.split(".")[-1]) > 200 for n, a in g.FRONT_END["edge"]["pins"].items() if n != "net-edge-ingress"))' \
  'True'

# The three representations of that set -- this table, the emitted unit, and the role variable that
# becomes EDGE_TRUSTED_PROXY -- have to agree, and the generator REFUSES when they do not. The
# checks below mutate the table and read the refusal, because a guard that has never been seen to
# fail is a guard nobody has tested.
expect "the role's trusted list names exactly the pinned addresses" \
  'g.generate(); print("no refusal")' \
  'no refusal'
expect "a network joined without a pin is refused" \
  'g.FRONT_END["edge"]["pins"].pop("net-proxy-grafana"); g.generate()' \
  'REFUSAL: edge: joins net-proxy-grafana with no pinned address'
expect "a pin on a network the edge does not join is refused" \
  'g.FRONT_END["edge"]["pins"]["net-proxy-loki"] = "172.28.9.250"; g.generate()' \
  'REFUSAL: edge: pins an address on net-proxy-loki, which it does not join'
expect "a pin outside its network's subnet is refused" \
  'g.FRONT_END["edge"]["pins"]["net-proxy-api"] = "10.0.0.9"; g.generate()' \
  'REFUSAL: edge: 10.0.0.9 is outside 172.28.13.0/24'
expect "a pinned address the role does not trust is refused" \
  'g.FRONT_END["edge"]["pins"]["net-proxy-ingest"] = "172.28.7.249"; g.generate()' \
  'pinned for edge but missing from basetool_host_edge_trusted_proxies'

echo "== the loopback publishes a host service depends on =="
# AddHost= solves container->host. Nothing solves host->container, because rootless Podman keeps
# the container network in a user namespace: a published port is the only way in.
expect "loki publishes for the host-native shipper" \
  'print(g.PODMAN_LOOPBACK_PUBLISH["loki"][0])' \
  '127.0.0.1:3100:3100'
# Every one of them must be bound to loopback, or a port meant for one local process is on the wire.
expect "every loopback publish is actually on loopback" \
  'print(all(p.startswith("127.0.0.1:") for v in g.PODMAN_LOOPBACK_PUBLISH.values() for p in v))' \
  'True'
# The collision that decided tempo's host port: the host-native alloy binds 0.0.0.0:4317 for its
# own OTLP receiver, and 0.0.0.0 includes loopback. Publishing tempo there would fail to bind, and
# the symptom would be a container that will not start rather than anything naming this choice.
expect "no loopback publish collides with alloy's own OTLP ports" \
  'print(all(p.split(":")[1] not in ("4317", "4318") for v in g.PODMAN_LOOPBACK_PUBLISH.values() for p in v))' \
  'True'
# A publish for a service the translation deleted would be silently inert.
expect "every loopback publish names a service that is still a container" \
  'print(all(g.DISPOSITION.get(s, ("",))[0] != "delete" for s in g.PODMAN_LOOPBACK_PUBLISH))' \
  'True'

echo "== a stop grace is podman's stop timeout, and systemd waits longer than podman (OPS-PERF-01) =="
# Quadlet's ExecStop is `podman rm -f`, which kills after the CONTAINER's stop timeout -- podman's
# 10 s default unless StopTimeout= says otherwise. Until 2026-09-22 only TimeoutStopSec= was emitted,
# so every JVM, Loki, Tempo and both databases were SIGKILLed after 10 s whatever compose said.
unit_of() { # $1 service -> python that prints that service's rendered unit from the real compose
  printf 'import io, yaml\nfor p in (g.COMPOSE_APP, g.COMPOSE_MON):\n    d = yaml.safe_load(io.open(p, encoding="utf-8"))\n    s = (d.get("services") or {}).get("%s")\n    if s is not None:\n        print(g.render_container("%s", s))\n' "$1" "$1"
}
expect "a 30s grace becomes StopTimeout=30" \
  'print(g.render_container("x", {"image": "a/b:1", "stop_grace_period": "30s"}))' \
  'StopTimeout=30'
expect "...and systemd waits the grace plus the margin" \
  'print(g.render_container("x", {"image": "a/b:1", "stop_grace_period": "30s"}))' \
  'TimeoutStopSec=45'
expect "a compound grace is read as a duration, not stripped of an s" \
  'print(g.render_container("x", {"image": "a/b:1", "stop_grace_period": "1m30s"}))' \
  'StopTimeout=90'
expect "no grace, no stop keys -- podman's default applies and is not restated" \
  'u = g.render_container("x", {"image": "a/b:1"}); print("StopTimeout" in u or "TimeoutStopSec" in u)' \
  'False'
for svc in backend frontend ingest keycloak db-backend loki tempo; do
  expect "${svc}: every unit with a TimeoutStopSec= also carries a StopTimeout= below it" \
    "$(unit_of "$svc")"'' \
    'StopTimeout='
done
expect "the rule holds for every generated unit, not only the ones named above" \
  'files, _ = g.generate()
bad = []
for rel, text in files.items():
    if not rel.endswith(".container"):
        continue
    stop = [int(l.split("=", 1)[1]) for l in text.splitlines() if l.startswith("StopTimeout=")]
    sysd = [int(l.split("=", 1)[1]) for l in text.splitlines() if l.startswith("TimeoutStopSec=")]
    if bool(stop) != bool(sysd) or (stop and sysd[0] <= stop[0]):
        bad.append(rel)
print("mismatched:", bad)' \
  'mismatched: []'

echo "== native Quadlet keys instead of raw podman arguments (OPS-MOD-01) =="
# PodmanArgs= is appended to `podman run` unread, so a typo in it ships; a key is validated by the
# generator. RunInit=, Ulimit= and the network Options= all exist in podman 5.8's
# podman-systemd.unit(5) -- the comment that said "Quadlet has no Init= key" looked for the wrong
# name. --cpus and --oom-score-adj have no key and stay arguments.
expect "init: true becomes RunInit=true" \
  'print(g.render_container("x", {"image": "a/b:1", "init": True}))' \
  'RunInit=true'
expect "a nofile ulimit becomes Ulimit=" \
  'print(g.render_container("x", {"image": "a/b:1", "ulimits": {"nofile": {"soft": 65536, "hard": 65536}}}))' \
  'Ulimit=nofile=65536:65536'
expect "an unknown ulimit is refused rather than dropped" \
  'print(g.render_container("x", {"image": "a/b:1", "ulimits": {"nproc": 10}}))' \
  "REFUSAL: x: unrecognised ulimit(s) ['nproc']"
expect_not "no generated unit passes --init or --ulimit as a raw argument any more" \
  'files, _ = g.generate(); print("\n".join(l for t in files.values() for l in t.splitlines() if l.startswith("PodmanArgs=")))' \
  '--init'
expect_not "...nor --ulimit" \
  'files, _ = g.generate(); print("\n".join(l for t in files.values() for l in t.splitlines() if l.startswith("PodmanArgs=")))' \
  '--ulimit'
expect "the ingress egress block uses the network Options= key" \
  'files, _ = g.generate(); print(files["quadlet/systemd/net-edge-ingress.network"])' \
  'Options=no_default_route=true'
expect_not "...and no network carries PodmanArgs= at all" \
  'files, _ = g.generate(); print("".join(t for r, t in files.items() if r.endswith(".network")))' \
  'PodmanArgs='
expect_not "the measured control no longer reads as an unmeasured hypothesis" \
  'files, _ = g.generate(); print(files["quadlet/systemd/net-edge-ingress.network"])' \
  'HYPOTHESIS'

echo "== the config tree is mounted read-only, everywhere (OPS-SEC-04) =="
# keycloak mounted its theme, its provider directory and realm-export.json writable until
# 2026-09-22. /var/iri/code is what the deployer rewrites on every release.
expect "a writable config-tree mount is refused" \
  'print(g._volume("./keycloak-theme/krt-theme:/opt/keycloak/themes/krt-theme", "keycloak"))' \
  'REFUSAL: keycloak: mounts /var/iri/code/keycloak-theme/krt-theme from the config tree without `:ro`'
expect "an absolute config-tree path is held to the same rule" \
  'print(g._volume("/var/iri/code/keycloak/providers:/opt/keycloak/providers", "keycloak"))' \
  'without `:ro`'
expect "the same mount with :ro passes" \
  'print(g._volume("./keycloak-theme/krt-theme:/opt/keycloak/themes/krt-theme:ro", "keycloak"))' \
  '/var/iri/code/keycloak-theme/krt-theme:/opt/keycloak/themes/krt-theme:ro'
expect "a data mount outside the config tree stays writable" \
  'print(g._volume("/var/iri/keycloak/log:/var/log/keycloak", "keycloak"))' \
  '/var/iri/keycloak/log:/var/log/keycloak'
expect_not "the production keycloak unit no longer mounts realm-export.json -- prod runs start, never --import-realm" \
  'files, _ = g.generate(); print(files["quadlet/systemd/keycloak.container"])' \
  'realm-export.json'
expect "every Volume= from /var/iri/code in every generated unit ends in :ro" \
  'files, _ = g.generate()
bad = [l for t in files.values() for l in t.splitlines()
       if l.startswith("Volume=/var/iri/code") and "ro" not in l.split(":")[2:3][0].split(",") ]
print("writable:", bad)' \
  'writable: []'

echo "== the data networks are internal under Quadlet (OPS-SEC-05, ADR-0162) =="
for net in net-db-backend net-db-keycloak net-redis-backend net-redis-frontend net-redis-ingest; do
  expect "${net} is Internal=true" \
    "files, _ = g.generate(); print(files['quadlet/systemd/${net}.network'])" \
    'Internal=true'
done
# The networks that carry egress for someone must NOT be internal: the edge's ingress bridge (the
# only DNAT target for the published ports), acme's egress and the scrape network.
for net in net-edge-ingress net-acme-egress net-monitoring-scrape net-backend-keycloak; do
  expect "${net} is not internal" \
    "files, _ = g.generate(); print('Internal=true' in files['quadlet/systemd/${net}.network'].splitlines())" \
    'False'
done
expect "a container whose every network is internal may not publish a port" \
  'g.PODMAN_LOOPBACK_PUBLISH["redis"] = ("127.0.0.1:6379:6379",); g.generate()' \
  'REFUSAL: redis: every network it joins is internal'
expect "...nor dial the host gateway" \
  'g.PODMAN_HOST_ALIASES["db-backend"] = ("alloy",); g.generate()' \
  'REFUSAL: db-backend: every network it joins is internal'
expect "a stale entry naming a network no container joins is refused" \
  'g.QUADLET_INTERNAL_NETWORKS = g.QUADLET_INTERNAL_NETWORKS | {"net-nobody"}; g.generate()' \
  'REFUSAL: QUADLET_INTERNAL_NETWORKS names net-nobody'
expect "a network compose already makes internal cannot be named a second time" \
  'print(g.render_network("net-db-backend", {"internal": True}))' \
  'REFUSAL: net-db-backend: compose already declares it internal'

printf '%d passed, %d failed\n' "$PASSED" "$FAILED"
[[ $FAILED -eq 0 ]]
