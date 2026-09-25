#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
MINT="scripts/mint-internal-tls.sh"

tests_run=0
tests_failed=0
check() {
  local desc="$1"
  shift
  tests_run=$((tests_run + 1))
  if "$@"; then
    echo "  ok   - ${desc}"
  else
    tests_failed=$((tests_failed + 1))
    echo "  FAIL - ${desc}"
  fi
}
refute() {
  local desc="$1"
  shift
  tests_run=$((tests_run + 1))
  if "$@"; then
    tests_failed=$((tests_failed + 1))
    echo "  FAIL - ${desc}"
  else
    echo "  ok   - ${desc}"
  fi
}

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}" "${TMP}.log"' EXIT
export TLS_STORE_PASSWORD='mint-selftest-password'

entries() { keytool -list -keystore "$1" -storepass:env TLS_STORE_PASSWORD 2>/dev/null | grep -c "$2" || true; }
leaf() {
  keytool -exportcert -rfc -alias basetool -keystore "${TMP}/$1.p12" \
    -storepass:env TLS_STORE_PASSWORD >"${TMP}/$1.pem" 2>/dev/null
}
# shellcheck disable=SC2317,SC2329
verifies() { openssl verify -CAfile "${TMP}/ca.crt" "$@" >/dev/null 2>&1; }
# shellcheck disable=SC2317,SC2329
has_ext() { openssl x509 -in "$1" -noout -ext "$2" 2>/dev/null | grep -q "$3"; }
mint_rc() {
  local rc=0
  "$@" >/dev/null 2>&1 || rc=$?
  echo "${rc}"
}

echo "Scenario: a full mint"
if sh "${MINT}" --out "${TMP}" --days 30 \
     --service 'backend=dns:backend,dns:localhost,ip:127.0.0.1' \
     --service 'ingest=dns:ingest,dns:localhost,ip:127.0.0.1' >"${TMP}.log" 2>&1; then
  check "mint exits 0" true
else
  check "mint exits 0" false
  cat "${TMP}.log"
fi

files="$(cd "${TMP}" && find . -mindepth 1 | sort | tr '\n' ' ')"
check "exactly ca.crt, truststore.p12 and one .p12 per service (got: ${files})" \
  [ "${files}" = "./backend.p12 ./ca.crt ./ingest.p12 ./truststore.p12 " ]
check "a keystore is written 0600, whatever the caller's umask" \
  [ "$(stat -c %a "${TMP}/backend.p12")" = 600 ]
check "the truststore holds no private key" [ "$(entries "${TMP}/truststore.p12" PrivateKeyEntry)" = 0 ]
check "...and exactly one trusted entry, the CA" [ "$(entries "${TMP}/truststore.p12" trustedCertEntry)" = 1 ]
check "a service keystore holds its own key" [ "$(entries "${TMP}/backend.p12" PrivateKeyEntry)" = 1 ]
check "...and the CA as a trusted entry" [ "$(entries "${TMP}/backend.p12" trustedCertEntry)" = 1 ]

leaf backend
leaf ingest
check "the backend leaf chains to the CA" verifies "${TMP}/backend.pem"
check "...and verifies as 'backend'" verifies -verify_hostname backend "${TMP}/backend.pem"
check "...and as 'localhost'" verifies -verify_hostname localhost "${TMP}/backend.pem"
check "...and as 127.0.0.1" verifies -verify_ip 127.0.0.1 "${TMP}/backend.pem"
refute "...but NOT as 'ingest'" verifies -verify_hostname ingest "${TMP}/backend.pem"
refute "...nor as 'keycloak'" verifies -verify_hostname keycloak "${TMP}/backend.pem"
refute "the ingest leaf does NOT verify as 'backend'" verifies -verify_hostname backend "${TMP}/ingest.pem"
check "a leaf carries serverAuth" has_ext "${TMP}/ingest.pem" extendedKeyUsage "TLS Web Server Authentication"
check "the CA is a CA that cannot sign another CA" has_ext "${TMP}/ca.crt" basicConstraints "CA:TRUE, pathlen:0"

echo "Scenario: refusals"
rc="$(mint_rc sh "${MINT}" --out "${TMP}" --service 'backend=dns:backend')"
check "an existing file is not overwritten without --force (exit ${rc})" [ "${rc}" = 1 ]
check "...and a refused run leaves no work directory" [ -z "$(find "${TMP}" -name '.mint-*')" ]
rc="$(mint_rc env -u TLS_STORE_PASSWORD sh "${MINT}" --out "${TMP}" --service 'x=dns:x')"
check "no password in the environment is refused (exit ${rc})" [ "${rc}" = 2 ]
rc="$(mint_rc sh "${MINT}" --service 'x=dns:x')"
check "no --out is refused (exit ${rc})" [ "${rc}" = 2 ]
rc="$(mint_rc sh "${MINT}" --out "${TMP}" --service 'x')"
check "a service without SANs is refused (exit ${rc})" [ "${rc}" = 2 ]

echo
if [ "${tests_failed}" -eq 0 ]; then
  echo "All ${tests_run} mint-internal-tls.sh tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} mint-internal-tls.sh test(s) failed."
exit 1
