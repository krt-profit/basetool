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

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Template bean ({@code @moneyFormat}) that rounds monetary amounts to whole aUEC with {@link
 * RoundingMode#HALF_UP}, before {@code #numbers.formatInteger} would apply {@link
 * RoundingMode#HALF_EVEN}.
 */
@Component("moneyFormat")
public class MoneyFormat {

  /**
   * Rounds the given {@link BigDecimal} to scale 0 using {@link RoundingMode#HALF_UP}.
   *
   * @param value the amount to round; {@code null} is passed through
   * @return the rounded value, or {@code null} if {@code value} was {@code null}
   */
  @Nullable
  public BigDecimal round(@Nullable BigDecimal value) {
    if (value == null) {
      return null;
    }
    return value.setScale(0, RoundingMode.HALF_UP);
  }

  /**
   * Rounds a {@link Number} like {@link #round(BigDecimal)}, converting it via {@link
   * Number#toString()} to avoid binary floating-point artefacts.
   *
   * @param value the amount to round; {@code null} is passed through
   * @return the rounded value, or {@code null} if {@code value} was {@code null}
   */
  @Nullable
  public BigDecimal roundNumber(@Nullable Number value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal bd) {
      return round(bd);
    }
    return round(new BigDecimal(value.toString()));
  }
}
