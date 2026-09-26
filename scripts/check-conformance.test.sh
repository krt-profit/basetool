#!/usr/bin/env bash
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

gen_cert() {
  local name=$1 days=$2
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

cat > "${WORK}/hoststub.sh" <<'STUB'
#!/usr/bin/env bash
cmd="${1:-}"
scenario="${STUB_SCENARIO:-healthy}"

case "$cmd" in
  "cd /; "*) ;;
  *) echo "hoststub: command did not arrive rooted at / -- see HostRunner.run in check-conformance.py" >&2
     exit 1 ;;
esac

emit_prom() { printf '{"status":"success","data":{"resultType":"vector","result":[%s]}}\n' "$1"; }
sample() { printf '{"metric":{%s},"value":[0,"%s"]}' "$1" "$2"; }

case "$cmd" in
  *"command -v podman"*)
    echo "${STUB_RUNTIME:-podman}"
    ;;
  *"SSH_CONNECTION"*)
    echo ""
    ;;
  *"inspect edge --format"*"LogConfig.Type"*)
    echo "${STUB_LOG_DRIVER:-journald}"
    ;;
  *"podman logs edge"*"--since 60m"*|*"journalctl CONTAINER_NAME=edge"*"60 min ago"*)
    case "$scenario" in
      single-bucket)
        echo "172.28.15.1 - - [16/Sep/2026:13:00:00 +0000] \"GET / HTTP/1.1\" 200"
        echo "172.28.15.1 - - [16/Sep/2026:13:00:01 +0000] \"GET /x HTTP/1.1\" 200"
        echo "2026/09/17 22:10:02 [notice] 1#1: using the \"epoll\" event method"
        echo "edge: starting nginx"
        ;;
      *)
        echo "203.0.113.42 - - [16/Sep/2026:13:00:00 +0000] \"GET / HTTP/1.1\" 200"
        echo "198.51.100.7 - - [16/Sep/2026:13:00:01 +0000] \"GET /x HTTP/1.1\" 200"
        echo "2026/09/17 22:10:02 [notice] 1#1: using the \"epoll\" event method"
        ;;
    esac
    ;;
  *"podman logs edge"*|*"journalctl CONTAINER_NAME=edge"*)
    case "$scenario" in
      no-log-line)  printf '' ;;
      private-addr) echo "172.28.15.1 - - [16/Sep/2026:13:00:00 +0000] \"GET /healthz HTTP/1.1\" 200" ;;
      mapped-addr)  echo "::ffff:172.28.15.10 - - [16/Sep/2026:13:00:00 +0000] \"GET /healthz HTTP/1.1\" 200" ;;
      *)            echo "203.0.113.42 - - [16/Sep/2026:13:00:00 +0000] \"GET /healthz HTTP/1.1\" 200" ;;
    esac
    ;;
    *"podman inspect edge --format '{{json .NetworkSettings.Ports}}'"*)
      case "$scenario" in
        edge-world) echo '{"8080/tcp":[{"HostIp":"0.0.0.0","HostPort":"80"}]}' ;;
        *)          echo '{"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"8080"}]}' ;;
      esac
      ;;
    *"ip -o addr show scope global"*)
      case "$scenario" in
        edge-no-addr) echo "" ;;
        *)            printf '10.9.0.15\n2003:db8::1\n' ;;
      esac
      ;;
    *"--connect-timeout 4"*"http://"*)
      case "$scenario" in
        edge-open)     echo "0" ;;
        edge-proxyproto) echo "52" ;;
        *)             echo "7" ;;
      esac
      ;;
  *"exec redis"*)
    case "$scenario" in
      redis-open)     echo "+PONG" ;;
      redis-absent)   echo "ABSENT" ;;
      redis-no-reply) echo "NO_REPLY" ;;
      redis-strange)  echo "+SOMETHING ELSE" ;;
      *)              echo "-NOAUTH Authentication required." ;;
    esac
    ;;
  *"podman inspect acme"*)
    case "$scenario" in
      no-acme) echo "" ;;
      *)       echo "example.test ingest.example.test" ;;
    esac
    ;;
  *"test -r /var/iri/code/.env"*)
    case "$scenario" in
      env-unreadable) echo "" ;;
      *)              echo "yes" ;;
    esac
    ;;
  *"test -e /var/iri/code/.env"*)
    case "$scenario" in
      env-no-env) echo "" ;;
      *)          echo "yes" ;;
    esac
    ;;
  *"test -f /var/iri/code/.env"*)
    case "$scenario" in
      env-no-env) echo "" ;;
      *)          echo "yes" ;;
    esac
    ;;
  *"/var/iri/code/.env"*)
    case "$scenario" in
      env-no-env)     echo "" ;;
      env-unreadable) echo "" ;;
      env-orphan)     printf 'IRI_TRUSTSTORE_HOST_PATH=/var/iri/secrets/truststore.p12\n' ;;
      env-dropin)     printf 'IRI_KEYCLOAK_HOST_ALIAS=basetool.example.test:10.0.0.9\n' ;;
      traces-on|traces-absent|traces-zero)
                      printf 'MONITORING_TRACING_ENABLED=true\n' ;;
      traces-off)     printf 'MONITORING_TRACING_ENABLED=false\n' ;;
      *)              printf 'POSTGRES_DB=basetool\n' ;;
    esac
    ;;
  *".config/containers/systemd"*|*"/etc/containers/systemd/users/"*)
    case "$scenario" in
      env-no-units) echo "" ;;
      env-dropin)   printf '[Container]\nAddHost=basetool.example.test:10.0.0.9\n' ;;
      *)            printf '[Container]\nImage=ghcr.io/example/basetool-backend:stable\nVolume=/var/iri/secrets/keystore.p12:/run/secrets/truststore.p12:ro\n' ;;
    esac
    ;;
  *"exec prometheus"*|*"query="*)
    if [ "$scenario" = "no-monitoring" ]; then
      echo 'Error: no such object: "prometheus"' >&2
      exit 125
    fi
    ;;&
  *"ReadonlyRootfs"*)
    for n in edge acme keycloak backend frontend ingest db-backend db-keycloak redis; do
      ro=true
      case "$scenario" in
        writable-edge)     [ "$n" = edge ] && ro=false ;;
        writable-keycloak) [ "$n" = keycloak ] && ro=false ;;
        ro-container-gone) [ "$n" = redis ] && continue ;;
      esac
      printf '/%s|%s\n' "$n" "$ro"
    done
    ;;
  *"uid_map"*)
    name=db-backend
    case "$cmd" in *db-keycloak*) name=db-keycloak ;; *redis*) name=redis ;; esac
    case "$scenario" in
      container-gone)  [ "$name" = redis ] && { echo ABSENT; exit 0; } ;;
      uid-unreadable)  [ "$name" = redis ] && { echo "Uid:"; exit 0; } ;;
    esac
    case "$scenario" in
      rootless-podman)
        case "$name" in redis) host_uid=100998 ;; *) host_uid=100069 ;; esac
        printf 'Uid:\t%s\t%s\t%s\t%s\n' "$host_uid" "$host_uid" "$host_uid" "$host_uid"
        printf 'MAP 0 1000 1\nMAP 1 100000 65536\n'
        ;;
      *)
        case "$name" in redis) host_uid=999 ;; *) host_uid=70 ;; esac
        [ "$scenario" = redis-root ] && [ "$name" = redis ] && host_uid=0
        [ "$scenario" = db-wrong-uid ] && [ "$name" = db-backend ] && host_uid=26
        printf 'Uid:\t%s\t%s\t%s\t%s\n' "$host_uid" "$host_uid" "$host_uid" "$host_uid"
        printf 'MAP 0 0 4294967295\n'
        ;;
    esac
    ;;
  *"podman ps -a"*)
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
  *"query=count%28basetool_container_pids%29"*)
    case "$scenario" in
      series-missing) emit_prom "" ;;
      series-empty)   emit_prom "$(sample '' 0)" ;;
      *)              emit_prom "$(sample '' 22)" ;;
    esac
    ;;
  *"query=count%28basetool_container_"*)
    emit_prom "$(sample '' 22)"
    ;;
  *"loki_distributor_lines_received_total"*)
    case "$scenario" in
      logs-stopped) emit_prom "$(sample '' 0)" ;;
      logs-absent)  emit_prom "" ;;
      *)            emit_prom "$(sample '' 11.8)" ;;
    esac
    ;;
  *"otelcol_receiver_accepted_spans_total"*)
    case "$scenario" in
      traces-absent) emit_prom "" ;;
      traces-zero)   emit_prom "$(sample '' 0)" ;;
      *)             emit_prom "$(sample '' 4127)" ;;
    esac
    ;;
  *"node_exporter_build_info"*)
    case "$scenario" in
      exporter-drift) echo 'node_exporter_build_info{branch="HEAD",goversion="go1.24.1",revision="x",tags="",version="1.11.0"} 1' ;;
      exporter-down)  echo "" ;;
      *)              echo "node_exporter_build_info{branch=\"HEAD\",goversion=\"go1.24.1\",revision=\"x\",tags=\"\",version=\"${STUB_NODE_PIN}\"} 1" ;;
    esac
    ;;
  *"alloy_build_info"*)
    echo "alloy_build_info{branch=\"HEAD\",goarch=\"amd64\",goos=\"linux\",goversion=\"go1.24.1\",revision=\"x\",tags=\"\",version=\"v${STUB_ALLOY_PIN}\"} 1"
    ;;
  *"is-enabled dnf-automatic.timer"*)
    case "$scenario" in updates-disabled) echo "disabled" ;; *) echo "enabled" ;; esac
    ;;
  *"is-active dnf-automatic.timer"*)
    case "$scenario" in updates-disabled) echo "inactive" ;; *) echo "active" ;; esac
    ;;
  *"/etc/dnf/automatic.conf"*)
    case "$scenario" in
      updates-download-only) printf 'upgrade_type = security\napply_updates = no\nexclude = podman crun\n' ;;
      updates-everything)    printf 'upgrade_type = default\napply_updates = yes\nexclude = podman crun\n' ;;
      updates-runtime)       printf 'upgrade_type = security\napply_updates = yes\n' ;;
      *)                     printf 'upgrade_type = security\napply_updates = yes\nexclude = podman podman-* crun conmon netavark aardvark-dns containers-common containers-common-* passt passt-*\n' ;;
    esac
    ;;
  *)
    echo "hoststub: unhandled command: $cmd" >&2
    exit 1
    ;;
