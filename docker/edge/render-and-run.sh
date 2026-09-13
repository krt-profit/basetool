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

SHELL_FORMAT=''
for v in ${EDGE_VARS}; do
  SHELL_FORMAT="${SHELL_FORMAT}\${${v}}"
done

mkdir -p "${CONF_OUT}"
rm -f "${CONF_OUT}"/*.conf

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
