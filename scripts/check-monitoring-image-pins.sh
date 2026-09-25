#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
# image-pin-gate: ignore-file
set -euo pipefail

COMPOSE_FILE="docker-compose.monitoring.yml"

EXCLUDED_PATHS_REGEX='^(CHANGELOG\.md$|CHANGELOG-ARCHIVE\.md$|docs/adr/|docs/archive/|docker-compose[^/]*\.yml$)'

EXEMPTION_HEADER_LINES=10

fix_mode=0
case "${1:-}" in
  --fix) fix_mode=1 ;;
  "") ;;
  *)
    printf 'usage: %s [--fix]\n' "$0" >&2
    exit 2
    ;;
esac

if [ ! -f "$COMPOSE_FILE" ]; then
  printf 'error: %s not found — run this from the repository root.\n' "$COMPOSE_FILE" >&2
  exit 2
fi

escape_ere() {
  printf '%s' "$1" | sed -e 's/[.]/\\./g' -e 's/[+]/\\+/g'
}

escape_sed_pattern() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/[.[*^$]/\\&/g' -e 's/#/\\#/g'
}

escape_sed_replacement() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/#/\\#/g'
}

declare -A expected_tag=()
while IFS= read -r image_ref; do
  [ -n "$image_ref" ] || continue
  image_ref="${image_ref%@*}"
  case "$image_ref" in
    *:*) ;;
    *) continue ;;
  esac
  expected_tag["${image_ref%:*}"]="${image_ref##*:}"
done < <(sed -nE 's/^[[:space:]]*image:[[:space:]]*"?([^"[:space:]#]+)"?.*$/\1/p' "$COMPOSE_FILE")

if [ ${#expected_tag[@]} -eq 0 ]; then
  printf 'error: no image: pins parsed out of %s — the gate would pass vacuously.\n' "$COMPOSE_FILE" >&2
  exit 2
fi

if ! tracked_files="$(git ls-files)"; then
  printf 'error: git ls-files failed — cannot enumerate the files to check.\n' >&2
  exit 2
fi
mapfile -t path_included < <(
  printf '%s\n' "$tracked_files" | grep -Ev "$EXCLUDED_PATHS_REGEX" || true
)
if [ ${#path_included[@]} -eq 0 ]; then
  printf 'error: no tracked files to check — the enumeration is broken, not the repository.\n' >&2
  exit 2
fi

mapfile -t candidate_files < <(
  printf '%s\0' "${path_included[@]}" | xargs -0 grep -lI '' -- 2>/dev/null || true
)
if [ ${#candidate_files[@]} -eq 0 ]; then
  printf 'error: no text files to check — the enumeration is broken, not the repository.\n' >&2
  exit 2
fi

declare -A exempt_file=()
while IFS= read -r exempt; do
  [ -n "$exempt" ] || continue
  exempt_file["$exempt"]=1
done < <(awk -v limit="$EXEMPTION_HEADER_LINES" \
  'FNR <= limit && (/image-pin-gate: ignore-file/ || (/Doc type:/ && /Historical/)) \
     && !seen[FILENAME]++ { print FILENAME }' \
  "${candidate_files[@]}" || true)

scan_files=()
for candidate in "${candidate_files[@]}"; do
  [ -z "${exempt_file["$candidate"]:-}" ] || continue
  scan_files+=("$candidate")
done
if [ ${#scan_files[@]} -eq 0 ]; then
  printf 'error: every tracked file is exempt — the gate would pass vacuously.\n' >&2
  exit 2
fi

printf 'Checking %d monitoring image pin(s) across %d tracked file(s).\n' \
  "${#expected_tag[@]}" "${#scan_files[@]}"

drift_count=0
for repository in "${!expected_tag[@]}"; do
  want="${expected_tag[$repository]}"
  repository_ere="$(escape_ere "$repository")"
  while IFS= read -r hit; do
    [ -n "$hit" ] || continue
    file="${hit%%:*}"
    rest="${hit#*:}"
    line="${rest%%:*}"
    found_ref="${rest#*:}"
    found_tag="${found_ref##*:}"
    [ "$found_tag" = "$want" ] && continue
    drift_count=$((drift_count + 1))
    printf '  %s:%s  %s:%s  ->  %s\n' "$file" "$line" "$repository" "$found_tag" "$want"
    if [ "$fix_mode" -eq 1 ]; then
      search="$(escape_sed_pattern "${repository}:${found_tag}")"
      replacement="$(escape_sed_replacement "${repository}:${want}")"
      sed -i "s#${search}#${replacement}#g" "$file"
    fi
  done < <(grep -HnEoI "${repository_ere}:[A-Za-z0-9][A-Za-z0-9._-]*" "${scan_files[@]}" || true)
done

if [ "$drift_count" -eq 0 ]; then
  printf 'OK — every documented monitoring image pin matches %s.\n' "$COMPOSE_FILE"
  exit 0
fi

if [ "$fix_mode" -eq 1 ]; then
  printf '\nRewrote %d stale pin(s). Review the diff, then commit.\n' "$drift_count"
  exit 0
fi

cat >&2 <<EOF

Found $drift_count documented image pin(s) that disagree with $COMPOSE_FILE.

A pinned tag in a runbook command is what an operator pastes during an incident. When it names a
version that is not deployed, the command still succeeds — against the wrong binary — so the wrong
answer is silent.

Fix them all with:

    scripts/check-monitoring-image-pins.sh --fix
EOF
exit 1
