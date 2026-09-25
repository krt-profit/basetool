#!/usr/bin/env bash
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKER="${SCRIPT_DIR}/check-logging-facade.sh"

if [[ ! -f "$CHECKER" ]]; then
  echo "FATAL: checker not found at ${CHECKER}" >&2
  exit 1
fi

tests_run=0
tests_failed=0
LAST_OUTPUT=""
LAST_STATUS=0

make_repo() {
  local dir rel body
  dir="$(mktemp -d)"
  while [ "$#" -ge 2 ]; do
    rel="$1"
    body="$2"
    shift 2
    mkdir -p "${dir}/$(dirname "$rel")"
    printf '%s\n' "$body" >"${dir}/${rel}"
  done
  git -C "$dir" init --quiet
  git -C "$dir" add -A
  printf '%s' "$dir"
}

run_checker() {
  local dir="$1"
  set +e
  LAST_OUTPUT="$(cd "$dir" && bash "$CHECKER" 2>&1)"
  LAST_STATUS=$?
  set -e
}

expect() {
  local name="$1" want_status="$2" dir="$3" want_text="${4:-}"
  tests_run=$((tests_run + 1))
  run_checker "$dir"
  if [ "$LAST_STATUS" -ne "$want_status" ]; then
    printf 'FAIL %s: expected exit %s, got %s\n' "$name" "$want_status" "$LAST_STATUS" >&2
    printf '%s\n' "$LAST_OUTPUT" | sed 's/^/     | /' >&2
    tests_failed=$((tests_failed + 1))
    rm -rf "$dir"
    return
  fi
  if [ -n "$want_text" ] && ! printf '%s' "$LAST_OUTPUT" | grep -q "$want_text"; then
    printf 'FAIL %s: report did not mention %s\n' "$name" "$want_text" >&2
    printf '%s\n' "$LAST_OUTPUT" | sed 's/^/     | /' >&2
    tests_failed=$((tests_failed + 1))
    rm -rf "$dir"
    return
  fi
  printf 'ok   %s\n' "$name"
  rm -rf "$dir"
}

CLEAN_MAIN='package p;
import lombok.extern.slf4j.Slf4j;
@Slf4j
public class Clean {
  public void go() { log.info("fine"); }
}'

MANUAL_SLF4J='package p;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class Manual {
  private static final Logger log = LoggerFactory.getLogger(Manual.class);
}'

MANUAL_JBOSS='package p;
import org.jboss.logging.Logger;
public class Manual {
  private static final Logger LOG = Logger.getLogger(Manual.class);
}'

CONSOLE='package p;
public class Noisy {
  public void go() { System.out.println("nope"); }
}'

CAPTURE_TEST='package p;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
class SomethingTest {
  private final Logger target = (Logger) LoggerFactory.getLogger(Clean.class);
}'

E2E_PRINT='package p;
class SmokeE2eTest {
  void go() { System.out.println("driving the browser"); }
}'

expect "a Lombok-derived logger passes" 0 \
  "$(make_repo backend/src/main/java/p/Clean.java "$CLEAN_MAIN")" "Logging facade OK"

expect "a hand-rolled SLF4J logger in main is flagged" 1 \
  "$(make_repo backend/src/main/java/p/Manual.java "$MANUAL_SLF4J")" "Hand-rolled logger"

expect "a hand-rolled JBoss logger in main is flagged" 1 \
  "$(make_repo keycloak-spi/src/main/java/p/Manual.java "$MANUAL_JBOSS")" "Hand-rolled logger"

expect "a console write in main is flagged" 1 \
  "$(make_repo frontend/src/main/java/p/Noisy.java "$CONSOLE")" "Console write"

expect "a log-CAPTURE handle in a test is NOT flagged" 0 \
  "$(make_repo backend/src/main/java/p/Clean.java "$CLEAN_MAIN" \
      backend/src/test/java/p/SomethingTest.java "$CAPTURE_TEST")" "Logging facade OK"

expect "deliberate console output in the e2e suite is NOT flagged" 0 \
  "$(make_repo frontend/src/main/java/p/Clean.java "$CLEAN_MAIN" \
      frontend/src/e2e/java/p/SmokeE2eTest.java "$E2E_PRINT")" "Logging facade OK"

expect "a tree with no production sources says so" 0 \
  "$(make_repo docs/notes.md 'not java')" "nothing to check"

printf '\n%d test(s), %d failure(s)\n' "$tests_run" "$tests_failed"
[ "$tests_failed" -eq 0 ]
