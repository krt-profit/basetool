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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jetbrains.annotations.NotNull;

/**
 * The results an exchange write op ends with and the rounding of stock amounts (REQ-XCH-015,
 * REQ-XCH-016).
 */
final class ExchangeOps {

  /** The result of an op that found the state it asked for. */
  static final String UNCHANGED = "unchanged";

  /** The result of an op that was refused. */
  static final String REJECTED = "rejected";

  /** The result of an op that changed something. */
  static final String APPLIED = "applied";

  /** The result of an op whose reference matched no catalogue entry or no entry of the member. */
  static final String UNMATCHED = "unmatched";

  /** The result of an op whose reference matched several catalogue entries. */
  static final String AMBIGUOUS = "ambiguous";

  /** The write counter's outcome of a change set the mass-change guard held back. */
  static final String HELD = "held";

  /** Not instantiable. */
  private ExchangeOps() {}

  /**
   * Rounds an amount to its unit: three decimals for SCU, whole pieces otherwise.
   *
   * @param amount the amount
   * @param unit the unit
   * @return the rounded amount
   */
  static @NotNull BigDecimal round(@NotNull BigDecimal amount, @NotNull String unit) {
    return amount.setScale(QuantityType.SCU.name().equals(unit) ? 3 : 0, RoundingMode.HALF_UP);
  }
}
