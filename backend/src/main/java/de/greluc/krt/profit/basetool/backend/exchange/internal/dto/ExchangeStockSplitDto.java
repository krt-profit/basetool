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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * How a member's stock lot splits into their personal stock and their own shared stock in each
 * org-unit pool (REQ-XCH-016).
 *
 * @param personal the personal part, zero when the lot holds no personal rows
 * @param pools the member's part of each pool they hold rows of the lot in, ordered by unit id
 */
public record ExchangeStockSplitDto(
    @NotNull ExchangeQuantityDto personal, @NotNull List<ExchangeStockSplitPool> pools) {

  /**
   * The member's own shared stock of a lot in one org unit's pool.
   *
   * @param orgUnit the pool's unit
   * @param quantity the summed amount with its unit
   */
  public record ExchangeStockSplitPool(
      @NotNull ExchangeStockSplitUnit orgUnit, @NotNull ExchangeQuantityDto quantity) {}

  /**
   * A reference to an org unit.
   *
   * @param id the unit's id
   */
  public record ExchangeStockSplitUnit(@NotNull UUID id) {}
}
