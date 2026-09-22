#!/usr/bin/env bash
# =============================================================================================
# Self-test for scripts/check-conformance.py
#
# The conformance suite's acceptance has two halves, and this is the second one:
#
#   1. it goes green against the running host           (run it against the host)
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
# deploy.test.sh stubbing podman, cosign and flock on PATH.
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

# EVERY command must arrive rooted at /, and this guard is why the whole suite notices if it stops.
#
# `ssh root@<host>` starts in /root, which is 0550 root:root on the RHEL family. sudo keeps the
# caller's working directory, so the first probe that reaches the rootless containers --
# `sudo -n -u <service-user> ... podman ps` -- dies with "cannot chdir to /root: Permission denied".
# The runtime-detection loop swallows that, falls back to bare podman, and root's own podman has no
# containers: NINE checks then report a healthy host as absent, from the exact invocation the
# cutover runbook prescribes. Measured on the testing host 2026-09-21.
#
# Asserting it here rather than in one scenario means no check can quietly lose the prefix: the
# arms below all match with a leading `*`, so the prefix does not disturb them.
case "$cmd" in
  "cd /; "*) ;;
  *) echo "hoststub: command did not arrive rooted at / -- see HostRunner.run in check-conformance.py" >&2
     exit 1 ;;
esac

emit_prom() { printf '{"status":"success","data":{"resultType":"vector","result":[%s]}}\n' "$1"; }
sample() { printf '{"metric":{%s},"value":[0,"%s"]}' "$1" "$2"; }

