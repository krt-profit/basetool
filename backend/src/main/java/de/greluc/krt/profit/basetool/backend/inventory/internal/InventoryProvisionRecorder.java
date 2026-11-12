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

import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialProvision;
import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialProvisionSelection;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Builds explicit provision facts from the ordinary owning Inventory allocation action. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryProvisionRecorder {
  private final InventoryOrderWrites orderWrites;

  /**
   * Plans initial order targets and possible merge destinations before the first stock insert.
   *
   * @param item the assembled new stock row
   * @param allocations the initial order allocations
   * @param merge actual per-action merge policy of this incoming row
   */
  public void prepareInitial(
      InventoryItem item, List<InventoryAllocationInput> allocations, boolean merge) {
    orderWrites.lockBookIn(
        new StockCommands.StockBookInScope(
            item.getUser().getId(),
            new StockLot(
                item.getMaterial(),
                item.getGameItem(),
                item.getLocation(),
                item.getQuality(),
                item.getStolen()),
            allocations.stream().map(InventoryAllocationInput::targetId).toList(),
            Boolean.TRUE.equals(item.getPersonal()),
            item.getOwningOrgUnit() == null ? null : item.getOwningOrgUnit().getId(),
            merge));
  }

  /**
   * Rejects a unit-credit selection on a non-order dimension.
   *
   * @param write the allocation being changed
   */
  public void validateDimension(InventoryAllocationWriteDto write) {
    if (write.provision() != null && write.field() != InventoryAllocationDimension.JOB_ORDER) {
      throw new BadRequestException("orders.claim.provision.only_material_order");
    }
  }

  /**
   * Rejects material provision selection on mission-only initial allocations.
   *
   * @param allocations the mission allocations supplied at check-in
   */
  public void assertUnattributed(List<InventoryAllocationInput> allocations) {
    if (allocations.stream().anyMatch(allocation -> allocation.provision() != null)) {
      throw new BadRequestException("orders.claim.provision.only_material_order");
    }
  }

  /**
   * Records selected first-provision facts before a new stock row is merged.
   *
   * @param item the inserted physical row
   * @param allocations its initial order allocations
   */
  public void recordInitial(InventoryItem item, List<InventoryAllocationInput> allocations) {
    for (InventoryAllocationInput allocation : allocations) {
      record(item, allocation.targetId(), allocation.provision(), allocation.amount());
    }
  }

  /**
   * Sends a genuine new quantity budget and physical identity to the target-owned contribution
   * gate.
   *
   * @param item the validated actual stock row
   * @param orderId the target order
   * @param selection the explicitly selected promise, or null for unattributed stock
   * @param amount newly provided or newly allocated quantity in this action
   */
  public void record(
      InventoryItem item,
      UUID orderId,
      @Nullable MaterialProvisionSelection selection,
      double amount) {
    if (selection == null) {
      return;
    }
    if (item.getMaterial() == null) {
      throw new BadRequestException("orders.claim.provision.only_material_order");
    }
    orderWrites.recordProvision(
        new MaterialProvision(
            orderId,
            item.getId(),
            item.getMaterial().getId(),
            item.getQuality(),
            ScuAmounts.toQuanta(amount),
            item.getQuantityProvenance(),
            selection));
  }
}
