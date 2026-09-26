#!/usr/bin/env bash
set -euo pipefail

export MSYS2_ARG_CONV_EXCL='/CN='

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EDGE_DIR="${REPO_ROOT}/docker/edge"
IMAGE="$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(nginxinc\/nginx-unprivileged:[^[:space:]]*\).*/\1/p' "${REPO_ROOT}/docker-compose.yml" | head -1)"
[[ -n "${IMAGE}" ]] \
  || { echo "FAIL: no nginx-unprivileged image in docker-compose.yml - the edge service changed"; exit 1; }

[[ -d "${EDGE_DIR}" ]] || { echo "FAIL: ${EDGE_DIR} does not exist"; exit 1; }
command -v openssl >/dev/null || { echo "FAIL: openssl not on PATH"; exit 1; }

mapfile -t EDGE_VARS < <(
  # shellcheck disable=SC2016
  grep -rhoE '\$\{EDGE_HOST_[A-Z_]+\}' "${EDGE_DIR}"     | tr -d '${}'     | sort -u
)
(( ${#EDGE_VARS[@]} > 0 ))   || { echo "FAIL: no \${EDGE_HOST_*} reference under ${EDGE_DIR} — are the vhost templates still templates?"; exit 1; }

missing=()
for v in "${EDGE_VARS[@]}"; do
  grep -qE "^[[:space:]]+${v}:" "${REPO_ROOT}/docker-compose.yml" || missing+=("${v}")
done
if (( ${#missing[@]} > 0 )); then
  echo "FAIL: the edge templates use variables docker-compose.yml does not pass: ${missing[*]}"
  exit 1
fi

grep -qE '^[[:space:]]+ACME_HOSTS:' "${REPO_ROOT}/docker-compose.yml"   || { echo "FAIL: docker-compose.yml no longer passes ACME_HOSTS to the acme service"; exit 1; }

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
  openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj "/CN=${h}" \
    -keyout "${CERT_DIR}/${h}/privkey.pem" \
    -out    "${CERT_DIR}/${h}/fullchain.pem" >/dev/null 2>&1
done

cp "${CERT_DIR}/${HOSTS[0]}/fullchain.pem" "${CERT_DIR}/upstream-ca.crt"

openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=grafana" \
  -addext "subjectAltName=DNS:grafana" \
  -keyout "${CERT_DIR}/grafana-upstream.key" -out "${CERT_DIR}/grafana-upstream.crt" >/dev/null 2>&1
rm -f "${CERT_DIR}/grafana-upstream.key"

echo "==> validating ${EDGE_DIR} with ${IMAGE}"

to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

NOFILE="$(awk '
  /^  [a-z0-9-]+:$/ { in_edge = ($0 == "  edge:") }
  in_edge && /^ *soft:/ { print $2; exit }
' "${REPO_ROOT}/docker-compose.yml")"
[[ -n "${NOFILE}" ]] \
  || { echo "FAIL: the edge service declares no ulimits.nofile.soft"; exit 1; }

ENV_ARGS=()
for v in "${EDGE_VARS[@]}"; do ENV_ARGS+=(-e "${v}=${RENDER[${v}]}"); done

check_mode() {
  local mode="$1" trusted="$2" grafana_verify="${3:-off}"
  local out="${CERT_DIR}/${mode}-nginx-t.out"
  echo "==> validating the '${mode}' shape"

  docker run --rm --user 0:0 --network none \
  "${ENV_ARGS[@]}" -e EDGE_RENDER_ONLY=1 -e EDGE_TRUSTED_PROXY="${trusted}" --ulimit "nofile=${NOFILE}:${NOFILE}" \
  -e EDGE_GRAFANA_UPSTREAM_VERIFY="${grafana_verify}" \
  -v "$(to_native "${EDGE_DIR}"):/edge:ro" \
  -v "$(to_native "${CERT_DIR}"):/certs:ro" \
  --entrypoint sh \
  "${IMAGE}" -c '
  set -eu
  cp -r /edge /etc/nginx/edge
  cp -r /certs /etc/nginx/certs
  mv /etc/nginx/certs/upstream-ca.crt /etc/nginx/upstream-ca.crt
  mv /etc/nginx/certs/grafana-upstream.crt /etc/nginx/grafana-upstream.crt
  mkdir -p /var/www/acme /usr/share/nginx/html/maintenance /tmp/nginx
  : > /usr/share/nginx/html/maintenance/maintenance.html
  : > /usr/share/nginx/html/maintenance/maintenance.json
  sh /etc/nginx/edge/render-and-run.sh
  nginx -t -c /etc/nginx/edge/nginx.conf
  echo "@@@CONFIG-DUMP@@@"
  nginx -T -c /etc/nginx/edge/nginx.conf
  echo "@@@RUNTIME@@@"
  nginx -g "daemon off;" -c /etc/nginx/edge/nginx.conf > /tmp/runtime.log 2>&1 &
  npid=$!
  sleep 2
  kill "$npid" 2>/dev/null || true
  wait "$npid" 2>/dev/null || true
  cat /tmp/runtime.log
' 2>&1 | tee "${out}"

  sed -n '1,/@@@CONFIG-DUMP@@@/p' "${out}" > "${CERT_DIR}/${mode}-warnings"
  if grep -q '\[warn\]' "${CERT_DIR}/${mode}-warnings"; then
  echo "FAIL: nginx -t emitted a warning (shown above). Fix it or state why it is acceptable."
  exit 1
  fi

  for h in "${HOSTS[@]}"; do
  grep -qF "server_name ${h};" "${out}" \
    || { echo "FAIL: ${h} is missing from the assembled configuration"; exit 1; }
  done

  sed -n '/@@@RUNTIME@@@/,$p' "${out}" > "${CERT_DIR}/${mode}-runtime.out"
  if grep -qE '\[(warn|alert|emerg)\]' "${CERT_DIR}/${mode}-runtime.out"; then
  echo "FAIL: the edge logged a warning or worse when it actually started:"
  grep -E '\[(warn|alert|emerg)\]' "${CERT_DIR}/${mode}-runtime.out" | sed 's/^/  /'
  exit 1
  fi

  local dump="${CERT_DIR}/${mode}-directives"
  grep -vE '^[[:space:]]*#' "${out}" > "${dump}"

  if [[ -n "${trusted}" ]]; then
    grep -qE 'listen .*proxy_protocol' "${dump}" \
      || { echo "FAIL: ${mode}: no listener speaks proxy_protocol"; exit 1; }
    local _tp
    for _tp in ${trusted}; do
      grep -qF "set_real_ip_from ${_tp};" "${dump}" \
        || { echo "FAIL: ${mode}: ${_tp} is not trusted - the client address is not restored from the header"; exit 1; }
    done
    [[ "$(grep -cF 'set_real_ip_from ' "${dump}")" == "$(wc -w <<<"${trusted}")" ]] \
      || { echo "FAIL: ${mode}: the rendered set_real_ip_from count does not match EDGE_TRUSTED_PROXY"; exit 1; }
    grep -qF 'real_ip_header proxy_protocol;' "${dump}" \
      || { echo "FAIL: ${mode}: real_ip_header is not set to proxy_protocol"; exit 1; }
  else
    grep -qE 'listen .*proxy_protocol' "${dump}" \
      && { echo "FAIL: ${mode}: a listener speaks proxy_protocol with no front end configured"; exit 1; }
    grep -qF 'set_real_ip_from' "${dump}" \
      && { echo "FAIL: ${mode}: a header is trusted with nothing in front of the edge"; exit 1; }
  fi

  local gz
  gz="$(grep -E '^[[:space:]]*gzip_types ' "${dump}" || true)"
  [[ -n "${gz}" ]] || { echo "FAIL: ${mode}: no gzip_types - the edge compresses text/html only"; exit 1; }
  local gz_list t
  gz_list="$(tr -s ' ;\t' '\n' <<<"${gz}")"
  for t in text/css text/javascript application/javascript application/json image/svg+xml; do
    grep -qxF "${t}" <<<"${gz_list}" \
      || { echo "FAIL: ${mode}: gzip_types does not list ${t}"; exit 1; }
  done
  grep -qxF 'text/event-stream' <<<"${gz_list}" \
    && { echo "FAIL: ${mode}: gzip_types lists text/event-stream - gzip would buffer the SSE streams"; exit 1; }
  grep -qE '^[[:space:]]*gzip_vary on;' "${dump}" \
    || { echo "FAIL: ${mode}: gzip_vary is not on"; exit 1; }

  awk '/listen 127.0.0.1:8081/,/^}/' "${dump}" | grep -q 'proxy_protocol' \
    && { echo "FAIL: ${mode}: the health listener speaks proxy_protocol"; exit 1; }
  grep -qE 'listen (127\.0\.0\.1|\[::1\]):8081;' "${dump}" \
    || { echo "FAIL: ${mode}: the loopback health listener is missing"; exit 1; }

  local admin
  admin="$(awk '/location \^~ \/auth\/admin/,/^[[:space:]]*}/' "${dump}")"
  grep -q 'allow 172.28.15.1;' <<<"${admin}" \
    || { echo "FAIL: ${mode}: the ingress-gateway grant is missing from /auth/admin"; exit 1; }
  if [[ -n "${trusted}" ]]; then
    grep -q 'allow 127.0.0.1;' <<<"${admin}" \
      || { echo "FAIL: ${mode}: /auth/admin does not admit the tunnel's loopback address - this is the #1885 lockout, reintroduced"; exit 1; }
    grep -q 'allow ::1;' <<<"${admin}" \
      || { echo "FAIL: ${mode}: /auth/admin admits 127.0.0.1 but not ::1 - one word of the ssh command would decide whether the console opens"; exit 1; }
  else
    grep -q 'allow 127.0.0.1;' <<<"${admin}" \
      && { echo "FAIL: ${mode}: /auth/admin grants loopback on a listener where it is not the tunnel"; exit 1; }
  fi
  grep -q 'deny all;' <<<"${admin}" \
    || { echo "FAIL: ${mode}: /auth/admin lost its load-bearing 'deny all'"; exit 1; }

  local gtls='include /etc/nginx/edge/include/upstream-grafana-tls.conf;'
  if [[ "${grafana_verify}" == "on" ]]; then
    grep -qF "${gtls}" "${dump}" \
      || { echo "FAIL: ${mode}: EDGE_GRAFANA_UPSTREAM_VERIFY=on but the Grafana location does not include its TLS block"; exit 1; }
    grep -qE '^[[:space:]]*proxy_ssl_trusted_certificate /etc/nginx/grafana-upstream.crt;' "${dump}" \
      || { echo "FAIL: ${mode}: the Grafana upstream is not anchored on Grafana's own certificate"; exit 1; }
    grep -qE '^[[:space:]]*proxy_ssl_name[[:space:]]+grafana;' "${dump}" \
      || { echo "FAIL: ${mode}: the Grafana upstream does not check the name grafana"; exit 1; }
  else
    grep -qF "${gtls}" "${dump}" \
      && { echo "FAIL: ${mode}: the Grafana upstream is verified although EDGE_GRAFANA_UPSTREAM_VERIFY is off"; exit 1; }
  fi
  [[ "$(grep -cE '^[[:space:]]*proxy_ssl_trusted_certificate /etc/nginx/grafana-upstream.crt;' "${dump}")" -le 1 ]] \
    || { echo "FAIL: ${mode}: Grafana's certificate is an anchor in more than one place"; exit 1; }

  echo "==> '${mode}' is valid, ${#HOSTS[@]} vhosts rendered and present, and starts clean"
}

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
check_mode frontend-rootless '172.28.15.10 172.28.3.250 172.28.4.250 172.28.7.250 172.28.11.250 172.28.13.250'
check_mode grafana-verified '' on

refuses 'an IPv4 wildcard'         '0.0.0.0/0'
refuses 'an IPv6 wildcard'         '::/0'
refuses 'a prefix'                 '172.28.15.0/24'
refuses 'a bare IPv4 wildcard'     '0.0.0.0'
refuses 'a bare IPv6 wildcard'     '::'
refuses 'a prefix in a list'       '172.28.3.250 172.28.15.0/24'
refuses 'a wildcard in a list'     '172.28.3.250 0.0.0.0'

refuses_admin() {
  local label="$1" value="$2"
  if docker run --rm --user 0:0 --network none "${ENV_ARGS[@]}" \
       -e EDGE_RENDER_ONLY=1 -e EDGE_TRUSTED_PROXY='172.28.15.10' -e EDGE_ADMIN_ALLOW="${value}" \
       -v "$(to_native "${EDGE_DIR}"):/edge:ro" --entrypoint sh "${IMAGE}" -c '
         set -eu; cp -r /edge /etc/nginx/edge; sh /etc/nginx/edge/render-and-run.sh
       ' >/dev/null 2>&1; then
    echo "FAIL: EDGE_ADMIN_ALLOW='${value}' (${label}) was ACCEPTED - it must be refused"
    exit 1
  fi
  echo "==> refused admin ${label}: ${value}"
}

refuses_admin 'a prefix'        '10.0.0.0/8'
refuses_admin 'an IPv4 wildcard' '0.0.0.0'
refuses_admin 'one good and one bad address' '10.9.0.7 192.168.0.0/16'

refuses_grafana() {
  local label="$1" value="$2" with_cert="$3"
  if docker run --rm --user 0:0 --network none "${ENV_ARGS[@]}" \
       -e EDGE_RENDER_ONLY=1 -e EDGE_GRAFANA_UPSTREAM_VERIFY="${value}" -e WITH_CERT="${with_cert}" \
       -v "$(to_native "${EDGE_DIR}"):/edge:ro" -v "$(to_native "${CERT_DIR}"):/certs:ro" \
       --entrypoint sh "${IMAGE}" -c '
         set -eu; cp -r /edge /etc/nginx/edge
         [ "${WITH_CERT}" = yes ] && cp /certs/grafana-upstream.crt /etc/nginx/grafana-upstream.crt
         sh /etc/nginx/edge/render-and-run.sh
       ' >/dev/null 2>&1; then
    echo "FAIL: EDGE_GRAFANA_UPSTREAM_VERIFY='${value}' (${label}) was ACCEPTED - it must be refused"
    exit 1
  fi
  echo "==> refused grafana ${label}: ${value}"
}

refuses_grafana 'on without the certificate' 'on' no
refuses_grafana 'an unknown value' 'yes' yes

echo "==> edge configuration is valid in ALL FOUR shapes"
