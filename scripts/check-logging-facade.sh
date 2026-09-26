#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

readonly MANUAL_LOGGER_PATTERN='(LoggerFactory|Logger)\.getLogger[[:space:]]*\('

readonly CONSOLE_WRITE_PATTERN='System\.(out|err)\.(print|println|printf|write)[[:space:]]*\('

fail=0

report() {
  local title="$1" remedy="$2"
  shift 2
  printf '\n%s\n' "$title" >&2
  printf '  %s\n' "$@" >&2
  printf '\n  %s\n' "$remedy" >&2
  fail=1
}

mapfile -t sources < <(git ls-files '*/src/main/java/**/*.java' | sort)

if [ ${#sources[@]} -eq 0 ]; then
  echo "No production Java sources tracked; nothing to check." >&2
  exit 0
fi

printf 'Checking the logging facade across %d production source(s).\n' "${#sources[@]}"

mapfile -t manual < <(grep -HnE "$MANUAL_LOGGER_PATTERN" "${sources[@]}" || true)
if [ ${#manual[@]} -gt 0 ]; then
  report \
    "Hand-rolled logger in production code (${#manual[@]} occurrence(s)):" \
    "Use Lombok: @Slf4j, or @JBossLog inside keycloak-spi. It derives the logger name from the declaring type, which a copy-pasted .class literal does not." \
    "${manual[@]}"
fi

mapfile -t console < <(grep -HnE "$CONSOLE_WRITE_PATTERN" "${sources[@]}" || true)
if [ ${#console[@]} -gt 0 ]; then
  report \
    "Console write in production code (${#console[@]} occurrence(s)):" \
    "Log it instead, so the line carries the correlation id and the MDC fields and lands in the prod JSON appender." \
    "${console[@]}"
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi

echo "Logging facade OK: every production logger comes from Lombok, and nothing writes to the console."
