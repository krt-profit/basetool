#!/usr/bin/env bash
# =============================================================================================
# Self-test for scripts/cert-expiry-metrics.py
#
# The collector reads certificate FILES and writes them where node_exporter's textfile collector
# will serve them. It exists because a blackbox probe can only see a certificate something is
# SERVING, and the internal CA is served by nothing -- so the certificate whose expiry breaks every
# verified upstream at once, together with the probes that would otherwise have warned about the
# leaves, was the one with no coverage at all (measured on the testing host 2026-09-20).
#
# Which is exactly why it is worth testing hermetically. The values feed two alerts with different
# thresholds, and the one thing that decides between them -- `self_signed`, which is issuer ==
# subject -- is a string comparison over openssl output that nothing else would ever notice getting
# wrong. A CA silently labelled `self_signed="false"` gets 14 days instead of 90 and the rotation
# is started too late to finish; a leaf labelled `"true"` gets 90 and pages three months early
# until it is ignored. Neither failure looks like a failure.
#
# So the test mints throwaway certificates with openssl -- a self-signed root whose DN carries the
# commas and spaces a real one does, and a leaf it signed -- plus one long-expired certificate
# embedded as a literal, and asserts what comes out. It also drops in the two file kinds that MUST
# NOT be read: a private key, and a .pem that is not a certificate at all.
#
# None of the material here is a credential in any sense: it is generated at run time into a temp
# directory, used to check a string comparison, and deleted. It never leaves this script.
#
# If docker is available it also runs `promtool check metrics` over the result, because "I read the
# output and it looked like exposition format" is not the same claim as "Prometheus accepts it" --
# and a DN containing a comma is precisely the case that would prove the difference. Skipped, not
# failed, where docker is absent.
#
# Requires: bash, python3, openssl. Optional: docker (for the promtool leg).
#
#   bash scripts/cert-expiry-metrics.test.sh
# =============================================================================================
set -uo pipefail

# Git-Bash/MSYS rewrites an argument that looks like an absolute path, and an openssl -subj is
# exactly that shape: "/C=DE/O=..." arrives as "C:/Program Files/Git/C=DE/O=..." and openssl
# rejects the name. Ignored on Linux -- but without it this script cannot run on the workstation it
# was written on, and a test that only runs in CI is a test nobody runs before pushing.
#
# The EXCLUSION list and not MSYS_NO_PATHCONV=1: that one switches conversion off wholesale, which
# then breaks the conversion this script NEEDS -- python here is a native Windows binary and cannot
# open the /d/... form of the collector's path. Measured both ways 2026-09-20.
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

# assert_line <label> <extended-regex>
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

# ---------------------------------------------------------------------------------------------
# The material. Everything here is minted now and deleted on exit.
# ---------------------------------------------------------------------------------------------
mkdir -p "$CERTS"
cd "$CERTS" || exit 1

# A self-signed root, 60 days out: outside the 14-day rule, inside the 90-day one. Its DN carries
# commas and spaces, because a real one does and that is what exercises the label quoting.
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out basetool-ca.crt -days 60 \
  -subj "/C=DE/O=DAS KARTELL/OU=basetool/CN=DAS KARTELL basetool CA" >/dev/null 2>&1 \
  || { echo "openssl could not mint the root" >&2; exit 1; }

# A leaf the root signed, 10 days out: inside the 14-day rule, and NOT self-signed.
openssl req -newkey rsa:2048 -nodes -keyout service.key -out service.csr \
  -subj "/CN=service.internal" >/dev/null 2>&1
openssl x509 -req -in service.csr -CA basetool-ca.crt -CAkey ca.key -CAcreateserial \
  -out service.crt -days 10 >/dev/null 2>&1
rm -f service.csr

