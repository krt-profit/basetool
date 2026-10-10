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

package de.greluc.krt.profit.basetool.backend.bank.api;

import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;

/**
 * Formatting helpers for bank aUEC amounts, shared across the bank services.
 *
 * <p>Dependency-free so both the org-unit-blind bank core and the seam can share it.
 *
 * <p>It must not import {@code OwnerScopeService} or read org-unit state, or ArchUnit breaks.
 */
public final class BankAmounts {

  /** Not instantiable. */
  private BankAmounts() {}

  /**
   * Renders a whole-aUEC amount without trailing zeros or scientific notation (display/audit).
   *
   * @param amount the amount to render; never {@code null}
   * @return the plain whole-number string
   */
  @NotNull
  public static String plain(@NotNull BigDecimal amount) {
    return amount.stripTrailingZeros().toPlainString();
  }
}
