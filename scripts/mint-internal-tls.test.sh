#!/usr/bin/env bash
#
# Self-test for scripts/mint-internal-tls.sh (REQ-SEC-TBD04T, ADR-TBD04T).
#
# Needs keytool and openssl -- both on the ubuntu-latest runner. Mints into a temp directory with a
# throwaway password and checks what the production host relies on:
#   * the files it promises, and nothing else (no CA key left anywhere);
#   * every leaf chains to the CA, and verifies for EACH of its own names and for no other
#     service's name -- the property hostname verification turns into a security boundary;
#   * the truststore holds the CA and no key;
#   * the refusals: no password, no --out, an existing file without --force.
#
set -euo pipefail

cd "$(dirname "$0")/.."
MINT="scripts/mint-internal-tls.sh"

tests_run=0
tests_failed=0
ok() { tests_run=$((tests_run + 1)); echo "  ok   - $1"; }
bad() { tests_run=$((tests_run + 1)); tests_failed=$((tests_failed + 1)); echo "  FAIL - $1"; }
check() { if eval "$1"; then ok "$2"; else bad "$2"; fi; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT
export TLS_STORE_PASSWORD='mint-selftest-password'

echo "Scenario: a full mint"
sh "${MINT}" --out "${TMP}" --days 30 \
  --service 'backend=dns:backend,dns:localhost,ip:127.0.0.1' \
  --service 'ingest=dns:ingest,dns:localhost,ip:127.0.0.1' >"${TMP}.log" 2>&1 \
  && ok "mint exits 0" || { bad "mint exits 0"; cat "${TMP}.log"; }

files="$(cd "${TMP}" && find . -mindepth 1 | sort | tr '\n' ' ')"
check '[ "${files}" = "./backend.p12 ./ca.crt ./ingest.p12 ./truststore.p12 " ]' \
  "exactly ca.crt, truststore.p12 and one .p12 per service (got: ${files})"

list() { keytool -list -keystore "$1" -storepass:env TLS_STORE_PASSWORD 2>/dev/null; }
check '[ "$(list "${TMP}/truststore.p12" | grep -c PrivateKeyEntry)" = 0 ]' "the truststore holds no private key"
check '[ "$(list "${TMP}/truststore.p12" | grep -c trustedCertEntry)" = 1 ]' "...and exactly one trusted entry, the CA"
check '[ "$(list "${TMP}/backend.p12" | grep -c PrivateKeyEntry)" = 1 ]' "a service keystore holds its own key"
check '[ "$(list "${TMP}/backend.p12" | grep -c trustedCertEntry)" = 1 ]' "...and the CA as a trusted entry"

leaf() {
  keytool -exportcert -rfc -alias basetool -keystore "${TMP}/$1.p12" \
    -storepass:env TLS_STORE_PASSWORD >"${TMP}/$1.pem" 2>/dev/null
}
leaf backend
leaf ingest
verify() { openssl verify -CAfile "${TMP}/ca.crt" "$@" >/dev/null 2>&1; }
check 'verify "${TMP}/backend.pem"' "the backend leaf chains to the CA"
check 'verify -verify_hostname backend "${TMP}/backend.pem"' "...and verifies as 'backend'"
check 'verify -verify_hostname localhost "${TMP}/backend.pem"' "...and as 'localhost'"
check 'verify -verify_ip 127.0.0.1 "${TMP}/backend.pem"' "...and as 127.0.0.1"
check '! verify -verify_hostname ingest "${TMP}/backend.pem"' "...but NOT as 'ingest'"
check '! verify -verify_hostname keycloak "${TMP}/backend.pem"' "...nor as 'keycloak'"
check '! verify -verify_hostname backend "${TMP}/ingest.pem"' "the ingest leaf does NOT verify as 'backend'"
check 'openssl x509 -in "${TMP}/ingest.pem" -noout -ext extendedKeyUsage 2>/dev/null | grep -q "TLS Web Server Authentication"' \
  "a leaf carries serverAuth"
check 'openssl x509 -in "${TMP}/ca.crt" -noout -ext basicConstraints 2>/dev/null | grep -q "CA:TRUE, pathlen:0"' \
  "the CA is a CA that cannot sign another CA"

echo "Scenario: refusals"
rc=0; sh "${MINT}" --out "${TMP}" --service 'backend=dns:backend' >/dev/null 2>&1 || rc=$?
check '[ "${rc}" = 1 ]' "an existing file is not overwritten without --force (exit ${rc})"
check '[ -z "$(find "${TMP}" -name ".mint-*")" ]' "...and a refused run leaves no work directory"
rc=0; env -u TLS_STORE_PASSWORD sh "${MINT}" --out "${TMP}" --service 'x=dns:x' >/dev/null 2>&1 || rc=$?
check '[ "${rc}" = 2 ]' "no password in the environment is refused (exit ${rc})"
rc=0; sh "${MINT}" --service 'x=dns:x' >/dev/null 2>&1 || rc=$?
check '[ "${rc}" = 2 ]' "no --out is refused (exit ${rc})"
rc=0; sh "${MINT}" --out "${TMP}" --service 'x' >/dev/null 2>&1 || rc=$?
check '[ "${rc}" = 2 ]' "a service without SANs is refused (exit ${rc})"

echo
if [ "${tests_failed}" -eq 0 ]; then
  echo "All ${tests_run} mint-internal-tls.sh tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} mint-internal-tls.sh test(s) failed."
exit 1
