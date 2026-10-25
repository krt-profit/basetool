#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf '::error title=gitless-config-cache::%s\n' "$1" >&2
  exit 1
}

note() {
  printf '%s\n' "$1"
}

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd -- "${repo_root}"

wrapper_jar='gradle/wrapper/gradle-wrapper.jar'
stamp='frontend/build/bootBuildInfo/META-INF/build-info.properties'

[[ -f ${wrapper_jar} ]] || fail "${wrapper_jar} is missing"
[[ -e .git ]] ||
  fail ".git is missing, so the probe short-circuits on its first condition and the PATH half of \
the guard would never be exercised; run this in a git checkout"

java_bin=''
for candidate in "${JAVA_HOME:-}/bin/java" "${JAVA_HOME:-}/bin/java.exe"; do
  if [[ -n ${JAVA_HOME:-} && -x ${candidate} ]]; then
    java_bin="${candidate}"
    break
  fi
done
if [[ -z ${java_bin} ]]; then
  java_bin="$(command -v java || true)"
fi
[[ -n ${java_bin} ]] || fail 'no java executable found; set JAVA_HOME'

work="$(mktemp -d)"
trap 'rm -rf -- "${work}"' EXIT
nogit="${work}/empty-path"
mkdir -p -- "${nogit}"

if PATH="${nogit}" command -v git >/dev/null 2>&1; then
  fail "git is still reachable with PATH=${nogit}; the check would prove nothing"
fi

project_version="$(
  sed -n 's/^[[:space:]]*version = "\(.*\)"$/\1/p' build.gradle.kts | head -1
)"
if [[ -z ${project_version} ]]; then
  project_version="$(
    sed -n '/^[[:space:]]*version =[[:space:]]*$/,/getOrElse/s/^[[:space:]]*\.getOrElse("\(.*\)")$/\1/p' \
      build.gradle.kts | head -1
  )"
fi
[[ -n ${project_version} ]] || fail 'could not read the project version from build.gradle.kts'

git_version="$(git describe --tags --always --dirty 2>/dev/null | sed 's/^v//' || true)"
[[ -n ${git_version} ]] ||
  fail 'git describe produced nothing; the git-present half cannot be checked'
[[ ${git_version} != "${project_version}" ]] ||
  fail "git describe answers ${git_version}, which is the project version, so a fallback and a \
git-derived stamp are indistinguishable"

gradle_run() {
  local log="$1" path="$2"
  shift 2
  local code=0
  env PATH="${path}" "${java_bin}" -jar "${wrapper_jar}" \
    :frontend:bootBuildInfo --configuration-cache --console=plain "$@" >"${log}" 2>&1 || code=$?
  printf '%s' "${code}"
}

expect_log() {
  local log="$1" needle="$2" what="$3"
  grep -qF -- "${needle}" "${log}" || {
    sed -n '1,200p' "${log}" >&2
    fail "${what}: the build log does not contain '${needle}'"
  }
}

expect_stamp() {
  local expected="$1" what="$2" actual
  [[ -f ${stamp} ]] || fail "${what}: ${stamp} was not written"
  actual="$(sed -n 's/^build\.version=//p' "${stamp}")"
  [[ ${actual} == "${expected}" ]] ||
    fail "${what}: build.version is '${actual}', expected '${expected}'"
}

note "project version: ${project_version}"
note "git describe:    ${git_version}"
note ''

rm -f -- "${stamp}"

note '--- 1/4 git unreachable, cold entry ---'
code="$(gradle_run "${work}/cold.log" "${nogit}")"
[[ ${code} == 0 ]] || {
  sed -n '1,200p' "${work}/cold.log" >&2
  fail "a git-less :frontend:bootBuildInfo --configuration-cache exited ${code}; it must succeed \
and store its entry (REQ-OPS-021)"
}
expect_log "${work}/cold.log" 'Configuration cache entry stored.' 'git unreachable, cold entry'
expect_stamp "${project_version}" 'git unreachable, cold entry'

note '--- 2/4 git unreachable, entry reused ---'
code="$(gradle_run "${work}/warm.log" "${nogit}")"
[[ ${code} == 0 ]] || {
  sed -n '1,200p' "${work}/warm.log" >&2
  fail "the second git-less run exited ${code}"
}
expect_log "${work}/warm.log" 'Configuration cache entry reused.' 'git unreachable, entry reused'

note '--- 3/4 git reachable, the stamp stays git describe ---'
rm -f -- "${stamp}"
code="$(gradle_run "${work}/withgit-cold.log" "${PATH}")"
[[ ${code} == 0 ]] || {
  sed -n '1,200p' "${work}/withgit-cold.log" >&2
  fail "a :frontend:bootBuildInfo --configuration-cache with git on PATH exited ${code}"
}
expect_stamp "${git_version}" 'git reachable'

note '--- 4/4 git reachable, entry reused ---'
code="$(gradle_run "${work}/withgit-warm.log" "${PATH}")"
[[ ${code} == 0 ]] || {
  sed -n '1,200p' "${work}/withgit-warm.log" >&2
  fail "the second run with git on PATH exited ${code}"
}
expect_log "${work}/withgit-warm.log" 'Configuration cache entry reused.' \
  'git reachable, entry reused'

note ''
note 'OK: the frontend stores its configuration-cache entry with and without git on PATH.'
