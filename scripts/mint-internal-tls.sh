#!/bin/sh

set -eu
umask 077

usage() {
  cat >&2 <<'EOT'
USAGE
  TLS_STORE_PASSWORD=... mint-internal-tls.sh --out DIR \
      --service backend=dns:backend,dns:localhost,ip:127.0.0.1 \
      --service frontend=dns:frontend,dns:localhost,ip:127.0.0.1 [...] \
      [--days 3650] [--prefix NAME-] [--ca-cn TEXT] [--leaf-cn-suffix TEXT] [--force]

  The password comes from the environment only, never an argument.

Exit codes: 0 done, 1 refused or failed (nothing half-written is left behind), 2 bad invocation.
EOT
  exit 2
}

OUT=""
DAYS=3650
PREFIX=""
CA_CN="Profit Basetool internal CA"
LEAF_SUFFIX="Profit Basetool internal"
FORCE=0
SERVICES=""

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --days) DAYS="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --ca-cn) CA_CN="$2"; shift 2 ;;
    --leaf-cn-suffix) LEAF_SUFFIX="$2"; shift 2 ;;
    --service) SERVICES="${SERVICES}${SERVICES:+
}$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) usage ;;
    *) echo "mint-internal-tls: unknown argument: $1" >&2; usage ;;
  esac
done

[ -n "$OUT" ] || { echo "mint-internal-tls: --out is required" >&2; exit 2; }
[ -n "$SERVICES" ] || { echo "mint-internal-tls: at least one --service NAME=SANS is required" >&2; exit 2; }
[ -n "${TLS_STORE_PASSWORD:-}" ] || { echo "mint-internal-tls: TLS_STORE_PASSWORD must be set in the environment" >&2; exit 2; }
case "$DAYS" in ''|*[!0-9]*) echo "mint-internal-tls: --days must be a number" >&2; exit 2 ;; esac
command -v keytool >/dev/null 2>&1 || { echo "mint-internal-tls: keytool not found (run it in the backend image)" >&2; exit 1; }
[ -d "$OUT" ] || { echo "mint-internal-tls: --out $OUT is not a directory" >&2; exit 1; }

targets="${PREFIX}ca.crt ${PREFIX}truststore.p12"
for spec in $SERVICES; do
  name="${spec%%=*}"
  sans="${spec#*=}"
  case "$name" in ''|*[!a-z0-9-]*) echo "mint-internal-tls: bad service name '$name'" >&2; exit 2 ;; esac
  if [ -z "$sans" ] || [ "$sans" = "$spec" ]; then
    echo "mint-internal-tls: --service $spec needs =SANS" >&2
    exit 2
  fi
  targets="$targets ${PREFIX}${name}.p12"
done
if [ "$FORCE" -ne 1 ]; then
  for t in $targets; do
    if [ -e "$OUT/$t" ]; then
      echo "mint-internal-tls: $OUT/$t exists; refusing to overwrite it (move it aside, or --force)" >&2
      exit 1
    fi
  done
fi

export MSYS_NO_PATHCONV=1

WORK="$OUT/.mint-$$"
mkdir "$WORK"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

KS() { keytool "$@" -storepass:env TLS_STORE_PASSWORD; }

echo "1. CA key pair (temporary; destroyed at the end)"
keytool -genkeypair -alias ca -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -dname "CN=${CA_CN}, O=DAS KARTELL, C=DE" -validity "$DAYS" \
  -ext 'bc:c=ca:true,pathlen:0' -ext 'ku:c=keyCertSign,cRLSign' \
  -keystore "$WORK/ca.p12" -storetype PKCS12 \
  -storepass:env TLS_STORE_PASSWORD -keypass:env TLS_STORE_PASSWORD >/dev/null
KS -exportcert -alias ca -keystore "$WORK/ca.p12" -rfc -file "$WORK/ca.crt" >/dev/null

echo "2. CA-only truststore"
KS -importcert -noprompt -alias ca -file "$WORK/ca.crt" \
  -keystore "$WORK/truststore.p12" -storetype PKCS12 >/dev/null

for spec in $SERVICES; do
  name="${spec%%=*}"
  sans="${spec#*=}"
  echo "3. ${name}: key pair, signed leaf [${sans}]"
  keytool -genkeypair -alias basetool -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
    -dname "CN=${name}, OU=${LEAF_SUFFIX}, O=DAS KARTELL, C=DE" -validity "$DAYS" \
    -keystore "$WORK/${name}.p12" -storetype PKCS12 \
    -storepass:env TLS_STORE_PASSWORD -keypass:env TLS_STORE_PASSWORD >/dev/null
  KS -certreq -alias basetool -keystore "$WORK/${name}.p12" -file "$WORK/${name}.csr" >/dev/null
  KS -gencert -alias ca -keystore "$WORK/ca.p12" \
    -infile "$WORK/${name}.csr" -outfile "$WORK/${name}.crt" -rfc -validity "$DAYS" \
    -ext "san=${sans}" -ext 'ku:c=digitalSignature,keyEncipherment' -ext 'eku=serverAuth' >/dev/null
  KS -importcert -noprompt -alias ca -file "$WORK/ca.crt" -keystore "$WORK/${name}.p12" >/dev/null
  KS -importcert -noprompt -alias basetool -file "$WORK/${name}.crt" -keystore "$WORK/${name}.p12" >/dev/null
done

echo "4. move the results into place, destroy the CA key"
mv -f "$WORK/ca.crt" "$OUT/${PREFIX}ca.crt"
mv -f "$WORK/truststore.p12" "$OUT/${PREFIX}truststore.p12"
for spec in $SERVICES; do
  name="${spec%%=*}"
  mv -f "$WORK/${name}.p12" "$OUT/${PREFIX}${name}.p12"
done

echo
echo "Done: $targets in $OUT. The CA key no longer exists."
