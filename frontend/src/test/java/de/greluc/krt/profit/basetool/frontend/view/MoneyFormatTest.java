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

package de.greluc.krt.profit.basetool.frontend.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MoneyFormat} — the Thymeleaf-facing rounding helper used by the operation
 * and mission detail templates to render aUEC totals without decimal places. The tests pin two
 * properties that the templates rely on:
 *
 * <ol>
 *   <li><b>HALF_UP semantics, not HALF_EVEN.</b> The whole point of this bean is to bypass the
 *       default {@link java.text.DecimalFormat} rounding mode that Thymeleaf's {@code
 *       #numbers.formatInteger} would otherwise apply, so the boundary cases that distinguish the
 *       two modes ({@code 0.5}, {@code 1.5}, {@code 2.5}, {@code 3.5}) are exercised explicitly.
 *   <li><b>Null safety.</b> Templates pass nullable BigDecimals through without an {@code th:if}
 *       guard in a few places; the bean must mirror Thymeleaf's "null renders as empty" contract
 *       instead of throwing an NPE during render.
 * </ol>
 */
class MoneyFormatTest {

  private final MoneyFormat moneyFormat = new MoneyFormat();

  @Test
  void roundBigDecimal_appliesHalfUpAtTheBoundary() {
    assertEquals(new BigDecimal("1"), moneyFormat.round(new BigDecimal("0.5")));
    assertEquals(new BigDecimal("2"), moneyFormat.round(new BigDecimal("1.5")));
    assertEquals(new BigDecimal("3"), moneyFormat.round(new BigDecimal("2.5")));
    assertEquals(new BigDecimal("4"), moneyFormat.round(new BigDecimal("3.5")));
  }

  @Test
  void roundBigDecimal_truncatesBelowHalf() {
    assertEquals(new BigDecimal("1"), moneyFormat.round(new BigDecimal("1.49")));
    assertEquals(new BigDecimal("2"), moneyFormat.round(new BigDecimal("2.49")));
    assertEquals(new BigDecimal("1500000"), moneyFormat.round(new BigDecimal("1500000.49")));
  }

  @Test
  void roundBigDecimal_roundsUpAboveHalf() {
    assertEquals(new BigDecimal("2"), moneyFormat.round(new BigDecimal("1.51")));
    assertEquals(new BigDecimal("3"), moneyFormat.round(new BigDecimal("2.99")));
    assertEquals(new BigDecimal("1500001"), moneyFormat.round(new BigDecimal("1500000.51")));
  }

  @Test
  void roundBigDecimal_negativeHalvesGoAwayFromZero() {
    assertEquals(new BigDecimal("-1"), moneyFormat.round(new BigDecimal("-0.5")));
    assertEquals(new BigDecimal("-2"), moneyFormat.round(new BigDecimal("-1.5")));
  }

  @Test
  void roundBigDecimal_zeroAndWholeNumbersAreReturnedAsIs() {
    assertEquals(new BigDecimal("0"), moneyFormat.round(BigDecimal.ZERO.setScale(2)));
    assertEquals(new BigDecimal("42"), moneyFormat.round(new BigDecimal("42")));
  }

  @Test
  void roundBigDecimal_nullReturnsNull() {
    assertNull(moneyFormat.round((BigDecimal) null));
  }

  @Test
  void roundNumber_handlesDouble() {
    assertEquals(new BigDecimal("1500000"), moneyFormat.roundNumber(Double.valueOf(1_499_999.51)));
    assertEquals(new BigDecimal("2"), moneyFormat.roundNumber(Double.valueOf(1.5)));
    assertEquals(new BigDecimal("0"), moneyFormat.roundNumber(Double.valueOf(0.49)));
  }

  @Test
  void roundNumber_handlesLong() {
    assertEquals(new BigDecimal("123"), moneyFormat.roundNumber(Long.valueOf(123L)));
  }

  @Test
  void roundNumber_bigDecimalIsDelegatedWithoutWideningThroughDouble() {
    BigDecimal precise = new BigDecimal("100000000000000000000.5");

    BigDecimal rounded = moneyFormat.roundNumber(precise);

    assertEquals(new BigDecimal("100000000000000000001"), rounded);
  }

  @Test
  void roundNumber_nullReturnsNull() {
    assertNull(moneyFormat.roundNumber(null));
  }
}
