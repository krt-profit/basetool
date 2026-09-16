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
# EDGE_TRUSTED_PROXY holds the ONE address the edge accepts a PROXY header from.
# Unset means there is no front end: the listeners stay plain and the edge trusts
# nothing, which is what the Docker deployment does today. Setting it switches
# every public listener to `proxy_protocol` AND restores the client address from
# that header -- the two have to move together, because a `proxy_protocol`
# listener rejects a header-less connection and a plain listener never sees one.
#
# PROXY protocol ASSERTS a source address; it does not measure one. So this value
# is a trust decision, and the refusals below are the two ways it is got wrong:
# trusting everything, or trusting a range wide enough to contain an attacker.
EDGE_LISTEN_OPTS=''
if [ -n "${EDGE_TRUSTED_PROXY:-}" ]; then
  case "${EDGE_TRUSTED_PROXY}" in
    0.0.0.0/0|::/0|*' '*)
      echo "edge: refusing to start - EDGE_TRUSTED_PROXY=${EDGE_TRUSTED_PROXY}" >&2
      echo "edge: that trusts every client to forge its own address. Name the" >&2
      echo "edge: front end's single address (ADR-0187), never a wildcard." >&2
      exit 1
      ;;
    */*)
      echo "edge: refusing to start - EDGE_TRUSTED_PROXY=${EDGE_TRUSTED_PROXY}" >&2
      echo "edge: a prefix is not specific enough. The front end has ONE address," >&2
      echo "edge: pinned with IP= in its unit, and anything else in that range" >&2
      echo "edge: could forge a client address past the rate limiter and the" >&2
      echo "edge: admin allow-list (ADR-0187)." >&2
      exit 1
      ;;
  esac
  EDGE_LISTEN_OPTS='proxy_protocol'
fi
export EDGE_LISTEN_OPTS

SHELL_FORMAT='${EDGE_LISTEN_OPTS}'
for v in ${EDGE_VARS}; do
  SHELL_FORMAT="${SHELL_FORMAT}\${${v}}"
done

mkdir -p "${CONF_OUT}"
rm -f "${CONF_OUT}"/*.conf

# After the wipe, or the next start deletes it again.
if [ -n "${EDGE_TRUSTED_PROXY:-}" ]; then
  # http context: `include /tmp/edge-conf.d/*.conf` sits inside `http`, the same
  # place 00-maps.conf is already included from.
  printf 'set_real_ip_from %s;
real_ip_header proxy_protocol;
'     "${EDGE_TRUSTED_PROXY}" > "${CONF_OUT}/00-realip.conf"
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
