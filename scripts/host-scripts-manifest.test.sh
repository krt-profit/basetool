#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MANIFEST_TOOL="${SCRIPT_DIR}/host-scripts-manifest.sh"
LIST="${SCRIPT_DIR}/host-scripts.list"
ROLE_TASKS="${REPO_ROOT}/ansible/roles/basetool_host/tasks"

tests_run=0
tests_failed=0
WORK="$(mktemp -d "${TMPDIR:-/tmp}/host-scripts-manifest-test.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT

record() {
  local ok="$1" desc="$2"
  tests_run=$((tests_run + 1))
  if [[ "${ok}" -eq 1 ]]; then
    echo "  ok   - ${desc}"
  else
    tests_failed=$((tests_failed + 1))
    echo "  FAIL - ${desc}"
  fi
}

check() {
  local desc="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    record 1 "${desc}"
  else
    record 0 "${desc}"
  fi
}

list_entries() {
  grep -v '^$' "${LIST}"
}

role_mentions() {
  grep -qE "(^|[[:space:]\"/:])$(printf '%s' "$1" | sed 's/\./\\./g')([[:space:]\"]|$)" "${ROLE_TASKS}"/*.yml
}

role_destinations() {
  grep -ho 'code/scripts/[A-Za-z0-9._/-]*' "${ROLE_TASKS}"/*.yml | sed 's#^code/scripts/##' | sort -u
}

apply_path_entries() {
  sed -n 's/^APPLY_PATH_SCRIPTS=(\(.*\))$/\1/p' "${SCRIPT_DIR}/deploy.sh" | tr ' ' '\n'
}

sourced_libs() {
  # shellcheck disable=SC2016
  sed -n 's#^\. "\${BASETOOL_SCRIPT_DIR}/\(lib/[A-Za-z0-9._-]*\)"$#\1#p' "${SCRIPT_DIR}/deploy.sh"
}

scenario_list_names_real_unique_files() {
  echo "Scenario: the list names real, unique, safe paths"
  local rel dupes unsafe=0 absent=0
  for rel in $(list_entries); do
    [[ -f "${SCRIPT_DIR}/${rel}" ]] || absent=1
    [[ "${rel}" =~ ^[A-Za-z0-9._][A-Za-z0-9._/-]*$ && "${rel}" != *..* ]] || unsafe=1
  done
  check "every entry exists under scripts/" test "${absent}" -eq 0
  dupes="$(list_entries | sort | uniq -d)"
  check "no entry is listed twice" test -z "${dupes}"
  check "no entry is an unsafe path" test "${unsafe}" -eq 0
  if grep -q $'\r' "${LIST}"; then record 0 "the list has LF line endings"; else record 1 "the list has LF line endings"; fi
}

scenario_role_installs_exactly_the_listed_files() {
  echo "Scenario: the Ansible role installs exactly the files the list names"
  local rel dest
  for rel in $(list_entries); do
    if role_mentions "${rel}"; then
      record 1 "the role installs ${rel}"
    else
      record 0 "the role installs ${rel}"
    fi
  done
  for dest in $(role_destinations); do
    case "${dest}" in
      lib | docker-cleanup.sh | *"{"*) continue ;;
    esac
    if list_entries | grep -qxF -- "${dest}"; then
      record 1 "the role's ${dest} is in the list"
    else
      record 0 "the role installs ${dest} but the list omits it"
    fi
  done
}

scenario_apply_path_is_listed_and_complete() {
  echo "Scenario: deploy.sh's apply path is a subset of the list and covers what it sources"
  local rel
  for rel in $(apply_path_entries); do
    if list_entries | grep -qxF -- "${rel}"; then
      record 1 "${rel} (apply path) is in the list"
    else
      record 0 "${rel} (apply path) is in the list"
    fi
  done
  for rel in $(sourced_libs); do
    if apply_path_entries | grep -qxF -- "${rel}"; then
      record 1 "deploy.sh sources ${rel} and gates on it"
    else
      record 0 "deploy.sh sources ${rel} but APPLY_PATH_SCRIPTS omits it"
    fi
  done
  if apply_path_entries | grep -qxF -- "deploy.sh" && apply_path_entries | grep -qxF -- "render-env-d.py"; then
    record 1 "deploy.sh and the env renderer it runs are gated"
  else
    record 0 "deploy.sh and the env renderer it runs are gated"
  fi
}

scenario_generate_matches_sha256sum_and_verifies() {
  echo "Scenario: generate prints one sha256sum line per listed file, and sha256sum -c accepts it on the scripts directory"
  local out="${WORK}/manifest" n_list n_out rel want got
  "${MANIFEST_TOOL}" generate > "${out}"
  n_list="$(list_entries | wc -l | tr -d '[:space:]')"
  n_out="$(wc -l < "${out}" | tr -d '[:space:]')"
  check "one line per listed file" test "${n_list}" = "${n_out}"
  rel="deploy.sh"
  want="$(sha256sum < "${SCRIPT_DIR}/${rel}")"
  got="$(grep -F "  ${rel}" "${out}" | cut -d' ' -f1)"
  check "deploy.sh carries its own sha256" test "${want%% *}" = "${got}"
  check "sha256sum -c accepts the manifest from the scripts directory" bash -c "cd '${SCRIPT_DIR}' && sha256sum -c --quiet '${out}'"
  printf 'x\n' >> "${WORK}/probe"
  cp "${SCRIPT_DIR}/deploy.sh" "${WORK}/deploy.sh"
  echo "changed" >> "${WORK}/deploy.sh"
  if (cd "${WORK}" && sha256sum -c --quiet "${out}" >/dev/null 2>&1); then
    record 0 "sha256sum -c rejects a changed copy"
  else
    record 1 "sha256sum -c rejects a changed copy"
  fi
}

scenario_rev_reads_bytes_exactly() {
  echo "Scenario: --rev hashes the committed bytes, whatever the checkout does to line endings"
  local repo="${WORK}/repo" digest
  mkdir -p "${repo}/scripts"
  git -C "${repo}" init -q
  git -C "${repo}" config user.email t@example.invalid
  git -C "${repo}" config user.name t
  git -C "${repo}" config core.autocrlf false
  printf 'one.sh\n' > "${repo}/scripts/host-scripts.list"
  printf 'echo crlf\r\n' > "${repo}/scripts/one.sh"
  git -C "${repo}" add scripts
  git -C "${repo}" commit -q -m seed
  digest="$(printf 'echo crlf\r\n' | sha256sum)"
  if [[ "$(cd "${repo}" && "${MANIFEST_TOOL}" generate --rev HEAD)" == "${digest%% *}  one.sh" ]]; then
    record 1 "the CRLF bytes of the commit are hashed"
  else
    record 0 "the CRLF bytes of the commit are hashed"
  fi
  if (cd "${repo}" && "${MANIFEST_TOOL}" generate --rev no-such-rev >/dev/null 2>&1); then
    record 0 "an unknown revision fails"
  else
    record 1 "an unknown revision fails"
  fi
}

scenario_generate_refuses_bad_lists() {
  echo "Scenario: generate refuses an unsafe path, a missing file and an empty list"
  local dir="${WORK}/tool" bad
  mkdir -p "${dir}"
  cp "${MANIFEST_TOOL}" "${dir}/host-scripts-manifest.sh"
  for bad in '../etc/passwd' '/etc/passwd' 'a b.sh' 'a/../b.sh' 'missing.sh'; do
    printf '%s\n' "${bad}" > "${dir}/host-scripts.list"
    if bash "${dir}/host-scripts-manifest.sh" generate >/dev/null 2>&1; then
      record 0 "'${bad}' is refused"
    else
      record 1 "'${bad}' is refused"
    fi
  done
  : > "${dir}/host-scripts.list"
  if bash "${dir}/host-scripts-manifest.sh" generate >/dev/null 2>&1; then
    record 0 "an empty list is refused"
  else
    record 1 "an empty list is refused"
  fi
}

scenario_the_release_ships_the_manifest() {
  echo "Scenario: the config image and the release workflow carry the manifest"
  check "the config Dockerfile copies host-scripts.sha256 into /config" grep -q 'host-scripts\.sha256 /config/host-scripts\.sha256' "${REPO_ROOT}/docker/config/Dockerfile"
  check "release-images.yml generates it before the config build" grep -q 'host-scripts-manifest.sh generate' "${REPO_ROOT}/.github/workflows/release-images.yml"
  check "release-images.yml asserts it is in the built bundle" grep -q 'host-scripts.sha256' "${REPO_ROOT}/.github/workflows/release-images.yml"
}

scenario_list_names_real_unique_files
scenario_role_installs_exactly_the_listed_files
scenario_apply_path_is_listed_and_complete
scenario_generate_matches_sha256sum_and_verifies
scenario_rev_reads_bytes_exactly
scenario_generate_refuses_bad_lists
scenario_the_release_ships_the_manifest

echo
if [[ "${tests_failed}" -eq 0 ]]; then
  echo "All ${tests_run} host-scripts manifest tests passed."
  exit 0
fi
echo "${tests_failed}/${tests_run} host-scripts manifest test(s) failed."
exit 1
