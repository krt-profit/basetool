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

package de.greluc.krt.profit.basetool.backend.supply.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The read SPI through which the supply module learns who may read a need's maximum price and
 * purchase progress beyond the raising unit and the governing leadership (REQ-SUP-006). The
 * purchasing module implements it for its trade delegates; with no implementation nobody else may.
 */
public interface NeedPriceAudience {

  /**
   * Tells whether the member may read the purchase fields of every need.
   *
   * @param userId the member
   * @return {@code true} when the member holds an active trade-delegate appointment
   */
  boolean mayReadPurchaseFields(@NotNull UUID userId);
}