esac
STUB
chmod +x "${WORK}/hoststub.sh"

ALL_LOCAL=(--host frontend=localhost --host ingest=localhost
           --host grafana=localhost --host api=localhost)
ALL_IP=(--host frontend=127.0.0.1 --host ingest=127.0.0.1
        --host grafana=127.0.0.1 --host api=127.0.0.1)
MIXED=(--host frontend=localhost --host ingest=localhost
       --host grafana=localhost --host api=127.0.0.1)
STUB_ARGS=(--host-stub "bash ${WORK}/hoststub.sh")

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
  if [ "$expected" = "fail" ]; then
    RED_COVERED="${RED_COVERED}${check}
"
  fi
  ok "$label"
}

start_fixture() {
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

say ""
say "== the suite is wired correctly =="
if "$PY" "$SUITE" --list >/dev/null 2>&1; then ok "--list runs"; else bad "--list runs"; fi

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
SEAM_OUT="$("$PY" "$SUITE" --only vhost-reachable "${ALL_LOCAL[@]}" 2>/dev/null || true)"
if printf '%s' "$SEAM_OUT" | grep -q "test seams active"; then
  ok "the report says when a test seam is active"
else
  bad "the report says when a test seam is active"
fi

say ""
say "== external checks go GREEN against a correct fixture =="
export STUB_SCENARIO=healthy
start_fixture || exit 2
assert_status "vhost-reachable passes"    vhost-reachable    pass - -- "${ALL_LOCAL[@]}"
assert_status "certificate-valid passes"  certificate-valid  pass - -- "${ALL_LOCAL[@]}"
assert_status "certificate-shared passes" certificate-shared pass - \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
assert_status "http-redirects passes"     http-redirects     pass - -- "${ALL_LOCAL[@]}"
assert_status "ipv6-reachable passes"     ipv6-reachable     pass - -- "${ALL_LOCAL[@]}"

say ""
say "== external checks go RED when the target is broken =="
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

say ""
say "== host checks go GREEN against a healthy stub =="
export STUB_SCENARIO=healthy
assert_status "client-address-visible passes" client-address-visible pass "203.0.113.42" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
assert_status "containers-running passes" containers-running pass "up and healthy" -- "${STUB_ARGS[@]}"
assert_status "containers-unprivileged passes" containers-unprivileged pass "redis=999" -- "${STUB_ARGS[@]}"
assert_status "containers-read-only passes" containers-read-only pass "all 9 app containers" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=rootless-podman assert_status "containers-unprivileged passes through a rootless uid_map" containers-unprivileged pass "db-backend=70" -- "${STUB_ARGS[@]}"
assert_status "redis-requires-auth passes" redis-requires-auth pass "NOAUTH" -- "${STUB_ARGS[@]}"
assert_status "scrape-targets-up passes"  scrape-targets-up  pass "targets up"     -- "${STUB_ARGS[@]}"
assert_status "container-metrics passes"  container-metrics  pass "populated"      -- "${STUB_ARGS[@]}"
assert_status "log-streams passes"        log-streams        pass "ingesting"      -- "${STUB_ARGS[@]}"
STUB_SCENARIO=traces-on assert_status \
  "trace-pipeline passes when spans are arriving" trace-pipeline pass "accepted" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=traces-off assert_status \
  "trace-pipeline skips when tracing is switched off" trace-pipeline skip "emit no spans" \
  -- "${STUB_ARGS[@]}"

say ""
say "== host checks go RED when the host is broken =="
STUB_SCENARIO=private-addr assert_status \
  "client-address-visible fails on a private/bridge client address" \
  client-address-visible fail "private/bridge address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
STUB_SCENARIO=mapped-addr assert_status \
  "client-address-visible fails on the IPv4-MAPPED form of a bridge address" \
  client-address-visible fail "private/bridge address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
STUB_SCENARIO=single-bucket assert_status \
  "client-address-visible fails when the edge sees one distinct client" \
  client-address-visible fail "distinct client address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
STUB_RUNTIME=podman STUB_SCENARIO=private-addr assert_status \
  "client-address-visible reads the log under podman too" \
  client-address-visible fail "private/bridge address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
STUB_RUNTIME=podman STUB_LOG_DRIVER=k8s-file STUB_SCENARIO=private-addr assert_status \
  "client-address-visible reads the log under podman k8s-file too" \
  client-address-visible fail "private/bridge address" \
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

STUB_SCENARIO=redis-root assert_status "containers-unprivileged fails when redis runs as root inside the container" containers-unprivileged fail "running as ROOT" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=db-wrong-uid assert_status "containers-unprivileged fails on a uid that is neither root nor the expected one" containers-unprivileged fail "expected 70" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=uid-unreadable assert_status "containers-unprivileged fails rather than passes when the uid cannot be read" containers-unprivileged fail "could not read" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=container-gone assert_status "containers-unprivileged fails when the container is not running at all" containers-unprivileged fail "not running" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=writable-edge assert_status "containers-read-only fails on a writable root filesystem" containers-read-only fail "writable root filesystem" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=writable-keycloak assert_status "containers-read-only fails on keycloak, which needed a tmpfs to get there" containers-read-only fail "writable root filesystem" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=ro-container-gone assert_status "containers-read-only fails when a container is absent rather than passing it over" containers-read-only fail "says nothing" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=container-absent assert_status \
  "containers-running fails on a missing container" \
  containers-running fail "is absent" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=redis-open assert_status \
  "redis-requires-auth fails when an unauthenticated ping is answered" \
  redis-requires-auth fail "UNAUTHENTICATED ping" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=redis-absent assert_status \
  "redis-requires-auth fails when there is no redis container" \
  redis-requires-auth fail "no running redis" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=redis-no-reply assert_status \
  "redis-requires-auth fails when the probe cannot complete" \
  redis-requires-auth fail "says nothing about" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=redis-strange assert_status \
  "redis-requires-auth fails on an answer that is neither" \
  redis-requires-auth fail "neither the healthy answer" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=target-down assert_status \
  "scrape-targets-up fails on up=0" scrape-targets-up fail "up=0" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=target-absent assert_status \
  "scrape-targets-up fails on a target Prometheus does not know" \
  scrape-targets-up fail "absent from Prometheus" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=series-missing assert_status \
  "container-metrics fails on an absent series" \
  container-metrics fail "basetool_container_pids" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=series-empty assert_status \
  "container-metrics fails on a series with no samples" \
  container-metrics fail "basetool_container_pids" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=logs-stopped assert_status \
  "log-streams fails when ingestion has stopped" log-streams fail "has stopped" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=logs-absent assert_status \
  "log-streams fails when the metric is absent" log-streams fail "no samples" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=traces-absent assert_status \
  "trace-pipeline fails when no span has ever arrived" \
  trace-pipeline fail "no samples at all" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=traces-zero assert_status \
  "trace-pipeline fails when the receiver has accepted zero" \
  trace-pipeline fail "accepted 0 spans" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=edge-open assert_status \
  "edge-not-directly-reachable fails when the edge answers on a routable address" \
  edge-not-directly-reachable fail "can be forged" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=edge-proxyproto assert_status \
  "edge-not-directly-reachable fails when the port connects but answers nothing" \
  edge-not-directly-reachable fail "can be forged" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=edge-world assert_status \
  "edge-not-directly-reachable skips when nothing is in front of the edge" \
  edge-not-directly-reachable skip "does not publish on loopback" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=edge-no-addr assert_status \
  "edge-not-directly-reachable skips when the host reports no global address" \
  edge-not-directly-reachable skip "no global address" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=healthy assert_status \
  "edge-not-directly-reachable passes when every routable address refuses" \
  edge-not-directly-reachable pass "refused on 2 global address" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=no-monitoring assert_status \
  "container-metrics fails clearly when the host has no monitoring plane" \
  container-metrics fail "no monitoring plane" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=env-orphan assert_status \
  "env-reaches-the-units fails when the .env sets a baked variable the units ignore" \
  env-reaches-the-units fail "has no effect" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=env-dropin assert_status \
  "env-reaches-the-units passes when a drop-in carries the override" \
  env-reaches-the-units pass "reach the units" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=env-no-env assert_status \
  "env-reaches-the-units skips when the host has no .env" \
  env-reaches-the-units skip "no /var/iri/code/.env" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=env-no-units assert_status \
  "env-reaches-the-units skips when the host has no Quadlet units anywhere" \
  env-reaches-the-units skip "no Quadlet units found" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=env-unreadable assert_status \
  "env-reaches-the-units says so when it cannot READ the .env, not that it is empty" \
  env-reaches-the-units skip "not readable by this SSH account" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=healthy assert_status \
  "env-reaches-the-units passes when no per-host override is set at all" \
  env-reaches-the-units pass "uncontested" -- "${STUB_ARGS[@]}"

pins="$("$PY" - "$SUITE" <<'PYEOF'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("cc", sys.argv[1])
m = importlib.util.module_from_spec(spec)
sys.modules["cc"] = m
spec.loader.exec_module(m)
print(m._compose_pinned_version("node-exporter") or "", m._compose_pinned_version("alloy") or "")
PYEOF
)"
read -r STUB_NODE_PIN STUB_ALLOY_PIN <<< "$pins"
export STUB_NODE_PIN STUB_ALLOY_PIN
if [[ "$STUB_NODE_PIN" =~ ^[0-9]+\.[0-9]+ && "$STUB_ALLOY_PIN" =~ ^[0-9]+\.[0-9]+ ]]; then
  ok "the compose pins are readable without a YAML parser (node-exporter ${STUB_NODE_PIN}, alloy ${STUB_ALLOY_PIN})"