# One that has already expired -- notAfter 2020-01-02T00:00:00Z, i.e. 1577923200.
#
# EMBEDDED rather than minted, because it cannot be minted portably: `openssl req -not_before/
# -not_after` arrived in OpenSSL 3.5, and the CI runner and the WSL used to cross-check this script
# both ship 3.0.13. An earlier version of this file generated it conditionally and skipped the leg
# otherwise -- which meant the one assertion about an expiry that has ALREADY passed ran on exactly
# one machine in the world, and not on the one gating the merge.
#
# It is a certificate and nothing more: CN=expired.invalid (RFC 2606 reserves `.invalid`, so it can
# never name anything real), the private key was destroyed at generation, and it was already four
# years dead when it was pasted here. Publishing a deliberately worthless artefact and leaking a
# real one are opposite acts -- the same reasoning ADR-0139 sets out for the committed test TLS
# material.
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

# The two things it must NOT read: a .pem that is not a certificate, and a private key. The first
# must be skipped LOUDLY without costing the others their coverage; the second must never be opened
# at all -- this collector has no business touching private material and the alerts need none.
echo "this is not a certificate" > notacert.pem
cp ca.key looks-important.key

cd "$HERE" || exit 1

# =============================================================================================
say "== it reads what it should and writes exposition format =="
# =============================================================================================
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

# =============================================================================================
say ""
say "== self_signed is what decides between a 14-day and a 90-day alert =="
# =============================================================================================
# The whole reason the label exists. Getting it wrong in either direction produces an alert that
# is wrong in a way nobody would look for.
assert_line "the root is self_signed=true" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*basetool-ca\.crt".*self_signed="true"\}'
assert_line "the CA-issued leaf is self_signed=false" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*service\.crt".*self_signed="false"\}'
assert_line "the leaf names its issuer" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*service\.crt",subject="CN=service\.internal",issuer="CN=DAS KARTELL basetool CA,OU=basetool,O=DAS KARTELL,C=DE"'

# =============================================================================================
say ""
say "== a DN is rendered as RFC 2253, identically on every openssl =="
# =============================================================================================
# openssl's DEFAULT DN format is not stable across versions -- measured 2026-09-20 on one file,
# 3.0.13 prints `C = DE, O = ...` and 3.5.7 prints `C=DE, O=...`. Prometheus identifies a series by
# its labels, so the default would retire every certificate series on an openssl upgrade and start
# new ones, the old set going stale exactly like a collector that stopped. RFC 2253 is defined by
# the RFC and not by the tool: most-specific-first, no spaces, its own escaping.
#
# This pair of assertions is what found that, by being run on a second openssl.
assert_line "the root's full DN is carried, in RFC 2253 order" \
  'subject="CN=DAS KARTELL basetool CA,OU=basetool,O=DAS KARTELL,C=DE"'
assert_absent "openssl's version-dependent default form is not used" \
  'subject="C ?= ?DE,'

# =============================================================================================
say ""
say "== the arithmetic the alerts do =="
# =============================================================================================
# Not "a number appeared" but "the number means 60 days and 10 days", because a timezone bug in the
# date parse is worth up to a day and would be invisible in any other assertion. calendar.timegm
# rather than mktime is the fix it guards.
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
    # Both separators. The collector reports the path it was handed, and on the workstation this
    # test is also run by hand on, that is a backslash path -- a rsplit("/") there silently keeps
    # the whole path as the key and every lookup below misses, which reads as "the collector did
    # not report it" rather than as a bug in the test.
    seen[path.replace("\\", "/").rsplit("/", 1)[-1]] = (value - now) / 86400.0

failed = 0
for name, days in want.items():
    got = seen.get(name)
    if got is None:
        print("  FAIL  %s is missing from the output" % name)
        failed += 1
    # Five minutes of slack and no more. The certificate was minted seconds ago, so the remainder
    # is a hair under the requested whole number and a strict equality would fail for the wrong
    # reason -- but a day of slack would swallow exactly the error worth catching here, a date
    # parsed in local time instead of GMT.
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

