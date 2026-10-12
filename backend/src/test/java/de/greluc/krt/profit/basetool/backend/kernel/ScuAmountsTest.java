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

package de.greluc.krt.profit.basetool.backend.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pins the SCU storage precision: every amount is rounded to three decimals, half up. The {@code
 * amount} columns are {@code double}, so summing fractional refinery yields can produce values like
 * {@code 37.160000000000004}.
 */
class ScuAmountsTest {

  @Test
  void roundToScuScaleStripsFloatingPointNoiseBeyondThreeDecimals() {
    assertEquals(37.16, ScuAmounts.roundToScuScale(37.160000000000004));
    assertEquals(2.93, ScuAmounts.roundToScuScale(2.9299999999999997));
    assertEquals(1.59, ScuAmounts.roundToScuScale(1.5899999999999999));
  }

  @Test
  void roundToScuScaleRoundsHalfUpAtTheThirdDecimal() {
    assertEquals(1.235, ScuAmounts.roundToScuScale(1.2345));
    assertEquals(1.234, ScuAmounts.roundToScuScale(1.2344));
  }

  @Test
  void roundToScuScaleLeavesCleanValuesAndNullUntouched() {
    assertEquals(5.0, ScuAmounts.roundToScuScale(5.0));
    assertEquals(12.5, ScuAmounts.roundToScuScale(12.5));
    assertNull(ScuAmounts.roundToScuScale(null));
  }
}
