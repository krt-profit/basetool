#!/usr/bin/env bash
#
# Guards docs/adr/ against the numbering mistake parallel branches make
# structurally: two branches each pick "the next free number" from whatever
# their own checkout can see, and both land on the same one.
#
# It has happened four times in this repository. 0060 was caught by hand
# before it landed (the monitoring-stack ADR was renumbered to 0072 on its way
# to main). 0154 was not: two different accepted decisions carried that number
# on main from 2026-09-02 until 2026-09-13, because they reached main in the
# same release commit. 0165 was named by a merged ADR and by an ADR on an open
# PR at the same time. 0169 was taken by a branch that had not been pushed, so
# no sweep over refs could see it. The standing ones are resolved by ADR-0171;
# this script is the part that stops the next one.
#
# Two checks, mirroring scripts/check-flyway-migrations.sh:
#
#   1. Duplicate numbers -- two files in the ADR directory sharing a four-digit
#      prefix. An ADR's number is its identity: it is how specs, Javadoc,
#      CHANGELOG entries, other ADRs and the knowledge base refer to it, so a
#      number naming two decisions makes every one of those references
#      ambiguous, and renumbering later breaks all of them at once.
#
#   2. Numbers already claimed on the base branch -- an ADR this branch adds,
#      or renumbers into, whose number is already taken on the base by a
#      DIFFERENT file. This is
#      the collision proper, and it is invisible to the branch that created it:
#      both branches are green in isolation and only the merge produces the
#      duplicate. Comparing against a freshly fetched base tip turns that into
#      a PR failure as soon as the sibling lands.
#
# Deliberately NOT checked: that a new number is greater than the highest on
# the base. Flyway needs monotonic versions; ADRs do not, and gaps here are
# normal and transient -- an open PR routinely holds a number below main's tip
# (0164 was held by an open PR while main was already at 0165). Requiring
# monotonic growth would force renumbering of ADRs that collide with nothing.
#
# Known limits, stated rather than papered over:
#   - Check 2 only fires once the sibling has actually landed on the base AND
#     this branch's CI re-runs. Two PRs that both take a free number and merge
#     back to back without a re-run still produce a duplicate on main -- check 1
#     then fails the push-to-main run immediately, which is the same day rather
#     than the same minute.
#   - Nothing stops two *open* branches from choosing the same free number in
#     the first place, and a branch that has not been pushed is invisible to
#     every check here and to any sweep over refs -- 0169 was claimed exactly
#     that way. Preventing it needs a claim at the moment of picking, which is
#     a habit (claim the number at push time, and read the knowledge base's
#     decision index, which lists numbers held by work in flight), not a check.
#
# Usage:
#   scripts/check-adr-numbering.sh
#   ADR_BASE_REF=origin/main scripts/check-adr-numbering.sh
#
# Configuration via environment variables:
#   ADR_DIR       Directory to scan (default: docs/adr). Relative to the
#                 repository root.
#   ADR_BASE_REF  Git ref for the base branch tip (e.g. "origin/main"). When
#                 unset or not resolvable, check 2 is skipped and only the
#                 duplicate check runs, so a quick local pass needs no fetch.
#
# Emits GitHub Actions `::error` / `::notice` annotations; exits non-zero if
# either check finds a violation (both always run, so one invocation reports
# everything).

set -euo pipefail

ADR_DIR="${ADR_DIR:-docs/adr}"
BASE_REF="${ADR_BASE_REF:-}"

# Anchor at the repository root so a relative ADR_DIR resolves the same way
# from CI, a git hook, or an interactive shell.
if repo_root=$(git rev-parse --show-toplevel 2>/dev/null); then
  cd "$repo_root"
fi

if [[ ! -d "$ADR_DIR" ]]; then
  echo "::error title=ADR check::ADR directory not found: ${ADR_DIR}"
  exit 1
fi

# "0154-self-enrolment-....md" -> "0154". Only files already matched against
# the four-digit-prefix pattern below are passed in here.
adr_number() {
  printf '%s' "${1:0:4}"
}

# ---------------------------------------------------------------------------
# Check 1: no two files in the working tree share a number.
# ---------------------------------------------------------------------------
mapfile -t files < <(
  find "$ADR_DIR" -maxdepth 1 -type f -name '*.md' |
    sed 's#.*/##' |
    grep -E '^[0-9]{4}-.*\.md$' |
    sort || true
)

