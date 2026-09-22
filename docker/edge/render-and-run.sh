#!/bin/sh
# =============================================================================
# Render the edge's per-vhost configuration, then become nginx (ADR-0162).
#
# nginx has no variables in `server_name`, and there is exactly ONE promoted
# configuration bundle for every environment — so the host names cannot live in
# the committed files. They arrive as environment variables from the host `.env`,
# which is host-specific and deliberately never bundled, and are substituted here
# at start-up.
#
# Why this file rather than the image's own template mechanism: that one renders
# `/etc/nginx/templates/*.template` into `/etc/nginx/conf.d/`, and this container
# runs `read_only: true` with its configuration mounted at `/etc/nginx/edge`.
# Rendering into `/tmp` — a tmpfs the compose file already grants — keeps the
# read-only root filesystem, and `nginx.conf` includes from there.
#
# `envsubst` is given an EXPLICIT variable list. Without one it substitutes every
# `$name` it finds, which in an nginx configuration means `$scheme`,
# `$remote_addr`, `$request_uri` and the rest would be replaced by empty strings
# — a config that still parses and silently does the wrong thing. Verified in the
# pinned image: with the list, `$scheme` and `$remote_addr` come through
# untouched.
# =============================================================================
set -eu

CONF_SRC=/etc/nginx/edge/conf.d
CONF_OUT=/tmp/edge-conf.d

# One variable per vhost. The certificate directory is derived from the same
# name, so the layout `/etc/nginx/certs/<host>/{fullchain,privkey}.pem` holds in
# every environment and `acme` needs to know nothing about this file.
# EDGE_HOST_KEYCLOAK is gone since ADR-0166: Keycloak answers at /auth on the web
# host and no longer has a vhost, a certificate directory or a name of its own.
EDGE_VARS='EDGE_HOST_FRONTEND EDGE_HOST_INGEST EDGE_HOST_GRAFANA EDGE_HOST_API'

missing=''
for v in ${EDGE_VARS}; do
  eval "value=\${${v}:-}"
  [ -n "${value}" ] || missing="${missing} ${v}"
done
if [ -n "${missing}" ]; then
  # Refuse rather than render. An empty substitution produces `server_name ;`,
  # which nginx rejects — but it would reject it with a line number in a
  # generated file nobody has on disk, so say it plainly here instead.
  echo "edge: refusing to start — unset host variable(s):${missing}" >&2
  echo "edge: set them in the host .env (see docs/deployment.md)" >&2
  exit 1
fi