case "$cmd" in
  *"command -v podman"*)
    # The runtime probe. Podman only since 2026-09-22; the arms below match on `podman …`.
    echo "${STUB_RUNTIME:-podman}"
    ;;
  *"SSH_CONNECTION"*)
    # Deliberately empty: the address-equality branch is an extra assertion when an SSH source is
    # available, never a requirement, and the harness must exercise the path that does without it.
    echo ""
    ;;
  *"inspect edge --format"*"LogConfig.Type"*)
    # The check ASKS which log driver the container has rather than assuming one. Both
    # answers are real: the testing host resolved journald (2026-09-18), production
    # resolved k8s-file (2026-09-22) and returned nothing at all to the journalctl
    # form for the whole life of the host. STUB_LOG_DRIVER drives both paths.
    echo "${STUB_LOG_DRIVER:-journald}"
    ;;
  *"podman logs edge"*"--since 60m"*|*"journalctl CONTAINER_NAME=edge"*"60 min ago"*)
    # Access-log LINES now, not a pre-counted number: the check counts distinct addresses itself,
    # in python, so that a first field which is not an address cannot be counted as a client.
    case "$scenario" in
      single-bucket)
        # One real client, collapsed -- plus the nginx error-log lines that used to be counted
        # alongside it. This scenario passed before 2026-09-18 with count=3.
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
      # The same collapse written the way a single dual-stack bind produces it. The prefix-string
      # classifier read this as PUBLIC and passed.
      mapped-addr)  echo "::ffff:172.28.15.10 - - [16/Sep/2026:13:00:00 +0000] \"GET /healthz HTTP/1.1\" 200" ;;
      *)            echo "203.0.113.42 - - [16/Sep/2026:13:00:00 +0000] \"GET /healthz HTTP/1.1\" 200" ;;
    esac
    ;;
    *"podman inspect edge --format '{{json .NetworkSettings.Ports}}'"*)
      case "$scenario" in
        # The pre-ADR-0187 shape: published to the world, so there is nothing to assert yet.
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
      # curl's EXIT CODE now, not %{http_code}: 7 is "could not connect", which is the only
      # passing state. See check_edge_not_directly_reachable for why `000` could not tell a
      # refused port from an established connection that returned nothing.
      case "$scenario" in
        # Something answered on a routable address: the invariant is gone, and the PROXY header the
        # edge trusts can be forged by anyone able to reach that port.
        edge-open)     echo "0" ;;
        # Connected, then got an empty reply -- what :8443 does to a client that does not speak
        # PROXY protocol. Reachable from the internet, and the old probe read it as refused.
        edge-proxyproto) echo "52" ;;
        *)             echo "7" ;;
      esac
      ;;
  # The redis ping comes from INSIDE the container now: the host has no route to a rootless
  # container's IP, so the /dev/tcp probe did not fail, it hung until the runner's timeout killed
  # it. The scenarios are unchanged -- only the command that carries them.
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
  # --- env-reaches-the-units -----------------------------------------------------------------
  # The host .env. Only variables the generator BAKES matter here; anything else is carried into
  # the container by env.d and is not this check's business.
  # `test -r` comes first: it is a longer match on the same path and must not fall into the grep
  # branch below. env-unreadable answers empty, which is what an account that cannot open the file
  # sees -- and the check has to tell that apart from a file that simply sets none of the keys.
  *"test -r /var/iri/code/.env"*)
    case "$scenario" in
      env-unreadable) echo "" ;;
      *)              echo "yes" ;;
    esac
    ;;
  # `test -e` is asked BEFORE `test -r` by the trace check, to tell a file that is not there yet
  # from one that cannot be opened. Run against the migration target before its first deploy, the
  # old order reported "not readable" about a file that did not exist.
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
      # Tracing ON. The apps emit, so an empty or zero span counter is a real fault rather than a
      # switched-off feature -- which is the whole distinction trace-pipeline has to make.
      traces-on|traces-absent|traces-zero)
                      printf 'MONITORING_TRACING_ENABLED=true\n' ;;
      traces-off)     printf 'MONITORING_TRACING_ENABLED=false\n' ;;
      *)              printf 'POSTGRES_DB=basetool\n' ;;
    esac
    ;;
  # The units AND their drop-ins, concatenated the way the check reads them -- the drop-in is the
  # supported way to give one host a different value, so it has to be part of the answer.
  # The units AND their drop-ins. The check reads three locations in ONE `cat`, and
  # /etc/containers/systemd/users/<uid> is the one a release actually delivers to -- match on that
  # as well, or the stub answers nothing for the path that matters on a real host.
  *".config/containers/systemd"*|*"/etc/containers/systemd/users/"*)
    case "$scenario" in
      env-no-units) echo "" ;;
      env-dropin)   printf '[Container]\nAddHost=basetool.example.test:10.0.0.9\n' ;;
      *)            printf '[Container]\nImage=ghcr.io/example/basetool-backend:stable\nVolume=/var/iri/secrets/keystore.p12:/run/secrets/truststore.p12:ro\n' ;;
    esac
    ;;
  # A host with no monitoring plane now looks like what the RUNTIME says, because that is what the
  # classification reads. It used to echo a sentinel of our own -- and the check matched that
  # sentinel against an error message that quotes the command, which itself contained the sentinel,
  # so every failure of the helper (a timeout included) reported "no running prometheus container".
  *"exec prometheus"*|*"query="*)
    if [ "$scenario" = "no-monitoring" ]; then
      echo 'Error: no such object: "prometheus"' >&2
      exit 125
    fi
    ;;&
  *"ReadonlyRootfs"*)
    # one invocation for every app container at once; the check strips a leading slash either way
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
    # One invocation per container, so the name in the command says which one is being asked
    # about. The suite reads the HOST's view of pid 1 and translates it, so the stub speaks in
    # host uids: the identity map is a rootful Docker host, the 100000-based map a rootless one.
    name=db-backend
    case "$cmd" in *db-keycloak*) name=db-keycloak ;; *redis*) name=redis ;; esac
    case "$scenario" in
      container-gone)  [ "$name" = redis ] && { echo ABSENT; exit 0; } ;;
      uid-unreadable)  [ "$name" = redis ] && { echo "Uid:"; exit 0; } ;;
    esac
    case "$scenario" in
      rootless-podman)
        # container uid N arrives as subuid base + N - 1: 100069 -> 70, 100998 -> 999
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
  # PERCENT-ENCODED, because the query is now built with urllib.parse.quote and handed to wget
  # inside the container rather than to curl's --data-urlencode on the host. `count(x)` arrives as
  # `count%28x%29`, and the ` or ` between the two container families as `%20or%20`. Matching the
  # readable spelling silently stopped matching anything, which showed up as "unhandled command".
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
  # ABSENT and ZERO are different faults here and both are faults. Absent means no span has ever
  # arrived -- the shape measured on the testing host, where the app containers could not resolve
  # `alloy` at all -- and zero means the receiver exists but is being fed nothing.
  *"otelcol_receiver_accepted_spans_total"*)
    case "$scenario" in
      traces-absent) emit_prom "" ;;
      traces-zero)   emit_prom "$(sample '' 0)" ;;
      *)             emit_prom "$(sample '' 4127)" ;;
    esac
    ;;
  # --- host-exporter-versions ------------------------------------------------------------------
  # The pins come from the real compose file (the harness exports them), so a Dependabot bump there
  # moves the healthy answer with it instead of turning this suite red.
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
  # --- security-updates-enabled ----------------------------------------------------------------
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
assert_status "containers-unprivileged passes" containers-unprivileged pass "redis=999" -- "${STUB_ARGS[@]}"
assert_status "containers-read-only passes" containers-read-only pass "all 9 app containers" -- "${STUB_ARGS[@]}"
# The same three containers on a ROOTLESS host, where each uid arrives as a subuid. Green only if
# the uid_map translation works -- the half of this check a Docker-shaped stub cannot exercise, and
# the half the migration depends on.
STUB_SCENARIO=rootless-podman assert_status "containers-unprivileged passes through a rootless uid_map" containers-unprivileged pass "db-backend=70" -- "${STUB_ARGS[@]}"
assert_status "redis-requires-auth passes" redis-requires-auth pass "NOAUTH" -- "${STUB_ARGS[@]}"
assert_status "scrape-targets-up passes"  scrape-targets-up  pass "targets up"     -- "${STUB_ARGS[@]}"
assert_status "container-metrics passes"  container-metrics  pass "populated"      -- "${STUB_ARGS[@]}"
assert_status "log-streams passes"        log-streams        pass "ingesting"      -- "${STUB_ARGS[@]}"
STUB_SCENARIO=traces-on assert_status \
  "trace-pipeline passes when spans are arriving" trace-pipeline pass "accepted" -- "${STUB_ARGS[@]}"
