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

# HISTORICAL RECORDS ARE NEVER REWRITTEN
# --------------------------------------
# Some documents state what was true at the moment they were written. For those, a tag that differs
# from today's compose file is not staleness — it is the record doing its job. Rewriting it (which
# is what --fix does, and what a red gate pressures a contributor into running) falsifies the record
# instead of refreshing a stale doc, so they are excluded from the scan entirely:
#
#   CHANGELOG.md / CHANGELOG-ARCHIVE.md
#       A release note naming the version that shipped in that release is correct forever, by
#       construction.
#
#   docs/adr/
#       An ADR records a decision taken at a point in time, and ADR-0076 already reads "pinned to
#       grafana/tempo:3.0.2". That sentence documents what was decided, not what is deployed. On the
#       next Tempo bump the gate would fail and its own advertised remedy would edit an ACCEPTED
#       decision record to name a version that decision never made — a retroactive rewrite of the
#       history the ADR process exists to preserve. An ADR that no longer matches reality is
#       superseded by a new ADR; it is never silently edited. Same footing as the changelog, hence
#       the same exclusion.
#
# Individual frozen documents outside those trees opt out through the repository's existing
# front-matter convention instead of through a name list here: a "Doc type: ... Historical ..."
# marker within the first few lines (see docs/BANK_PLAN.md and
# docs/REFINERY_SCREENSHOT_IMPORT_PLAN.md, both frozen after implementation) exempts the file. That
# is the inline escape hatch — a future incident or post-mortem write-up is covered the moment it
# carries that header, with no edit here. The repository tracks no incident/post-mortem directory
# today, which is why none is listed above; add its prefix here if one is ever introduced.
EXCLUDED_DOCS_REGEX='^(CHANGELOG\.md$|CHANGELOG-ARCHIVE\.md$|docs/adr/)'

# How far into a file the "Doc type:" front matter is looked for. Deliberately small: docs/specs/
# INDEX.md quotes the very same header deep in its body as an authoring example, and that file is a
# living index that must stay in scope.
HISTORICAL_HEADER_LINES=10

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

# Escape an arbitrary literal for the SEARCH half of `sed 's#…#…#'`: the BRE metacharacters plus the
# '#' delimiter. This is not theoretical — 'ghcr.io/google/cadvisor' contains dots today, and an
# unescaped '.' matches any character, so the --fix rewrite could hit neighbouring text that differs
# only in those positions. Backslash is escaped first so the backslashes introduced by the later
# expressions are not escaped a second time.
escape_sed_pattern() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/[.[*^$]/\\&/g' -e 's/#/\\#/g'
}

# Escape an arbitrary literal for the REPLACEMENT half of `sed 's#…#…#'`, where only '\', '&' (the
# whole match) and the '#' delimiter carry meaning. Kept adjacent to escape_sed_pattern: the two are
# only ever correct as a pair, against the same delimiter.
escape_sed_replacement() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/&/\\&/g' -e 's/#/\\#/g'
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

if ! tracked_docs="$(git ls-files '*.md')"; then
  printf 'error: git ls-files failed — cannot enumerate the docs to check.\n' >&2
  exit 2
fi
mapfile -t candidate_docs < <(
  printf '%s\n' "$tracked_docs" | grep -Ev "$EXCLUDED_DOCS_REGEX" || true
)
if [ ${#candidate_docs[@]} -eq 0 ]; then
  # Not "nothing to drift, pass" — this repository always tracks Markdown, so an empty list means
  # the enumeration broke (no git on PATH, run outside a work tree, a mangled exclude regex). A gate
  # that goes green when it could not look is worse than no gate: it reports "checked, clean".
  printf 'error: no Markdown files to check — the enumeration is broken, not the repository.\n' >&2
  exit 2
fi

# Second exclusion pass: individually frozen documents, recognised by their
# "Doc type: ... Historical ..." front matter (see the EXCLUDED_DOCS_REGEX comment). One awk over
# the whole list rather than a `head | grep` per file — under `set -o pipefail` a head that exits on
# SIGPIPE would turn a match into a spurious pipeline failure.
declare -A frozen_doc=()
while IFS= read -r frozen; do
  [ -n "$frozen" ] || continue
  frozen_doc["$frozen"]=1
done < <(awk -v limit="$HISTORICAL_HEADER_LINES" \
  'FNR <= limit && /Doc type:/ && /Historical/ && !seen[FILENAME]++ { print FILENAME }' \
  "${candidate_docs[@]}" || true)

doc_files=()
for candidate in "${candidate_docs[@]}"; do
  [ -z "${frozen_doc["$candidate"]:-}" ] || continue
  doc_files+=("$candidate")
done
if [ ${#doc_files[@]} -eq 0 ]; then
  # Same reasoning as above: every tracked document being exempt means the exemption logic broke.
  printf 'error: every Markdown file is exempt — the gate would pass vacuously.\n' >&2
  exit 2
fi

printf 'Checking %d monitoring image pin(s) across %d Markdown file(s).\n' \
  "${#expected_tag[@]}" "${#doc_files[@]}"

drift_count=0
for repository in "${!expected_tag[@]}"; do
  want="${expected_tag[$repository]}"
  repository_ere="$(escape_ere "$repository")"
  # A tag may carry dots, dashes and underscores; stop at anything else so a trailing backslash or
  # quote in the surrounding command is not swallowed into the tag.
  #
  # `grep -H` forces the `file:line:match` prefix the split below parses. Without it grep omits the
  # filename whenever the list happens to hold exactly one file, and the split would silently read
  # the line number as the filename — "the list has more than one entry" is not an invariant this
  # loop gets to assume, least of all once exclusions can shrink it.
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
      # Both halves are literals that must survive `sed` unchanged, so both are escaped for their
      # own side of the s command (see the two helpers).
      search="$(escape_sed_pattern "${repository}:${found_tag}")"
      replacement="$(escape_sed_replacement "${repository}:${want}")"
      sed -i "s#${search}#${replacement}#g" "$file"
    fi
  done < <(grep -HnEo "${repository_ere}:[A-Za-z0-9][A-Za-z0-9._-]*" "${doc_files[@]}" || true)
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
