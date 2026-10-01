#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALLOW_LIST="${REPO_ROOT}/docker/edge/include/api-allowlist.conf"
IMAGE="$(sed -n 's/^[[:space:]]*image:[[:space:]]*\(nginxinc\/nginx-unprivileged:[^[:space:]]*\).*/\1/p' "${REPO_ROOT}/docker-compose.yml" | head -1)"
[[ -n "${IMAGE}" ]] || { echo "FAIL: no nginx-unprivileged image in docker-compose.yml"; exit 1; }
[[ -f "${ALLOW_LIST}" ]] || { echo "FAIL: ${ALLOW_LIST} does not exist"; exit 1; }
command -v curl >/dev/null || { echo "FAIL: curl not on PATH"; exit 1; }

to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

WORK="$(mktemp -d)"
NAME="edge-allowlist-behaviour-$$"
cleanup() {
  docker rm -f "${NAME}" >/dev/null 2>&1 || true
  rm -rf "${WORK}"
}
trap cleanup EXIT

cp "${ALLOW_LIST}" "${WORK}/api-allowlist.conf"
cat > "${WORK}/nginx.conf" <<'CONF'
pid /tmp/nginx.pid;
events { worker_connections 64; }
http {
  access_log off;
  error_log /dev/stderr warn;
  server {
    listen 8080;
    include /etc/nginx/edge/api-allowlist.conf;
    location / { return 200 "reached"; }
  }
}
CONF

docker run -d --name "${NAME}" -p 127.0.0.1::8080 \
  -v "$(to_native "${WORK}/nginx.conf"):/etc/nginx/nginx.conf:ro" \
  -v "$(to_native "${WORK}/api-allowlist.conf"):/etc/nginx/edge/api-allowlist.conf:ro" \
  "${IMAGE}" >/dev/null

PORT="$(docker port "${NAME}" 8080/tcp | head -1 | sed 's/.*://')"
[[ -n "${PORT}" ]] || { echo "FAIL: no published port"; exit 1; }

for _ in $(seq 1 30); do
  if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:${PORT}/api/v1/orders"; then break; fi
  sleep 1
done

NIL="00000000-0000-4000-8000-00000000cafe"
failures=0
checked=0

expect() {
  local want="$1" method="$2" path="$3" got
  checked=$((checked + 1))
  if [[ "${method}" == "HEAD" ]]; then
    got="$(curl -s -o /dev/null -I -w '%{http_code}' --max-time 5 "http://127.0.0.1:${PORT}${path}" || true)"
  else
    got="$(curl -s -o /dev/null -X "${method}" -w '%{http_code}' --max-time 5 "http://127.0.0.1:${PORT}${path}" || true)"
  fi
  if [[ "${got}" == "${want}" ]]; then
    printf '  ok    %s %s -> %s\n' "${method}" "${path}" "${got}"
  else
    failures=$((failures + 1))
    printf '  FAIL  %s %s -> %s (wanted %s)\n' "${method}" "${path}" "${got}" "${want}"
  fi
}

echo "== POST /api/v1/operations is admitted, and only that (B-02)"
expect 200 POST /api/v1/operations
expect 200 POST "/api/v1/operations?probe=1"
expect 404 GET /api/v1/operations
expect 404 HEAD /api/v1/operations
expect 404 PUT /api/v1/operations
expect 404 PATCH /api/v1/operations
expect 404 DELETE /api/v1/operations
expect 404 POST /api/v1/operations/
expect 404 POST /api/v1/operationsX
expect 404 POST /api/v1/operations%2F
expect 404 POST /api/v1/Operations
expect 404 POST /api/v1/operations/foo
expect 404 POST /api/v1/operations/foo/bar
expect 404 POST /api/v1/operation

echo "== the neighbouring operations rules are unchanged"
expect 200 GET /api/v1/operations/search
expect 405 POST /api/v1/operations/search
expect 200 GET /api/v1/operations/lookup
expect 405 POST /api/v1/operations/lookup
expect 200 GET "/api/v1/operations/${NIL}"
expect 200 PUT "/api/v1/operations/${NIL}"
expect 405 POST "/api/v1/operations/${NIL}"
expect 405 DELETE "/api/v1/operations/${NIL}"
expect 200 GET "/api/v1/operations/${NIL}/payouts"
expect 405 POST "/api/v1/operations/${NIL}/payouts"
expect 200 PUT "/api/v1/operations/${NIL}/payouts/paid-out"

echo "== other families are unchanged"
expect 200 POST /api/v1/orders
expect 404 GET /api/v1/missions
expect 404 POST /api/v1/missions
expect 404 POST /api/v1/users
expect 404 GET /api/v1/unknown

echo "${checked} checked, ${failures} failed"
[[ "${failures}" -eq 0 ]]