# Tracing off is a SKIP and not a pass. An app that emits nothing is not a healthy pipeline, and
# reporting it as one is how a check starts agreeing with everything.
STUB_SCENARIO=traces-off assert_status \
  "trace-pipeline skips when tracing is switched off" trace-pipeline skip "emit no spans" \
  -- "${STUB_ARGS[@]}"

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
# The same collapse, spelled the way a single dual-stack bind produces it. Until 2026-09-18 the
# classifier compared text prefixes, so `::ffff:172.28.15.10` matched none of them and the check
# billed as "the check the whole suite exists for" returned PASS on the failure it exists to
# detect.
STUB_SCENARIO=mapped-addr assert_status \
  "client-address-visible fails on the IPv4-MAPPED form of a bridge address" \
  client-address-visible fail "private/bridge address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
# A collapsed edge whose log ALSO carries nginx error lines. The distinct count merged stderr and
# counted `2026/09/17` and `edge:` as clients, so this scenario reported green with count=3.
STUB_SCENARIO=single-bucket assert_status \
  "client-address-visible fails when the edge sees one distinct client" \
  client-address-visible fail "distinct client address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
# The same host through the OTHER runtime: podman's journald driver, where `podman logs` returns
# nothing and the read has to go to journalctl. Measured on the testing host 2026-09-18.
STUB_RUNTIME=podman STUB_SCENARIO=private-addr assert_status \
  "client-address-visible reads the log under podman too" \
  client-address-visible fail "private/bridge address" \
  -- "${STUB_ARGS[@]}" "${ALL_LOCAL[@]}"
# ...and under the OTHER podman log driver, which is the one that was assumed away. A
# host on `k8s-file` returns nothing to `journalctl CONTAINER_NAME=`, ever -- so the
# check read an empty log and reported "the request did not reach this edge" about an
# edge serving every request on the machine. Measured on production 2026-09-22. The
# verdict has to come from the log's CONTENT here, exactly as it does under journald.
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

