# shellcheck shell=bash

reconcile_monitoring_reload() {
  local svc="$1" subpath="$2" src snap
  src="${COMPOSE_DIR}/monitoring/${subpath}"
  snap="${MON_RELOAD_STATE_DIR}/${svc}"
  [[ -d "${src}" ]] || return 0
  if diff -rq "${snap}" "${src}" >/dev/null 2>&1; then
    return 0
  fi
  log "  monitoring: ${subpath} config differs from the last applied snapshot → recreating ${svc} (re-resolves the bind-mount inode)"
  if rt_monitoring_recreate "${svc}" >/dev/null 2>&1; then
    install -d -m 0755 "${MON_RELOAD_STATE_DIR}" 2>/dev/null || true
    rm -rf "${snap}"
    if cp -R "${src}" "${snap}" 2>/dev/null; then
      date +%s >"${MON_RELOAD_STATE_DIR}/${svc}.applied" 2>/dev/null || true
    else
      log "  monitoring: WARN could not snapshot ${subpath} baseline (will re-apply next tick)"
    fi
  else
    log "  monitoring: WARN recreate of ${svc} failed (non-gating; monitoring stack down?) — will retry next tick"
  fi
}

write_prometheus_config_applied_metric() {
  local applied_file applied
  applied_file="${MON_RELOAD_STATE_DIR}/prometheus.applied"
  [[ -f "${applied_file}" ]] || return 0
  applied="$(cat "${applied_file}" 2>/dev/null || true)"
  [[ "${applied}" =~ ^[0-9]+$ ]] || return 0
  {
    echo "# HELP basetool_monitoring_config_applied_timestamp Unix time deploy.sh last recreated a monitoring component for an on-disk config change."
    echo "# TYPE basetool_monitoring_config_applied_timestamp gauge"
    echo "basetool_monitoring_config_applied_timestamp{component=\"prometheus\"} ${applied}"
  } | write_textfile monitoring-config.prom || true
}

write_monitoring_reconcile_state_metric() {
  {
    echo "# HELP basetool_monitoring_reconcile_disabled 1 when the basetool-monitoring stack is running but deploy.sh's monitoring reconcile is gated off (BASETOOL_MONITORING_ENABLED != true), so on-disk monitoring config changes are never reloaded into the running Prometheus/alloy/blackbox."
    echo "# TYPE basetool_monitoring_reconcile_disabled gauge"
    echo "basetool_monitoring_reconcile_disabled{component=\"deploy\"} ${1}"
  } | write_textfile monitoring-reconcile.prom || true
}

reconcile_edge() {
  local src="${COMPOSE_DIR}/docker/edge"
  local snap="${EDGE_STATE_DIR}/config"
  local fp_file="${EDGE_STATE_DIR}/certs.sha256"
  local drift="" fp_now="" fp_old="" fp_lines=""

  [[ -d "${src}" ]] || return 0

  diff -rq "${snap}" "${src}" >/dev/null 2>&1 || drift="config"

  if rt_is_running edge; then
    fp_lines="$(rt_exec edge sh -c \
      'find /etc/nginx/certs -name fullchain.pem -type f -exec sha256sum {} +' \
      2>/dev/null || true)"
  fi
  if [[ -n "${fp_lines}" ]]; then
    fp_now="$(printf '%s\n' "${fp_lines}" | awk '{print $1}' | sort | sha256sum | cut -d' ' -f1)"
    fp_old="$(cat "${fp_file}" 2>/dev/null || true)"
    if [[ "${fp_now}" != "${fp_old}" ]]; then
      drift="${drift:+${drift} + }certificates"
    fi
  fi

  if [[ -z "${drift}" ]]; then
    write_edge_config_stale_metric 0
    return 0
  fi

  if load_edge_state "${drift}"; then
    record_edge_applied "${src}" "${snap}" "${fp_file}" "${fp_now}"
  else
    write_edge_config_stale_metric 1
  fi
}

# A configuration nginx refuses is never recreated: a new edge would not start on it, while the
# running one still serves the configuration it has loaded.
load_edge_state() {
  local drift="$1" out="" rc=0 line
  if rt_is_running edge; then
    out="$(rt_exec edge sh "${EDGE_RELOAD_SCRIPT}" 2>&1)" || rc=$?
    if ((rc == 0)); then
      log "  edge: ${drift} differs from the last applied state -> nginx reloaded in place (no restart, the maintenance page stays up)"
      return 0
    fi
    if ((rc == EDGE_RELOAD_REFUSED)); then
      log "  edge: WARN ${drift} differs, but nginx refused the new configuration — the edge keeps serving the one it has loaded; retries next tick"
      while IFS= read -r line; do
        log "  edge: nginx: ${line}"
      done < <(printf '%s\n' "${out}" | head -n 5)
      return 1
    fi
    log "  edge: ${drift} differs and the in-place reload failed (exit ${rc}) -> recreating edge"
  else
    log "  edge: ${drift} differs and the edge is not running -> recreating edge"
  fi
  if rt_recreate edge >/dev/null 2>&1; then
    return 0
  fi
  log "  edge: WARN recreate failed (non-gating) — will retry next tick"
  return 1
}

