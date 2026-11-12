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

import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookDirection;
import de.greluc.krt.profit.basetool.backend.inventory.api.RebookCommands.RebookRefusal;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLotWritePlans.RebookLot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Shares the ordinary rebooking source decision between normal steps and restoration prechecks. */
final class InventoryLotRebookSimulation {
  private InventoryLotRebookSimulation() {}

  /** Applies one owning canonical rebooking selection only to the supplied prospective state. */
  static InventoryLotBatchProgram.Rebooking select(
      @NotNull RebookLot intent,
      @NotNull InventoryLotProspectiveRows rows,
      @NotNull Set<UUID> memberships) {
    var request = intent.request();
    boolean toPool = request.direction() == RebookDirection.TO_POOL;
    boolean whole =
        InventoryItem.countsWholeUnits(request.lot().material(), request.lot().gameItem());
    if (whole && request.amount() % 1 != 0) {
      return new InventoryLotBatchProgram.Rebooking(
          request, RebookRefusal.UNIT_MISMATCH, List.of());
    }
    if (toPool && !memberships.contains(request.poolUnitId())) {
      return new InventoryLotBatchProgram.Rebooking(request, RebookRefusal.NOT_A_MEMBER, List.of());
    }
    var sources =
        rows.rebookSources(request.lot(), request.poolUnitId(), toPool).stream()
            .map(InventoryLotProspectiveRows.Row::rebookSource)
            .toList();
    var selected =
        InventoryStockDecisions.selectRebookSources(sources, request.amount(), toPool, whole);
    List<InventoryLotBatchProgram.Transfer> takes = new ArrayList<>();
    for (var take : selected.takes()) {
      var source = rows.require(take.key());
      BigDecimal amount = BigDecimal.valueOf(take.amount());
      InventoryLotProspectiveRows.Row target;
      if (toPool && !source.isPersonal() && amount.compareTo(source.getAmount()) == 0) {
        rows.place(source, request.lot(), false, request.poolUnitId());
        target = source;
      } else {
        target =
            rows.create(
                request.lot(),
                !toPool,
                toPool ? request.poolUnitId() : source.getUnit(),
                amount,
                rows.quantityPrefix(source, amount),
                source.getRestoredBy());
        rows.lower(source, amount);
      }
      var victims = rows.merge(target, whole);
      takes.add(
          new InventoryLotBatchProgram.Transfer(source.getKey(), target.getKey(), amount, victims));
    }
    return new InventoryLotBatchProgram.Rebooking(request, selected.refusal(), List.copyOf(takes));
  }
}
