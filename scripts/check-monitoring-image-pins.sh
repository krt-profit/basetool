#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Verifies that every pinned monitoring image tag quoted in the Markdown docs matches the
# authoritative tag in docker-compose.monitoring.yml.
#
# WHY THIS GATE EXISTS
# --------------------
# Dependabot bumps the image tags in the compose file; nothing used to update the docs. That went
# unnoticed for months: on 2026-08-02 the monitoring runbook's version table was stale on 8 of its
# 12 rows, and the rollout runbook's table on a similar number.
#
# The table was never the sharp edge, though. The docs also carry *copy-pasteable* commands that
# pin a tag, e.g.
#
#     docker run --rm -v "$PWD/monitoring/alloy:/cfg" grafana/alloy:v1.17.1 fmt /cfg/config.alloy
#
# An operator who pastes that validates their config against a version they do not run — and gets a
# green result that means nothing. That is a silent wrong answer during exactly the kind of incident
# the runbook exists for. So the pins stay (a reproducible command should be pinned) and this gate
# keeps them honest instead.
#
# The hand-maintained "Version" COLUMNS were deleted rather than gated: a column that duplicates the
# compose file adds nothing over `grep image: docker-compose.monitoring.yml`, and it cannot be true
# about the *running* stack anyway (see the compose-definition apply gap the runbooks document).
#
# USAGE
#   scripts/check-monitoring-image-pins.sh          # verify; non-zero exit on drift
#   scripts/check-monitoring-image-pins.sh --fix    # rewrite the docs to the compose tags
#
set -euo pipefail

COMPOSE_FILE="docker-compose.monitoring.yml"

# Historical records: a release note naming the version that shipped at the time is CORRECT and must
# never be rewritten to today's tag.
EXCLUDED_DOCS_REGEX='^(CHANGELOG\.md|CHANGELOG-ARCHIVE\.md)$'

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

# Escape a container-image repository for use inside an ERE. Only '.' and '+' are metacharacters
# that realistically occur in a repository name ('/' and '-' are literal in ERE).
escape_ere() {
  printf '%s' "$1" | sed -e 's/[.]/\\./g' -e 's/[+]/\\+/g'
}

# repository -> tag, straight from the compose file. Deriving the set means a newly added monitoring
# image is covered automatically, with no second list to forget.
declare -A expected_tag=()
while IFS= read -r image_ref; do
  [ -n "$image_ref" ] || continue
  # Every monitoring image is pinned as repository:tag; a digest pin or an unpinned image is a
  # separate policy problem, so skip rather than guess.
  case "$image_ref" in
    *@*) continue ;;
    *:*) ;;
    *) continue ;;
  esac
  expected_tag["${image_ref%:*}"]="${image_ref##*:}"
done < <(sed -nE 's/^[[:space:]]*image:[[:space:]]*"?([^"[:space:]#]+)"?.*$/\1/p' "$COMPOSE_FILE")

if [ ${#expected_tag[@]} -eq 0 ]; then
  printf 'error: no image: pins parsed out of %s — the gate would pass vacuously.\n' "$COMPOSE_FILE" >&2
  exit 2
fi

mapfile -t doc_files < <(git ls-files '*.md' | grep -Ev "$EXCLUDED_DOCS_REGEX" || true)
if [ ${#doc_files[@]} -eq 0 ]; then
  printf 'No Markdown files tracked; nothing to check.\n'
  exit 0
fi

printf 'Checking %d monitoring image pin(s) across %d Markdown file(s).\n' \
  "${#expected_tag[@]}" "${#doc_files[@]}"

drift_count=0
for repository in "${!expected_tag[@]}"; do
  want="${expected_tag[$repository]}"
  repository_ere="$(escape_ere "$repository")"
  # A tag may carry dots, dashes and underscores; stop at anything else so a trailing backslash or
  # quote in the surrounding command is not swallowed into the tag.
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
      sed -i "s|${repository}:${found_tag}|${repository}:${want}|g" "$file"
    fi
  done < <(grep -nEo "${repository_ere}:[A-Za-z0-9][A-Za-z0-9._-]*" "${doc_files[@]}" || true)
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
