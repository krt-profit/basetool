#!/usr/bin/env bash
# =============================================================================================
# Self-test for scripts/check-conformance.py
#
# The conformance suite's acceptance has two halves, and this is the second one:
#
#   1. it goes green against the current Docker stack   (run it against the host)
#   2. EVERY check has been shown red at least once     (this file)
#
# A green check that cannot go red is decoration. That lesson cost a day on 2026-09-12, and it is
# why this harness exists rather than a note saying the checks were eyeballed.
#
# It goes one step further, because the first draft of this file taught it: a check can also go
# red for the WRONG REASON and look like proof. Every red assertion therefore names a substring
# its failure message must contain. The draft's twelve host scenarios were all "red" because
# Windows could not execute the stub at all - twelve green ticks asserting nothing.
#
# No host, no daemon, no network beyond loopback: the external checks run against a local TLS
# fixture (throwaway self-signed certificates generated here, never committed) and the host-side
# checks run against a stub that prints what the host would have printed. Same principle as
# deploy.test.sh stubbing docker, cosign and flock on PATH.
#
# Requires: bash, python3, openssl. Runs on Git-Bash on Windows and on a CI runner.
#
#   bash scripts/check-conformance.test.sh
# =============================================================================================
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUITE="${HERE}/check-conformance.py"
PY="${PYTHON:-python}"
command -v "$PY" >/dev/null 2>&1 || PY=python3

WORK="$(mktemp -d)"
HTTPS_PORT="${CONFORMANCE_TEST_HTTPS_PORT:-18443}"
HTTP_PORT="${CONFORMANCE_TEST_HTTP_PORT:-18080}"
FIXTURE_PID=""

PASSED=0
FAILED=0
RED_COVERED=""

say() { printf '%s\n' "$*"; }
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

stop_fixture() {
  if [ -n "$FIXTURE_PID" ]; then
    kill "$FIXTURE_PID" 2>/dev/null
    wait "$FIXTURE_PID" 2>/dev/null
    FIXTURE_PID=""
  fi
}

