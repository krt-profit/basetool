# shellcheck shell=bash

mirror_dir() {
  local src="$1" dst="$2" rc=0
  DEPLOY_STEP="mirror ${dst}"
  if command -v rsync >/dev/null 2>&1; then
    rsync -rlpt --delete "${src}/" "${dst}/" || rc=$?
  else
    { rm -rf "${dst}" && install -d "${dst%/*}" && cp -R "${src}" "${dst}"; } || rc=$?
  fi
  if (( rc != 0 )); then
    log "mirror of ${src} onto ${dst} failed (exit ${rc}) — the tool's own error is above"
    return "${rc}"
  fi
  return 0
}

extract_config_bundle() {
  local ref="$1" dest="$2"
  rm -rf "${dest}"
  install -d -m 0755 "${dest}"
  rt_extract_from_image "${ref}" "/config/." "${dest}/" /bundle \
    || fail "cannot extract /config from config image ${ref}"
}

assert_no_secrets() {
  local dir="$1"
  if find "${dir}" \( \
        -iname '.env' -o -iname '*.p12' -o -iname '*.jks' \
        -o -iname '*.pem' -o -iname '*.key' -o -iname 'realm-export.json' \
      \) -print -quit 2>/dev/null | grep -q .; then
    local hit
    hit="$(find "${dir}" \( \
        -iname '.env' -o -iname '*.p12' -o -iname '*.jks' \
        -o -iname '*.pem' -o -iname '*.key' -o -iname 'realm-export.json' \
      \) -print -quit 2>/dev/null)"
    fail "SECURITY: promoted config bundle contains a forbidden secret-shaped file '${hit}' — aborting before apply"
  fi
  if [[ -d "${dir}/keycloak/providers" ]]; then
    fail "SECURITY: promoted config bundle contains keycloak/providers — aborting before apply"
  fi
}

