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

package de.greluc.krt.profit.basetool.backend.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GlobalExceptionHandler#maskQuotedValues(String)} — the REQ-OBS-004 guard
 * that keeps the rejected user value out of the logged {@code causeMessage} of a malformed-body 400
 * while preserving the structural triage text.
 */
class GlobalExceptionHandlerMaskQuotedValuesTest {

  /** A Jackson value-bearing message has its quoted value masked but its structure kept. */
  @Test
  void masksTheQuotedValueButKeepsTheStructuralText() {
    String raw =
        "Cannot deserialize value of type `Foo` from String \"secret@example.com\":"
            + " not one of the values accepted for Enum class";

    String masked = GlobalExceptionHandler.maskQuotedValues(raw);

    assertThat(masked).doesNotContain("secret@example.com");
    assertThat(masked).contains("\"***\"");
    assertThat(masked).contains("Cannot deserialize value of type");
    assertThat(masked).contains("not one of the values accepted");
  }

  /** A pure JSON syntax error carries no quoted value, so it is passed through unchanged. */
  @Test
  void leavesAQuoteFreeSyntaxErrorUnchanged() {
    String raw = "Unexpected end-of-input within/between Object entries";

    assertThat(GlobalExceptionHandler.maskQuotedValues(raw)).isEqualTo(raw);
  }

  /** Every quoted run is masked when a message carries more than one. */
  @Test
  void masksEveryQuotedRun() {
    String raw = "field \"handle\" rejected value \"alice\"";

    String masked = GlobalExceptionHandler.maskQuotedValues(raw);

    assertThat(masked).isEqualTo("field \"***\" rejected value \"***\"");
  }

  /** A {@code null} message returns {@code null} without throwing. */
  @Test
  void returnsNullForNull() {
    assertThat(GlobalExceptionHandler.maskQuotedValues(null)).isNull();
  }
}
