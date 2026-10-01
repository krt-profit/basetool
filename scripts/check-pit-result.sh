#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: check-pit-result.sh <module> <pit-log> <mutations.xml> <pit-step-outcome>" >&2
  exit 2
fi

module="$1"
log="$2"
report="$3"
outcome="$4"

if [[ "${outcome}" != "success" ]]; then
  echo "::error title=PIT (${module})::the PIT step ended '${outcome:-unknown}', not 'success' - the report is partial or absent"
  exit 1
fi

if grep -q "PitHelpError" "${log}" 2>/dev/null; then
  grep -m 5 -B 2 "PitHelpError" "${log}" || true
  echo "::error title=PIT (${module})::PIT aborted with PitHelpError - the suite is not green under PIT, so no mutation was run"
  exit 1
fi

if [[ ! -s "${report}" ]]; then
  echo "::error title=PIT (${module})::no ${report} - PIT did not produce a result"
  exit 1
fi

if ! grep -q '</mutations>' "${report}"; then
  echo "::error title=PIT (${module})::${report} has no closing </mutations> - PIT was cut off before it finished"
  exit 1
fi

mutations="$(grep -c '<mutation ' "${report}" || true)"
echo "PIT (${module}): ${mutations} mutations in ${report}"
if [[ "${mutations:-0}" -eq 0 ]]; then
  echo "::error title=PIT (${module})::${report} lists no mutations"
  exit 1
fi
