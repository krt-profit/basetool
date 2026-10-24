#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'USAGE'
Usage: host-scripts-manifest.sh generate [--rev <git-rev>]

Prints the sha256 manifest of the host scripts named in scripts/host-scripts.list,
one "<sha256>  <path>" line per file, in list order. The format is what
`sha256sum -c` reads, run from /opt/basetool/scripts on the host.

  --rev <git-rev>   Read the list and every script from that revision with
                    `git show` (byte-exact, independent of the checkout's line
                    endings). Default: the files next to this script.
USAGE
}

die() {
  echo "host-scripts-manifest: $*" >&2
  exit 1
}

read_script() {
  local rev="$1" rel="$2"
  if [[ -n "${rev}" ]]; then
    git show "${rev}:scripts/${rel}"
  else
    cat "${SCRIPT_DIR}/${rel}"
  fi
}

generate() {
  local rev="" list rel digest
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --rev)
        [[ -n "${2:-}" ]] || die "--rev requires a value"
        rev="$2"
        shift 2
        ;;
      *)
        die "unknown argument: $1"
        ;;
    esac
  done
  list="$(read_script "${rev}" host-scripts.list)" || die "cannot read scripts/host-scripts.list${rev:+ at ${rev}}"
  [[ -n "${list}" ]] || die "scripts/host-scripts.list is empty"
  while IFS= read -r rel; do
    rel="${rel%$'\r'}"
    [[ -n "${rel}" ]] || continue
    [[ "${rel}" =~ ^[A-Za-z0-9._][A-Za-z0-9._/-]*$ && "${rel}" != *..* ]] || die "unsafe path in host-scripts.list: ${rel}"
    digest="$(read_script "${rev}" "${rel}" | sha256sum)" || die "cannot read scripts/${rel}${rev:+ at ${rev}}"
    printf '%s  %s\n' "${digest%% *}" "${rel}"
  done <<< "${list}"
}

case "${1:-}" in
  generate)
    shift
    generate "$@"
    ;;
  -h | --help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
