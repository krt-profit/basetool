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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;

/**
 * The current sale prices of one material across the non-hidden terminals that buy it at a positive
 * price, the terminal set of the material overview and the material page.
 *
 * @param highest the highest price and the terminal paying it, the first by name on a tie
 * @param lowest the lowest price and the terminal paying it, the first by name on a tie
 * @param average the mean price, rounded {@code HALF_UP} to two decimals like the material page's
 *     average sale price
 * @param terminals the number of terminals the prices span; at least 1
 */
public record MaterialSaleQuote(
    @NotNull TerminalSalePrice highest,
    @NotNull TerminalSalePrice lowest,
    @NotNull BigDecimal average,
    int terminals) {

  /**
   * One terminal's sale price.
   *
   * @param pricePerScu the price in aUEC per SCU; always positive
   * @param terminalName the terminal
   */
  public record TerminalSalePrice(@NotNull BigDecimal pricePerScu, @NotNull String terminalName) {}
}
