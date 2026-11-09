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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockWriteObserver;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Applies the target-owned prerequisite to every affected order slice of a stock write. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryOrderWrites {

  private final InventoryItemRepository rows;
  private final OrderLinkedStockWriteObserver observer;

  /** Takes the shared eligibility lock before any Inventory lot or row lock. */
  public void lockEligibility() {
    observer.beforeOrderLinkedStockWrite(List.of());
  }

  /**
   * Validates the order slices currently attached to a managed entry.
   *
   * @param row the stock entry about to change
   */
  public void assertRow(@NotNull InventoryItem row) {
    assertOrders(
        row.getJobOrderAllocations().stream()
            .map(InventoryJobOrderAllocation::getJobOrderId)
            .toList());
  }

  /**
   * Validates existing slices before a bulk deletion or rewrite.
   *
   * @param rowIds the stock rows about to change
   */
  public void assertRows(@NotNull Collection<UUID> rowIds) {
    if (!rowIds.isEmpty()) {
      assertOrders(rows.findAllocatedOrderIds(rowIds));
    }
  }

  /**
   * Validates explicitly added or removed target identities.
   *
   * @param orderIds the affected target orders
   */
  public void assertOrders(@NotNull Collection<UUID> orderIds) {
    observer.beforeOrderLinkedStockWrite(orderIds);
  }

  /**
   * Validates every nonpersonal row affected by a scoped wipe.
   *
   * @param adminAll whether all rows are in scope
   * @param activeUnit the pinned unit, or null
   * @param memberUnits the caller's scoped units
   */
  public void assertWipe(
      boolean adminAll, @Nullable UUID activeUnit, Collection<UUID> memberUnits) {
    assertOrders(rows.findAllocatedOrdersInWipeScope(adminAll, activeUnit, memberUnits));
  }
}
