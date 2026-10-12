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

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/** The one storage precision of every SCU amount: three decimals, rounded half up. */
public final class ScuAmounts {

  private static final int SCALE = 3;

  private ScuAmounts() {}

  /**
   * Rounds an SCU amount to three decimals with {@link RoundingMode#HALF_UP}, leaving {@code null}
   * untouched.
   *
   * @param value the raw amount, possibly carrying floating-point noise
   * @return {@code value} rounded to three decimals, or {@code null} when {@code value} is {@code
   *     null}
   */
  @Contract("null -> null")
  @Nullable
  public static Double roundToScuScale(@Nullable Double value) {
    if (value == null) {
      return null;
    }
    return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
  }
}
