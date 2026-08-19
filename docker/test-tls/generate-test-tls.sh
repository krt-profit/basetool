#!/usr/bin/env bash
#
# Regenerates the shared TLS material of the local test stack.
#
# You almost certainly do NOT need to run this. The output is committed, and that
# is the whole point: every developer's test stack, every CI run and the Android
# dev build speak TLS with the SAME certificate, so nothing has to be installed
# by hand anywhere. Run it only when the material expires (see VALIDITY_DAYS) or
# when a new service hostname has to be added to the SAN list below.
#
# What it produces, and why it is safe to publish:
#
#   basetool-test-ca.crt        the trust anchor. A certificate, no key.
#   basetool-test-keystore.p12  alias `basetool`: the server key + its chain,
#                               alias `ca`:       the anchor again, so the same
#                                                 file also works as the
#                                                 TRUSTSTORE that frontend and
#                                                 ingest use to validate
#                                                 https://backend:11261.
#
# **The CA private key is destroyed at the end of this script and is never
# written to the repository.** That is deliberate and load-bearing: this
# repository is public, so a CA key committed here would let anyone mint a
# certificate that every dev build trusts. Without it the published material can
# do exactly one thing — impersonate the hostnames in SAN_LIST, all of which are
# loopback, emulator or docker-network names, to builds that are debuggable.
#
# The trade-off it buys: the leaf can never be re-issued from the same anchor.
# Regenerating means running this script and updating BOTH repositories, hence
# the deliberately long validity.
#
# NEVER point a production deployment at this file. Production keeps its own
# keystore, bind-mounted at runtime and never committed (`.gitignore` still
# refuses the exact name `keystore.p12` for that reason).
#
set -euo pipefail

cd "$(dirname "$0")"

# 20 years. The CA key is destroyed below, so nothing can re-issue the leaf; a
# short lifetime would only buy a scheduled outage in everyone's test stack.
# Public CAs cap server certificates at 398 days — that rule binds publicly
# trusted roots, not a private anchor a debug build opts into.
VALIDITY_DAYS=7300

# Not a secret, and deliberately not treated as one: the file it protects is
# published in the same directory. It exists because PKCS12 requires one.
PASSWORD='basetool-test'

# Every name any service serves under with this keystore, plus the two addresses
# the Android emulator reaches the host by. All of them are loopback, emulator or
# docker-network names — the published key cannot impersonate anything real.
SAN_LIST='dns:localhost,dns:backend,dns:backend-dev,dns:frontend,dns:frontend-dev,dns:ingest,dns:ingest-dev,dns:host.docker.internal,ip:127.0.0.1,ip:10.0.2.2'

CA_DN='CN=Profit Basetool TEST CA - NOT FOR PRODUCTION, OU=basetool test stack, O=DAS KARTELL, C=DE'
LEAF_DN='CN=Profit Basetool test stack - NOT FOR PRODUCTION, OU=basetool test stack, O=DAS KARTELL, C=DE'

CA_STORE='ca-temporary.p12'
KEYSTORE='basetool-test-keystore.p12'
ANCHOR='basetool-test-ca.crt'

# MSYS/Git-Bash rewrites anything that looks like a path, which mangles both the
# DNs and the SAN list. Every keytool call below therefore runs with the
# conversion off.
export MSYS_NO_PATHCONV=1

rm -f "$CA_STORE" "$KEYSTORE" "$ANCHOR" leaf.csr leaf.crt

echo "1/6 CA key pair (temporary — destroyed in step 6)"
keytool -genkeypair -alias ca -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -dname "$CA_DN" -validity "$VALIDITY_DAYS" \
  -ext 'bc:c=ca:true,pathlen:0' -ext 'ku:c=keyCertSign,cRLSign' \
  -keystore "$CA_STORE" -storetype PKCS12 -storepass "$PASSWORD" -keypass "$PASSWORD"

echo "2/6 server key pair, alias 'basetool' (pinned in application.yml)"
keytool -genkeypair -alias basetool -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
  -dname "$LEAF_DN" -validity "$VALIDITY_DAYS" \
  -keystore "$KEYSTORE" -storetype PKCS12 -storepass "$PASSWORD" -keypass "$PASSWORD"

echo "3/6 sign the server certificate with the CA"
keytool -certreq -alias basetool -keystore "$KEYSTORE" -storepass "$PASSWORD" -file leaf.csr
keytool -gencert -alias ca -keystore "$CA_STORE" -storepass "$PASSWORD" \
  -infile leaf.csr -outfile leaf.crt -rfc -validity "$VALIDITY_DAYS" \
  -ext "san=$SAN_LIST" \
  -ext 'ku:c=digitalSignature,keyEncipherment' \
  -ext 'eku=serverAuth'

echo "4/6 export the anchor"
keytool -exportcert -alias ca -keystore "$CA_STORE" -storepass "$PASSWORD" -rfc -file "$ANCHOR"

echo "5/6 assemble the keystore (chain, then the anchor as a trusted entry)"
# The CA has to go in FIRST: importing the signed leaf fails with "Failed to
# establish chain from reply" while its issuer is unknown to this keystore.
keytool -importcert -noprompt -alias ca -file "$ANCHOR" \
  -keystore "$KEYSTORE" -storepass "$PASSWORD"
keytool -importcert -noprompt -alias basetool -file leaf.crt \
  -keystore "$KEYSTORE" -storepass "$PASSWORD"

echo "6/6 destroy the CA private key"
rm -f "$CA_STORE" leaf.csr leaf.crt

echo
echo "Done. Committed artefacts:"
ls -l "$ANCHOR" "$KEYSTORE"
echo
keytool -list -keystore "$KEYSTORE" -storepass "$PASSWORD" | sed -n '1,12p'
