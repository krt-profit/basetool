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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRelocationCount;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Lager's part of a patch change's relocation (REQ-PATCH-025, ADR-0383): the location of every
 * row from before the cutoff changes in place, as an exception to the append-only rule
 * (REQ-INV-001), so earmarks, pledges and offers stay attached.
 */
@Service
@RequiredArgsConstructor
class InventoryStockRelocation implements StockRelocation {

  /** The most row ids one statement binds. */
  private static final int MAX_IDS_PER_STATEMENT = 4_096;

  private final InventoryRelocationRepository relocations;
  private final InventoryItemRepository rows;
  private final InventoryOrderWrites orderWrites;
  private final LiveSyncPublisher liveSync;

  @Override
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable List<StockRelocationCount> countByLocation(
      @NotNull UUID holder, @NotNull Instant createdBefore) {
    Map<List<UUID>, StockRelocationCount> counts = new LinkedHashMap<>();
    for (InventoryRelocationRepository.LocationGroup group :
        relocations.countByLocation(holder, createdBefore)) {
      StockRelocationCount count = toCount(group);
      counts.merge(
          Arrays.asList(count.locationId(), count.orgUnitId()),
          count,
          (a, b) ->
              new StockRelocationCount(
                  a.locationId(),
                  a.locationName(),
                  a.orgUnitId(),
                  a.orgUnitName(),
                  a.rows() + b.rows()));
    }
    return List.copyOf(counts.values());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int relocate(
      @NotNull UUID holder, @NotNull Instant createdBefore, @NotNull UUID targetLocationId) {
    orderWrites.lockEligibility();
    List<InventoryRelocationRepository.MovableRow> planned =
        relocations.findMovable(holder, createdBefore, targetLocationId);
    if (planned.isEmpty()) {
      return 0;
    }
    Set<Long> lots = new TreeSet<>();
    for (InventoryRelocationRepository.MovableRow row : planned) {
      lots.add(lotKey(holder, row, row.getLocationId()));
      lots.add(lotKey(holder, row, targetLocationId));
    }
    orderWrites.lockStockLots(lots);
    List<InventoryRelocationRepository.MovableRow> locked =
        relocations.findMovable(holder, createdBefore, targetLocationId);
    for (InventoryRelocationRepository.MovableRow row : locked) {
      if (!lots.contains(lotKey(holder, row, row.getLocationId()))) {
        throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
      }
    }
    List<UUID> ids =
        rows.lockRowIdsOrderById(
            locked.stream().map(InventoryRelocationRepository.MovableRow::getId).toList());
    int moved = 0;
    Instant now = Instant.now();
    for (List<UUID> batch : batches(ids)) {
      moved += relocations.moveTo(batch, targetLocationId, now);
    }
    if (moved > 0) {
      liveSync.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), List.of("stock", "stock-levels"));
      liveSync.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board"));
    }
    return moved;
  }

  /**
   * Derives the advisory key of a row's lot at a location.
   *
   * @param holder the holder
   * @param row the row's lot facts
   * @param locationId the location
   * @return the lot lock key
   */
  private static long lotKey(
      @NotNull UUID holder,
      @NotNull InventoryRelocationRepository.MovableRow row,
      @NotNull UUID locationId) {
    return InventoryLotLockRepository.lotKey(
        holder,
        StockLot.key(
            row.getMaterialId(),
            row.getGameItemId(),
            locationId,
            row.getQuality(),
            Boolean.TRUE.equals(row.getStolen())));
  }

  /**
   * Folds a query group into a count: personal rows count as the holder's own whatever unit they
   * are stamped with, the others as their pool's.
   *
   * @param group the query group
   * @return the count
   */
  private static @NotNull StockRelocationCount toCount(
      @NotNull InventoryRelocationRepository.LocationGroup group) {
    boolean pool = !Boolean.TRUE.equals(group.getPersonal()) && group.getOrgUnitId() != null;
    return new StockRelocationCount(
        group.getLocationId(),
        group.getLocationName(),
        pool ? group.getOrgUnitId() : null,
        pool ? group.getOrgUnitName() : null,
        group.getRowCount());
  }

  /**
   * Splits ids into statement-sized batches.
   *
   * @param ids the ids
   * @return the batches, in order
   */
  private static @NotNull List<List<UUID>> batches(@NotNull Collection<UUID> ids) {
    List<UUID> list = List.copyOf(new HashSet<>(ids));
    List<List<UUID>> batches = new ArrayList<>();
    for (int start = 0; start < list.size(); start += MAX_IDS_PER_STATEMENT) {
      batches.add(list.subList(start, Math.min(start + MAX_IDS_PER_STATEMENT, list.size())));
    }
    return batches;
  }
}
