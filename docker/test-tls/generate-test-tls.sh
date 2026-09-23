#!/usr/bin/env bash
#
# Regenerates the shared TLS material of the local test stack.
#
# You almost certainly do NOT need to run this. The output is committed, and that
# is the whole point: every developer's test stack, every CI run and the Android
# dev build speak TLS with the SAME material, so nothing has to be installed by
# hand anywhere. Run it only when the material expires (see --days below) or when
# a service needs a hostname that is not in its SAN list.
#
# It has the SHAPE production has (REQ-SEC-070, ADR-0211): one private CA,
# one leaf per service, a CA-only truststore -- minted by the same
# scripts/mint-internal-tls.sh the production host runs, only with published
# throwaway values:
#
#   basetool-test-ca.crt          the trust anchor. A certificate, no key.
#   basetool-test-<svc>.p12       per service (backend, frontend, ingest, keycloak):
#                                 alias `basetool` = that service's key + chain,
#                                 alias `ca`       = the anchor as a trusted entry.
#   basetool-test-truststore.p12  alias `ca` only.
#
# **The CA private key is destroyed by the mint script and is never written to
# the repository.** That is deliberate and load-bearing: this repository is
# public, so a CA key committed here would let anyone mint a certificate that
# every dev build trusts. Without it the published material can do exactly one
# thing -- impersonate the hostnames in the SAN lists, all of which are loopback,
# emulator or docker-network names, to builds that are debuggable.
#
# The trade-off it buys: no leaf can ever be re-issued from the same anchor.
# Regenerating means running this script and updating BOTH repositories (the
# Android dev build bundles basetool-test-ca.crt), hence the long validity.
#
# NEVER point a production deployment at these files. Production mints its own
# material on the host (docs/deployment.md, "Internal TLS"), bind-mounted at
# runtime and never committed.
#
set -euo pipefail

cd "$(dirname "$0")"

# Not a secret, and deliberately not treated as one: the files it protects are
# published in the same directory. It exists because PKCS12 requires one.
export TLS_STORE_PASSWORD='basetool-test'

# Every name the service serves under in a test stack, plus the two addresses the
# Android emulator reaches the host by. Loopback, emulator and docker-network
# names only -- the published keys cannot impersonate anything real.
LOCAL='dns:localhost,dns:host.docker.internal,ip:127.0.0.1,ip:10.0.2.2'

rm -f basetool-test-ca.crt basetool-test-truststore.p12 \
  basetool-test-backend.p12 basetool-test-frontend.p12 basetool-test-ingest.p12 basetool-test-keycloak.p12

# 20 years. The CA key is destroyed, so nothing can re-issue a leaf; a short
# lifetime would only buy a scheduled outage in everyone's test stack. Public CAs
# cap server certificates at 398 days -- that rule binds publicly trusted roots,
# not a private anchor a debug build opts into.
sh ../../scripts/mint-internal-tls.sh --out . --prefix basetool-test- --days 7300 \
  --ca-cn 'Profit Basetool TEST CA - NOT FOR PRODUCTION' \
  --leaf-cn-suffix 'basetool test stack - NOT FOR PRODUCTION' \
  --service "backend=dns:backend,dns:backend-dev,${LOCAL}" \
  --service "frontend=dns:frontend,dns:frontend-dev,${LOCAL}" \
  --service "ingest=dns:ingest,dns:ingest-dev,${LOCAL}" \
  --service "keycloak=dns:keycloak,dns:keycloak-dev,${LOCAL}"

echo
ls -l basetool-test-*
