#!/usr/bin/env bash
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="${HERE}/host-updates-metrics.sh"
[[ -f "${SUT}" ]] || { echo "FATAL: ${SUT} not found" >&2; exit 1; }

PASSED=0
FAILED=0
ok()  { PASSED=$((PASSED + 1)); printf '  ok    %s\n' "$*"; }
bad() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/host-updates-test.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
OUT="${WORK}/textfile/host-updates.prom"
mkdir -p "${WORK}/textfile" "${WORK}/bin"

cat > "${WORK}/bin/needs-restarting" <<'STUB'
#!/usr/bin/env bash
exit "${NR_RC:-0}"
STUB
chmod +x "${WORK}/bin/needs-restarting"

run() {
  env IRI_HOST_UPDATES_METRICS_FILE="${OUT}" IRI_NEEDS_RESTARTING="${WORK}/bin/needs-restarting" \
    "$@" bash "${SUT}"
}

value() { awk -v n="$1" '$1 == n { print $2 }' "${OUT}" 2>/dev/null; }

expect_value() {
  local got; got="$(value "$2")"
  if [[ "${got}" == "$3" ]]; then ok "$1"; else bad "$1 -- wanted '$3', got '${got}'"; fi
}

echo "== a successful dnf-automatic run is recorded by the run itself =="
before="$(date +%s)"
run SERVICE_RESULT=success NR_RC=0
expect_value "the run counts as a success" basetool_host_updates_last_run_success 1
ts="$(value basetool_host_updates_last_run_timestamp_seconds)"
if [[ "${ts}" =~ ^[0-9]+$ ]] && (( ts >= before )); then ok "the run is timestamped now"; else bad "timestamp '${ts}' is not this run"; fi
expect_value "no reboot is due" basetool_host_reboot_required 0

echo "== a failed run reads as a failure, not as silence =="
run SERVICE_RESULT=exit-code NR_RC=0
expect_value "systemd's exit-code result is a 0" basetool_host_updates_last_run_success 0

echo "== a kernel update that is installed and not active asks for a reboot =="
run SERVICE_RESULT=success NR_RC=1
expect_value "needs-restarting's exit 1 is a reboot required" basetool_host_reboot_required 1

echo "== the boot refresh keeps the last run and re-reads only the reboot flag =="
printf 'basetool_host_updates_last_run_timestamp_seconds 1700000000\nbasetool_host_updates_last_run_success 1\nbasetool_host_reboot_required 1\n' > "${OUT}"
run NR_RC=0
expect_value "the last run's timestamp survives a boot" basetool_host_updates_last_run_timestamp_seconds 1700000000
expect_value "...and so does its outcome" basetool_host_updates_last_run_success 1
expect_value "after the reboot, none is due any more" basetool_host_reboot_required 0

echo "== what cannot be read is left out, never guessed =="
rm -f "${OUT}"
run NR_RC=0
if [[ -z "$(value basetool_host_updates_last_run_timestamp_seconds)" ]]; then
  ok "a host that never ran dnf-automatic has no run timestamp -- absent() is what alerts on it"
else
  bad "a run timestamp was invented for a host that never ran"
fi
run SERVICE_RESULT=success NR_RC=2
if ! grep -q '^basetool_host_reboot_required ' "${OUT}"; then
  ok "an unreadable reboot state is omitted rather than written as 0"
else
  bad "an unreadable reboot state was written as $(value basetool_host_reboot_required)"
fi
printf 'basetool_host_updates_last_run_timestamp_seconds garbage\n' > "${OUT}"
run NR_RC=0
if [[ -z "$(value basetool_host_updates_last_run_timestamp_seconds)" ]]; then
  ok "a corrupted previous value is not carried forward"
else
  bad "a corrupted value was carried forward"
fi

echo "== the file is replaced, never written in place =="
run SERVICE_RESULT=success NR_RC=0
leftovers="$(find "${WORK}/textfile" -name '.host-updates.prom.*' | wc -l | tr -d ' ')"
if [[ "${leftovers}" == 0 ]]; then ok "no temporary file is left behind"; else bad "${leftovers} temporary file(s) left behind"; fi
if grep -q '^# TYPE basetool_host_reboot_required gauge$' "${OUT}"; then ok "each series is typed"; else bad "missing TYPE line"; fi
if run SERVICE_RESULT=success IRI_HOST_UPDATES_METRICS_FILE="${WORK}/nowhere/x.prom" 2>/dev/null; then
  bad "an unwritable output directory reported success"
else
  ok "an unwritable output directory is a failure, not a quiet no-op"
fi

printf '%d passed, %d failed\n' "${PASSED}" "${FAILED}"
[[ ${FAILED} -eq 0 ]]