cleanup() {
  stop_fixture
  rm -rf "$WORK"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------
# Throwaway TLS material, generated per run into $WORK and never written into the repository.
# Two certificates differing only in their key, so `certificate-shared` has two distinct leaves
# to find, plus one expiring tomorrow so `certificate-valid` has a real margin to refuse.
# ---------------------------------------------------------------------------------------------
gen_cert() { # name days
  local name=$1 days=$2
  # MSYS2_ARG_CONV_EXCL: on Git-Bash for Windows the shell rewrites a leading-slash argument into
  # a Windows path, so -subj "/CN=localhost" arrives as "C:/Program Files/Git/CN=localhost" and
  # openssl refuses it. The blunt fix, MSYS_NO_PATHCONV=1, is WRONG here: it also stops
  # converting -keyout and -out, and the native openssl then cannot find /tmp/... at all.
  # Exclude only the subject. Inert on Linux and in CI.
  MSYS2_ARG_CONV_EXCL="/CN=" openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "${WORK}/${name}.key" -out "${WORK}/${name}.crt" -days "$days" \
    -subj "/CN=localhost/O=basetool conformance self-test NOT FOR PRODUCTION" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:0:0:0:0:0:0:0:1" \
    >/dev/null 2>&1 || return 1
}

say "generating throwaway TLS material in ${WORK}"
gen_cert primary 3650 || { say "openssl failed - is it on PATH?"; exit 2; }
gen_cert secondary 3650 || exit 2
gen_cert expiring 1 || exit 2
cat "${WORK}/primary.crt" "${WORK}/secondary.crt" "${WORK}/expiring.crt" > "${WORK}/bundle.pem"

# ---------------------------------------------------------------------------------------------
# The fixture: a TLS server and a plain-HTTP server on loopback, both scriptable.
# ---------------------------------------------------------------------------------------------
cat > "${WORK}/fixture.py" <<'FIXTURE'
"""A scriptable loopback HTTPS/HTTP fixture for the conformance suite's self-test."""
import argparse
import http.server
import socket
import ssl
import sys
import threading


class Handler(http.server.BaseHTTPRequestHandler):
    """Answers with whatever status the scenario asked for."""

    protocol_version = "HTTP/1.1"
    status_root = 200
    status_other = 200

    def log_message(self, *args):
        """Swallow the fixture's own request log."""

    def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler's spelling
        """Answer the configured status, with a Location when it is a redirect."""
        status = self.status_root if self.path == "/" else self.status_other
        if 300 <= status < 400:
            self.send_response(status)
            self.send_header("Location", "https://localhost/")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        body = b"fixture\n"
        self.send_response(status)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class Server(http.server.ThreadingHTTPServer):
    """A threading server that can be pinned to one address family."""

    daemon_threads = True
    allow_reuse_address = True


def serve(family, addr, port, ctx, handler):
    """Start one listener thread.

    Args:
        family: ``socket.AF_INET`` or ``socket.AF_INET6``.
        addr: the loopback address to bind.
        port: the port to bind.
        ctx: an ``SSLContext`` to wrap the listener with, or ``None`` for plain HTTP.
        handler: the request handler class.

    Returns:
        The started thread, or ``None`` when that address could not be bound.
    """
    Server.address_family = family
    try:
        srv = Server((addr, port), handler)
    except OSError as exc:
        print(f"fixture: cannot bind {addr}:{port} ({exc})", file=sys.stderr)
        return None
    if ctx:
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    return thread


def main():
    """Parse the scenario flags, start the listeners and block."""
    p = argparse.ArgumentParser()
    p.add_argument("--https-port", type=int, required=True)
    p.add_argument("--http-port", type=int, required=True)
    p.add_argument("--cert", required=True)
    p.add_argument("--key", required=True)
    p.add_argument("--sni-cert")
    p.add_argument("--sni-key")
    p.add_argument("--sni-name", default="localhost")
    p.add_argument("--status-root", type=int, default=200)
    p.add_argument("--status-other", type=int, default=200)
    p.add_argument("--http-status", type=int, default=308)
    p.add_argument("--no-v6", action="store_true")
    args = p.parse_args()

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(args.cert, args.key)
    if args.sni_cert:
        alt = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        alt.load_cert_chain(args.sni_cert, args.sni_key)

        def pick(sock, name, _ctx):
            """Serve the alternate certificate for one SNI name, the default otherwise."""
            if name == args.sni_name:
                sock.context = alt

        ctx.sni_callback = pick

    class TLSHandler(Handler):
        """The HTTPS handler, carrying this scenario's statuses."""

        status_root = args.status_root
        status_other = args.status_other

    class PlainHandler(Handler):
        """The plain-HTTP handler, which answers one status for every path."""

        status_root = args.http_status
        status_other = args.http_status

    started = [serve(socket.AF_INET, "127.0.0.1", args.https_port, ctx, TLSHandler),
               serve(socket.AF_INET, "127.0.0.1", args.http_port, None, PlainHandler)]
    if not args.no_v6:
        started.append(serve(socket.AF_INET6, "::1", args.https_port, ctx, TLSHandler))
        started.append(serve(socket.AF_INET6, "::1", args.http_port, None, PlainHandler))
    if not any(started):
        sys.exit("fixture: nothing could be bound")
    print("fixture: ready", flush=True)
    threading.Event().wait()


if __name__ == "__main__":
    main()
FIXTURE

# ---------------------------------------------------------------------------------------------
# The host stub. Receives the host command as its last argument and prints what the host would
# have printed. STUB_SCENARIO selects the deviation; `healthy` is the baseline.
# ---------------------------------------------------------------------------------------------
cat > "${WORK}/hoststub.sh" <<'STUB'
#!/usr/bin/env bash
cmd="${1:-}"
scenario="${STUB_SCENARIO:-healthy}"

emit_prom() { printf '{"status":"success","data":{"resultType":"vector","result":[%s]}}\n' "$1"; }
sample() { printf '{"metric":{%s},"value":[0,"%s"]}' "$1" "$2"; }

case "$cmd" in
  *"SSH_CONNECTION"*)
    # Deliberately empty: the address-equality branch is an extra assertion when an SSH source is
    # available, never a requirement, and the harness must exercise the path that does without it.
    echo ""
    ;;
  *"docker logs edge"*"--since 60m"*)
    case "$scenario" in
      single-bucket) echo "1" ;;
      *)             echo "37" ;;
    esac
    ;;
  *"docker logs edge"*)
    case "$scenario" in
      no-log-line)  printf '' ;;
      private-addr) echo "172.28.15.1 - - [16/Sep/2026:13:00:00 +0000] \"GET /healthz HTTP/1.1\" 200" ;;
      *)            echo "203.0.113.42 - - [16/Sep/2026:13:00:00 +0000] \"GET /healthz HTTP/1.1\" 200" ;;
    esac
    ;;
  *"docker inspect acme"*)
    case "$scenario" in
      no-acme) echo "" ;;
      *)       echo "example.test ingest.example.test" ;;
    esac
    ;;
  *"docker inspect prometheus"*|*"query="*)
    if [ "$scenario" = "no-monitoring" ]; then
      echo "NO_PROMETHEUS_ADDRESS" >&2
      exit 1
    fi
    ;;&
  *"docker ps -a"*)
    printf 'edge|Up 15 hours (healthy)\nacme|Up 2 days\nkeycloak|Up 15 hours (healthy)\n'
    printf 'backend|Up 9 hours (healthy)\nfrontend|Up 9 hours (healthy)\ningest|Up 9 hours (healthy)\n'
    printf 'db-keycloak|Up 3 days (healthy)\nredis|Up 2 days (healthy)\n'
    case "$scenario" in
      container-down)      printf 'db-backend|Exited (0) 3 days ago\n' ;;
      container-unhealthy) printf 'db-backend|Up 2 days (unhealthy)\n' ;;
      container-absent)    : ;;
      *)                   printf 'db-backend|Up 2 days (healthy)\n' ;;
    esac
    ;;
  *"query=up"*)
    case "$scenario" in
      target-down)
        emit_prom "$(sample '"job":"basetool-backend"' 0),$(sample '"job":"basetool-frontend"' 1),$(sample '"job":"basetool-ingest"' 1),$(sample '"job":"keycloak"' 1)" ;;
      target-absent)
        emit_prom "$(sample '"job":"basetool-frontend"' 1),$(sample '"job":"basetool-ingest"' 1),$(sample '"job":"keycloak"' 1)" ;;
      *)
        emit_prom "$(sample '"job":"basetool-backend"' 1),$(sample '"job":"basetool-frontend"' 1),$(sample '"job":"basetool-ingest"' 1),$(sample '"job":"keycloak"' 1)" ;;
    esac
    ;;
  *"query=count(container_threads)"*)
    case "$scenario" in
      series-missing) emit_prom "" ;;
      series-empty)   emit_prom "$(sample '' 0)" ;;
      *)              emit_prom "$(sample '' 22)" ;;
    esac
    ;;
  *"query=count(container_"*)
    emit_prom "$(sample '' 22)"
    ;;
  *"loki_distributor_lines_received_total"*)
    case "$scenario" in
      logs-stopped) emit_prom "$(sample '' 0)" ;;
      logs-absent)  emit_prom "" ;;
      *)            emit_prom "$(sample '' 11.8)" ;;
    esac
    ;;
  *)
    echo "hoststub: unhandled command: $cmd" >&2
    exit 1
    ;;
