/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.ingest.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for the log-injection guard. The gateway is the only internet-facing module and its
 * accepted payload carries free-text provenance fields, so a value that reaches a log line must not
 * be able to forge a second one — a fabricated {@code ERROR} line in the ingest log would be taken
 * at face value during an incident.
 *
 * <p><b>This test guards a triplicated class.</b> {@code LogSafe} exists three times — backend
 * {@code support}, frontend {@code logging}, ingest {@code logging} — because the no-shared-module
 * convention rules out extracting it and the backend copy is additionally pinned to the
 * dependency-free {@code support} leaf by the ADR-0047 cycle rule. Three copies of a
 * security-relevant sanitiser drift, so the drift is made mechanical rather than left to a Javadoc
 * convention, with two layers:
 *
 * <ol>
 *   <li>The expectation table between the LOGSAFE-TABLE markers below is the single description of
 *       the guard's behaviour and is kept character-for-character identical in all three test
 *       classes; {@link #sharedExpectationTableIsIdenticalInAllThreeModules()} reads the three test
 *       sources and compares the regions, so a row added, changed or dropped in one module and not
 *       in the others fails the build in every module.
 *   <li>{@link #mirroredImplementationIsIdenticalInAllThreeModules()} does the same for the code
 *       itself: the three {@code LogSafe} sources must match inside their LOGSAFE-MIRROR markers,
 *       which is stricter than the table (it also catches a comment, a constant or a helper that
 *       was only fixed in one module). Only the package line and the module-specific class Javadoc
 *       live outside the markers.
 * </ol>
 *
 * <p>Both checks run in all three modules, so whichever module's tests you run reports the drift.
 */
class LogSafeTest {

  /** Repository-relative path of the backend copy — the one the other two are compared against. */
  private static final String BACKEND_MAIN =
      "backend/src/main/java/de/greluc/krt/profit/basetool/backend/support/LogSafe.java";

  /** Repository-relative path of the frontend copy of the mirrored implementation. */
  private static final String FRONTEND_MAIN =
      "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/logging/LogSafe.java";

  /** Repository-relative path of the ingest copy of the mirrored implementation. */
  private static final String INGEST_MAIN =
      "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/logging/LogSafe.java";

  /** Repository-relative path of the backend test carrying the reference expectation table. */
  private static final String BACKEND_TEST =
      "backend/src/test/java/de/greluc/krt/profit/basetool/backend/support/LogSafeTest.java";

  /** Repository-relative path of the frontend test carrying its copy of the expectation table. */
  private static final String FRONTEND_TEST =
      "frontend/src/test/java/de/greluc/krt/profit/basetool/frontend/logging/LogSafeTest.java";

  /** Repository-relative path of the ingest test carrying its copy of the expectation table. */
  private static final String INGEST_TEST =
      "ingest/src/test/java/de/greluc/krt/profit/basetool/ingest/logging/LogSafeTest.java";

  /** Opening marker of the mirrored code region inside the three {@code LogSafe} sources. */
  private static final String MIRROR_BEGIN = ">>> LOGSAFE-MIRROR BEGIN";

  /** Closing marker of the mirrored code region inside the three {@code LogSafe} sources. */
  private static final String MIRROR_END = "<<< LOGSAFE-MIRROR END";

  /**
   * Opening marker of the shared expectation table below. Assembled from two literals on purpose:
   * spelled out in one piece, this very declaration would be the first match when the parity test
   * searches <em>this</em> file for the marker, and the extracted region would be the wrong one.
   */
  private static final String TABLE_BEGIN = ">>> LOGSAFE-TABLE" + " BEGIN";

  /**
   * Closing marker of the shared expectation table; assembled from two literals for the same reason
   * as {@link #TABLE_BEGIN}.
   */
  private static final String TABLE_END = "<<< LOGSAFE-TABLE" + " END";

  /** Failure text naming the drifted module and file, with the repair instruction. */
  private static final String DRIFT =
      "the %s copy of %s drifted from the backend copy — the three modules keep a hand-maintained "
          + "mirror because no shared module may hold it, so propagate the edit to all three";

  /**
   * Runs every row of the shared expectation table against this module's copy of the guard.
   *
   * <p>The display name deliberately omits the arguments: the table carries NUL, DEL and the two
   * Unicode separators, and those characters are illegal in the JUnit XML report a CI run parses.
   *
   * @param value the input handed to the guard, {@code null} for the null-input rows
   * @param maxLength the cap handed to the guard
   * @param expected the exact rendering the guard must produce
   */
  @ParameterizedTest(name = "[{index}]")
  @MethodSource("sanitisationTable")
  void sanitisesEveryRowOfTheSharedTable(String value, int maxLength, String expected) {
    assertThat(LogSafe.text(value, maxLength))
        .as("row of the shared expectation table (index in the display name)")
        .isEqualTo(expected);
  }

  // >>> LOGSAFE-TABLE BEGIN
  /**
   * The input/output expectation table all three modules must satisfy — the single description of
   * what {@code LogSafe.text} does, kept character-for-character identical in the backend, frontend
   * and ingest copies of this test class. Do not edit one copy alone: the parity test compares the
   * three regions and fails everywhere if they diverge.
   *
   * @return one {@code (value, maxLength, expected)} row per sanitisation rule
   */
  static Stream<Arguments> sanitisationTable() {
    return Stream.of(
        // Ordinary text survives verbatim — umlauts and punctuation are not line-breaking, and a
        // term that no longer matches what was typed is useless in a log.
        Arguments.of("Quantanium (Lager Süd)", 60, "Quantanium (Lager Süd)"),
        Arguments.of("1234567890", 10, "1234567890"),
        // Line-breaking characters become '?' so a second log line cannot be forged.
        Arguments.of("a\nb", 60, "a?b"),
        Arguments.of("a\r\nb\tc", 60, "a??b?c"),
        Arguments.of("a\u0000b", 60, "a?b"), // NUL
        Arguments.of("a\u0085b", 60, "a?b"), // U+0085 NEXT LINE — an ISO control
        Arguments.of("a\u007fb", 60, "a?b"), // DEL
        Arguments.of("a\u2028b", 60, "a?b"), // U+2028 LINE SEPARATOR — not an ISO control
        Arguments.of("a\u2029b", 60, "a?b"), // U+2029 PARAGRAPH SEPARATOR — not an ISO control
        Arguments.of("a\u2028\u2029\nb", 60, "a???b"),
        // Overlong values are cut and the cut is marked, sanitisation and cap composing.
        Arguments.of("12345678901", 10, "1234567890…"),
        Arguments.of("a\nb-cdefghijklmnop", 8, "a?b-cdef…"),
        // Nothing to log at all collapses to the stable token, keeping the field count constant.
        Arguments.of(null, 10, LogSafe.NONE),
        Arguments.of("", 10, LogSafe.NONE),
        Arguments.of("   ", 10, LogSafe.NONE),
        Arguments.of("\u2028\u2029", 10, LogSafe.NONE)); // both separators are whitespace
  }

  // <<< LOGSAFE-TABLE END

  @Test
  void replacesEveryControlCharacterSoASecondLineCannotBeForged() {
    String forged = "krt-extractor\n2026-08-02 07:00:00.000 ERROR --- fabricated";

    String safe = LogSafe.text(forged, 200);

    assertThat(safe).doesNotContain("\n").doesNotContain("\r");
    assertThat(safe).startsWith("krt-extractor?");
  }

  @Test
  void replacesCarriageReturnsAndTabsToo() {
    assertThat(LogSafe.text("a\r\nb\tc", 50)).isEqualTo("a??b?c");
  }

  @Test
  void replacesTheTwoUnicodeSeparatorsThatAreNotIsoControls() {
    // U+2028 and U+2029 are the blind spot of Character.isISOControl: it returns false for both,
    // yet several log viewers and JSON consumers end a line on them, so a value carrying one could
    // forge a second line for exactly those readers.
    assertThat(Character.isISOControl('\u2028')).isFalse();
    assertThat(Character.isISOControl('\u2029')).isFalse();

    assertThat(LogSafe.text("krt-extractor\u2028ERROR --- fabricated", 200))
        .isEqualTo("krt-extractor?ERROR --- fabricated");
    assertThat(LogSafe.text("krt-extractor\u2029ERROR --- fabricated", 200))
        .isEqualTo("krt-extractor?ERROR --- fabricated");
  }

  @Test
  void capsAnOverlongValueAndMarksTheCut() {
    String safe = LogSafe.text("x".repeat(500), 10);

    assertThat(safe).startsWith("xxxxxxxxxx").hasSize(11);
    assertThat(safe).endsWith("…");
  }

  @Test
  void doesNotMarkAValueThatFitsExactly() {
    assertThat(LogSafe.text("1234567890", 10)).isEqualTo("1234567890");
  }

  @Test
  void rendersNullAndBlankAsAStableToken() {
    // A stable token keeps the field count of the log line constant, so a line stays greppable.
    assertThat(LogSafe.text(null, 10)).isEqualTo(LogSafe.NONE);
    assertThat(LogSafe.text("", 10)).isEqualTo(LogSafe.NONE);
    assertThat(LogSafe.text("   ", 10)).isEqualTo(LogSafe.NONE);
  }

  @Test
  void leavesOrdinaryProvenanceTextUntouched() {
    // Version punctuation is not control text and must survive verbatim, otherwise the logged
    // provenance no longer matches the value the extractor actually sent.
    assertThat(LogSafe.text("krt-extractor 1.4.2-beta+build.7", 60))
        .isEqualTo("krt-extractor 1.4.2-beta+build.7");
  }

  /**
   * Asserts the frontend and ingest copies of {@code LogSafe} match the backend copy inside the
   * LOGSAFE-MIRROR markers, so a fix applied to one module cannot silently leave the other two
   * modules logging what this one sanitises.
   *
   * @throws IOException if one of the three implementation sources cannot be read
   */
  @Test
  void mirroredImplementationIsIdenticalInAllThreeModules() throws IOException {
    Path root = findRepoRoot();
    String backend = region(root.resolve(BACKEND_MAIN), MIRROR_BEGIN, MIRROR_END);

    assertThat(region(root.resolve(FRONTEND_MAIN), MIRROR_BEGIN, MIRROR_END))
        .as(DRIFT, "frontend", "LogSafe")
        .isEqualTo(backend);
    assertThat(region(root.resolve(INGEST_MAIN), MIRROR_BEGIN, MIRROR_END))
        .as(DRIFT, "ingest", "LogSafe")
        .isEqualTo(backend);
  }

  /**
   * Asserts the three test classes carry the same expectation table, so the behaviour the three
   * modules are held to cannot diverge even where their implementations somehow could.
   *
   * @throws IOException if one of the three test sources cannot be read
   */
  @Test
  void sharedExpectationTableIsIdenticalInAllThreeModules() throws IOException {
    Path root = findRepoRoot();
    String backend = region(root.resolve(BACKEND_TEST), TABLE_BEGIN, TABLE_END);

    assertThat(backend).as("the reference expectation table must not be empty").isNotBlank();
    assertThat(region(root.resolve(FRONTEND_TEST), TABLE_BEGIN, TABLE_END))
        .as(DRIFT, "frontend", "expectation table")
        .isEqualTo(backend);
    assertThat(region(root.resolve(INGEST_TEST), TABLE_BEGIN, TABLE_END))
        .as(DRIFT, "ingest", "expectation table")
        .isEqualTo(backend);
  }

  /**
   * Reads {@code source} and returns the text between the first occurrence of {@code begin} and the
   * first occurrence of {@code end}, with line endings normalised, so two files can be compared on
   * their marked region alone while their surrounding module-specific prose differs.
   *
   * @param source the Java source file to read
   * @param begin the opening marker; must occur in the file
   * @param end the closing marker; must occur after the opening one
   * @return the region between the two markers, stripped of leading and trailing whitespace
   * @throws IOException if the file cannot be read
   */
  private static String region(Path source, String begin, String end) throws IOException {
    String text = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
    int from = text.indexOf(begin);
    int to = text.indexOf(end);
    assertThat(from).as("opening marker '%s' is missing in %s", begin, source).isNotNegative();
    assertThat(to).as("closing marker '%s' is missing in %s", end, source).isGreaterThan(from);
    return text.substring(from + begin.length(), to).strip();
  }

  /**
   * Walks up from the test's working directory until the directory holding {@code
   * settings.gradle.kts} is found, so the cross-module source paths resolve no matter which
   * module's test task is running.
   *
   * @return the repository root directory
   * @throws IllegalStateException if no {@code settings.gradle.kts} is found on the way up
   */
  private static Path findRepoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException(
          "Could not locate the repository root (settings.gradle.kts) from "
              + System.getProperty("user.dir"));
    }
    return dir;
  }
}
