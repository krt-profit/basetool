#!/bin/sh
set -eu

CONF_SRC=/etc/nginx/edge/conf.d
CONF_OUT=/tmp/edge-conf.d

EDGE_VARS='EDGE_HOST_FRONTEND EDGE_HOST_INGEST EDGE_HOST_GRAFANA EDGE_HOST_API'

missing=''
for v in ${EDGE_VARS}; do
  eval "value=\${${v}:-}"
  [ -n "${value}" ] || missing="${missing} ${v}"
done
if [ -n "${missing}" ]; then
  echo "edge: refusing to start — unset host variable(s):${missing}" >&2
  echo "edge: set them in the host .env (see docs/deployment.md)" >&2
  exit 1
fi

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

EDGE_ADMIN_ALLOW_EXTRA=''
if [ -n "${EDGE_LISTEN_OPTS}" ]; then
  EDGE_ADMIN_ALLOW_EXTRA='
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

GRAFANA_UPSTREAM_CERT=/etc/nginx/grafana-upstream.crt
case "${EDGE_GRAFANA_UPSTREAM_VERIFY:-off}" in
  on|true)
    if [ ! -s "${GRAFANA_UPSTREAM_CERT}" ]; then
      echo "edge: refusing to start - EDGE_GRAFANA_UPSTREAM_VERIFY=on but ${GRAFANA_UPSTREAM_CERT}" >&2
      echo "edge: is missing or empty. It is Grafana's own certificate, mounted from" >&2
      echo "edge: /var/iri/monitoring/certs/grafana.crt (monitoring/README.md)." >&2
      exit 1
    fi
    EDGE_GRAFANA_UPSTREAM_TLS='include /etc/nginx/edge/include/upstream-grafana-tls.conf;'
    echo "edge: Grafana's upstream certificate is verified (pinned)"
    ;;
  off|false|'')
    EDGE_GRAFANA_UPSTREAM_TLS='# EDGE_GRAFANA_UPSTREAM_VERIFY=off - Grafana upstream encrypted, not verified'
    echo "edge: Grafana's upstream certificate is NOT verified (EDGE_GRAFANA_UPSTREAM_VERIFY=off)"
    ;;
  *)
    echo "edge: refusing to start - EDGE_GRAFANA_UPSTREAM_VERIFY=${EDGE_GRAFANA_UPSTREAM_VERIFY}" >&2
    echo "edge: expected on or off." >&2
    exit 1
    ;;
esac
export EDGE_GRAFANA_UPSTREAM_TLS

# shellcheck disable=SC2016
SHELL_FORMAT='${EDGE_LISTEN_OPTS}${EDGE_ADMIN_ALLOW_EXTRA}${EDGE_GRAFANA_UPSTREAM_TLS}'
for v in ${EDGE_VARS}; do
  SHELL_FORMAT="${SHELL_FORMAT}\${${v}}"
done

mkdir -p "${CONF_OUT}"
rm -f "${CONF_OUT}"/*.conf

RESOLVERS=''
if [ -r /etc/resolv.conf ]; then
  RESOLVERS=$(awk '/^nameserver[ \t]/ {
    if ($2 ~ /:/) printf "[%s] ", $2; else printf "%s ", $2
  }' /etc/resolv.conf)
fi

if [ -z "${RESOLVERS}" ]; then
  if [ -n "${EDGE_RENDER_ONLY:-}" ]; then
    RESOLVERS='192.0.2.1 '
    echo "edge: no /etc/resolv.conf - placeholder resolver, validation only"
  else
    echo "edge: refusing to start - no nameserver in /etc/resolv.conf" >&2
    echo "edge: a variable proxy_pass cannot resolve without one (ADR-0162)" >&2
    exit 1
  fi
fi
printf 'resolver %s valid=10s;\n' "${RESOLVERS% }" > "${CONF_OUT}/00-resolver.conf"
echo "edge: resolver ${RESOLVERS% }"

if [ -n "${EDGE_TRUSTED_PROXY:-}" ]; then
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

if [ -n "${EDGE_RENDER_ONLY:-}" ]; then
  echo "edge: EDGE_RENDER_ONLY is set — rendered, not starting nginx"
  exit 0
fi

exec nginx -g 'daemon off;' -c /etc/nginx/edge/nginx.conf