esac
STUB
chmod +x "${WORK}/hoststub.sh"

# ---------------------------------------------------------------------------------------------
# Harness
# ---------------------------------------------------------------------------------------------
ALL_LOCAL=(--host frontend=localhost --host ingest=localhost
           --host grafana=localhost --host api=localhost)
ALL_IP=(--host frontend=127.0.0.1 --host ingest=127.0.0.1
        --host grafana=127.0.0.1 --host api=127.0.0.1)
MIXED=(--host frontend=localhost --host ingest=localhost
       --host grafana=localhost --host api=127.0.0.1)
STUB_ARGS=(--host-stub "bash ${WORK}/hoststub.sh")

# assert_status <label> <check> <expected-status> <detail-substring|-> -- <suite args...>
#
# The fourth argument is what stops a red-for-the-wrong-reason from reading as proof: the result
# message has to contain it. Pass `-` when the reason is deliberately not asserted.
assert_status() {
  local label=$1 check=$2 expected=$3 needle=$4
  shift 4
  [ "${1:-}" = "--" ] && shift
  local out parsed got detail
  out="$("$PY" "$SUITE" --json --only "$check" "$@" 2>/dev/null)"
  parsed="$(printf '%s' "$out" | "$PY" -c "
import json,sys
try:
    d=json.loads(sys.stdin.read())
except Exception:
    print('parse-error|no JSON on stdout'); raise SystemExit
print((d[0]['status'] if d else 'none')+'|'+(d[0]['detail'] if d else ''))
")"
  got="${parsed%%|*}"
  detail="${parsed#*|}"

  if [ "$got" != "$expected" ]; then
    bad "$label - expected '$expected', got '$got' (${detail:0:110})"
    return
  fi
  if [ "$needle" != "-" ] && [[ "$detail" != *"$needle"* ]]; then
    bad "$label - status '$got' but for the wrong reason: wanted \"$needle\", got (${detail:0:110})"
    return
  fi
  # Only a red scenario that actually behaved counts towards coverage.
  if [ "$expected" = "fail" ]; then
    RED_COVERED="${RED_COVERED}${check}
"
  fi
  ok "$label"
}

