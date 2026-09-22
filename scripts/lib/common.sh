# shellcheck shell=bash
# =============================================================================
# The helpers every operational script repeated (OPS-SIMP-04, 2026-09-22).
#
# deploy.sh, backup.sh, restore-drill.sh and container-cleanup.sh each carried
# their own `log`, `fail` and `.env` reader, and five hand-written copies of the
# same atomic textfile write -- temp file, `mv`, a WARN on failure -- one of which
# (deploy.sh's) had grown quote-stripping the others lacked. A fix to one copy
# reached the others only by someone remembering to repeat it.
#
# Usage, after IRI_SCRIPT_DIR is set:
#
#     . "${IRI_SCRIPT_DIR}/lib/common.sh"
#     log "message"                          # [2026-09-22 04:15:00] message
#     fail "why"                             # logs FATAL: why, exits 1
#     read_env KEY                           # one value from ${COMPOSE_DIR}/.env
#     printf 'metric 1\n' | write_textfile x.prom
#
# Nothing here reads a secret into the environment: read_env returns one value to
# its caller and never echoes it.
# =============================================================================

#: Where the host configuration lives. The scripts set their own COMPOSE_DIR first; this is only
#: the fallback read_env uses when a caller has not.
: "${COMPOSE_DIR:=${IRI_COMPOSE_DIR:-/var/iri/code}}"

#: node_exporter's textfile directory, which every operational metric is written into.
: "${TEXTFILE_DIR:=${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}}"

# log <message>... -- one timestamped line on stdout, which the units append to /var/log/iri-*.log.
log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

# fail <message>... -- log it as FATAL and exit 1. container-runtime.sh's rt_die defers to this.
fail() {
  log "FATAL: $*"
  exit 1
}

# read_env <KEY> -- print one value from ${COMPOSE_DIR}/.env, or nothing.
#
# The LAST assignment wins, as it does for compose; whitespace around `=` and one layer of single or
# double quotes are removed, so `KEY = "value"` and `KEY=value` read the same. Only the one key is
# read: sourcing .env would pull every production secret into the calling process for no reason.
read_env() {
  local key="$1"
  [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || return 1
  sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "${COMPOSE_DIR}/.env" 2>/dev/null \
    | tail -n 1 | sed -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

# write_textfile <file-name> -- write stdin atomically to ${TEXTFILE_DIR}/<file-name>.
#
# A temporary file beside the target, then `mv`, so node_exporter never serves half a file. Returns
# non-zero and leaves nothing behind when the directory is not writable; every caller treats that as
# a WARN, because a metric must never fail the job it reports on.
write_textfile() {
  local name="$1" tmp
  [[ "${name}" =~ ^[A-Za-z0-9_.-]+\.prom$ ]] || { log "WARN: refusing textfile name '${name}'"; return 1; }
  install -d -m 0755 "${TEXTFILE_DIR}" 2>/dev/null || true
  tmp="${TEXTFILE_DIR}/.${name}.$$"
  if cat > "${tmp}" 2>/dev/null && mv -f "${tmp}" "${TEXTFILE_DIR}/${name}" 2>/dev/null; then
    return 0
  fi
  rm -f "${tmp}" 2>/dev/null || true
  log "WARN: could not write ${name} into ${TEXTFILE_DIR}"
  return 1
}
