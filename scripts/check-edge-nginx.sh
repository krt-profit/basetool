#!/usr/bin/env bash
# =============================================================================
# Validate the edge nginx configuration (ADR-0162).
#
# `nginx -t` is a real gate, not a syntax check: it parses every include,
# resolves every `limit_req_zone` reference by name, rejects an unknown
# directive, AND loads every certificate named by `ssl_certificate`. That last
# part is why this script mints throwaway certificates first — the real ones are
# written by the acme container on the production host and are not, and must not
# be, in this repository.
#
# The certificates are generated on the HOST rather than inside the container:
# `nginxinc/nginx-unprivileged` ships no openssl, and adding one with `apk` would
# make a config check depend on a package mirror. Git-Bash and the GitHub runner
# both have openssl, so the container stays offline and the gate stays fast.
#
# Usage:  scripts/check-edge-nginx.sh
# Exit:   0 = configuration is valid, 1 = it is not (nginx's own message is
#         printed verbatim)
# =============================================================================
set -euo pipefail

# Git-Bash/MSYS rewrites an argument that looks like a POSIX path, so `-subj
# "/CN=host"` arrives at openssl as `C:/Program Files/Git/CN=host` and the
# request is rejected. Excluding ONLY arguments that start with `/CN=` fixes
# that while leaving `-keyout` / `-out` converted — a blanket MSYS_NO_PATHCONV
# would break those instead, since a Windows openssl cannot write to `/tmp/...`.
# The variable is ignored on the Linux runner and in any real shell.
export MSYS2_ARG_CONV_EXCL='/CN='

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EDGE_DIR="${REPO_ROOT}/docker/edge"
# DERIVED from docker-compose.yml, for exactly the reason the NOFILE ceiling
# below is: a second, hand-written copy of a production value drifts, and it
# drifts silently. This one already had - it still read 1.29.3-alpine while
# compose had moved on - so the gate was proving that a DIFFERENT nginx than the
# one production runs accepts this configuration, which is the single thing it
# exists to rule out. A directive removed or tightened upstream would then have
# surfaced at deploy time instead, as a container that refuses to start.
#
# The digest is kept when compose pins one, so this validates the exact image
# rather than merely the same tag.
IMAGE="$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(nginxinc\/nginx-unprivileged:[^[:space:]]*\).*/\1/p' "${REPO_ROOT}/docker-compose.yml" | head -1)"
[[ -n "${IMAGE}" ]] \
  || { echo "FAIL: no nginx-unprivileged image in docker-compose.yml - the edge service changed"; exit 1; }

[[ -d "${EDGE_DIR}" ]] || { echo "FAIL: ${EDGE_DIR} does not exist"; exit 1; }
command -v openssl >/dev/null || { echo "FAIL: openssl not on PATH"; exit 1; }