infra_image_pins() {
  local tree="$1"
  {
    [[ -d "${tree}/quadlet/systemd" ]] \
      && grep -Eho '^Image=((docker\.io/)?(library/)?postgres:[^[:space:]]+|quay\.io/keycloak/keycloak:[^[:space:]]+)' \
           "${tree}"/quadlet/systemd/*.container 2>/dev/null
  } | sed -E 's/^Image=//; s#^docker\.io/(library/)?##; s/@sha256:[0-9a-f]+$//' | sort -u || true
}

snapshot_config_tree() {
  local dst="$1"
  rm -rf "${dst}"
  install -d -m 0755 "${dst}"
  [[ -f "${COMPOSE_DIR}/docker-compose.yml" ]] \
    && cp -a "${COMPOSE_DIR}/docker-compose.yml" "${dst}/docker-compose.yml"
  local sub
  for sub in maintenance edge acme; do
    if [[ -d "${COMPOSE_DIR}/docker/${sub}" ]]; then
      install -d "${dst}/docker"
      cp -a "${COMPOSE_DIR}/docker/${sub}" "${dst}/docker/${sub}"
    fi
  done
  [[ -d "${COMPOSE_DIR}/keycloak-theme" ]] \
    && cp -a "${COMPOSE_DIR}/keycloak-theme" "${dst}/keycloak-theme"
  [[ -f "${COMPOSE_DIR}/docker-compose.monitoring.yml" ]] \
    && cp -a "${COMPOSE_DIR}/docker-compose.monitoring.yml" "${dst}/docker-compose.monitoring.yml"
  [[ -d "${COMPOSE_DIR}/monitoring" ]] \
    && cp -a "${COMPOSE_DIR}/monitoring" "${dst}/monitoring"
  [[ -d "${COMPOSE_DIR}/quadlet" ]] \
    && cp -a "${COMPOSE_DIR}/quadlet" "${dst}/quadlet"
  [[ -f "${COMPOSE_DIR}/${HOST_SCRIPTS_MANIFEST_NAME}" ]] \
    && cp -a "${COMPOSE_DIR}/${HOST_SCRIPTS_MANIFEST_NAME}" "${dst}/${HOST_SCRIPTS_MANIFEST_NAME}"
  return 0
}

apply_config_tree() {
  local src="$1" dst="$2"
  if [[ -f "${src}/docker-compose.yml" ]]; then
    install -m 0644 "${src}/docker-compose.yml" "${dst}/.docker-compose.yml.tmp"
    mv -f "${dst}/.docker-compose.yml.tmp" "${dst}/docker-compose.yml"
  fi
  if [[ -f "${src}/docker-compose.monitoring.yml" ]]; then
    install -m 0644 "${src}/docker-compose.monitoring.yml" "${dst}/.docker-compose.monitoring.yml.tmp"
    mv -f "${dst}/.docker-compose.monitoring.yml.tmp" "${dst}/docker-compose.monitoring.yml"
  fi
  if [[ -d "${src}/monitoring" ]]; then
    install -d "${dst}/monitoring"
    mirror_dir "${src}/monitoring" "${dst}/monitoring"
  fi
  if [[ -d "${src}/docker/maintenance" ]]; then
    install -d "${dst}/docker"
    mirror_dir "${src}/docker/maintenance" "${dst}/docker/maintenance"
  fi
  if [[ -d "${src}/docker/edge" ]]; then
    install -d "${dst}/docker"
    mirror_dir "${src}/docker/edge" "${dst}/docker/edge"
  fi
  if [[ -d "${src}/docker/acme" ]]; then
    install -d "${dst}/docker"
    mirror_dir "${src}/docker/acme" "${dst}/docker/acme"
  fi
  if [[ -d "${src}/keycloak-theme" ]]; then
    mirror_dir "${src}/keycloak-theme" "${dst}/keycloak-theme"
  fi
  if [[ -d "${src}/quadlet" ]]; then
    mirror_dir "${src}/quadlet" "${dst}/quadlet"
  fi
  if [[ -f "${src}/${HOST_SCRIPTS_MANIFEST_NAME}" ]]; then
    install -m 0644 "${src}/${HOST_SCRIPTS_MANIFEST_NAME}" "${dst}/.${HOST_SCRIPTS_MANIFEST_NAME}.tmp"
    mv -f "${dst}/.${HOST_SCRIPTS_MANIFEST_NAME}.tmp" "${dst}/${HOST_SCRIPTS_MANIFEST_NAME}"
  else
    rm -f "${dst}/${HOST_SCRIPTS_MANIFEST_NAME}"
  fi
}

env_d_digests() {
  local f sum
  for f in "${ENV_D_DIR}"/*.env; do
    [[ -f "${f}" ]] || continue
    sum="$(sha256sum < "${f}")"
    printf '%s %s\n' "$(basename "${f}" .env)" "${sum%% *}"
  done
}

# A unit reads its EnvironmentFile only when its container is created, so a changed
# <service>.env re-defines that unit exactly like a changed .container file does.
note_env_d_changes() {
  local before="$1" units="$2" line svc
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    grep -qxF -- "${line}" <<< "${before}" && continue
    svc="${line%% *}"
    [[ -f "${units}/${svc}.container" ]] || continue
    log "env.d: ${svc}.env differs from the one it replaced → ${svc} is re-defined and restarts with this apply"
    rt_note_changed "${svc}"
  done <<< "$(env_d_digests)"
}

install_quadlet_units() {
  local src="${1}/quadlet" installed=0 removed=0

  if [[ ! -d "${src}/systemd" ]]; then
    log "config bundle carries no quadlet/systemd — units left as they are (pre-2026-09-18 bundle)"
    return 0
  fi

  local unit_dir="${RT_UNIT_DIR:?RT_UNIT_DIR is unset}"
  install -d "${unit_dir}"

  DEPLOY_STEP="render ${ENV_D_DIR}"
  if [[ -d "${src}/env.d" ]]; then
    if [[ -x "${ENV_RENDERER}" ]]; then
      local env_before
      env_before="$(env_d_digests)"
      if ! "${ENV_RENDERER}" --env "${ENV_FILE}"              --templates "${src}/env.d" --out "${ENV_D_DIR}" >/dev/null; then
        fail "rendering ${ENV_D_DIR} from the bundle's templates failed — a unit whose EnvironmentFile is missing does not start"
      fi
      log "rendered env.d from the bundle's templates"
      note_env_d_changes "${env_before}" "${src}/systemd"
    else
      fail "the bundle carries env.d templates but ${ENV_RENDERER} is not on this host (ansible role: 25-scripts.yml)"
    fi
  fi

  DEPLOY_STEP="install quadlet units into ${unit_dir}"
  local f base
  for f in "${src}"/systemd/*; do
    [[ -f "${f}" ]] || continue
    base="$(basename "${f}")"
    if [[ ! -f "${unit_dir}/${base}" ]] || ! cmp -s "${f}" "${unit_dir}/${base}"; then
      install -m 0644 "${f}" "${unit_dir}/${base}"
      installed=$(( installed + 1 ))
      [[ "${base}" == *.container ]] && rt_note_changed "${base%.container}"
    fi
  done

  local existing name
  for existing in "${unit_dir}"/*.container "${unit_dir}"/*.network "${unit_dir}"/*.volume; do
    [[ -f "${existing}" ]] || continue
    name="$(basename "${existing}")"
    [[ -f "${src}/systemd/${name}" ]] && continue
    if [[ "${name}" == *.container ]]; then
      rt_service_stop "${name%.container}" >/dev/null 2>&1         || log "WARN: could not stop retired unit ${name%.container} before removing it"
    fi
    rm -f "${existing}"
    removed=$(( removed + 1 ))
  done

  log "quadlet units: ${installed} installed/updated, ${removed} retired (${unit_dir})"
  return 0
}

assert_config_tree_writable() {
  local src="$1" me sub d parent bad count
  me="$(id -u)"
  DEPLOY_STEP="pre-flight: the config tree is writable"
  for d in "${COMPOSE_DIR}" "${RT_UNIT_DIR}"; do
    [[ -w "${d}" ]] \
      || fail "PRE-FLIGHT: ${d} is not writable by $(id -un) — nothing was changed; fix the owner (docs/deployment.md → Troubleshooting, 'deploy stuck on a config apply')"
  done
  if [[ -e "${ENV_D_DIR}" ]]; then
    [[ -w "${ENV_D_DIR}" ]] \
      || fail "PRE-FLIGHT: ${ENV_D_DIR} is not writable by $(id -un) — nothing was changed; the role's --tags directories restores deploy:basetool 2750"
  elif [[ -d "${src}/env.d" && ! -w "${ENV_D_DIR%/*}" ]]; then
    fail "PRE-FLIGHT: ${ENV_D_DIR} does not exist and ${ENV_D_DIR%/*} is not writable by $(id -un), so it cannot be created — nothing was changed; the role's --tags directories creates it"
  fi
  for sub in monitoring docker/maintenance docker/edge docker/acme keycloak-theme quadlet; do
    [[ -d "${src}/${sub}" ]] || continue
    d="${COMPOSE_DIR}/${sub}"
    if [[ -e "${d}" ]]; then
      bad="$(find "${d}" -type d \( ! -user "${me}" -o ! -writable \) -print 2>/dev/null || true)"
      if [[ -n "${bad}" ]]; then
        count="$(printf '%s\n' "${bad}" | wc -l | tr -d '[:space:]')"
        fail "PRE-FLIGHT: $(printf '%s\n' "${bad}" | head -n 1) is not owned or not writable by $(id -un) (${count} such directories under ${d}) — nothing was changed; fix with 'chown -R $(id -un):$(id -un) ${d}' or the role's --tags directories (docs/deployment.md → Troubleshooting)"
      fi
    else
      parent="${d%/*}"
      while [[ ! -e "${parent}" && "${parent}" == "${COMPOSE_DIR}"/* ]]; do
        parent="${parent%/*}"
      done
      [[ -w "${parent}" ]] \
        || fail "PRE-FLIGHT: ${parent} is not writable by $(id -un), so ${d} cannot be created — nothing was changed"
    fi
  done
  return 0
}

restore_previous_config_tree() {
  local rc=0 errexit_was_on=false
  if [[ ! -d "${CONFIG_PREVIOUS_DIR}/quadlet/systemd" ]]; then
    log "no previous definition in ${CONFIG_PREVIOUS_DIR} (the first bundle on this host) — nothing to restore to"
    return 2
  fi
  log "restoring previous host config from ${CONFIG_PREVIOUS_DIR}"
  [[ "$-" == *e* ]] && errexit_was_on=true
  set +e
  (
    set -e
    apply_config_tree "${CONFIG_PREVIOUS_DIR}" "${COMPOSE_DIR}"
    install_quadlet_units "${COMPOSE_DIR}"
  )
  rc=$?
  if [[ "${errexit_was_on}" == "true" ]]; then
    set -e
  fi
  if (( rc != 0 )); then
    log "restoring the previous host config FAILED (exit ${rc})"
    return 1
  fi
  log "previous host config restored"
  return 0
}

stage_keycloak_spi_jar() {
  local ref="$1"
  rm -f "${KEYCLOAK_SPI_STAGE_JAR}"
  rt_extract_from_image "${ref}" /providers/keycloak-spi.jar "${KEYCLOAK_SPI_STAGE_JAR}" /bundle \
    || fail "cannot extract /providers/keycloak-spi.jar from ${ref}"
  [[ -s "${KEYCLOAK_SPI_STAGE_JAR}" ]] \
    || fail "the provider JAR extracted from ${ref} is empty"
}

swap_in_keycloak_spi_jar() {
  if [[ "${REAPPLY}" == "true" ]]; then
    install -D -m 0644 "${KEYCLOAK_SPI_STAGE_JAR}" "${KEYCLOAK_SPI_JAR}"
    rm -f "${KEYCLOAK_SPI_STAGE_JAR}"
    rt_note_changed keycloak
    return 0
  fi
  if [[ -f "${KEYCLOAK_SPI_JAR}" ]]; then
    cp -a "${KEYCLOAK_SPI_JAR}" "${KEYCLOAK_SPI_PREVIOUS_JAR}"
    KEYCLOAK_SPI_HAD_PREVIOUS=true
  else
    rm -f "${KEYCLOAK_SPI_PREVIOUS_JAR}"
    KEYCLOAK_SPI_HAD_PREVIOUS=false
  fi
  KEYCLOAK_SPI_TOUCHED=true
  install -D -m 0644 "${KEYCLOAK_SPI_STAGE_JAR}" "${KEYCLOAK_SPI_JAR}"
  rm -f "${KEYCLOAK_SPI_STAGE_JAR}"
  rt_note_changed keycloak
}

restore_previous_keycloak_spi_jar() {
  if [[ "${KEYCLOAK_SPI_HAD_PREVIOUS}" == "true" ]]; then
    install -D -m 0644 "${KEYCLOAK_SPI_PREVIOUS_JAR}" "${KEYCLOAK_SPI_JAR}" || return 1
  else
    rm -f "${KEYCLOAK_SPI_JAR}" || return 1
  fi
  return 0
}

