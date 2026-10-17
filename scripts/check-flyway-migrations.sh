#!/usr/bin/env bash

set -euo pipefail

MIGRATION_DIR="${FLYWAY_MIGRATION_DIR:-backend/src/main/resources/db/migration}"
BASE_REF="${FLYWAY_BASE_REF:-}"
SKIP_SECTION="## Skipped version numbers"

if repo_root=$(git rev-parse --show-toplevel 2>/dev/null); then
  cd "$repo_root"
fi

if [[ ! -d "$MIGRATION_DIR" ]]; then
  echo "::error title=Flyway check::Migration directory not found: ${MIGRATION_DIR}"
  exit 1
fi

SKIP_LIST="${FLYWAY_SKIP_LIST:-${MIGRATION_DIR}/README.md}"

flyway_version() {
  local name="$1"
  name="${name#V}"
  name="${name%%__*}"
  printf '%s' "${name//_/.}"
}

version_gt() {
  [[ "$1" == "$2" ]] && return 1
  local highest
  highest=$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)
  [[ "$highest" == "$1" ]]
}

skipped_versions() {
  [[ -f "$SKIP_LIST" ]] || return 0
  awk -v section="$SKIP_SECTION" '
    { sub(/\r$/, "") }
    /^## / { in_section = ($0 == section); next }
    in_section && match($0, /^- \*\*V[0-9]+\*\*/) { print substr($0, 6, RLENGTH - 7) }
  ' "$SKIP_LIST"
}

mapfile -t files < <(
  find "$MIGRATION_DIR" -maxdepth 1 -type f -name 'V*__*.sql' -printf '%f\n' | sort
)

if [[ ${#files[@]} -eq 0 ]]; then
  echo "::notice title=Flyway check::No versioned migrations found in ${MIGRATION_DIR}."
  exit 0
fi

failed=0
declare -A version_to_file

for f in "${files[@]}"; do
  v=$(flyway_version "$f")
  if [[ -n "${version_to_file[$v]:-}" ]]; then
    echo "::error title=Duplicate Flyway version::Version ${v} is claimed by both '${version_to_file[$v]}' and '${f}'. Renumber one of them to the next unused integer."
    failed=1
  else
    version_to_file[$v]="$f"
  fi
done

declare -A present=()
min_version=""
max_version=""
for v in "${!version_to_file[@]}"; do
  [[ "$v" =~ ^[0-9]+$ ]] || continue
  n=$((10#$v))
  present[$n]="${version_to_file[$v]}"
  if [[ -z "$min_version" || $n -lt $min_version ]]; then min_version=$n; fi
  if [[ -z "$max_version" || $n -gt $max_version ]]; then max_version=$n; fi
done

declare -A skipped=()
while IFS= read -r s; do
  [[ -n "$s" ]] && skipped[$((10#$s))]=1
done < <(skipped_versions)

if [[ -n "$max_version" ]]; then
  for ((n = min_version; n <= max_version; n++)); do
    if [[ -z "${present[$n]:-}" && -z "${skipped[$n]:-}" ]]; then
      echo "::error title=Unexplained Flyway gap::No migration claims V${n}, and '${SKIP_LIST}' does not list it under '${SKIP_SECTION}'. If the number was given up (renumbered or dropped before its PR merged), add '- **V${n}** — <reason>' to that section; if an open PR still claims it, merge that PR first."
      failed=1
    fi
  done
fi

for s in "${!skipped[@]}"; do
  if [[ -n "${present[$s]:-}" ]]; then
    echo "::error title=Stale skipped Flyway version::V${s} is listed under '${SKIP_SECTION}' in '${SKIP_LIST}', but '${present[$s]}' claims it. Remove the entry."
    failed=1
  elif [[ -z "$max_version" || $s -gt $max_version ]]; then
    echo "::error title=Stale skipped Flyway version::V${s} is listed under '${SKIP_SECTION}' in '${SKIP_LIST}', but it lies above the highest migration. List only numbers below the tip."
    failed=1
  fi
done

if [[ -z "$BASE_REF" ]]; then
  echo "::notice title=Flyway check::FLYWAY_BASE_REF unset — skipping the new-migration ordering check (duplicate check still ran)."
elif ! git rev-parse --verify --quiet "$BASE_REF" >/dev/null; then
  echo "::notice title=Flyway check::Base ref '${BASE_REF}' not resolvable — skipping the new-migration ordering check."
else
  mapfile -t base_files < <(
    git ls-tree -r --name-only "$BASE_REF" -- "$MIGRATION_DIR" 2>/dev/null \
      | sed 's#.*/##' \
      | grep -E '^V.*__.*\.sql$' || true
  )

  if [[ ${#base_files[@]} -eq 0 ]]; then
    echo "::notice title=Flyway check::Base ref '${BASE_REF}' has no migrations — every added migration is trivially in order."
  else
    declare -A base_names=()
    base_versions=()
    for bf in "${base_files[@]}"; do
      base_names["$bf"]=1
      base_versions+=("$(flyway_version "$bf")")
    done
    max_base=$(printf '%s\n' "${base_versions[@]}" | sort -V | tail -n1)

    merge_base=$(git merge-base "$BASE_REF" HEAD 2>/dev/null || printf '%s' "$BASE_REF")
    mapfile -t added_files < <(
      git diff --diff-filter=A --name-only "$merge_base" HEAD -- "$MIGRATION_DIR" 2>/dev/null \
        | sed 's#.*/##' \
        | grep -E '^V.*__.*\.sql$' || true
    )

    if [[ ${#added_files[@]} -eq 0 ]]; then
      echo "::notice title=Flyway check::No new migrations added relative to '${BASE_REF}'."
    else
      new_files=()
      for af in "${added_files[@]}"; do
        if [[ -n "${base_names[$af]:-}" ]]; then
          echo "::notice title=Flyway check::'${af}' is already present on '${BASE_REF}' — the branch's own migration has landed on the base (e.g. a squash merge while this run was in flight); not treated as a new addition."
        else
          new_files+=("$af")
        fi
      done

      if [[ ${#new_files[@]} -eq 0 ]]; then
        echo "::notice title=Flyway check::No new migrations added relative to '${BASE_REF}' (all additions are already present on the base)."
      else
        echo "Highest version on '${BASE_REF}': V${max_base}"
        for af in "${new_files[@]}"; do
          av=$(flyway_version "$af")
          if version_gt "$av" "$max_base"; then
            echo "  ok: ${af} (V${av}) > V${max_base}"
          else
            echo "::error title=Out-of-order Flyway migration::New migration '${af}' (V${av}) does not sort after the current highest version on '${BASE_REF}' (V${max_base}). Renumber it to a version greater than V${max_base} — rebase onto the latest base and re-pick the next free integer."
            failed=1
          fi
        done
      fi
    fi
  fi
fi

if [[ $failed -eq 1 ]]; then
  echo
  echo "::error title=Flyway check failed::Fix the migration numbering above. See backend/src/main/resources/db/migration/README.md > 'Hard rules'."
  exit 1
fi

echo
echo "Flyway migration numbering OK: ${#files[@]} migration(s), no duplicates, ${#skipped[@]} listed gap(s) and no other, all additions in order."