# The failure this check exists for: redis skips its own privilege drop when SETUID/SETGID are
# missing and keeps running AS ROOT, healthy and answering PING. containers-running is green in
# exactly that state, which is why it cannot stand in for this check.
STUB_SCENARIO=redis-root assert_status "containers-unprivileged fails when redis runs as root inside the container" containers-unprivileged fail "running as ROOT" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=db-wrong-uid assert_status "containers-unprivileged fails on a uid that is neither root nor the expected one" containers-unprivileged fail "expected 70" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=uid-unreadable assert_status "containers-unprivileged fails rather than passes when the uid cannot be read" containers-unprivileged fail "could not read" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=container-gone assert_status "containers-unprivileged fails when the container is not running at all" containers-unprivileged fail "not running" -- "${STUB_ARGS[@]}"

# keycloak is in the list like everything else. It took a tmpfs over the one directory its start-time
# re-augmentation rewrites to get there, so it is the entry most likely to be quietly dropped on an
# image bump -- which is exactly why it has a scenario of its own.
STUB_SCENARIO=writable-edge assert_status "containers-read-only fails on a writable root filesystem" containers-read-only fail "writable root filesystem" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=writable-keycloak assert_status "containers-read-only fails on keycloak, which needed a tmpfs to get there" containers-read-only fail "writable root filesystem" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=ro-container-gone assert_status "containers-read-only fails when a container is absent rather than passing it over" containers-read-only fail "says nothing" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=container-absent assert_status \
  "containers-running fails on a missing container" \
  containers-running fail "is absent" -- "${STUB_ARGS[@]}"

# The 2026-07-10 defect, as a scenario. An ACL file without a `user default` line makes Redis
# reset default to nopass at load, and --requirepass did not save it -- measured on
# redis:8-alpine, which is why --requirepass was removed and this check took over the job of
# noticing. It is the only thing standing between that mistake and a session store, OAuth2
# refresh tokens included, readable by anything on the internal network.
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

# The measured shape: tracing on, and the counter does not exist at all because no span has ever
# reached the receiver. Nothing else in the monitoring plane reports this -- there is no alert on
# either end of the trace path -- so this assertion is the only thing standing between a silent
# trace outage and a cutover.
STUB_SCENARIO=traces-absent assert_status \
  "trace-pipeline fails when no span has ever arrived" \
  trace-pipeline fail "no samples at all" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=traces-zero assert_status \
  "trace-pipeline fails when the receiver has accepted zero" \
  trace-pipeline fail "accepted 0 spans" -- "${STUB_ARGS[@]}"

# ADR-0187 load-bearing invariant. The red case is the one that matters: a port answering on a
# routable address means the PROXY header can be forged, and nothing about a healthy-looking
# stack would show it.
STUB_SCENARIO=edge-open assert_status \
  "edge-not-directly-reachable fails when the edge answers on a routable address" \
  edge-not-directly-reachable fail "can be forged" -- "${STUB_ARGS[@]}"
# The case %{http_code} could not see: the connection is ESTABLISHED from a routable address and
# the listener then says nothing usable, because it is waiting for a PROXY header. Reachable from
# the internet; the old probe read curl's `000` as "refused" and passed.
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

# The real condition on the testing host today, and the message has to name it rather than leak
# the shell guard's marker.
STUB_SCENARIO=no-monitoring assert_status \
  "container-metrics fails clearly when the host has no monitoring plane" \
  container-metrics fail "no monitoring plane" -- "${STUB_ARGS[@]}"

# --- env-reaches-the-units -------------------------------------------------------------------
# The failure this defends against is a line in the .env that looks effective and is not. It cost
# three separate investigations in one day -- a container timing out against its own issuer, PKIX
# errors against a private CA, and an image tag that was only harmless by coincidence. Each
# presented as a different problem, and none of them as "that variable does nothing".
STUB_SCENARIO=env-orphan assert_status \
  "env-reaches-the-units fails when the .env sets a baked variable the units ignore" \
  env-reaches-the-units fail "has no effect" -- "${STUB_ARGS[@]}"
