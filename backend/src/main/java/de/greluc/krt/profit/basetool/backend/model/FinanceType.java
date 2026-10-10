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

package de.greluc.krt.profit.basetool.backend.model;

import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;

/** Whether a mission finance entry adds to or takes from the mission's total. */
public enum FinanceType {
  /** Money earned; adds to the total. */
  INCOME,
  /** Money spent; takes from the total. */
  EXPENSE;

  /**
   * The entry's contribution to a mission or operation total: the amount for income, its negation
   * for an expense.
   *
   * @param amount the entry's amount
   * @return the signed contribution
   */
  @NotNull
  public BigDecimal signed(@NotNull BigDecimal amount) {
    return switch (this) {
      case INCOME -> amount;
      case EXPENSE -> amount.negate();
    };
  }
}
