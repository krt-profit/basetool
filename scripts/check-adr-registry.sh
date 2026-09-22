#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Verifies that the ADR index in docs/adr/README.md still agrees with docs/adr/ -- that every
# decision has a row, that every row points at a real ADR, and that every row splits into exactly
# three cells rather than silently losing its status to a stray pipe.
#
# WHY THIS GATE EXISTS
# --------------------
# Nothing read docs/adr/README.md at all. The `adr-numbering` job is the only automated check over
# the ADR sequence and it reads FILENAMES: it fails a PR that duplicates a number or takes one the
# base already holds. To that gate, an ADR with no index row is a perfectly well-numbered ADR.
#
# Both failure modes it cannot see have already happened, and both were found by hand:
#
#   1. A missing row. 0192, 0193 and 0194 reached main with no row in the registry -- the table
#      jumped 0191 -> 0195 -- and sat that way until 2026-09-22 (#1963). Every other number from
#      0001 up had one, so this was two PRs forgetting, not a decision to exclude them. The index
#      is how a reader finds a decision they cannot already name; an unindexed ADR is, in practice,
#      an ADR nobody will read again.
#
#   2. A row that does not render. 0123, 0137 and 0140 each carried an UNESCAPED `|` inside their
#      prose -- `(... || amount > limit)`, `(heads/main|tags/v.+)` and `?status=PENDING|REJECTED`.
#      GFM splits a row on every unescaped pipe BEFORE it parses inline syntax, so a code span is
#      no shelter: all three of those pipes sit inside backticks and split the row anyway.
#
#      What that does is worse than it sounds, and worse than "the columns shift". Per the GFM
#      spec a row with more cells than its header keeps the first three and DISCARDS the excess --
#      so the rendered row still has three cells, and the `Accepted` status is simply gone. In its
#      place the status column showed whatever prose followed the stray pipe: `tags/v.+), and
#      re-signed anyway...` for 0137, `REJECTED -> ACTIVE refused with 400...` for 0140, and for
#      0123 the `||` produced an EMPTY cell, so its status rendered blank. Verified against
#      cmark-gfm, GitHub's own engine; note that other renderers (Python-Markdown among them) DO
#      honour code spans here and show all three rows correctly, which is part of why this lasted.
#
#      0137 is the instructive one: the SAME row escapes the pipe correctly in one place
#      (`heads/main\|tags/v.+`) and not in the other. It is invisible in the source -- the table is
#      padded to a fixed width, so a broken row looks exactly like an intact one -- and it is
#      invisible on the rendered page too, because a status cell holding prose in a 195-row table
#      reads as a formatting wobble rather than as a lost field.
#      Found 2026-09-22, fixed in the change that added this gate.
#
# Counting pipes is therefore the whole point, and it is why this is a script rather than a
# Markdown linter: markdownlint has no rule for "the cell count of this row differs from its
# table's header", because an irregular row is legal Markdown. It is only wrong HERE, where the
# third column is a status somebody greps for.
#
# WHAT IS DELIBERATELY NOT CHECKED
# --------------------------------
#   - Column alignment / padding. The table is padded to a common width by hand and 11 of its rows
#     already are not, which bothers nobody: it changes no rendered output. Gating it would be a
#     style rule masquerading as a correctness one, and it would fail every PR that adds a row.
#   - The prose of a row against the ADR it points at. A row summarises; a summary that has drifted
#     is a review question, not a mechanical one.
#   - The status column's value. `Status lifecycle` in the README allows several, they change over
#     an ADR's life, and the ADR file carries its own `Status:` -- reconciling the two is a
#     different gate with a different failure mode.
#   - Numbering itself. That is scripts/check-adr-numbering.sh, and duplicating it here would mean
#     two gates disagreeing about the same rule one day.
#
# USAGE
#   scripts/check-adr-registry.sh
#
# Configuration via environment variables:
#   ADR_DIR  Directory to scan (default: docs/adr), relative to the repository root. The index is
#            read from ${ADR_DIR}/README.md.
#
# Emits GitHub Actions `::error` / `::notice` annotations; exits non-zero on any violation. Every
# check runs, so one invocation reports everything rather than the first thing it trips over.
#
set -euo pipefail