# =============================================================================================
say ""
say "== the parse does not depend on the host's timezone =="
# =============================================================================================
# openssl prints notAfter in GMT and nothing else, so the parse must be GMT too. The trap is
# time.mktime, which interprets the struct in the HOST's zone -- a silent offset of up to a day in
# the half-hour zones, applied to the one number two alerts subtract time() from. calendar.timegm
# is the fix; this is what holds it.
#
# Asserted by running the collector under two zones and demanding byte-identical output, which is
# true regardless of which zone the machine running the test happens to be in.
TZ_PROBE="$("$PY" -c 'import time; print(time.mktime(time.gmtime(0)))' 2>/dev/null)"
TZ_PROBE_OTHER="$(TZ='Asia/Kolkata' "$PY" -c 'import time; print(time.mktime(time.gmtime(0)))' 2>/dev/null)"
if [ "$TZ_PROBE" = "$TZ_PROBE_OTHER" ]; then
  # Without this guard the comparison below would pass for the wrong reason on a platform that
  # ignores TZ -- a green check that checked nothing.
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

# =============================================================================================
say ""
say "== what it must never read =="
# =============================================================================================
# A private key carries no expiry and this collector must not hold private material. The rule is
# by construction (the suffix list), and it is asserted because a later "just add .key so the pair
# is complete" is an easy and terrible change.
assert_absent "the private key is not reported" 'looks-important\.key'
assert_absent "the CA's key is not reported"    'path="[^"]*ca\.key"'

# A .pem that is not a certificate is skipped LOUDLY -- and must not cost the others. A directory
# with a stray text file in it is a configuration mistake; a CA that goes unwatched because of one
# is the expensive kind.
case "$RUN_OUT" in
  *notacert.pem*) ok "the unreadable file is named on stderr" ;;
  *)              bad "the unreadable file is named on stderr - got: ${RUN_OUT}" ;;
esac
case "$RUN_OUT" in
  *"1 skipped"*) ok "the run reports how many it skipped" ;;
  *)             bad "the run reports how many it skipped - got: ${RUN_OUT}" ;;
esac
assert_absent "the unreadable file produced no series" 'notacert\.pem'

# =============================================================================================
say ""
say "== an expiry that has already passed =="
# =============================================================================================
assert_line "an expired certificate is still reported" \
  '^basetool_certificate_expiry_timestamp_seconds\{path="[^"]*expired\.crt"'
# The exact instant, not just "in the past" -- this is the one certificate in the set whose notAfter
# is a fixed, known number, so it pins the date parse to the second on every machine that runs it.
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

# =============================================================================================
say ""
say "== the file is written the way a scraped file has to be =="
# =============================================================================================
# node_exporter reads the whole textfile directory on every scrape, so a half-written file is a
# parse error served to Prometheus. The write is a sibling plus a rename; nothing may be left over.
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

# =============================================================================================
say ""
say "== it refuses to produce a misleading file =="
# =============================================================================================
# An empty metrics file and a mistyped directory look identical to Prometheus, and only one of them
# is benign -- so finding nothing is an error rather than a file with nothing in it.
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

# A directory holding ONLY unreadable files is the same claim as an empty one: no coverage. It must
# not write a file that says "0 certificates, all is well".
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

# --dry-run prints and writes nothing.
DRY="$("$PY" "$COLLECTOR" --dir "$CERTS" --dry-run 2>/dev/null)"
case "$DRY" in
  *basetool_certificate_expiry_timestamp_seconds*) ok "--dry-run prints the exposition text" ;;
  *)                                               bad "--dry-run prints the exposition text" ;;
esac

# =============================================================================================
say ""
say "== Prometheus itself accepts the output =="
# =============================================================================================
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

# =============================================================================================
say ""
say "-------------------------------------------------------------"
say "${PASSED} passed, ${FAILED} failed"
[ "$FAILED" -eq 0 ] || exit 1
