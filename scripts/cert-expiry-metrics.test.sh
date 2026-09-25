#!/usr/bin/env bash
set -uo pipefail

export MSYS2_ARG_CONV_EXCL='/C=;/CN=;/O=;/OU='

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLLECTOR="${HERE}/cert-expiry-metrics.py"
PY="${PYTHON:-python}"
command -v "$PY" >/dev/null 2>&1 || PY=python3

command -v openssl >/dev/null 2>&1 || {
  echo "openssl is required and is not on PATH" >&2
  exit 1
}

WORK="$(mktemp -d)"
CERTS="${WORK}/certs"
OUT="${WORK}/certificates.prom"

PASSED=0
FAILED=0

cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

say() { printf '%s\n' "$*"; }
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

assert_line() {
  local label=$1 pattern=$2
  if grep -Eq -- "$pattern" "$OUT"; then
    ok "$label"
  else
    bad "$label - no line matching /$pattern/"
  fi
}

assert_absent() {
  local label=$1 pattern=$2
  if grep -Eq -- "$pattern" "$OUT"; then
    bad "$label - unexpectedly found /$pattern/"
  else
    ok "$label"
  fi
}

mkdir -p "$CERTS"
cd "$CERTS" || exit 1

openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out basetool-ca.crt -days 60 \
  -subj "/C=DE/O=DAS KARTELL/OU=basetool/CN=DAS KARTELL basetool CA" >/dev/null 2>&1 \
  || { echo "openssl could not mint the root" >&2; exit 1; }

openssl req -newkey rsa:2048 -nodes -keyout service.key -out service.csr \
  -subj "/CN=service.internal" >/dev/null 2>&1
openssl x509 -req -in service.csr -CA basetool-ca.crt -CAkey ca.key -CAcreateserial \
  -out service.crt -days 10 >/dev/null 2>&1
rm -f service.csr

cat > expired.crt <<'PEM'
-----BEGIN CERTIFICATE-----
MIIDFTCCAf2gAwIBAgIUWTlSw2mTesxGLy9khfZY+RVW22MwDQYJKoZIhvcNAQEL
BQAwGjEYMBYGA1UEAwwPZXhwaXJlZC5pbnZhbGlkMB4XDTIwMDEwMTAwMDAwMFoX
DTIwMDEwMjAwMDAwMFowGjEYMBYGA1UEAwwPZXhwaXJlZC5pbnZhbGlkMIIBIjAN
BgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqyWelBAgeFtxhq6a3PxlgeNtSmpb
fLIhwuX3JZ4DA7amqv3/JxXpy5h6tcwjKoksdrRadVPySyYL6YQkTCM8WWpB4KgR
1gSm/yjX39BKLqd1E9b5cMOxi6BOMThatY9u3OJJSZqCkJLbZLpt3blKDpj+pp4j
GV32ZVFchHTPOipYsuDnBpvr5OnxYVXurbGegvlOhaO8qPZ+PI4lClE9gmA4DHtl
f771B9CukeMm7/7o3HRL+QuWXuw6p2kzo60bYHatLPu0ZPIIQMNWHdQLpeodFRBN
HnC5Rtd6+h0/3Ca0uZgZ/aynxZOkCdUHFkoR0p38TWOj4KrrS2bO+pLa3wIDAQAB
o1MwUTAdBgNVHQ4EFgQUJPOg5a9RxwhcnZ0KyPvVHWX6ioQwHwYDVR0jBBgwFoAU
JPOg5a9RxwhcnZ0KyPvVHWX6ioQwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0B
AQsFAAOCAQEAX0xLxhWNPf5kQ7qjZa46cputG7HIUFCM7MAS/p4LJywJ26q24LUK
RTBzQCVsybggf/QWHGOMSZsDShyTqelT/vECCYaBMSCOc8MsjhpRLurjvEX+bjti
y9bz1Ug58pVfLyq6au0R7wlWVMiEAziOY0WFeL6TXkAkTa0vSdorDMfwdJaxFPPi
P60BQabPH5y0C/xaXvYxNmpKYZhyK47pY9IHPC4+CSe2L8Zu7hcyxrMHFNK3gPnk
qIeDxvfzLrLkvANysLXLa88KJURZ0xvU+/PrLUbY2sWwfySYq1jyxDhRouT1OQBi
llhOp49ykojmDkAtXPoINEPDCyPw/oLw3w==
-----END CERTIFICATE-----
PEM

echo "this is not a certificate" > notacert.pem
cp ca.key looks-important.key

cd "$HERE" || exit 1

say "== it reads what it should and writes exposition format =="
RUN_OUT="$("$PY" "$COLLECTOR" --dir "$CERTS" --output "$OUT" 2>&1)"
RUN_RC=$?

if [ "$RUN_RC" -eq 0 ]; then
  ok "exits 0 with certificates present"