ADR_DIR="${ADR_DIR:-docs/adr}"

# The template is not a decision: it has no status, nothing links to it by number, and indexing it
# would put a row in the registry that no reader ever wants to follow. It is the one numbered file
# that is expected to have no row.
readonly TEMPLATE_FILE="0000-template.md"

# Anchor at the repository root so a relative ADR_DIR resolves the same way from CI, a git hook, or
# an interactive shell. Mirrors scripts/check-adr-numbering.sh.
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

# 0-based offsets of every `|` Markdown will treat as a cell separator, one per line.
#
# Escaped pipes are masked to a placeholder of the SAME LENGTH (`\|` -> `\@`) before scanning, so
# the offsets still index into the original line and can be used to quote the offending prose back
# to the author. Matching `\|` is the reason this cannot be a grep: the pattern needs a lookbehind
# for the backslash, which POSIX ERE does not have.
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

# ~45 characters of context on either side of an offset, so the report points at the pipe rather
# than reprinting a 4000-character row.
snippet_around() {
  local line="$1" at="$2" start len
  start=$((at - 45))
  if ((start < 0)); then
    start=0
  fi
  len=$((at - start + 46))
  printf '%s' "${line:start:len}"
}

# ---------------------------------------------------------------------------
# Pass 1: read the index. Every row is checked for shape and for a link that
# resolves; the numbers seen are collected for the completeness check below.
# ---------------------------------------------------------------------------
declare -A row_seen=()
rows=0
lineno=0

while IFS= read -r line || [[ -n $line ]]; do
  lineno=$((lineno + 1))
  # Tolerate a CRLF checkout: a trailing \r would otherwise ride along into the status cell.
  line="${line%$'\r'}"

  [[ $line =~ ^\|\ \[[0-9]{4}\] ]] || continue
  rows=$((rows + 1))

  number="${line:3:4}"

  # --- shape -------------------------------------------------------------
  mapfile -t offsets < <(unescaped_pipe_offsets "$line")
  if [[ ${#offsets[@]} -ne 4 ]]; then
    cells=$((${#offsets[@]} - 1))
    echo "::error title=ADR index row does not render::${README}:${lineno}: the row for ADR-${number} splits into ${cells} cells, not 3. An unescaped '|' in the prose is a cell separator even inside a code span, and GFM keeps the first three cells and DISCARDS the rest -- so the status column ends up showing prose (or nothing) and the real status is dropped. Escape it as '\\|'."
    # The first two and the last two pipes are the row's own structure; anything between them is
    # the defect. Quote each one so a 4000-character row does not have to be read by eye.
    last=$((${#offsets[@]} - 3))
    for ((i = 2; i <= last; i++)); do
      echo "    ${README}:${lineno}: ...$(snippet_around "$line" "${offsets[i]}")..."
    done
    failed=1
  fi

  # --- duplicate row -----------------------------------------------------
  if [[ -n ${row_seen[$number]:-} ]]; then
    echo "::error title=Duplicate ADR index row::${README}:${lineno}: ADR-${number} already has a row at line ${row_seen[$number]}. Two rows for one number make the index ambiguous about which decision that number names; keep the accurate one and delete the other."
    failed=1
  else
    row_seen[$number]="$lineno"
  fi

  # --- link ---------------------------------------------------------------
  # `| [0123](0123-some-title.md) | ...` -- the link target is the ADR's filename, relative to the
  # index, so it is also how a reader (and GitHub) gets from the table to the decision.
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

# ---------------------------------------------------------------------------
# Pass 2: every ADR on disk has a row. This is the half that 0192-0194 needed.
# ---------------------------------------------------------------------------
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