else
  bad "the compose pins could not be read: '${pins}'"
fi
STUB_SCENARIO=healthy assert_status \
  "host-exporter-versions passes when both host packages run the pinned version" \
  host-exporter-versions pass "match the compose pins" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=exporter-drift assert_status \
  "host-exporter-versions fails when the host package drifted from the pin" \
  host-exporter-versions fail "runs 1.11.0, the compose pin is" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=exporter-down assert_status \
  "host-exporter-versions fails, rather than passes, when the version cannot be read" \
  host-exporter-versions fail "not readable" -- "${STUB_ARGS[@]}"

STUB_SCENARIO=healthy assert_status \
  "security-updates-enabled passes on an enabled, security-only, runtime-excluding setup" \
  security-updates-enabled pass "security-only" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=updates-disabled assert_status \
  "security-updates-enabled fails when the timer is installed and not running" \
  security-updates-enabled fail "no security updates" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=updates-download-only assert_status \
  "security-updates-enabled fails when updates are downloaded and never applied" \
  security-updates-enabled fail "never installed" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=updates-everything assert_status \
  "security-updates-enabled fails on unattended feature updates" \
  security-updates-enabled fail "expected security" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=updates-runtime assert_status \
  "security-updates-enabled fails when the container runtime is not excluded" \
  security-updates-enabled fail "runtime is not excluded" -- "${STUB_ARGS[@]}"

