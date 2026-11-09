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

import java.math.BigDecimal;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Turns the bank's whole-aUEC amounts into the JSON integers the exchange answers (ADR-0010). */
final class ExchangeAmounts {

  /** Not instantiable. */
  private ExchangeAmounts() {}

  /**
   * An amount as a JSON integer of whole aUEC.
   *
   * @param amount the whole amount
   * @return its value
   * @throws ArithmeticException when the amount has a fraction or exceeds a {@code long}
   */
  static long whole(@NotNull BigDecimal amount) {
    return amount.longValueExact();
  }

  /**
   * An optional amount as a JSON integer of whole aUEC.
   *
   * @param amount the whole amount, or {@code null}
   * @return its value, or {@code null} for {@code null}
   * @throws ArithmeticException when the amount has a fraction or exceeds a {@code long}
   */
  @Contract("null -> null; !null -> !null")
  static @Nullable Long wholeOrNull(@Nullable BigDecimal amount) {
    return amount == null ? null : whole(amount);
  }
}