# The vhosts are DERIVED from the templates, and what is derived is now the
# VARIABLE names rather than hostnames: the per-vhost blocks are rendered at
# start-up from the host `.env`, because one promoted bundle serves every
# environment and nginx has no variables in `server_name`. Restating a list here
# would be a second source of truth, and it would be a production-specific one.
mapfile -t EDGE_VARS < <(
  # shellcheck disable=SC2016  # '${}' is a literal character set for tr, not an expansion
  grep -rhoE '\$\{EDGE_HOST_[A-Z_]+\}' "${EDGE_DIR}"     | tr -d '${}'     | sort -u
)
(( ${#EDGE_VARS[@]} > 0 ))   || { echo "FAIL: no \${EDGE_HOST_*} reference under ${EDGE_DIR} — are the vhost templates still templates?"; exit 1; }

# Every variable the templates consume must be passed by the compose file.
# Without this, adding a vhost renders `server_name ;`, which the container
# refuses to start on — correctly, but at deploy time rather than here.
missing=()
for v in "${EDGE_VARS[@]}"; do
  grep -qE "^[[:space:]]+${v}:" "${REPO_ROOT}/docker-compose.yml" || missing+=("${v}")
done
if (( ${#missing[@]} > 0 )); then
  echo "FAIL: the edge templates use variables docker-compose.yml does not pass: ${missing[*]}"
  exit 1
fi

# acme issues ONE multi-SAN certificate and publishes it into a directory per
# host, while the edge reads a per-host path derived from the same name. The two
# lists used to be compared here as literals; they are both operator-supplied
# now, so what this gate can still assert is that the two sides expect the same
# NUMBER of vhosts — and `render-and-run.sh` refuses to start on an unset one.
# The value-level agreement moved to where the values exist: the host `.env`.
grep -qE '^[[:space:]]+ACME_HOSTS:' "${REPO_ROOT}/docker-compose.yml"   || { echo "FAIL: docker-compose.yml no longer passes ACME_HOSTS to the acme service"; exit 1; }

# Synthetic names for the render. Deliberately NOT the production ones: a gate
# that only ever validates production's spelling would pass a template that
# hardcodes it.
declare -A RENDER=()
i=0
for v in "${EDGE_VARS[@]}"; do
  i=$((i + 1))
  RENDER["${v}"]="vhost${i}.check.invalid"
done
mapfile -t HOSTS < <(printf '%s
' "${RENDER[@]}" | sort -u)

CERT_DIR="$(mktemp -d)"
trap 'rm -rf "${CERT_DIR}"' EXIT

echo "==> minting throwaway certificates for ${#HOSTS[@]} hosts"
for h in "${HOSTS[@]}"; do
  mkdir -p "${CERT_DIR}/${h}"
  # 2048-bit RSA and one day of validity: this material exists for the length of
  # one `nginx -t` and key generation is otherwise the slowest step in the gate.
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj "/CN=${h}" \
    -keyout "${CERT_DIR}/${h}/privkey.pem" \
    -out    "${CERT_DIR}/${h}/fullchain.pem" >/dev/null 2>&1
done

# The trust anchor referenced by proxy_ssl_trusted_certificate. On the host this
# is the public certificate exported from the shared keystore; here any valid PEM
# proves the directive parses and the file is readable.
cp "${CERT_DIR}/${HOSTS[0]}/fullchain.pem" "${CERT_DIR}/upstream-ca.crt"

echo "==> validating ${EDGE_DIR} with ${IMAGE}"

# --user 0:0 applies to THIS VALIDATION CONTAINER ONLY. The image runs as uid 101
# and the runtime keeps it that way — that is the whole point of the unprivileged
# image. The validator needs root solely to assemble a writable /etc/nginx and
# the document roots. Nothing produced here is shipped.
#
# --network none: the check must never depend on the network, and an accidental
# `apk add` or upstream lookup creeping in should fail loudly rather than work on
# a developer's machine and hang in CI.
# Docker needs a native path for a bind mount. Git-Bash hands out `/d/Coding/...`,
# which the daemon cannot resolve; `cygpath -m` turns it into `D:/Coding/...`,
# which MSYS then also leaves alone because it no longer looks like a POSIX path.
# On Linux there is no cygpath and the paths are already native.
to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# The file-descriptor ceiling the compose file gives the edge, applied to THIS
# container too. Without it the validator runs on the daemon default, far above
# production's, so nginx never emits its worker_connections warning here and the
# gate stayed green while every production start logged one.
NOFILE="$(awk '
  /^  [a-z0-9-]+:$/ { in_edge = ($0 == "  edge:") }
  in_edge && /^ *soft:/ { print $2; exit }
' "${REPO_ROOT}/docker-compose.yml")"
[[ -n "${NOFILE}" ]] \
  || { echo "FAIL: the edge service declares no ulimits.nofile.soft"; exit 1; }

# Pass the synthetic host names in, and render through the runtime own script.
ENV_ARGS=()
for v in "${EDGE_VARS[@]}"; do ENV_ARGS+=(-e "${v}=${RENDER[${v}]}"); done

# The edge has TWO shapes since ADR-0187, and a gate that only proves one of them
# is the gate this project already had when the render step was wired up wrongly.
#   plain     - no front end. Listeners are bare and no header is trusted. This is
#               what the Docker deployment runs today.
#   frontend  - EDGE_TRUSTED_PROXY set. Every public listener speaks proxy_protocol
#               and the client address is restored from it.
# They are not variations of one configuration: a proxy_protocol listener REJECTS a
# header-less connection, so a mistake in either direction takes the site down.
check_mode() {
  local mode="$1" trusted="$2"
  local out="${CERT_DIR}/${mode}-nginx-t.out"
  echo "==> validating the '${mode}' shape"

  docker run --rm --user 0:0 --network none \
  "${ENV_ARGS[@]}" -e EDGE_RENDER_ONLY=1 -e EDGE_TRUSTED_PROXY="${trusted}" --ulimit "nofile=${NOFILE}:${NOFILE}" \
  -v "$(to_native "${EDGE_DIR}"):/edge:ro" \
  -v "$(to_native "${CERT_DIR}"):/certs:ro" \
  --entrypoint sh \
  "${IMAGE}" -c '
  set -eu
  # Mirrors the runtime layout exactly: ONE directory at /etc/nginx/edge, and the
  # main config selected with -c. Anything else would validate a shape the
  # container never runs.
  cp -r /edge /etc/nginx/edge
  cp -r /certs /etc/nginx/certs
  mv /etc/nginx/certs/upstream-ca.crt /etc/nginx/upstream-ca.crt
  mkdir -p /var/www/acme /usr/share/nginx/html/maintenance /tmp/nginx
  # nginx does not open these at parse time, but the roots must exist.
  : > /usr/share/nginx/html/maintenance/maintenance.html
  : > /usr/share/nginx/html/maintenance/maintenance.json
  # Render through the runtime script rather than a copy of its logic: a check
  # that renders differently validates a configuration nobody runs.
  sh /etc/nginx/edge/render-and-run.sh
  # -T, not -t. An `include` whose glob matches NOTHING is not an error in nginx,
  # so a plain -t passes a configuration with zero vhosts in it -- which is exactly
  # what this gate did the first time the render step was wired up wrongly.
  # -t and -T, separately, and the sentinel matters. `nginx -T` dumps the whole
  # configuration INCLUDING COMMENTS, and the comments in nginx.conf quote the very
  # warnings this gate looks for -- so a single combined stream makes the check find
  # its own documentation and fail. Warnings are read from the -t part, the vhost
  # presence from the -T part.
  nginx -t -c /etc/nginx/edge/nginx.conf
  echo "@@@CONFIG-DUMP@@@"
  nginx -T -c /etc/nginx/edge/nginx.conf
  echo "@@@RUNTIME@@@"
  # `nginx -t` PARSES. It does not start workers, and several of the things that
  # have taken this edge down only happen when they start: the pid path on a
  # read-only root, the temp directories nginx creates but does not parent, and the
  # worker_connections-versus-file-descriptor warning production logged on every
  # start while this gate stayed green. So start it for real, briefly, and read
  # what it says.
  nginx -g "daemon off;" -c /etc/nginx/edge/nginx.conf > /tmp/runtime.log 2>&1 &
  npid=$!
  sleep 2
  kill "$npid" 2>/dev/null || true
  wait "$npid" 2>/dev/null || true
  cat /tmp/runtime.log
' 2>&1 | tee "${out}"

  # `nginx -t` exits 0 on a warning, and a configuration that always warns is one
  # where the next — real — warning is not read. Treat any [warn] as a failure.
  # Only the part BEFORE the dump sentinel: everything after it is the configuration
  # itself, whose comments quote warnings verbatim.
  sed -n '1,/@@@CONFIG-DUMP@@@/p' "${out}" > "${CERT_DIR}/${mode}-warnings"
  if grep -q '\[warn\]' "${CERT_DIR}/${mode}-warnings"; then
  echo "FAIL: nginx -t emitted a warning (shown above). Fix it or state why it is acceptable."
  exit 1
  fi

  # The assertion that makes the dump worth taking: every synthetic host must appear
  # in the ASSEMBLED configuration. If the include path, the template suffix or the
  # render step breaks, nginx still reports "syntax is ok" -- and this does not.
  for h in "${HOSTS[@]}"; do
  grep -qF "server_name ${h};" "${out}" \
    || { echo "FAIL: ${h} is missing from the assembled configuration"; exit 1; }
  done

  # The runtime section, read on its own. `nginx -t` never prints these: a warning
  # about worker_connections versus the descriptor limit, an alert about a failed
  # setrlimit, an emerg about a path it cannot create. Production logged the first
  # of those on every start for two days while this gate reported the configuration
  # valid, because parsing and starting are not the same thing.
  sed -n '/@@@RUNTIME@@@/,$p' "${out}" > "${CERT_DIR}/${mode}-runtime.out"
  if grep -qE '\[(warn|alert|emerg)\]' "${CERT_DIR}/${mode}-runtime.out"; then
  echo "FAIL: the edge logged a warning or worse when it actually started:"
  grep -E '\[(warn|alert|emerg)\]' "${CERT_DIR}/${mode}-runtime.out" | sed 's/^/  /'
  exit 1
  fi

  # DIRECTIVES ONLY. `nginx -T` dumps the configuration including comments, and
  # nginx.conf's own comment explains at length why there is NO set_real_ip_from
  # here -- so an unfiltered grep finds the documentation and reports the opposite
  # of the truth. This gate already warns about that for [warn]; the same trap
  # applies to every assertion below.
  local dump="${CERT_DIR}/${mode}-directives"
  grep -vE '^[[:space:]]*#' "${out}" > "${dump}"

  # The two shapes have to differ in the assembled configuration, or the gate is
  # green because the switch did nothing rather than because it worked.
  if [[ -n "${trusted}" ]]; then
    grep -qE 'listen .*proxy_protocol' "${dump}" \
      || { echo "FAIL: ${mode}: no listener speaks proxy_protocol"; exit 1; }
    grep -qF "set_real_ip_from ${trusted};" "${dump}" \
      || { echo "FAIL: ${mode}: the client address is not restored from the header"; exit 1; }
    grep -qF 'real_ip_header proxy_protocol;' "${dump}" \
      || { echo "FAIL: ${mode}: real_ip_header is not set to proxy_protocol"; exit 1; }
  else
    grep -qE 'listen .*proxy_protocol' "${dump}" \
      && { echo "FAIL: ${mode}: a listener speaks proxy_protocol with no front end configured"; exit 1; }
    grep -qF 'set_real_ip_from' "${dump}" \
      && { echo "FAIL: ${mode}: a header is trusted with nothing in front of the edge"; exit 1; }
  fi

  # The health listener must NEVER speak proxy_protocol, in either shape: the
  # container's own HEALTHCHECK is a plain wget, and a rejected check would hold
  # the whole stack down behind an edge that is working.
  awk '/listen 127.0.0.1:8081/,/^}/' "${dump}" | grep -q 'proxy_protocol' \
    && { echo "FAIL: ${mode}: the health listener speaks proxy_protocol"; exit 1; }
  grep -qE 'listen (127\.0\.0\.1|\[::1\]):8081;' "${dump}" \
    || { echo "FAIL: ${mode}: the loopback health listener is missing"; exit 1; }

  echo "==> '${mode}' is valid, ${#HOSTS[@]} vhosts rendered and present, and starts clean"
}

# A value that trusts too much is the one way this feature turns into its
# opposite: a source-address forgery tool aimed at the rate limiter and the admin
# allow-list it exists to preserve. `render-and-run.sh` refuses those values, and
# a refusal nobody tests is a refusal that gets removed as dead code.
refuses() {
  local label="$1" value="$2"
  if docker run --rm --user 0:0 --network none        "${ENV_ARGS[@]}" -e EDGE_RENDER_ONLY=1 -e EDGE_TRUSTED_PROXY="${value}"        -v "$(to_native "${EDGE_DIR}"):/edge:ro" --entrypoint sh "${IMAGE}" -c '
         set -eu; cp -r /edge /etc/nginx/edge; sh /etc/nginx/edge/render-and-run.sh
       ' >/dev/null 2>&1; then
    echo "FAIL: EDGE_TRUSTED_PROXY='${value}' (${label}) was ACCEPTED - it must be refused"
    exit 1
  fi
  echo "==> refused ${label}: ${value}"
}

check_mode plain ''
check_mode frontend '172.28.15.10'

refuses 'an IPv4 wildcard' '0.0.0.0/0'
refuses 'an IPv6 wildcard' '::/0'
refuses 'a prefix'         '172.28.15.0/24'

echo "==> edge configuration is valid in BOTH shapes"