else
  bad "exits 0 with certificates present (rc=${RUN_RC}): ${RUN_OUT}"
fi

if [ -s "$OUT" ]; then
  ok "wrote a non-empty file"
else
  bad "wrote a non-empty file"
fi

assert_line "HELP for the expiry gauge" '^# HELP basetool_certificate_expiry_timestamp_seconds '
assert_line "TYPE is gauge"             '^# TYPE basetool_certificate_expiry_timestamp_seconds gauge$'
assert_line "the root is reported"      '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*basetool-ca\.crt"'
assert_line "the leaf is reported"      '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*service\.crt"'
assert_line "notBefore is reported too" '^basetool_certificate_not_before_timestamp_seconds\{'
assert_line "the file count is present" '^basetool_certificate_files [0-9]+$'
assert_line "the run is stamped"        '^basetool_certificate_metrics_timestamp_seconds [0-9]+$'

say ""
say "== self_signed is what decides between a 14-day and a 90-day alert =="
assert_line "the root is self_signed=true" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*basetool-ca\.crt".*self_signed="true"\}'
assert_line "the CA-issued leaf is self_signed=false" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*service\.crt".*self_signed="false"\}'
assert_line "the leaf names its issuer" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*service\.crt",subject="CN=service\.internal",issuer="CN=DAS KARTELL basetool CA,OU=basetool,O=DAS KARTELL,C=DE"'

say ""
say "== a DN is rendered as RFC 2253, identically on every openssl =="
assert_line "the root's full DN is carried, in RFC 2253 order" \
  'subject="CN=DAS KARTELL basetool CA,OU=basetool,O=DAS KARTELL,C=DE"'
assert_absent "openssl's version-dependent default form is not used" \
  'subject="C ?= ?DE,'

say ""
say "== the arithmetic the alerts do =="
if "$PY" - "$OUT" <<'PY'
import re, sys, time

now = int(time.time())
want = {"basetool-ca.crt": 60, "service.crt": 10}
seen = {}
for line in open(sys.argv[1], encoding="ascii"):
    if not line.startswith("basetool_certificate_expiry_timestamp_seconds{"):
        continue
    path = re.search(r'path="([^"]+)"', line).group(1)
    value = int(line.rsplit(" ", 1)[1])
    seen[path.replace("\\", "/").rsplit("/", 1)[-1]] = (value - now) / 86400.0

failed = 0
for name, days in want.items():
    got = seen.get(name)
    if got is None:
        print("  FAIL  %s is missing from the output" % name)
        failed += 1
    elif not (days - (5.0 / 1440.0) < got <= days):
        print("  FAIL  %s should expire in ~%d days, reports %.3f" % (name, days, got))
        failed += 1
    else:
        print("  ok    %s expires in %.2f days, as minted" % (name, got))
sys.exit(1 if failed else 0)
PY
then PASSED=$((PASSED + 2)); else FAILED=$((FAILED + 1)); fi

if "$PY" - "$OUT" <<'PY'
import re, sys

before, after = {}, {}
for line in open(sys.argv[1], encoding="ascii"):
    for prefix, sink in (("basetool_certificate_not_before_timestamp_seconds{", before),
                         ("basetool_certificate_expiry_timestamp_seconds{", after)):
        if line.startswith(prefix):
            sink[re.search(r'path="([^"]+)"', line).group(1)] = int(line.rsplit(" ", 1)[1])
bad = [p for p in after if p in before and not before[p] < after[p]]
if bad or not after:
    print("  FAIL  notBefore is not before notAfter for: %s" % (bad or "<no certificates>"))
    sys.exit(1)
print("  ok    notBefore precedes notAfter for all %d certificates" % len(after))
PY
then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); fi

say ""
say "== the parse does not depend on the host's timezone =="
TZ_PROBE="$("$PY" -c 'import time; print(time.mktime(time.gmtime(0)))' 2>/dev/null)"
TZ_PROBE_OTHER="$(TZ='Asia/Kolkata' "$PY" -c 'import time; print(time.mktime(time.gmtime(0)))' 2>/dev/null)"
if [ "$TZ_PROBE" = "$TZ_PROBE_OTHER" ]; then
  say "  skip  timezone leg - TZ has no effect on this platform's mktime"
else
  A="$(TZ='UTC'           "$PY" "$COLLECTOR" --dir "$CERTS" --dry-run 2>/dev/null \
        | grep -E '^basetool_certificate_(expiry|not_before)')"
  B="$(TZ='Asia/Kolkata'  "$PY" "$COLLECTOR" --dir "$CERTS" --dry-run 2>/dev/null \
        | grep -E '^basetool_certificate_(expiry|not_before)')"
  if [ -n "$A" ] && [ "$A" = "$B" ]; then
    ok "UTC and UTC+5:30 produce identical timestamps"
  else
    bad "UTC and UTC+5:30 produce identical timestamps"
    diff <(printf '%s\n' "$A") <(printf '%s\n' "$B") | sed 's/^/      /' | head -6
  fi
