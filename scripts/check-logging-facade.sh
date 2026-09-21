#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
#
# Verifies that production code obtains its logger from Lombok and never by hand, and that nothing
# in production code writes to stdout/stderr instead of logging.
#
# WHY THIS GATE EXISTS
# --------------------
# `CLAUDE.md` has said "Logging: @Slf4j — never instantiate loggers manually" since early in the
# project, and production code has honoured it: at the time this gate was written all 306 loggers in
# `src/main` came from Lombok (301 `@Slf4j` + 5 `@JBossLog`) and not one was hand-rolled. Nothing
# enforced that. The sibling rule -- WHICH Lombok annotation -- is enforced by `lombok.config`
# (`@Slf4j` everywhere, `@JBossLog` in `keycloak-spi`, everything else a compile error), so a wrong
# *flavour* cannot reach a branch.
# A hand-written `LoggerFactory.getLogger(...)` sails straight past that, because to `lombok.config`
# it is simply not a Lombok annotation at all.
#
# That matters beyond tidiness. A hand-rolled field is where the logger name drifts from the
# declaring class (copy-paste keeps the neighbour's `.class`), which silently misroutes the line
# against the per-logger levels in `logback-spring.xml` -- the mechanism `PageNotFoundLogLevelTest`
# exists to protect. Lombok derives the name from the type it sits on and cannot get that wrong.
#
# WHY TESTS ARE DELIBERATELY OUT OF SCOPE
# ---------------------------------------
# 33 test files hold a `LoggerFactory.getLogger(...)` and every one of them is CORRECT: they are not
# logging, they are CAPTURING. A log assertion needs the `Logger` *instance* of the class under test
# so it can attach a `ListAppender` (or, in `PageNotFoundLogLevelTest`, read the configured level
# back). `@Slf4j` is the opposite operation and cannot express it. Flagging those would be a gate
# that is wrong 33 times out of 33, so the scan covers `src/main` only.
#
# `src/e2e` is out of scope for the same reason from the other end: the Playwright suite prints
# progress to the console on purpose, for a human watching a browser drive itself.
#
# USAGE
#   scripts/check-logging-facade.sh    # verify; non-zero exit and a report on any violation
#
set -euo pipefail

# Hand-rolled loggers. `Logger.getLogger(` covers both the JBoss and the java.util.logging spelling;
# `LoggerFactory.getLogger(` covers SLF4J. Lombok emits the same calls, but into generated code that
# never exists in a source file -- which is exactly what makes a source scan the right instrument.
readonly MANUAL_LOGGER_PATTERN='(LoggerFactory|Logger)\.getLogger[[:space:]]*\('

# Console writes. Production code that needs to say something says it through the logger, so it
# carries the correlation id, the MDC fields and the prod JSON encoding with it (REQ-OBS-*).
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

# Every tracked production source. `git ls-files` rather than `find` so an untracked scratch file
# cannot turn the gate red, and so the scan reads the same tree CI does.
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