say ""
say "== the client-address classifier, on the spellings a scenario cannot reach =="
classifier_result="$("$PY" - "$SUITE" <<'PYEOF'
import importlib.util
import sys

spec = importlib.util.spec_from_file_location("cc", sys.argv[1])
module = importlib.util.module_from_spec(spec)
sys.modules["cc"] = module          # dataclasses resolves a class's module by name
spec.loader.exec_module(module)

CASES = [
    ("::ffff:172.28.15.10", True,  "the IPv4-mapped bridge address (finding 1)"),
    ("::ffff:10.9.0.14",    True,  "the mapped shape this deployment measured"),
    ("172.28.15.1",         True,  "the same address written bare"),
    ("172.2.3.4",           False, "public, but the old '172.2' prefix called it private"),
    ("172.31.255.254",      True,  "the top of RFC 1918's 172.16/12"),
    ("172.32.0.1",          False, "one address outside it"),
    ("203.0.113.42",        False, "TEST-NET-3, which the fixtures use as a public client"),
    ("127.0.0.1",           True,  "loopback"),
    ("::1",                 True,  "loopback, v6"),
    ("fd00::1",             True,  "RFC 4193 unique-local"),
    ("fe80::1%eth0",        True,  "link-local carrying a zone id"),
    ("2026/09/17",          True,  "not an address at all -- an nginx [notice] line"),
    ("8.8.8.8",             False, "plainly public"),
]
for text, want, why in CASES:
    got = module._is_private(text)
    print(f"{'ok' if got == want else 'FAIL'}|{text}|{got}|{why}")