start_fixture() { # extra fixture args...
  stop_fixture
  "$PY" "${WORK}/fixture.py" --https-port "$HTTPS_PORT" --http-port "$HTTP_PORT" \
    --cert "${WORK}/primary.crt" --key "${WORK}/primary.key" "$@" >"${WORK}/fixture.log" 2>&1 &
  FIXTURE_PID=$!
  for _ in $(seq 1 60); do
    grep -q "fixture: ready" "${WORK}/fixture.log" 2>/dev/null && return 0
    kill -0 "$FIXTURE_PID" 2>/dev/null || { say "fixture died:"; cat "${WORK}/fixture.log"; return 1; }
    sleep 0.2
  done
  say "fixture did not become ready"; cat "${WORK}/fixture.log"; return 1
}

export BASETOOL_CONFORMANCE_HTTPS_PORT="$HTTPS_PORT"
export BASETOOL_CONFORMANCE_HTTP_PORT="$HTTP_PORT"
export BASETOOL_CONFORMANCE_CA_BUNDLE="${WORK}/bundle.pem"

# =============================================================================================
say ""
say "== the suite is wired correctly =="
# =============================================================================================
if "$PY" "$SUITE" --list >/dev/null 2>&1; then ok "--list runs"; else bad "--list runs"; fi

# RFC 6125 wildcard matching, unit-tested directly. Found by running the suite against the
# testing host on 2026-09-16: it serves *.basetool.greluc.me and the check reported three vhosts
# uncovered, which was a defect in the check rather than a finding about the host. A wildcard
# must match exactly one label -- neither the apex nor a deeper name.
if "$PY" - <<'WILDCARD' >/dev/null 2>&1
import importlib.util, sys
spec = importlib.util.spec_from_file_location("cc", "scripts/check-conformance.py")
cc = importlib.util.module_from_spec(spec); sys.modules["cc"] = cc; spec.loader.exec_module(cc)
cases = [
    ("api.example.com",   ["*.example.com"], True),
    ("example.com",       ["*.example.com"], False),
    ("a.b.example.com",   ["*.example.com"], False),
    ("API.Example.COM",   ["*.example.com"], True),
    ("example.com.",      ["example.com"],   True),
    ("evil.com",          ["*.example.com"], False),
    ("example.com",       ["example.com", "api.example.com"], True),
]
sys.exit(0 if all(cc._san_covers(h, s) is e for h, s, e in cases) else 1)
WILDCARD
then
  ok "a wildcard SAN covers one label, and only one"
