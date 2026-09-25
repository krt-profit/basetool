# shellcheck shell=bash

: "${COMPOSE_DIR:=${IRI_COMPOSE_DIR:-/var/iri/code}}"

: "${TEXTFILE_DIR:=${IRI_MONITORING_TEXTFILE_DIR:-/var/iri/monitoring/textfile}}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

fail() {
  log "FATAL: $*"
  exit 1
}

read_env() {
  local key="$1"
  [[ "${key}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || return 1
  sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "${COMPOSE_DIR}/.env" 2>/dev/null \
    | tail -n 1 | sed -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'\$/\1/"
}

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