record_edge_applied() {
  local src="$1" snap="$2" fp_file="$3" fp_now="$4"
  install -d -m 0755 "${EDGE_STATE_DIR}" 2>/dev/null || true
  rm -rf "${snap}"
  if cp -R "${src}" "${snap}" 2>/dev/null; then
    date +%s >"${EDGE_STATE_DIR}/applied" 2>/dev/null || true
  else
    log "  edge: WARN could not snapshot the config baseline (will re-apply next tick)"
  fi
  if [[ -n "${fp_now}" ]]; then
    printf '%s\n' "${fp_now}" >"${fp_file}" 2>/dev/null || true
  fi
  write_edge_config_stale_metric 0
}

write_edge_config_stale_metric() {
  {
    echo "# HELP basetool_edge_config_stale 1 when the edge configuration or certificates on disk differ from what the running edge has loaded and deploy.sh could not load them; 0 once they are loaded."
    echo "# TYPE basetool_edge_config_stale gauge"
    echo "basetool_edge_config_stale{component=\"edge\"} ${1}"
  } | write_textfile edge-config.prom || true
}

reconcile_monitoring_reloads() {
  if [[ "${BASETOOL_MONITORING_ENABLED:-false}" != "true" ]]; then
    if rt_monitoring_is_running; then
      log "  monitoring: WARN basetool-monitoring is RUNNING but BASETOOL_MONITORING_ENABLED != 'true' — on-disk monitoring config changes will NOT be reloaded into Prometheus/alloy/blackbox (set BASETOOL_MONITORING_ENABLED=true in the basetool-deploy service env)"
      write_monitoring_reconcile_state_metric 1
    fi
    return 0
  fi
  rt_monitoring_configured || return 0
  write_monitoring_reconcile_state_metric 0
  if ! rt_monitoring_up >/dev/null 2>&1; then
    log "  monitoring: WARN definition reconcile failed — non-gating, retries next tick"
  fi
  reconcile_monitoring_reload prometheus prometheus
  reconcile_monitoring_reload alloy alloy
  reconcile_monitoring_reload blackbox-exporter blackbox
  write_prometheus_config_applied_metric
}

write_token_expiry_metric() {
  local raw epoch
  if [[ ! -f "${TOKEN_EXPIRY_FILE}" ]]; then
    rm -f "${TOKEN_METRIC_FILE}" 2>/dev/null || true
    return 0
  fi
  raw="$(tr -d '[:space:]' <"${TOKEN_EXPIRY_FILE}" 2>/dev/null || true)"
  if [[ -z "${raw}" ]]; then
    rm -f "${TOKEN_METRIC_FILE}" 2>/dev/null || true
    return 0
  fi
  epoch="$(date -u -d "${raw}" +%s 2>/dev/null || true)"
  if ! [[ "${epoch}" =~ ^[0-9]+$ ]]; then
    log "WARN: could not parse GHCR token expiry '${raw}' from ${TOKEN_EXPIRY_FILE}; removing the metric rather than leaving the previous value asserted"
    rm -f "${TOKEN_METRIC_FILE}" 2>/dev/null || true
    return 0
  fi
  {
    echo "# HELP basetool_ghcr_token_expiry_timestamp Unix time the GHCR pull token expires (operator-recorded in ${TOKEN_FILE}.expiry)."
    echo "# TYPE basetool_ghcr_token_expiry_timestamp gauge"
    echo "basetool_ghcr_token_expiry_timestamp ${epoch}"
  } | write_textfile "$(basename "${TOKEN_METRIC_FILE}")" || true
}