else
  bad "a wildcard SAN covers one label, and only one"
fi
if "$PY" "$SUITE" --only no-such-check >/dev/null 2>&1; then
  bad "an unknown --only is rejected"
else
  ok "an unknown --only is rejected"
fi
if "$PY" "$SUITE" --host bogus >/dev/null 2>&1; then
  bad "a malformed --host is rejected"
else
  ok "a malformed --host is rejected"
fi
if "$PY" "$SUITE" --json --only container-metrics 2>/dev/null | grep -q '"status": "skip"'; then
  ok "a host check skips rather than fails without host access"
else
  bad "a host check skips rather than fails without host access"
fi
# Captured first, then grepped: `set -o pipefail` makes a pipeline inherit the SUITE's exit
# status, and here the suite legitimately exits 1 because nothing is listening yet - so the
# pipeline would report failure even though grep found the line.
SEAM_OUT="$("$PY" "$SUITE" --only vhost-reachable "${ALL_LOCAL[@]}" 2>/dev/null || true)"
if printf '%s' "$SEAM_OUT" | grep -q "test seams active"; then
  ok "the report says when a test seam is active"
else
  bad "the report says when a test seam is active"
fi

# =============================================================================================
say ""
say "== external checks go GREEN against a correct fixture =="
# =============================================================================================
export STUB_SCENARIO=healthy
start_fixture || exit 2
assert_status "vhost-reachable passes"    vhost-reachable    pass - -- "${ALL_LOCAL[@]}"
assert_status "certificate-valid passes"  certificate-valid  pass - -- "${ALL_LOCAL[@]}"
assert_status "certificate-shared passes" certificate-shared pass - \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
assert_status "http-redirects passes"     http-redirects     pass - -- "${ALL_LOCAL[@]}"
assert_status "ipv6-reachable passes"     ipv6-reachable     pass - -- "${ALL_LOCAL[@]}"

# =============================================================================================
say ""
say "== external checks go RED when the target is broken =="
# =============================================================================================
stop_fixture
assert_status "vhost-reachable fails when nothing listens" \
  vhost-reachable fail "unreachable" -- "${ALL_LOCAL[@]}"

start_fixture --status-root 503 || exit 2
assert_status "vhost-reachable fails on a 5xx upstream" \
  vhost-reachable fail "answered 503" -- "${ALL_LOCAL[@]}"

start_fixture --cert "${WORK}/expiring.crt" --key "${WORK}/expiring.key" || exit 2
assert_status "certificate-valid fails inside the renewal margin" \
  certificate-valid fail "expires in" -- "${ALL_LOCAL[@]}"

start_fixture || exit 2
assert_status "certificate-valid fails when the SAN does not cover the name" \
  certificate-valid fail "not covered by SAN list" -- "${ALL_IP[@]}"

start_fixture --sni-cert "${WORK}/secondary.crt" --sni-key "${WORK}/secondary.key" || exit 2
assert_status "certificate-shared fails on two distinct leaves" \
  certificate-shared fail "distinct leaf certificates" -- "${STUB_ARGS[@]}" "${MIXED[@]}"
STUB_SCENARIO=no-acme assert_status \
  "certificate-shared skips where the host issues no certificates of its own" \
  certificate-shared skip "ACME_HOSTS is empty" -- "${STUB_ARGS[@]}" "${MIXED[@]}"

start_fixture --http-status 200 || exit 2
assert_status "http-redirects fails when :80 serves content" \
  http-redirects fail "did not redirect" -- "${ALL_LOCAL[@]}"

start_fixture --no-v6 || exit 2
assert_status "ipv6-reachable fails when the target has no v6 listener" \
  ipv6-reachable fail "unreachable" -- "${ALL_LOCAL[@]}"

start_fixture || exit 2
assert_status "rate-limit-active fails when nothing is ever refused" \
  rate-limit-active fail "drew no 429" -- --include-load "${ALL_LOCAL[@]}"
assert_status "rate-limit-active skips without --include-load" \
  rate-limit-active skip "opt-in" -- "${ALL_LOCAL[@]}"

