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

import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolAllocationTarget;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRelocation;
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
   * @param holderLots further lots the command changes
   * @param entryIds the named source entries
   * @param relocations the destination changes of named entries
   * @param requestedScopes material scopes introduced by book-ins
   * @param allocationTargets new order allocations of current rows
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void lockLots(
      @NotNull Map<UUID, List<StockLot>> holderLots,
      @NotNull Collection<UUID> entryIds,
      @NotNull Collection<PoolRelocation> relocations,
      @NotNull Collection<OrderMaterialScope> requestedScopes,
      @NotNull Collection<PoolAllocationTarget> allocationTargets) {
    Set<Long> keys = new TreeSet<>();
    Set<UUID> rows = new TreeSet<>();
    holderLots.forEach((holder, lots) ->
        lots.forEach(lot -> keys.add(InventoryStockCommands.lotLockKey(holder, lot.key()))));
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
    holderLots.forEach((holder, lots) -> lots.forEach(lot -> rows.addAll(lotRowIds(holder, lot))));
    targets.forEach(target -> rows.addAll(lotRowIds(target)));
    orderWrites.lockRowsAndTargets(rows, requestedScopes, allocationTargets);
    InventoryStockCommands.lockInOrder(inventoryRepository, keys);
    holderLots.forEach((holder, lots) -> lots.forEach(lot -> rows.addAll(lotRowIds(holder, lot))));
    targets.forEach(target -> rows.addAll(lotRowIds(target)));
    if (!rows.isEmpty()) {
      inventoryRepository.lockAllByIdOrderById(rows);
    }
    orderWrites.validateRows(rows);
  }

  /**
   * Reads the ids of a holder's rows of a lot, personal and shared, without locking them.
   *
   * @param holder the holder
   * @param lot the lot
   * @return the row ids
   */
  private @NotNull List<UUID> lotRowIds(@NotNull UUID holder, @NotNull StockLot lot) {
    return lotRowIds(
        new TargetLot(
            holder,
            lot.material() != null ? lot.material().getId() : null,
            lot.gameItem() != null ? lot.gameItem().getId() : null,
            lot.location().getId(),
            lot.quality(),
            lot.stolen()));
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
