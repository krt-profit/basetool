#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

ADR_DIR="${ADR_DIR:-docs/adr}"

readonly TEMPLATE_FILE="0000-template.md"

if repo_root=$(git rev-parse --show-toplevel 2>/dev/null); then
  cd "$repo_root"
fi

if [[ ! -d "$ADR_DIR" ]]; then
  echo "::error title=ADR registry::ADR directory not found: ${ADR_DIR}"
  exit 1
fi

README="${ADR_DIR}/README.md"
if [[ ! -f "$README" ]]; then
  echo "::error title=ADR registry::ADR index not found: ${README}"
  exit 1
fi

failed=0

unescaped_pipe_offsets() {
  local masked="${1//\\|/\\@}"
  local rest="$masked" pos=0 head
  while [[ $rest == *'|'* ]]; do
    head="${rest%%|*}"
    pos=$((pos + ${#head}))
    printf '%s\n' "$pos"
    pos=$((pos + 1))
    rest="${rest#*|}"
  done
}

snippet_around() {
  local line="$1" at="$2" start len
  start=$((at - 45))
  if ((start < 0)); then
    start=0
  fi
  len=$((at - start + 46))
  printf '%s' "${line:start:len}"
}

declare -A row_seen=()
rows=0
lineno=0

while IFS= read -r line || [[ -n $line ]]; do
  lineno=$((lineno + 1))
  line="${line%$'\r'}"

  [[ $line =~ ^\|\ \[[0-9]{4}\] ]] || continue
  rows=$((rows + 1))

  number="${line:3:4}"

  mapfile -t offsets < <(unescaped_pipe_offsets "$line")
  if [[ ${#offsets[@]} -ne 4 ]]; then
    cells=$((${#offsets[@]} - 1))
    echo "::error title=ADR index row does not render::${README}:${lineno}: the row for ADR-${number} splits into ${cells} cells, not 3. An unescaped '|' in the prose is a cell separator even inside a code span, and GFM keeps the first three cells and DISCARDS the rest -- so the status column ends up showing prose (or nothing) and the real status is dropped. Escape it as '\\|'."
    last=$((${#offsets[@]} - 3))
    for ((i = 2; i <= last; i++)); do
      echo "    ${README}:${lineno}: ...$(snippet_around "$line" "${offsets[i]}")..."
    done
    failed=1
  fi

  if [[ -n ${row_seen[$number]:-} ]]; then
    echo "::error title=Duplicate ADR index row::${README}:${lineno}: ADR-${number} already has a row at line ${row_seen[$number]}. Two rows for one number make the index ambiguous about which decision that number names; keep the accurate one and delete the other."
    failed=1
  else
    row_seen[$number]="$lineno"
  fi

  if [[ $line =~ ^\|\ \[([0-9]{4})\]\(([^\)]+)\) ]]; then
    target="${BASH_REMATCH[2]}"
    if [[ ! -f "${ADR_DIR}/${target}" ]]; then
      echo "::error title=ADR index link is dangling::${README}:${lineno}: the row for ADR-${number} links to '${target}', which does not exist in ${ADR_DIR}/. A renamed ADR keeps its number but changes its filename; update the link."
      failed=1
    elif [[ ${target:0:4} != "$number" ]]; then
      echo "::error title=ADR index link points at another ADR::${README}:${lineno}: the row is labelled ADR-${number} but links to '${target}'. One of the two is a copy-paste; fix whichever is wrong."
      failed=1
    fi
  else
    echo "::error title=ADR index row is malformed::${README}:${lineno}: the row for ADR-${number} does not open with a '[NNNN](NNNN-title.md)' link. Every row's first cell is the link that takes a reader to the decision."
    failed=1
  fi
done <"$README"

if [[ $rows -eq 0 ]]; then
  echo "::error title=ADR registry::No index rows found in ${README}. A row looks like '| [0001](0001-title.md) | summary | Accepted |'."
  exit 1
fi

mapfile -t files < <(
  find "$ADR_DIR" -maxdepth 1 -type f -name '*.md' |
    sed 's#.*/##' |
    grep -E '^[0-9]{4}-.*\.md$' |
    sort || true
)

indexed=0
for f in "${files[@]}"; do
  [[ $f == "$TEMPLATE_FILE" ]] && continue
  n="${f:0:4}"
  if [[ -z ${row_seen[$n]:-} ]]; then
    echo "::error title=ADR missing from the index::'${f}' has no row in ${README}. Add one to the '## Index' table -- the index is how a decision is found by anyone who cannot already name it, and the numbering gate cannot see this (it reads filenames only)."
    failed=1
  else
    indexed=$((indexed + 1))
  fi
done

if [[ $failed -eq 1 ]]; then
  echo
  echo "::error title=ADR registry check failed::Fix the ADR index above. See docs/adr/README.md > 'Index'."
  exit 1
fi

echo
echo "ADR registry OK: ${rows} index row(s), ${indexed} ADR(s) indexed, every row splits into exactly 3 cells and links to a real decision."
