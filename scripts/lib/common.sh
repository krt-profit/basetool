# shellcheck shell=bash

: "${COMPOSE_DIR:=${BASETOOL_COMPOSE_DIR:-/opt/basetool}}"
: "${CONFIG_DIR:=${BASETOOL_CONFIG_DIR:-/etc/basetool}}"
: "${ENV_FILE:=${BASETOOL_ENV_FILE:-${CONFIG_DIR}/basetool.env}}"
: "${ENV_D_DIR:=${BASETOOL_ENV_D_DIR:-${CONFIG_DIR}/env.d}}"
: "${CACHE_DIR:=${BASETOOL_CACHE_DIR:-/var/cache/basetool}}"

: "${TEXTFILE_DIR:=${BASETOOL_MONITORING_TEXTFILE_DIR:-/var/lib/basetool/textfile}}"

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
  sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "${ENV_FILE}" 2>/dev/null \
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
