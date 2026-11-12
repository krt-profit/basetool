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

import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEntryState;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityEvidence;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityFragment;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Reads complete immutable named-entry facts for restoration without managed row side effects. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
class InventoryPoolRestoreFacts {
  private final InventoryItemRepository rows;
  private final InventoryStockPlanningReads planningReads;

  /**
   * An existing physical row, including its actual missions and complete ordered quantity proof.
   */
  record Entry(
      InventoryStockRowSnapshot row,
      Map<UUID, Double> missions,
      StockQuantityComposition quantity) {
    Entry {
      missions = Map.copyOf(missions);
    }

    /** Returns the available shared view, preserving physical presence for a wrong-kind row. */
    @Nullable
    PoolEntryState sharedState() {
      var pool = row.getOwningOrgUnit();
      return row.getPersonal() || pool == null
          ? null
          : new PoolEntryState(
              row.getId(),
              row.getUser().getId(),
              pool.getId(),
              StockLot.key(
                  row.getMaterial() == null ? null : row.getMaterial().getId(),
                  row.getGameItem() == null ? null : row.getGameItem().getId(),
                  row.getLocation().getId(),
                  row.getQuality(),
                  row.getStolen()),
              row.getAmount(),
              row.getVersion(),
              row.getJobOrders(),
              missions,
              quantity.knownPhysical(),
              quantity);
    }
  }

  /** Returns an explicit immutable presence value for every declared named physical identity. */
  Map<UUID, Optional<Entry>> read(Collection<UUID> rowIds) {
    Map<UUID, Optional<Entry>> result = new LinkedHashMap<>();
    rowIds.forEach(id -> result.put(id, Optional.empty()));
    if (result.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Map<UUID, Double>> missions = new HashMap<>();
    for (var slice : rows.findRestoreMissionSlices(result.keySet())) {
      missions
          .computeIfAbsent(slice.getRowId(), _ -> new HashMap<>())
          .put(slice.getMissionId(), slice.getAmount());
    }
    Map<UUID, List<StockQuantityEvidence>> quantities = new HashMap<>();
    for (var quantity : rows.findRestoreQuantities(result.keySet())) {
      quantities.put(quantity.getRowId(), List.copyOf(quantity.getQuantityProvenance()));
    }
    for (var row : planningReads.readRows(result.keySet())) {
      List<StockQuantityEvidence> stored = quantities.get(row.getId());
      if (stored == null) {
        throw new ObjectOptimisticLockingFailureException(InventoryItem.class, row.getId());
      }
      StockQuantityComposition quantity =
          stored.isEmpty() && row.getAmount() > 0.0
              ? StockQuantityComposition.known(
                  List.of(
                      new StockQuantityFragment(
                          row.getId(), 0L, ScuAmounts.toQuanta(row.getAmount()))))
              : new StockQuantityComposition(stored);
      try {
        InventoryQuantityComposition.validated(quantity);
        if (quantity.totalQuanta() != ScuAmounts.toQuanta(row.getAmount())) {
          throw new IllegalArgumentException("Stored evidence must describe its physical amount");
        }
      } catch (IllegalArgumentException | ArithmeticException _) {
        throw new ObjectOptimisticLockingFailureException(InventoryItem.class, row.getId());
      }
      result.put(
          row.getId(),
          Optional.of(new Entry(row, missions.getOrDefault(row.getId(), Map.of()), quantity)));
    }
    return Map.copyOf(result);
  }
}
