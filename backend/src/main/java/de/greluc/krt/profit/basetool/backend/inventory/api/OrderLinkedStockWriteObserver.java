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

import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Validates order prerequisites before Inventory changes stock linked to those orders. */
@ObserverSpi
public interface OrderLinkedStockWriteObserver {

  /**
   * Stabilizes and validates all affected order responsibilities without granting order writes.
   *
   * @param orderIds the actually affected orders
   */
  void beforeOrderLinkedStockWrite(@NotNull Collection<UUID> orderIds);

  /**
   * Locks every affected material capacity scope before stock lots or rows are locked.
   *
   * @param scopes the complete current and requested material scopes
   */
  void beforeOrderLinkedMaterialWrite(@NotNull Collection<OrderMaterialScope> scopes);

  /**
   * Rejects unplanned material scopes after stock locks without acquiring another material lock.
   *
   * @param scopes the actual material scopes found under stock locks
   */
  void assertOrderLinkedMaterialWrite(@NotNull Collection<OrderMaterialScope> scopes);

  /**
   * Credits a writer-selected promise from this action's genuinely new suitable provision.
   *
   * @param provision the actual stock facts and explicit selection before any merge
   */
  void recordMaterialProvision(MaterialProvision provision);
}