if [[ ${#files[@]} -eq 0 ]]; then
  echo "::notice title=ADR check::No numbered ADRs found in ${ADR_DIR}."
  exit 0
fi

failed=0
declare -A number_to_file=()

for f in "${files[@]}"; do
  n=$(adr_number "$f")
  if [[ -n "${number_to_file[$n]:-}" ]]; then
    echo "::error title=Duplicate ADR number::ADR-${n} is claimed by both '${number_to_file[$n]}' and '${f}'. An ADR number is its identity -- renumber the less-referenced one to the next unused number and update every inbound reference (docs/adr/README.md, docs/specs/, Javadoc, CHANGELOG.md, the knowledge base)."
    failed=1
  else
    number_to_file[$n]="$f"
  fi
done

# ---------------------------------------------------------------------------
# Check 2: no ADR added or renumbered on this branch takes a number the base
# already uses.
# ---------------------------------------------------------------------------
if [[ -z "$BASE_REF" ]]; then
  echo "::notice title=ADR check::ADR_BASE_REF unset -- skipping the base-collision check (duplicate check still ran)."
elif ! git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
  echo "::notice title=ADR check::Base ref '${BASE_REF}' not resolvable -- skipping the base-collision check."
else
  mapfile -t base_files < <(
    git ls-tree -r --name-only "$BASE_REF" -- "$ADR_DIR" 2>/dev/null |
      sed 's#.*/##' |
      grep -E '^[0-9]{4}-.*\.md$' || true
  )

  if [[ ${#base_files[@]} -eq 0 ]]; then
    echo "::notice title=ADR check::Base ref '${BASE_REF}' has no ADRs -- every added ADR trivially claims a free number."
  else
    declare -A base_names=() base_number_to_file=()
    for bf in "${base_files[@]}"; do
      base_names["$bf"]=1
      bn=$(adr_number "$bf")
      # The base itself may already carry a duplicate -- that is how 0154 got
      # onto main. Keep the first filename seen; check 1 is what reports it.
      [[ -n "${base_number_to_file[$bn]:-}" ]] || base_number_to_file[$bn]="$bf"
    done

    # Files this branch adds -- or renumbers into -- relative to where it
    # diverged from the base. Diffing from the merge-base rather than the base
    # tip avoids flagging ADRs that merely arrived on the base after the branch
    # started. `R` is in the filter because a renumber IS a claim on the
    # destination number: git reports it as a rename, and `--name-only` prints
    # the destination path, which is the number being claimed. Filtering on `A`
    # alone (as the Flyway checker does, where migrations are never renamed)
    # would let a renumber land on an occupied number unseen.
    merge_base=$(git merge-base "$BASE_REF" HEAD 2>/dev/null || printf '%s' "$BASE_REF")
    mapfile -t added_files < <(
      git diff --diff-filter=AR --name-only "$merge_base" HEAD -- "$ADR_DIR" 2>/dev/null |
        sed 's#.*/##' |
        grep -E '^[0-9]{4}-.*\.md$' || true
    )

    if [[ ${#added_files[@]} -eq 0 ]]; then
      echo "::notice title=ADR check::No new ADRs added relative to '${BASE_REF}'."
    else
      evaluated=0
      for af in "${added_files[@]}"; do
        # An addition already on the base under the SAME filename is this
        # branch's own ADR, landed while the run was in flight (a squash merge
        # creates a new commit, so the merge-base never advances and the file
        # keeps showing as added). Comparing it against itself would report a
        # collision with its own number.
        if [[ -n "${base_names[$af]:-}" ]]; then
          echo "::notice title=ADR check::'${af}' is already present on '${BASE_REF}' -- the branch's own ADR has landed on the base (e.g. a squash merge while this run was in flight); not treated as a new addition."
          continue
        fi
        evaluated=$((evaluated + 1))
        an=$(adr_number "$af")
        claimed="${base_number_to_file[$an]:-}"
        if [[ -n "$claimed" ]]; then
          echo "::error title=ADR number already claimed::New ADR '${af}' takes ADR-${an}, which '${claimed}' already holds on '${BASE_REF}'. Renumber this branch's ADR to a number free on the base and update its title line, the docs/adr/README.md index row and every reference to it."
          failed=1
        else
          echo "  ok: ${af} (ADR-${an}) is free on '${BASE_REF}'"
        fi
      done
      if [[ "$evaluated" -eq 0 ]]; then
        echo "::notice title=ADR check::No new ADRs added relative to '${BASE_REF}' (all additions are already present on the base)."
      fi
    fi
  fi
fi

if [[ $failed -eq 1 ]]; then
  echo
  echo "::error title=ADR check failed::Fix the ADR numbering above. See docs/adr/README.md > 'Numbering' and ADR-0171."
  exit 1
fi

echo
echo "ADR numbering OK: ${#files[@]} ADR(s), no duplicate numbers."
