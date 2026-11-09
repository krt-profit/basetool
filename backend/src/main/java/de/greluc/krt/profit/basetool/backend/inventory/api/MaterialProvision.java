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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Inventory-owned facts of one genuine newly provided or newly earmarked material quantity.
 *
 * @param orderId the target order
 * @param rowId the actual physical storage row
 * @param materialId the actual material
 * @param quality the stock quality
 * @param budgetQuanta the action's new suitable quantity in thousandths of a unit
 * @param quantity the current physical composition, before any merge
 * @param selection the explicit unit contribution selected by the writer
 */
public record MaterialProvision(
    @NotNull UUID orderId,
    @NotNull UUID rowId,
    @NotNull UUID materialId,
    @Nullable Integer quality,
    long budgetQuanta,
    @NotNull List<StockQuantityFragment> quantity,
    @NotNull MaterialProvisionSelection selection) {
  /** Keeps the Inventory fact immutable across the synchronous observer call. */
  public MaterialProvision {
    quantity = List.copyOf(quantity);
    if (budgetQuanta < 0L) {
      throw new IllegalArgumentException("Provision quantity cannot be negative");
    }
  }
}