# --- optional: a PROXY-protocol front end in front of the edge (ADR-0187) -----
#
# EDGE_TRUSTED_PROXY holds the addresses the edge accepts a PROXY header from.
# Unset means there is no front end: the listeners stay plain and the edge trusts
# nothing, which is what the Docker deployment does today. Setting it switches
# every public listener to `proxy_protocol` AND restores the client address from
# that header -- the two have to move together, because a `proxy_protocol`
# listener rejects a header-less connection and a plain listener never sees one.
#
# PROXY protocol ASSERTS a source address; it does not measure one. So this value
# is a trust decision, and the refusals below are the two ways it is got wrong:
# trusting everything, or trusting a range wide enough to contain an attacker.
# A SPACE-SEPARATED LIST since 2026-09-22, and it was one address before that. The rule that
# matters is unchanged -- never a prefix, name each address -- and a list of literals is the
# opposite of a wildcard rather than a step toward one.
#
# Why it has to be a list: under rootless Podman the peer the edge sees is the edge's OWN address,
# and rootlessport picks WHICH of the container's networks to present it on. Measured on the
# production host 2026-09-22 across three recreations with nothing else changed: the peer appeared
# on net-proxy-frontend, then net-proxy-grafana, then net-proxy-api. Pinning one network only moves
# the choice to another. So every network the edge is on is pinned, and all of those addresses are
# named here -- a finite, fixed set, each one the edge itself, none of them reachable by anybody
# else.
EDGE_LISTEN_OPTS=''
if [ -n "${EDGE_TRUSTED_PROXY:-}" ]; then
  for _tp in ${EDGE_TRUSTED_PROXY}; do
    case "${_tp}" in
      0.0.0.0|::)
        echo "edge: refusing to start - EDGE_TRUSTED_PROXY contains ${_tp}" >&2
        echo "edge: that trusts every client to forge its own address. Name each" >&2
        echo "edge: address the front end can reach the edge from (ADR-0187)," >&2
        echo "edge: never a wildcard." >&2
        exit 1
        ;;
      */*)
        echo "edge: refusing to start - EDGE_TRUSTED_PROXY contains ${_tp}" >&2
        echo "edge: a prefix is not specific enough. Each address is pinned with" >&2
        echo "edge: ip= in the edge's unit, and anything else in that range could" >&2
        echo "edge: forge a client address past the rate limiter and the admin" >&2
        echo "edge: allow-list (ADR-0187)." >&2
        exit 1
        ;;
    esac
  done
  unset _tp
  EDGE_LISTEN_OPTS='proxy_protocol'
fi
export EDGE_LISTEN_OPTS

# --- the /auth/admin allow-list, which has to move with the listener ----------
#
# The allow-list in 10-frontend.conf.template names six CONTAINER-BRIDGE gateways,
# because that is what $remote_addr was before ADR-0187: the operator's
# `ssh -L 443:127.0.0.1:443` tunnel entered the published port directly and
# arrived on 172.28.15.1.
#
# Under proxy_protocol $remote_addr is whatever haproxy asserts, and the same
# tunnel now arrives as 127.0.0.1 -- matching none of the six. That fails CLOSED,
# so it is a lockout rather than a bypass, but it is a lockout in the middle of
# the cutover, which reads as a broken tunnel and is not one. #1885 already cost
# a day to this exact shape on the retired vhost.
#
# Measured on the testing host 2026-09-18, three ways, because "127.0.0.1 means
# someone on this host" is the whole safety argument and it had to be shown:
#
#   from the host's loopback (the tunnel)      -> the edge logged 127.0.0.1
#   from the host's routable address, which is
#     also how the Caddy in front arrives      -> the edge logged 10.9.0.15
#   from inside a container                    -> Network unreachable; a rootless
#                                                 container on a netavark bridge
#                                                 cannot reach the host at all
#
# So loopback here cannot be produced by a container, by a LAN peer, or by the
# proxy in front: it means a process already running on the host, which has more
# access than the admin console by definition.
#
# It is rendered rather than written into the template because it is only true in
# ONE of the two modes. A blanket `allow 127.0.0.1;` in a file shared by both
# would also be a standing grant on a host where something local forwards to
# haproxy, and would read as intentional there.
#
# EDGE_ADMIN_ALLOW adds further addresses -- a jump host, a VPN endpoint -- under
# the same rules as EDGE_TRUSTED_PROXY: literal addresses only, never a prefix.
EDGE_ADMIN_ALLOW_EXTRA=''
if [ -n "${EDGE_LISTEN_OPTS}" ]; then
  EDGE_ADMIN_ALLOW_EXTRA='
    # proxy_protocol mode: the operator tunnel arrives as the host loopback.
    allow 127.0.0.1;
    allow ::1;'
fi
for a in ${EDGE_ADMIN_ALLOW:-}; do
  case "${a}" in
    */*|0.0.0.0|::)
      echo "edge: refusing to start - EDGE_ADMIN_ALLOW=${a}" >&2
      echo "edge: the Keycloak admin console is not handed a range. Name each" >&2
      echo "edge: address, the way EDGE_TRUSTED_PROXY is named (ADR-0187)." >&2
      exit 1
      ;;
  esac
  EDGE_ADMIN_ALLOW_EXTRA="${EDGE_ADMIN_ALLOW_EXTRA}
    allow ${a};"
done
export EDGE_ADMIN_ALLOW_EXTRA

# shellcheck disable=SC2016  # the literal token is the point: this is envsubst's allow-list of
# names to substitute, not an expansion. Expanding it here would hand envsubst the VALUE and it
# would then substitute nothing, which fails silently -- every listener would render without its
# options and the proxy_protocol shape would quietly become the plain one.
SHELL_FORMAT='${EDGE_LISTEN_OPTS}${EDGE_ADMIN_ALLOW_EXTRA}'
for v in ${EDGE_VARS}; do
  SHELL_FORMAT="${SHELL_FORMAT}\${${v}}"
done

