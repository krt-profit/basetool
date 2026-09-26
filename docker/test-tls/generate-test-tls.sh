#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

export TLS_STORE_PASSWORD='basetool-test'

LOCAL='dns:localhost,dns:host.docker.internal,ip:127.0.0.1,ip:10.0.2.2'

rm -f basetool-test-ca.crt basetool-test-truststore.p12 \
  basetool-test-backend.p12 basetool-test-frontend.p12 basetool-test-ingest.p12 basetool-test-keycloak.p12

sh ../../scripts/mint-internal-tls.sh --out . --prefix basetool-test- --days 7300 \
  --ca-cn 'Profit Basetool TEST CA - NOT FOR PRODUCTION' \
  --leaf-cn-suffix 'basetool test stack - NOT FOR PRODUCTION' \
  --service "backend=dns:backend,dns:backend-dev,${LOCAL}" \
  --service "frontend=dns:frontend,dns:frontend-dev,${LOCAL}" \
  --service "ingest=dns:ingest,dns:ingest-dev,${LOCAL}" \
  --service "keycloak=dns:keycloak,dns:keycloak-dev,${LOCAL}"

echo
ls -l basetool-test-*
