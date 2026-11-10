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

import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolAllocationTarget;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands.StockBookInScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates all advisory lot locks before the row locks of a pool-entry command. */
@Service
@RequiredArgsConstructor
class InventoryPoolLotLocks {

  private final InventoryItemRepository inventoryRepository;
  private final InventoryOrderWrites orderWrites;

  /**
   * Locks source and destination lots in key order, then their rows in id order (ADR-0229). The
   * caller holds the shared hierarchy lock before entering this protocol.
   *
   * @param entryIds the named source entries
   * @param relocations the destination changes of named entries
   * @param allocationTargets new order allocations of current rows
   * @param bookings exact initial stock destinations and actual merge policies
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void lockLots(
      @NotNull Collection<UUID> entryIds,
      @NotNull Collection<PoolRelocation> relocations,
      @NotNull Collection<PoolAllocationTarget> allocationTargets,
      @NotNull Collection<StockBookInScope> bookings) {
    Set<Long> keys = new TreeSet<>();
    Set<UUID> rows = new TreeSet<>();
    Set<UUID> entries = new HashSet<>(entryIds);
    relocations.forEach(relocation -> entries.add(relocation.entryId()));
    List<TargetLot> targets = new ArrayList<>();
    if (!entries.isEmpty()) {
      Map<UUID, InventoryItemRepository.RowLot> byId = new HashMap<>();
      for (InventoryItemRepository.RowLot row : inventoryRepository.findRowLots(entries)) {
        String lot =
            StockLot.key(
                row.getMaterialId(),
                row.getGameItemId(),
                row.getLocationId(),
                row.getQuality(),
                Boolean.TRUE.equals(row.getStolen()));
        keys.add(InventoryStockCommands.lotLockKey(row.getHolderId(), lot));
        rows.add(row.getId());
        byId.put(row.getId(), row);
        targets.add(
            new TargetLot(
                row.getHolderId(),
                row.getMaterialId(),
                row.getGameItemId(),
                row.getLocationId(),
                row.getQuality(),
                Boolean.TRUE.equals(row.getStolen())));
      }
      for (PoolRelocation relocation : relocations) {
        InventoryItemRepository.RowLot row = byId.get(relocation.entryId());
        if (row != null) {
          TargetLot target = TargetLot.of(row, relocation);
          keys.add(InventoryStockCommands.lotLockKey(target.holder(), target.key()));
          targets.add(target);
        }
      }
    }
    targets.forEach(target -> rows.addAll(lotRowIds(target)));
    Set<UUID> planned = Set.copyOf(rows);
    orderWrites.prepareWrites(rows, bookings, List.of(), allocationTargets, keys);
    targets.forEach(target -> rows.addAll(lotRowIds(target)));
    if (!planned.equals(rows)) {
      throw new de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException(
          "error.inventory.concurrent_scope_changed");
    }
  }

  /**
   * Reads the ids of a holder's rows of a lot named by its ids, personal and shared, without
   * locking them.
   *
   * @param lot the holder and the lot
   * @return the row ids
   */
  private @NotNull List<UUID> lotRowIds(@NotNull TargetLot lot) {
    return lot.materialId() != null
        ? inventoryRepository.findMaterialLotRowIds(
            lot.holder(),
            lot.materialId(),
            lot.locationId(),
            lot.quality() == null ? 0 : lot.quality(),
            lot.stolen())
        : inventoryRepository.findItemLotRowIds(
            lot.holder(), Objects.requireNonNull(lot.gameItemId()), lot.locationId(), lot.stolen());
  }

  /**
   * A holder's lot named by its ids.
   *
   * @param holder the holder
   * @param materialId the material, or {@code null} for an item
   * @param gameItemId the item, or {@code null} for a material
   * @param locationId the location
   * @param quality the quality, or {@code null}
   * @param stolen the stolen marker
   */
  private record TargetLot(
      @NotNull UUID holder,
      @Nullable UUID materialId,
      @Nullable UUID gameItemId,
      @NotNull UUID locationId,
      @Nullable Integer quality,
      boolean stolen) {

    /**
     * The lot an entry is relocated into.
     *
     * @param row the entry's lot
     * @param relocation what changes
     * @return the target lot
     */
    static @NotNull TargetLot of(
        @NotNull InventoryItemRepository.RowLot row, @NotNull PoolRelocation relocation) {
      return new TargetLot(
          relocation.holder() != null ? relocation.holder() : row.getHolderId(),
          row.getMaterialId(),
          row.getGameItemId(),
          relocation.locationId() != null ? relocation.locationId() : row.getLocationId(),
          row.getQuality(),
          relocation.stolen() != null ? relocation.stolen() : Boolean.TRUE.equals(row.getStolen()));
    }

    /**
     * The stock lot key ({@link StockLot#key}) whose advisory lock guards this holder's lot.
     *
     * @return the lot key, without the holder
     */
    @NotNull
    String key() {
      return StockLot.key(materialId, gameItemId, locationId, quality, stolen);
    }
  }
}