mkdir -p "${CONF_OUT}"
rm -f "${CONF_OUT}"/*.conf

# --- the resolver, derived rather than hardcoded --------------------------------
#
# `proxy_pass` with a variable host defers resolution to run time, which is what
# lets an upstream container be recreated with a new address without restarting
# the edge. That needs a `resolver`, and the address is RUNTIME-SPECIFIC:
#
#   Docker  -> its embedded DNS at 127.0.0.11
#   Podman  -> aardvark-dns, on the gateway of each attached network
#
# nginx.conf used to carry 127.0.0.11 literally. Under Podman nothing listens
# there, so nginx sent each query into a void, waited the default
# resolver_timeout of 30s, and answered 503 -- while TCP and TLS completed
# normally, so the failure was invisible to every check short of a real request.
#
# /etc/resolv.conf is correct on both runtimes by construction: it is what the
# container's own resolver uses. Measured on Podman 5.8.2 with the edge on six
# networks: all nine addresses it lists answer for a container on any one of
# them, so passing them all is safe and no round-robin can pick a server that
# would say NXDOMAIN for a name another server knows.
#
# IPv6 addresses need brackets in an nginx resolver directive.
RESOLVERS=''
if [ -r /etc/resolv.conf ]; then
  RESOLVERS=$(awk '/^nameserver[ \t]/ {
    if ($2 ~ /:/) printf "[%s] ", $2; else printf "%s ", $2
  }' /etc/resolv.conf)
fi

if [ -z "${RESOLVERS}" ]; then
  if [ -n "${EDGE_RENDER_ONLY:-}" ]; then
    # A configuration check has no network and no DNS: scripts/check-edge-nginx.sh
    # renders with `--network none`, where /etc/resolv.conf does not merely lack a
    # nameserver -- it does not exist. `nginx -t` still needs a syntactically valid
    # directive, and which address it names cannot affect a parse.
    #
    # 192.0.2.1 is TEST-NET-1 (RFC 5737), reserved for documentation and routable
    # nowhere, so this can never be mistaken for a working configuration if it
    # somehow reaches a running edge.
    RESOLVERS='192.0.2.1 '
    echo "edge: no /etc/resolv.conf - placeholder resolver, validation only"
  else
    # Refuse at start rather than at the first proxied request. Without a resolver
    # nginx starts happily and then fails every upstream with "no resolver defined
    # to resolve <name>" -- an error that arrives hours later and reads as a DNS
    # outage rather than as a missing line.
    echo "edge: refusing to start - no nameserver in /etc/resolv.conf" >&2
    echo "edge: a variable proxy_pass cannot resolve without one (ADR-0162)" >&2
    exit 1
  fi
fi
printf 'resolver %s valid=10s;\n' "${RESOLVERS% }" > "${CONF_OUT}/00-resolver.conf"
echo "edge: resolver ${RESOLVERS% }"

# After the wipe, or the next start deletes it again.
if [ -n "${EDGE_TRUSTED_PROXY:-}" ]; then
  # http context: `include /tmp/edge-conf.d/*.conf` sits inside `http`, the same
  # place 00-maps.conf is already included from.
  : > "${CONF_OUT}/00-realip.conf"
  for _tp in ${EDGE_TRUSTED_PROXY}; do
    printf 'set_real_ip_from %s;\n' "${_tp}" >> "${CONF_OUT}/00-realip.conf"
  done
  unset _tp
  printf 'real_ip_header proxy_protocol;\n' >> "${CONF_OUT}/00-realip.conf"
  echo "edge: PROXY protocol on, trusting ${EDGE_TRUSTED_PROXY} only"
else
  echo "edge: no front end configured - listeners are plain, no header is trusted"
fi

# Environment-independent blocks are copied verbatim. `*.conf` cannot match
# `*.conf.template`, so the two loops never touch the same file.
for f in "${CONF_SRC}"/*.conf; do
  [ -e "${f}" ] || continue
  cp "${f}" "${CONF_OUT}/"
done

rendered=0
for t in "${CONF_SRC}"/*.conf.template; do
  [ -e "${t}" ] || continue
  out="${CONF_OUT}/$(basename "${t}" .template)"
  envsubst "${SHELL_FORMAT}" < "${t}" > "${out}"
  rendered=$((rendered + 1))
done
[ "${rendered}" -gt 0 ] || { echo "edge: no vhost templates found under ${CONF_SRC}" >&2; exit 1; }

echo "edge: rendered ${rendered} vhost(s) into ${CONF_OUT}"

# scripts/check-edge-nginx.sh renders through THIS file rather than a copy of its
# logic, then runs `nginx -t` itself. A check that renders differently from the
# runtime validates a configuration nobody runs.
if [ -n "${EDGE_RENDER_ONLY:-}" ]; then
  echo "edge: EDGE_RENDER_ONLY is set — rendered, not starting nginx"
  exit 0
fi

exec nginx -g 'daemon off;' -c /etc/nginx/edge/nginx.conf