PYEOF
)"
while IFS='|' read -r verdict text got why; do
  [[ -n "$verdict" ]] || continue
  if [[ "$verdict" == "ok" ]]; then
    ok "_is_private(${text}) = ${got} -- ${why}"
  else
    bad "_is_private(${text}) = ${got} -- ${why}"
  fi
done <<< "$classifier_result"

say ""
say "== every registered check has a red scenario that actually behaved =="
REGISTERED="$("$PY" "$SUITE" --list | awk '{print $1}' | sort -u)"
COVERED="$(printf '%s' "$RED_COVERED" | grep -v '^$' | sort -u)"
MISSING="$(comm -23 <(printf '%s\n' "$REGISTERED") <(printf '%s\n' "$COVERED"))"
if [ -z "$MISSING" ]; then
  ok "all $(printf '%s\n' "$REGISTERED" | wc -l | tr -d ' ') checks have a demonstrated red scenario"
else
  bad "no demonstrated red scenario for: $(printf '%s' "$MISSING" | tr '\n' ' ')"
fi

say ""
say "== a connection failure carries its errno =="
errno_probe="$("$PY" - "$SUITE" <<'PYEOF'
import importlib.util, socket, sys

spec = importlib.util.spec_from_file_location("conf", sys.argv[1])
m = importlib.util.module_from_spec(spec)
sys.modules["conf"] = m
spec.loader.exec_module(m)

sock = socket.socket()
sock.bind(("127.0.0.1", 0))
free_port = sock.getsockname()[1]
sock.close()

try:
    m._connect("127.0.0.1", free_port, socket.AF_INET, 5)
except OSError as exc:
    print("errno=%r" % (exc.errno,))
else:
    print("errno=CONNECTED-UNEXPECTEDLY")
PYEOF
)"
case "$errno_probe" in
  errno=None|errno=CONNECTED-UNEXPECTEDLY)
    bad "_connect lost the errno ($errno_probe) - the ipv6-reachable skip path is dead again" ;;
  errno=*)
    ok "_connect preserves the errno ($errno_probe)" ;;
  *)
    bad "the errno probe produced nothing usable: $errno_probe" ;;
esac

say ""
say "-------------------------------------------------------------"
say "${PASSED} passed, ${FAILED} failed"
[ "$FAILED" -eq 0 ] || exit 1