# The drop-in is the SUPPORTED way to give one host a different value, so it must pass -- a check
# that flagged the correct mechanism would just teach people to switch it off.
STUB_SCENARIO=env-dropin assert_status \
  "env-reaches-the-units passes when a drop-in carries the override" \
  env-reaches-the-units pass "reach the units" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=env-no-env assert_status \
  "env-reaches-the-units skips when the host has no .env" \
  env-reaches-the-units skip "no /var/iri/code/.env" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=env-no-units assert_status \
  "env-reaches-the-units skips when the host has no Quadlet units anywhere" \
  env-reaches-the-units skip "no Quadlet units found" -- "${STUB_ARGS[@]}"
# An UNREADABLE .env is not an empty one, and that difference is the whole point of this check
# existing. .env is 0640 deploy:deploy, so an ordinary login account gets empty output from the
# grep -- indistinguishable, before this scenario, from a file that sets none of the seven keys.
# Measured 2026-09-20: run as `sysadm` against the testing host, the check announced "sets none
# of the variables" about a file that sets IRI_KEYCLOAK_HOST_ALIAS. A check that concludes from
# a file it could not open is exactly what this one reports about other things.
STUB_SCENARIO=env-unreadable assert_status \
  "env-reaches-the-units says so when it cannot READ the .env, not that it is empty" \
  env-reaches-the-units skip "not readable by this SSH account" -- "${STUB_ARGS[@]}"
STUB_SCENARIO=healthy assert_status \
  "env-reaches-the-units passes when no per-host override is set at all" \
  env-reaches-the-units pass "uncontested" -- "${STUB_ARGS[@]}"

# --- host-exporter-versions (OPS-SEC-06) -------------------------------------------------------
# node_exporter and Alloy are host packages the role installs with `state: present`, so a host keeps
# whatever version it was provisioned with while Dependabot moves the compose pin. The pins below
# are read from the real compose file by the suite's own parser, and exported for the stub.
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

# --- security-updates-enabled (OPS-SEC-01, REQ-OPS-032) ----------------------------------------
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

# =============================================================================================
say ""
say "== the client-address classifier, on the spellings a scenario cannot reach =="
# =============================================================================================
# The scenarios above exercise _is_private through the check. This table exercises it directly,
# because two of its cases have no scenario: `172.2.3.4` is ordinary public space that the old
# text-prefix entry `"172.2"` called private -- a FALSE RED nothing would have caught -- and
# `172.32.0.1` sits one address outside RFC 1918's 172.16/12.
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
say "== a connection failure carries its errno =="
# =============================================================================================
# check_ipv6_reachable decides whether a failure belongs to the DEPLOYMENT or to THIS MACHINE by
# reading `exc.errno` -- ENETUNREACH means the runner has no IPv6 and the check must skip rather
# than report a red about four vhosts that are serving. `_connect` used to re-raise a
# single-argument OSError built from an f-string, whose errno is None, so that branch was dead code
# and the suite reported `FAIL ... [Errno 101] Network is unreachable` from any v4-only network
# (measured 2026-09-22). Nothing else in the suite would have noticed: the check still ran, still
# produced a verdict, and the verdict was confidently wrong.
errno_probe="$("$PY" - "$SUITE" <<'PYEOF'
import importlib.util, socket, sys

spec = importlib.util.spec_from_file_location("conf", sys.argv[1])
m = importlib.util.module_from_spec(spec)
# Registered BEFORE exec: the suite defines dataclasses, and dataclasses resolves a field's type
# through sys.modules[cls.__module__], which is None for a module loaded but never registered.
sys.modules["conf"] = m
spec.loader.exec_module(m)

# A port nothing listens on, on loopback: connect fails immediately with ECONNREFUSED. The value of
# the errno does not matter here -- that it SURVIVES does.
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

# =============================================================================================
say ""
say "-------------------------------------------------------------"
say "${PASSED} passed, ${FAILED} failed"
[ "$FAILED" -eq 0 ] || exit 1
