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

package de.greluc.krt.profit.basetool.backend.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogMaskerTest {

  @Test
  void maskEmail_shouldKeepDomainAndFirstChar() {
    assertThat(LogMasker.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
  }

  @Test
  void maskEmail_shouldReturnPlaceholderForNullOrBlank() {
    assertThat(LogMasker.maskEmail(null)).isEqualTo(LogMasker.NULL_PLACEHOLDER);
    assertThat(LogMasker.maskEmail("   ")).isEqualTo(LogMasker.NULL_PLACEHOLDER);
  }

  @Test
  void maskEmail_shouldFullyMaskInvalidFormat() {
    assertThat(LogMasker.maskEmail("no-at-sign")).isEqualTo(LogMasker.FULL_MASK);
    assertThat(LogMasker.maskEmail("@example.com")).isEqualTo(LogMasker.FULL_MASK);
    assertThat(LogMasker.maskEmail("alice@")).isEqualTo(LogMasker.FULL_MASK);
  }

  @Test
  void maskId_shouldKeepFirstAndLastTwoChars() {
    assertThat(LogMasker.maskId("123456789")).isEqualTo("12***89");
    assertThat(LogMasker.maskId(123456789L)).isEqualTo("12***89");
  }

  @Test
  void maskId_shouldFullyMaskShortValues() {
    assertThat(LogMasker.maskId("1234")).isEqualTo(LogMasker.FULL_MASK);
  }

  @Test
  void maskId_shouldReturnPlaceholderForNull() {
    assertThat(LogMasker.maskId(null)).isEqualTo(LogMasker.NULL_PLACEHOLDER);
    assertThat(LogMasker.maskId("")).isEqualTo(LogMasker.NULL_PLACEHOLDER);
  }

  @Test
  void maskToken_shouldTruncateLongTokens() {
    String jwt = "eyJhbGciOiJIUzI1NiJ9.payload.signature";
    assertThat(LogMasker.maskToken(jwt)).isEqualTo("eyJh***");
  }

  @Test
  void maskToken_shouldFullyMaskShortTokens() {
    assertThat(LogMasker.maskToken("short")).isEqualTo(LogMasker.FULL_MASK);
    assertThat(LogMasker.maskToken("12345678")).isEqualTo(LogMasker.FULL_MASK);
  }

  @Test
  void maskToken_shouldReturnPlaceholderForNullOrBlank() {
    assertThat(LogMasker.maskToken(null)).isEqualTo(LogMasker.NULL_PLACEHOLDER);
    assertThat(LogMasker.maskToken(" ")).isEqualTo(LogMasker.NULL_PLACEHOLDER);
  }

  @Test
  void maskPhone_shouldKeepLastTwoDigitsAndStripFormatting() {
    assertThat(LogMasker.maskPhone("+49 170 1234567")).isEqualTo("***67");
    assertThat(LogMasker.maskPhone("(0170) 12-34-567")).isEqualTo("***67");
  }

  @Test
  void maskPhone_shouldFullyMaskTooFewDigits() {
    assertThat(LogMasker.maskPhone("12")).isEqualTo(LogMasker.FULL_MASK);
  }

  @Test
  void maskPhone_shouldReturnPlaceholderForNullOrBlank() {
    assertThat(LogMasker.maskPhone(null)).isEqualTo(LogMasker.NULL_PLACEHOLDER);
    assertThat(LogMasker.maskPhone("")).isEqualTo(LogMasker.NULL_PLACEHOLDER);
  }

  @Test
  void mask_shouldHideValueButKeepLength() {
    assertThat(LogMasker.mask("secret")).isEqualTo("***(len=6)");
  }

  @Test
  void mask_shouldReturnPlaceholderForNullOrBlank() {
    assertThat(LogMasker.mask(null)).isEqualTo(LogMasker.NULL_PLACEHOLDER);
    assertThat(LogMasker.mask("   ")).isEqualTo(LogMasker.NULL_PLACEHOLDER);
  }
}
