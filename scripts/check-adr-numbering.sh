#!/usr/bin/env bash

set -euo pipefail

ADR_DIR="${ADR_DIR:-docs/adr}"
BASE_REF="${ADR_BASE_REF:-}"

if repo_root=$(git rev-parse --show-toplevel 2>/dev/null); then
  cd "$repo_root"
fi

if [[ ! -d "$ADR_DIR" ]]; then
  echo "::error title=ADR check::ADR directory not found: ${ADR_DIR}"
  exit 1
fi

adr_number() {
  printf '%s' "${1:0:4}"
}

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
      [[ -n "${base_number_to_file[$bn]:-}" ]] || base_number_to_file[$bn]="$bf"
    done

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
