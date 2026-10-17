#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

SHA_RE='^[0-9a-f]{40}$'

emit() {
  printf 'skip=%s\nreason=%s\n' "$1" "$2"
}

decide() {
  local event="$1" ref="$2" sha="$3" tip="$4" status="$5"
  case ${status} in
    ahead | behind | diverged | identical) ;;
    *) status='unknown' ;;
  esac
  if [[ ${event} != push || ${ref} != refs/heads/main ]]; then
    emit false "not a main push (event=${event}, ref=${ref})"
    return 0
  fi
  if [[ ! ${tip} =~ ${SHA_RE} ]]; then
    emit false "main's tip could not be read"
    return 0
  fi
  if [[ ${tip} == "${sha}" ]]; then
    emit false "this commit is the tip of main"
    return 0
  fi
  if [[ ${status} == ahead ]]; then
    emit true "superseded: main's tip is already ${tip:0:12}, whose own run reports the verdict for \
a tree that contains this commit"
    return 0
  fi
  emit false "this commit is not an ancestor of main's tip ${tip:0:12} (compare says \
'${status}', force push?)"
}

gather() {
  local event="$1" ref="$2" sha="$3" repo="${GH_REPO:-}" tip='' status=''
  if [[ ${event} == push && ${ref} == refs/heads/main ]]; then
    if ! tip="$(gh api "repos/${repo}/commits/main" --jq '.sha')"; then
      tip=''
    fi
    if [[ ${tip} =~ ${SHA_RE} && ${tip} != "${sha}" ]]; then
      if ! status="$(gh api "repos/${repo}/compare/${sha}...${tip}" --jq '.status')"; then
        status=''
      fi
    fi
  fi
  decide "${event}" "${ref}" "${sha}" "${tip}" "${status}"
}

usage() {
  printf 'usage: %s --check <event> <ref> <sha>\n' "$0" >&2
  printf '       %s --decide <event> <ref> <sha> <tip> <status>\n' "$0" >&2
  exit 2
}

case "${1:-}" in
  --check)
    [[ $# -eq 4 ]] || usage
    gather "$2" "$3" "$4"
    ;;
  --decide)
    [[ $# -eq 6 ]] || usage
    decide "$2" "$3" "$4" "$5" "$6"
    ;;
  *)
    usage
    ;;
esac
