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

package de.greluc.krt.profit.basetool.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for the log-injection guard all three applications share. The text reaching these log
 * lines comes from an authenticated member's search box or form field (backend, frontend) or from
 * the desktop extractor's free-text provenance fields (ingest), so the threat is a caller who can
 * make a log write a fabricated {@code ERROR} line — which would be taken at face value while
 * triaging an incident.
 *
 * <p>Until ADR-0205 this class existed three times, together with a parity test that read the other
 * two modules' sources and compared their marked regions byte for byte. With one implementation
 * there is nothing left to compare; the expectation table below is simply the specification.
 */
class LogSafeTest {

  /**
   * Runs every row of the expectation table against the guard.
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
  void sanitisesEveryRowOfTheTable(String value, int maxLength, String expected) {
    assertThat(LogSafe.text(value, maxLength))
        .as("row of the expectation table (index in the display name)")
        .isEqualTo(expected);
  }

  /**
   * The input/output expectation table — the single description of what {@code LogSafe.text} does.
   *
   * @return one {@code (value, maxLength, expected)} row per sanitisation rule
   */
  static Stream<Arguments> sanitisationTable() {
    return Stream.of(
        Arguments.of("Quantanium (Lager Süd)", 60, "Quantanium (Lager Süd)"),
        Arguments.of("1234567890", 10, "1234567890"),
        Arguments.of("a\nb", 60, "a?b"),
        Arguments.of("a\r\nb\tc", 60, "a??b?c"),
        Arguments.of("a\u0000b", 60, "a?b"),
        Arguments.of("a\u0085b", 60, "a?b"),
        Arguments.of("a\u007fb", 60, "a?b"),
        Arguments.of("a\u2028b", 60, "a?b"),
        Arguments.of("a\u2029b", 60, "a?b"),
        Arguments.of("a\u2028\u2029\nb", 60, "a???b"),
        Arguments.of("12345678901", 10, "1234567890…"),
        Arguments.of("a\nb-cdefghijklmnop", 8, "a?b-cdef…"),
        Arguments.of(null, 10, LogSafe.NONE),
        Arguments.of("", 10, LogSafe.NONE),
        Arguments.of("   ", 10, LogSafe.NONE),
        Arguments.of("\u2028\u2029", 10, LogSafe.NONE));
  }

  @Test
  void replacesEveryControlCharacterSoASecondLineCannotBeForged() {
    String forged = "quantanium\n2026-08-02 07:00:00.000 ERROR --- fabricated";

    String safe = LogSafe.text(forged, 200);

    assertThat(safe).doesNotContain("\n").doesNotContain("\r");
    assertThat(safe).startsWith("quantanium?");
  }

  @Test
  void replacesCarriageReturnsAndTabsToo() {
    assertThat(LogSafe.text("a\r\nb\tc", 50)).isEqualTo("a??b?c");
  }

  @Test
  void replacesTheTwoUnicodeSeparatorsThatAreNotIsoControls() {
    assertThat(Character.isISOControl('\u2028')).isFalse();
    assertThat(Character.isISOControl('\u2029')).isFalse();

    assertThat(LogSafe.text("quantanium\u2028ERROR --- fabricated", 200))
        .isEqualTo("quantanium?ERROR --- fabricated");
    assertThat(LogSafe.text("quantanium\u2029ERROR --- fabricated", 200))
        .isEqualTo("quantanium?ERROR --- fabricated");
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
    assertThat(LogSafe.text(null, 10)).isEqualTo(LogSafe.NONE);
    assertThat(LogSafe.text("", 10)).isEqualTo(LogSafe.NONE);
    assertThat(LogSafe.text("   ", 10)).isEqualTo(LogSafe.NONE);
  }

  @Test
  void leavesAnOrdinarySearchTermUntouched() {
    assertThat(LogSafe.text("Quantanium (Lager Süd)", 60)).isEqualTo("Quantanium (Lager Süd)");
  }

  @Test
  void leavesOrdinaryProvenanceTextUntouched() {
    assertThat(LogSafe.text("krt-extractor 1.4.2-beta+build.7", 60))
        .isEqualTo("krt-extractor 1.4.2-beta+build.7");
  }
}