fi

say ""
say "== what it must never read =="
assert_absent "the private key is not reported" 'looks-important\.key'
assert_absent "the CA's key is not reported"    'path="[^"]*ca\.key"'

case "$RUN_OUT" in
  *notacert.pem*) ok "the unreadable file is named on stderr" ;;
  *)              bad "the unreadable file is named on stderr - got: ${RUN_OUT}" ;;
esac
case "$RUN_OUT" in
  *"1 skipped"*) ok "the run reports how many it skipped" ;;
  *)             bad "the run reports how many it skipped - got: ${RUN_OUT}" ;;
esac
assert_absent "the unreadable file produced no series" 'notacert\.pem'

say ""
say "== an expiry that has already passed =="
assert_line "an expired certificate is still reported" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*expired\.crt"'
assert_line "its notAfter is 2020-01-02T00:00:00Z exactly" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*expired\.crt".*\} 1577923200$'
if "$PY" - "$OUT" <<'PY'
import sys, time
now = int(time.time())
for line in open(sys.argv[1], encoding="ascii"):
    if line.startswith("basetool_certificate_expiry_timestamp_seconds{") and "expired.crt" in line:
        value = int(line.rsplit(" ", 1)[1])
        if value < now:
            print("  ok    the expired certificate reports a past timestamp (%+.1f days)"
                  % ((value - now) / 86400.0))
            sys.exit(0)
        print("  FAIL  the expired certificate reports a FUTURE timestamp")
        sys.exit(1)
print("  FAIL  no series for expired.crt")
sys.exit(1)
PY
then PASSED=$((PASSED + 1)); else FAILED=$((FAILED + 1)); fi

say ""
say "== the file is written the way a scraped file has to be =="
if find "$(dirname "$OUT")" -maxdepth 1 -name '.*.tmp' | grep -q .; then
  bad "no temporary file is left behind"
else
  ok "no temporary file is left behind"
fi

if [ "$(tail -c 1 "$OUT" | od -An -c | tr -d ' ')" = "\\n" ]; then
  ok "ends with a newline"
else
  bad "ends with a newline"
fi

say ""
say "== it refuses to produce a misleading file =="
EMPTY="${WORK}/empty"
mkdir -p "$EMPTY"
if "$PY" "$COLLECTOR" --dir "$EMPTY" --dry-run >/dev/null 2>&1; then
  bad "a directory with no certificates is an error"
else
  ok "a directory with no certificates is an error"
fi
if "$PY" "$COLLECTOR" --dir "${WORK}/does-not-exist" --dry-run >/dev/null 2>&1; then
  bad "a missing directory is an error"
else
  ok "a missing directory is an error"
fi

ONLYBAD="${WORK}/onlybad"
mkdir -p "$ONLYBAD"
echo "not a certificate" > "${ONLYBAD}/x.pem"
if "$PY" "$COLLECTOR" --dir "$ONLYBAD" --output "${WORK}/never.prom" >/dev/null 2>&1; then
  bad "a directory of unreadable files is an error"
else
  ok "a directory of unreadable files is an error"
fi
if [ -e "${WORK}/never.prom" ]; then
  bad "no file is written when nothing parsed"
else
  ok "no file is written when nothing parsed"
fi

if "$PY" "$COLLECTOR" --dir "$CERTS" >/dev/null 2>&1; then
  bad "--output is required without --dry-run"
else
  ok "--output is required without --dry-run"
fi
if "$PY" "$COLLECTOR" --dir "$CERTS" --output /does/not/exist/x.prom >/dev/null 2>&1; then
  bad "an unwritable output is an error"
else
  ok "an unwritable output is an error"
fi

DRY="$("$PY" "$COLLECTOR" --dir "$CERTS" --dry-run 2>/dev/null)"
case "$DRY" in
  *basetool_certificate_expiry_timestamp_seconds*) ok "--dry-run prints the exposition text" ;;
  *)                                               bad "--dry-run prints the exposition text" ;;
esac

say ""
say "== Prometheus itself accepts the output =="
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  if docker run --rm -i --entrypoint promtool prom/prometheus:v3.14.0 check metrics < "$OUT" \
       >/dev/null 2>&1; then
    ok "promtool check metrics accepts the file"
  else
    bad "promtool check metrics accepts the file"
    docker run --rm -i --entrypoint promtool prom/prometheus:v3.14.0 check metrics < "$OUT" 2>&1 \
      | sed 's/^/      /' | head -10
  fi
else
  say "  skip  promtool leg - docker is not available here"
fi

say ""
say "-------------------------------------------------------------"
say "${PASSED} passed, ${FAILED} failed"
[ "$FAILED" -eq 0 ] || exit 1
