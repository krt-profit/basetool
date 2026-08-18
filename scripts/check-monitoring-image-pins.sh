#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# image-pin-gate: ignore-file — this script quotes a deliberately stale tag as an ILLUSTRATION
# below, and rewriting an illustration to today's tag would erase the very point it makes.
#
# Verifies that every pinned monitoring image tag quoted anywhere in the tracked sources matches the
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
# WHY THE SCAN IS NOT LIMITED TO MARKDOWN
# ---------------------------------------
# It was, until 2026-08-16, and the hazard simply moved: the same copy-pasteable command lives in the
# header comment of every promtool test under monitoring/prometheus/tests/, where nothing gated it.
# Nine of the ten files there had drifted, across three different Prometheus versions — the exact
# failure this gate was written to prevent, in the files an operator reaches for while validating
# alert rules. A pinned tag is a pinned tag whatever the file extension, so the scan now covers every
# tracked text file and the exemptions carry the nuance instead.
#
# USAGE
#   scripts/check-monitoring-image-pins.sh          # verify; non-zero exit on drift
#   scripts/check-monitoring-image-pins.sh --fix    # rewrite the drifted pins to the compose tags
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
#   docker-compose*.yml
#       The compose files are the AUTHORITY the expected tags are parsed out of. Scanning them would
#       compare each tag against itself — always green, pure noise — and the moment a second compose
#       file pinned a different tag of the same repository on purpose, the gate would flag the source
#       of truth. Excluded by path because "is this the authority?" is a property of the file, not
#       something a marker inside it should have to assert.
#
# Individual files anywhere in the tree opt out through an inline marker instead of through a name
# list here, so a future exemption needs no edit to this script:
#
#   "Doc type: ... Historical ..."  the repository's existing front-matter convention for a frozen
#       document (see docs/BANK_PLAN.md and docs/REFINERY_SCREENSHOT_IMPORT_PLAN.md, both frozen
#       after implementation). A future incident or post-mortem write-up is covered the moment it
#       carries that header. The repository tracks no incident/post-mortem directory today, which is
#       why none is listed above; add its prefix here if one is ever introduced.
#
#   "image-pin-gate: ignore-file"   this gate's own marker, for a file whose tags are deliberately
#       not real pins. Two carry it today and both belong to the gate itself: this script, whose
#       header quotes a stale tag as an illustration, and its test suite, whose fixtures pin wrong
#       tags on purpose — "repairing" those would make the suite pass vacuously, which is the one
#       outcome a regression suite must never have.
EXCLUDED_PATHS_REGEX='^(CHANGELOG\.md$|CHANGELOG-ARCHIVE\.md$|docs/adr/|docker-compose[^/]*\.yml$)'

# How far into a file the two exemption markers are looked for. Deliberately small, and NOT widened
# when the second marker was added: docs/specs/INDEX.md quotes the "Doc type:" header deep in its
# body as an authoring example, and that file is a living index that must stay in scope. A file that
# merely DISCUSSES a marker must not exempt itself by talking about it, so a real marker belongs
# immediately under the licence header — above the prose, not inside it.
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

if ! tracked_files="$(git ls-files)"; then
  printf 'error: git ls-files failed — cannot enumerate the files to check.\n' >&2
  exit 2
fi
mapfile -t path_included < <(
  printf '%s\n' "$tracked_files" | grep -Ev "$EXCLUDED_PATHS_REGEX" || true
)
if [ ${#path_included[@]} -eq 0 ]; then
  # Not "nothing to drift, pass" — this repository always tracks files, so an empty list means the
  # enumeration broke (no git on PATH, run outside a work tree, a mangled exclude regex). A gate
  # that goes green when it could not look is worse than no gate: it reports "checked, clean".
  printf 'error: no tracked files to check — the enumeration is broken, not the repository.\n' >&2
  exit 2
fi

# Binary files cannot carry a copy-pasteable command, and feeding them to awk below is at best
# noise. `grep -I` (= --binary-files=without-match) treats a binary as non-matching, so matching the
# always-true empty pattern prints exactly the text files. A zero-byte file has no line to match and
# drops out here too — it cannot carry a pin either, so that is harmless. xargs chunks the list, so
# this holds on a repository far larger than this one.
mapfile -t candidate_files < <(
  printf '%s\0' "${path_included[@]}" | xargs -0 grep -lI '' -- 2>/dev/null || true
)
if [ ${#candidate_files[@]} -eq 0 ]; then
  printf 'error: no text files to check — the enumeration is broken, not the repository.\n' >&2
  exit 2
fi

# Second exclusion pass: files that opt out individually, in one awk over the whole list rather than
# a `head | grep` per file — under `set -o pipefail` a head that exits on SIGPIPE would turn a match
# into a spurious pipeline failure. Two markers, both only honoured in the first
# EXEMPTION_HEADER_LINES lines so a file that merely *discusses* one (docs/specs/INDEX.md quotes the
# front-matter convention deep in its body as an authoring example) is not exempted by talking:
#
#   "Doc type: ... Historical ..."   the repository's existing front-matter convention for a frozen
#                                    document (see the EXCLUDED_PATHS_REGEX comment).
#   "image-pin-gate: ignore-file"    this gate's own marker, for a file whose tags are deliberately
#                                    not real pins. Two exist today, both belonging to the gate: the
#                                    illustrative stale command in this script's own header, and the
#                                    fixtures in its test suite, which pin wrong tags ON PURPOSE and
#                                    would otherwise be "repaired" into passing vacuously.
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
  # Same reasoning as above: every tracked file being exempt means the exemption logic broke.
  printf 'error: every tracked file is exempt — the gate would pass vacuously.\n' >&2
  exit 2
fi

printf 'Checking %d monitoring image pin(s) across %d tracked file(s).\n' \
  "${#expected_tag[@]}" "${#scan_files[@]}"

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
