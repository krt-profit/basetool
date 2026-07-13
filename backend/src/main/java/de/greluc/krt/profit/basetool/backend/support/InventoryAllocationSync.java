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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryJobOrderAllocation;
import de.greluc.krt.profit.basetool.backend.model.InventoryMissionAllocation;
import java.util.Iterator;
import java.util.UUID;

/**
 * Single implementation of the Variante-C soak mirror (REQ-INV-027): it rewrites an entry's {@link
 * InventoryItem#getJobOrderAllocations()} / {@link InventoryItem#getMissionAllocations()}
 * collections so they reflect the entry's single {@code jobOrder}/{@code mission} scalar, each as
 * one allocation carrying the entry's <em>full</em> current {@link InventoryItem#getAmount()}.
 *
 * <p>During the soak the scalar remains the source of truth for the single-assignment create/update
 * and every copy/deposit/decrement write path, while the allocation-reading views (stacking,
 * fulfilment sums, the mapper chips and rest) read only the allocation tables. Every write path
 * that seeds a new row, copies a source row or lowers an entry's amount must therefore call {@link
 * #mirrorScalars(InventoryItem)} <em>after</em> the scalars and amount are final, or the
 * allocation-based reads would drift from the row (a fulfilment credit that is too high, or a
 * transferred row that shows no allocation). Extracting it here — mirroring the {@link
 * InventoryAuditLabels} precedent — keeps {@code InventoryItemService}, {@code
 * InventoryCheckoutService} and {@code RefineryOrderService} on one byte-identical implementation
 * instead of each carrying a private copy that could diverge. Deleted with the scalars at the
 * column drop, once the per-allocation write endpoints own the collections outright.
 */
public final class InventoryAllocationSync {

  private InventoryAllocationSync() {}

  /**
   * Rebuilds {@code item}'s allocation collections from its {@code jobOrder}/{@code mission}
   * scalars: a set scalar becomes one allocation carrying the entry's full amount, an unset scalar
   * leaves that dimension empty.
   *
   * <p>The slice carrying the current scalar is updated <em>in place</em> (its {@code amount} is
   * re-set); only slices for a different or now-absent assignment are removed, and a slice is
   * inserted only when the scalar is newly set. This deliberately avoids the delete-then-re-add of
   * the <em>same</em> {@code (inventory_item_id, job_order_id)} pair: the unique constraint is not
   * deferrable (V216) and Hibernate flushes the fresh INSERT before the orphan DELETE, so a naive
   * clear-and-re-add would violate the constraint whenever a <em>persisted</em> entry is
   * re-mirrored to the same assignment — precisely the checkout / handover amount-reduce paths.
   * Relies on the entry's {@code cascade = ALL} + {@code orphanRemoval}: a removed slice is deleted
   * on flush, an added managed child inserted.
   *
   * @param item the managed entry whose scalar assignments to mirror into its allocation
   *     collections; never {@code null}. Its {@code amount} must already be at its final
   *     post-mutation value.
   */
  public static void mirrorScalars(InventoryItem item) {
    if (item.getJobOrder() == null) {
      item.getJobOrderAllocations().clear();
    } else {
      UUID orderId = item.getJobOrder().getId();
      InventoryJobOrderAllocation slice = null;
      Iterator<InventoryJobOrderAllocation> it = item.getJobOrderAllocations().iterator();
      while (it.hasNext()) {
        InventoryJobOrderAllocation a = it.next();
        if (slice == null && a.getJobOrder() != null && orderId.equals(a.getJobOrder().getId())) {
          slice = a;
        } else {
          it.remove();
        }
      }
      if (slice == null) {
        slice = new InventoryJobOrderAllocation();
        slice.setInventoryItem(item);
        slice.setJobOrder(item.getJobOrder());
        item.getJobOrderAllocations().add(slice);
      }
      slice.setAmount(item.getAmount());
    }

    if (item.getMission() == null) {
      item.getMissionAllocations().clear();
    } else {
      UUID missionId = item.getMission().getId();
      InventoryMissionAllocation slice = null;
      Iterator<InventoryMissionAllocation> it = item.getMissionAllocations().iterator();
      while (it.hasNext()) {
        InventoryMissionAllocation a = it.next();
        if (slice == null && a.getMission() != null && missionId.equals(a.getMission().getId())) {
          slice = a;
        } else {
          it.remove();
        }
      }
      if (slice == null) {
        slice = new InventoryMissionAllocation();
        slice.setInventoryItem(item);
        slice.setMission(item.getMission());
        item.getMissionAllocations().add(slice);
      }
      slice.setAmount(item.getAmount());
    }
  }
}
