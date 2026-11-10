# shellcheck shell=bash

verify_signature() {
  local ref="$1"
  if [[ "${COSIGN_VERIFY}" != "true" ]]; then
    log "WARNING: signature verification DISABLED (BASETOOL_COSIGN_VERIFY=false) — NOT verifying ${ref}"
    return 0
  fi
  local attempt delay err rc
  delay="${COSIGN_VERIFY_DELAY}"
  VERIFY_LAST_ERROR=""
  for (( attempt = 1; attempt <= COSIGN_VERIFY_ATTEMPTS; attempt++ )); do
    rc=0
    err="$(cosign verify "${ref}" \
      --certificate-identity-regexp "${COSIGN_IDENTITY_REGEXP}" \
      --certificate-oidc-issuer "${COSIGN_OIDC_ISSUER}" 2>&1 >/dev/null)" || rc=$?
    if (( rc == 0 )); then
      return 0
    fi
    VERIFY_LAST_ERROR="${err//$'\n'/ }"
    if (( attempt < COSIGN_VERIFY_ATTEMPTS )); then
      log "  ${ref}: verify attempt ${attempt}/${COSIGN_VERIFY_ATTEMPTS} failed (rc=${rc}), retrying in ${delay}s — ${VERIFY_LAST_ERROR}"
      sleep "${delay}"
      delay=$(( delay * 2 ))
    fi
  done
  return 1
}

verify_digest_or_die() {
  local label="$1" ref="$2"
  if verify_signature "${ref}"; then
    log "  ${label}: signature OK"
    return 0
  fi
  write_deploy_metric failure
  fail "SECURITY: cosign signature verification failed for ${label} (${ref}) after ${COSIGN_VERIFY_ATTEMPTS} attempts — refusing to deploy an unverified/untrusted image (expected identity: ${COSIGN_IDENTITY_REGEXP}); last cosign error: ${VERIFY_LAST_ERROR:-<none>}"
}

release_revision_problem() {
  local label ref revision first_label="" first_revision=""
  local -a pairs=(
    "backend|${BACKEND_IMAGE}@${BACKEND_DIGEST}"
    "frontend|${FRONTEND_IMAGE}@${FRONTEND_DIGEST}"
    "ingest|${INGEST_IMAGE}@${INGEST_DIGEST}"
  )
  [[ -n "${CONFIG_DIGEST}" ]] && pairs+=("config|${CONFIG_IMAGE}@${CONFIG_DIGEST}")
  [[ -n "${KEYCLOAK_SPI_DIGEST}" ]] && pairs+=("keycloak-spi|${KEYCLOAK_SPI_IMAGE}@${KEYCLOAK_SPI_DIGEST}")
  local pair
  for pair in "${pairs[@]}"; do
    label="${pair%%|*}"
    ref="${pair#*|}"
    revision="$(rt_image_revision "${ref}")" || revision=""
    if [[ -z "${revision}" ]]; then
      echo "${label} (${ref}) carries no readable org.opencontainers.image.revision label"
      return 2
    fi
    log "  ${label}: built from ${revision}" >&2
    if [[ -z "${first_revision}" ]]; then
      first_label="${label}"
      first_revision="${revision}"
    elif [[ "${revision}" != "${first_revision}" ]]; then
      echo "${label} is built from ${revision} but ${first_label} from ${first_revision}"
      return 1
    fi
  done
  return 0
}

mixed_release_waited_seconds() {
  local now since=""
  now="$(date +%s)"
  if [[ -f "${MIXED_RELEASE_FILE}" ]]; then
    read -r since _ < "${MIXED_RELEASE_FILE}" || true
  fi
  if ! [[ "${since}" =~ ^[0-9]+$ ]] || (( 10#${since} > now )); then
    since="${now}"
    printf '%d\n' "${since}" > "${MIXED_RELEASE_FILE}"
  fi
  echo $(( now - 10#${since} ))
}

assert_one_release_or_die() {
  if [[ "${REQUIRE_ONE_RELEASE}" != "true" ]]; then
    log "WARNING: release-revision gate DISABLED (BASETOOL_REQUIRE_ONE_RELEASE=false) — NOT comparing the images' source revisions"
    return 0
  fi
  local problem rc=0 waited
  problem="$(release_revision_problem)" || rc=$?
  if [[ "${rc}" -eq 0 ]]; then
    rm -f "${MIXED_RELEASE_FILE}"
    log "release revision: all target images share one source revision"
    return 0
  fi
  if [[ "${rc}" -ne 1 ]]; then
    write_deploy_metric failure
    fail "the ${TARGET_TAG} set cannot be shown to be one release: ${problem} — refusing to deploy, the next tick retries (BASETOOL_REQUIRE_ONE_RELEASE=false is the break-glass)"
  fi
  waited="$(mixed_release_waited_seconds)"
  if (( waited < MIXED_RELEASE_GRACE )); then
    log "the ${TARGET_TAG} set is not one release yet: ${problem} — a promotion is probably in progress; waiting for it to finish (${waited}s/${MIXED_RELEASE_GRACE}s), nothing applied, the next tick retries"
    exit 0
  fi
  write_deploy_metric failure
  fail "the ${TARGET_TAG} set has not been one release for ${waited}s: ${problem} — a promotion was probably interrupted; refusing to deploy a mixed release, re-run promote.yml for one version to finish it (BASETOOL_REQUIRE_ONE_RELEASE=false is the break-glass)"
}

check_only_verify_one() {
  local label="$1" ref="$2"
  if verify_signature "${ref}"; then
    log "  ${label}: signature OK"
    return 0
  fi
  log "  ${label}: SIGNATURE VERIFICATION FAILED (${ref}) — last cosign error: ${VERIFY_LAST_ERROR:-<none>}"
  return 1
}