write_deploy_metric() {
  local outcome="$1" now dur f v blocked
  now="$(date +%s)"
  dur=$((now - START_EPOCH))
  f="${DEPLOY_METRIC_FILE}"
  local prev_success=0 prev_rollback=0 prev_failure=0 prev_blocked=0
  if [[ -f "${f}" ]]; then
    prev_success="$(awk '/^basetool_deploy_last_success_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
    prev_rollback="$(awk '/^basetool_deploy_last_rollback_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
    prev_failure="$(awk '/^basetool_deploy_last_failure_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
    prev_blocked="$(awk '/^basetool_deploy_last_blocked_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
  fi
  for v in prev_success prev_rollback prev_failure prev_blocked; do
    [[ "${!v}" =~ ^[0-9]+$ ]] || printf -v "${v}" '%s' 0
  done
  case "${outcome}" in
    success) prev_success="${now}" ;;
    rollback) prev_rollback="${now}" ;;
    failure) prev_failure="${now}" ;;
    blocked) prev_blocked="${now}" ;;
  esac
  blocked=0
  [[ -f "${CONFIG_BLOCKED_FILE}" ]] && blocked=1
  {
    echo "# HELP basetool_deploy_last_success_timestamp Unix time of the last successful deploy."
    echo "# TYPE basetool_deploy_last_success_timestamp gauge"
    echo "basetool_deploy_last_success_timestamp ${prev_success}"
    echo "# HELP basetool_deploy_last_rollback_timestamp Unix time of the last deploy rollback (health gate reverted a release)."
    echo "# TYPE basetool_deploy_last_rollback_timestamp gauge"
    echo "basetool_deploy_last_rollback_timestamp ${prev_rollback}"
    echo "# HELP basetool_deploy_last_failure_timestamp Unix time of the last deploy failure."
    echo "# TYPE basetool_deploy_last_failure_timestamp gauge"
    echo "basetool_deploy_last_failure_timestamp ${prev_failure}"
    echo "# HELP basetool_deploy_last_blocked_timestamp Unix time of the last operator-gated (config-blocked) deploy."
    echo "# TYPE basetool_deploy_last_blocked_timestamp gauge"
    echo "basetool_deploy_last_blocked_timestamp ${prev_blocked}"
    echo "# HELP basetool_deploy_duration_seconds Runtime of the last deploy invocation in seconds."
    echo "# TYPE basetool_deploy_duration_seconds gauge"
    echo "basetool_deploy_duration_seconds ${dur}"
    echo "# HELP basetool_deploy_config_blocked Whether a postgres/Keycloak image change is operator-gated (1) or not (0)."
    echo "# TYPE basetool_deploy_config_blocked gauge"
    echo "basetool_deploy_config_blocked ${blocked}"
  } | write_textfile "$(basename "${f}")" || true
}

write_stack_health_metric() {
  local outcome="$1" now f prev_healthy prev_failed
  now="$(date +%s)"
  f="${STACK_HEALTH_METRIC_FILE}"
  prev_healthy=0
  prev_failed=0
  if [[ -f "${f}" ]]; then
    prev_healthy="$(awk '/^basetool_deploy_last_stack_healthy_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
    prev_failed="$(awk '/^basetool_deploy_last_health_restart_failed_timestamp /{print $2}' "${f}" 2>/dev/null || echo 0)"
  fi
  [[ "${prev_healthy}" =~ ^[0-9]+$ ]] || prev_healthy=0
  [[ "${prev_failed}" =~ ^[0-9]+$ ]] || prev_failed=0
  case "${outcome}" in
    healthy) prev_healthy="${now}" ;;
    restart_failed) prev_failed="${now}" ;;
  esac
  {
    echo "# HELP basetool_deploy_last_stack_healthy_timestamp Unix time deploy.sh last observed the running app stack at target and healthy."
    echo "# TYPE basetool_deploy_last_stack_healthy_timestamp gauge"
    echo "basetool_deploy_last_stack_healthy_timestamp ${prev_healthy}"
    echo "# HELP basetool_deploy_last_health_restart_failed_timestamp Unix time deploy.sh last failed to restore the deployed release: a targeted restart of an unhealthy at-target service, or a drift re-apply."
    echo "# TYPE basetool_deploy_last_health_restart_failed_timestamp gauge"
    echo "basetool_deploy_last_health_restart_failed_timestamp ${prev_failed}"
  } | write_textfile "$(basename "${f}")" || true
}

record_restart_window() {
  [[ "${RT_WINDOW_SECONDS}" =~ ^[0-9]+$ ]] || return 0
  log "restart window closed after ${RT_WINDOW_SECONDS}s"
  {
    echo "# HELP basetool_deploy_last_restart_window_seconds Seconds from the one stop of the last release, re-apply or heal window that closed healthy until every stack unit was started and healthy again."
    echo "# TYPE basetool_deploy_last_restart_window_seconds gauge"
    echo "basetool_deploy_last_restart_window_seconds ${RT_WINDOW_SECONDS}"
    echo "# HELP basetool_deploy_last_restart_window_timestamp Unix time that window closed."
    echo "# TYPE basetool_deploy_last_restart_window_timestamp gauge"
    echo "basetool_deploy_last_restart_window_timestamp $(date +%s)"
  } | write_textfile "$(basename "${RESTART_WINDOW_METRIC_FILE}")" || true
}