# =============================================================================================
say ""
say "== host checks go GREEN against a healthy stub =="
# =============================================================================================
export STUB_SCENARIO=healthy
assert_status "client-address-visible passes" client-address-visible pass "203.0.113.42" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
assert_status "containers-running passes" containers-running pass "up and healthy" -- "${STUB_ARGS[@]}"
assert_status "scrape-targets-up passes"  scrape-targets-up  pass "targets up"     -- "${STUB_ARGS[@]}"
assert_status "container-metrics passes"  container-metrics  pass "populated"      -- "${STUB_ARGS[@]}"
assert_status "log-streams passes"        log-streams        pass "ingesting"      -- "${STUB_ARGS[@]}"

# =============================================================================================
say ""
say "== host checks go RED when the host is broken =="
# =============================================================================================
# The first of these is the one this whole suite exists for: an edge reporting a bridge address
# for every client is the rootlessport failure mode, and PODMAN_MIGRATION_PLAN.md §3.1 rejects
# that configuration on exactly this evidence.
STUB_SCENARIO=private-addr assert_status \
  "client-address-visible fails on a private/bridge client address" \
  client-address-visible fail "private/bridge address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
STUB_SCENARIO=single-bucket assert_status \
  "client-address-visible fails when the edge sees one distinct client" \
  client-address-visible fail "distinct client address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
STUB_SCENARIO=no-log-line assert_status \
  "client-address-visible fails when the probe never reached the log" \
  client-address-visible fail "probe marker" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"

STUB_SCENARIO=container-down assert_status \
  "containers-running fails on an exited container" \
  containers-running fail "Exited" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=container-unhealthy assert_status \
  "containers-running fails on an unhealthy container" \
  containers-running fail "unhealthy" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=container-absent assert_status \
  "containers-running fails on a missing container" \
  containers-running fail "is absent" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=target-down assert_status \
  "scrape-targets-up fails on up=0" scrape-targets-up fail "up=0" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=target-absent assert_status \
  "scrape-targets-up fails on a target Prometheus does not know" \
  scrape-targets-up fail "absent from Prometheus" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=series-missing assert_status \
  "container-metrics fails on an absent series" \
  container-metrics fail "container_threads" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=series-empty assert_status \
  "container-metrics fails on a series with no samples" \
  container-metrics fail "container_threads" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=logs-stopped assert_status \
  "log-streams fails when ingestion has stopped" log-streams fail "has stopped" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=logs-absent assert_status \
  "log-streams fails when the metric is absent" log-streams fail "no samples" -- "${STUB_ARGS[@]}"

# The real condition on the testing host today, and the message has to name it rather than leak
# the shell guard's marker.
STUB_SCENARIO=no-monitoring assert_status \
  "container-metrics fails clearly when the host has no monitoring plane" \
  container-metrics fail "no monitoring plane" -- "${STUB_ARGS[@]}"

# =============================================================================================
say ""
say "== every registered check has a red scenario that actually behaved =="
# =============================================================================================
# Coverage is accumulated by assert_status itself, so it counts scenarios that RAN and produced
# the expected failure - not scenarios that were merely written down. Without this, adding a
# check and forgetting to break it would leave the suite one silent decoration heavier, which is
# the exact failure mode this harness exists to prevent.
REGISTERED="$("$PY" "$SUITE" --list | awk '{print $1}' | sort -u)"
COVERED="$(printf '%s' "$RED_COVERED" | grep -v '^$' | sort -u)"
MISSING="$(comm -23 <(printf '%s\n' "$REGISTERED") <(printf '%s\n' "$COVERED"))"
if [ -z "$MISSING" ]; then
  ok "all $(printf '%s\n' "$REGISTERED" | wc -l | tr -d ' ') checks have a demonstrated red scenario"
else
  bad "no demonstrated red scenario for: $(printf '%s' "$MISSING" | tr '\n' ' ')"
fi

# =============================================================================================
say ""
say "-------------------------------------------------------------"
say "${PASSED} passed, ${FAILED} failed"
[ "$FAILED" -eq 0 ] || exit 1
