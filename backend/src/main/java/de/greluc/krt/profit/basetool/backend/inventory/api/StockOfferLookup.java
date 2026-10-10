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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Answers what the Materialbörse promises on a Lager row (plan §5.3, REQ-MARKET-013); implemented
 * by the Materialbörse. Each read joins the caller's transaction.
 */
public interface StockOfferLookup {

  /**
   * Tells whether any offer, of any status, stands on the row.
   *
   * @param inventoryItemId the Lager row
   * @return {@code true} when an offer references the row
   */
  boolean isOffered(@NotNull UUID inventoryItemId);

  /**
   * Returns how much of a row its active offer promises: its SCU for a material offer, its whole
   * units for an item offer, {@code 0} without an active offer.
   *
   * @param inventoryItemId the Lager row
   * @return the offered amount
   */
  double activeOfferedAmount(@NotNull UUID inventoryItemId);
}
