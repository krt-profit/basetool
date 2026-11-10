# shellcheck shell=bash

host_scripts_compare() {
  local manifest="$1" line rel want actual installed service_uid
  service_uid="${RT_UNIT_DIR:-}"
  service_uid="${service_uid##*/}"
  [[ "${service_uid}" =~ ^[0-9]+$ ]] || service_uid="$(id -u)"
  local re='^([0-9a-f]{64})  ([A-Za-z0-9._][A-Za-z0-9._/-]*)$'
  HS_DIFFERS=()
  HS_MISSING=()
  HS_CHECKED=0
  [[ -s "${manifest}" ]] || return 2
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -n "${line}" ]] || continue
    if ! [[ "${line}" =~ ${re} ]] || [[ "${line}" == *..* ]]; then
      return 2
    fi
    want="${BASH_REMATCH[1]}"
    rel="${BASH_REMATCH[2]}"
    HS_CHECKED=$(( HS_CHECKED + 1 ))
    installed="${HOST_SCRIPTS_DIR}/${rel}"
    if [[ "${rel}" == host/etc/* ]]; then
      installed="${BASETOOL_HOST_ROOT:-}/${rel#host/}"
    fi
    if [[ ! -r "${installed}" ]]; then
      HS_MISSING+=("${rel}")
      continue
    fi
    if [[ "${rel}" == host/etc/* ]]; then
      actual="$(python3 "${BASETOOL_SCRIPT_DIR}/host_companions.py" --inspect "${installed}" --service-uid "${service_uid}")" || return 2
    else
      actual="$(sha256sum < "${installed}")"
    fi
    [[ "${actual%% *}" == "${want}" ]] || HS_DIFFERS+=("${rel}")
  done < "${manifest}"
  (( HS_CHECKED > 0 )) || return 2
  return 0
}

host_scripts_apply_path_drift() {
  local rel path out=""
  for rel in "${HS_DIFFERS[@]}" "${HS_MISSING[@]}"; do
    for path in "${APPLY_PATH_SCRIPTS[@]}"; do
      if [[ "${rel}" == "${path}" ]]; then
        out="${out}${out:+ }${rel}"
      fi
    done
  done
  printf '%s' "${out}"
}

write_host_scripts_metric() {
  local rel
  {
    if (( ${#HS_DIFFERS[@]} + ${#HS_MISSING[@]} > 0 )); then
      echo "# HELP basetool_host_scripts_drift 1 for each host script whose sha256 differs from (kind=differs) or is absent against (kind=missing) the manifest of the release it is compared with."
      echo "# TYPE basetool_host_scripts_drift gauge"
      for rel in "${HS_DIFFERS[@]}"; do
        echo "basetool_host_scripts_drift{file=\"${rel}\",kind=\"differs\"} 1"
      done
      for rel in "${HS_MISSING[@]}"; do
        echo "basetool_host_scripts_drift{file=\"${rel}\",kind=\"missing\"} 1"
      done
    fi
    echo "# HELP basetool_host_scripts_check_timestamp_seconds Unix time deploy.sh last compared the host scripts with a release manifest."
    echo "# TYPE basetool_host_scripts_check_timestamp_seconds gauge"
    echo "basetool_host_scripts_check_timestamp_seconds $(date +%s)"
  } | write_textfile host-scripts.prom || true
}

report_host_scripts() {
  local label="$1" rel
  write_host_scripts_metric
  if (( ${#HS_DIFFERS[@]} + ${#HS_MISSING[@]} == 0 )); then
    log "host scripts: all ${HS_CHECKED} match the manifest of the ${label}"
    return 0
  fi
  for rel in "${HS_DIFFERS[@]}"; do
    log "host scripts: WARN ${rel} differs from the manifest of the ${label}"
  done
  for rel in "${HS_MISSING[@]}"; do
    log "host scripts: WARN ${rel} is missing, the manifest of the ${label} lists it"
  done
  log "host scripts: WARN install the scripts of that release with the role (ansible-playbook site.yml --tags deploy,scripts, run from its tag; docs/deployment.md, Before a promotion: the host scripts)"
}

forget_host_scripts_metric() {
  rm -f "${TEXTFILE_DIR}/host-scripts.prom" 2>/dev/null || true
}

check_host_scripts_applied() {
  local manifest="${COMPOSE_DIR}/${HOST_SCRIPTS_MANIFEST_NAME}" rc=0
  if [[ ! -f "${manifest}" ]]; then
    forget_host_scripts_metric
    log "host scripts: the deployed release carries no ${HOST_SCRIPTS_MANIFEST_NAME}, so the host's scripts are not compared (the first release that ships one starts the check)"
    return 0
  fi
  host_scripts_compare "${manifest}" || rc=$?
  if (( rc != 0 )); then
    forget_host_scripts_metric
    log "host scripts: WARN ${manifest} is empty or malformed, so the host's scripts are not compared"
    return 0
  fi
  report_host_scripts "deployed release"
}

assert_host_scripts_current_or_die() {
  local manifest="$1" rc=0 apply
  if [[ ! -f "${manifest}" ]]; then
    log "host scripts: the config bundle carries no ${HOST_SCRIPTS_MANIFEST_NAME}, so nothing is compared (a bundle built before the check existed)"
    return 0
  fi
  host_scripts_compare "${manifest}" || rc=$?
  if (( rc != 0 )); then
    forget_host_scripts_metric
    log "host scripts: WARN the bundle's ${HOST_SCRIPTS_MANIFEST_NAME} is empty or malformed, so nothing is compared"
    return 0
  fi
  report_host_scripts "release being applied"
  apply="$(host_scripts_apply_path_drift)"
  [[ -n "${apply}" ]] || return 0
  if [[ "${FORCE}" == "true" ]]; then
    log "host scripts: WARN [${apply}] are not this release's, which apply it; --force given, applying anyway"
    return 0
  fi
  if [[ "${REQUIRE_CURRENT_SCRIPTS}" != "true" ]]; then
    log "WARNING: host-scripts gate DISABLED (BASETOOL_REQUIRE_CURRENT_SCRIPTS=false), applying with [${apply}] that are not this release's"
    return 0
  fi
  PRE_GATE_GUARD=false
  rm -f "${KEYCLOAK_SPI_STAGE_JAR}"
  write_deploy_metric failure
  fail "HOST SCRIPTS: [${apply}] on this host are not the ones this release was built with, and they are what applies it. Nothing was changed. Install that release's scripts first: run the role from its tag with --tags deploy,scripts (docs/deployment.md, Before a promotion: the host scripts); the next tick then applies the release. Break-glass: deploy.sh --force, or BASETOOL_REQUIRE_CURRENT_SCRIPTS=false"
}

