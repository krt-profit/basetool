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

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Supplies the stock projection of the job order with the Lager's material rows earmarked to
 * orders; owned by the layer so the projection names no Lager type, implemented by the inventory
 * module.
 */
public interface OrderLinkedStockRows {

  /**
   * Returns every material earmark of the given job orders as one row each, in a single query
   * (REQ-DATA-003).
   *
   * @param jobOrderIds the orders; an empty collection yields an empty list
   * @return one row per material slice
   */
  @NotNull
  List<JobOrderMaterialStockRow> materialStockOfOrders(@NotNull Collection<UUID> jobOrderIds);
}
