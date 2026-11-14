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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockPatchWipe;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeRow;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeShare;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wipes the stock a game patch wiped (REQ-PATCH-012): the rows are locked like every stock write's,
 * with the job orders' responsibility check excepted (REQ-PATCH-014, ADR-0382), and deleted by
 * their ids; earmarks cascade, and one summary event goes into the Lager's trail.
 */
@Service
@RequiredArgsConstructor
public class InventoryPatchWipeService implements StockPatchWipe {

  /** The sections of the shared Lager room a wipe changes. */
  private static final List<String> STOCK_SECTIONS = List.of("stock", "stock-levels");

  private final InventoryItemRepository rows;
  private final InventoryOrderWrites orderWrites;
  private final StockChangeObserver stockChangeObserver;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSyncPublisher;

  @Override
  @Transactional(readOnly = true)
  public @NotNull @Unmodifiable List<StockWipeRow> rowsCreatedBefore(@NotNull Instant cutoff) {
    return List.copyOf(rows.findWipeRowsCreatedBefore(cutoff));
  }

  @Override
  @Transactional(readOnly = true)
  public long countEarmarkedOrders(@NotNull Collection<UUID> rowIds) {
    return new HashSet<>(InventoryOrderWrites.readBatches(rowIds, rows::findAllocatedOrderIds))
        .size();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull @Unmodifiable List<StockWipeRow> lockForWipe(@NotNull Collection<UUID> rowIds) {
    return orderWrites.duringGameFactLoss(() -> {
      orderWrites.prepareWipe(rowIds);
      return List.copyOf(InventoryOrderWrites.readBatches(rowIds, rows::findWipeRowsByIdIn));
    });
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull StockWipeResult wipe(
      @NotNull Map<? extends StockWipeShare, ? extends Collection<UUID>> rowsByShare,
      @NotNull UUID patchChangeId,
      @NotNull String patchLabel) {
    return orderWrites.duringGameFactLoss(() -> {
      Set<UUID> ids = new LinkedHashSet<>();
      rowsByShare.values().forEach(ids::addAll);
      orderWrites.assertRows(ids);
      Set<UUID> orders =
          new HashSet<>(InventoryOrderWrites.readBatches(ids, rows::findAllocatedOrderIds));
      int holders =
          new HashSet<>(InventoryOrderWrites.readBatches(ids, rows::findHolderIds)).size();
      stockChangeObserver.beforeDelete(ids, StockChangeReason.PATCH_WIPE);
      int removed = rows.deleteAllByIdIn(ids);
      AuditDetails details =
          AuditDetails.of("rows", removed)
              .with("members", holders)
              .with("jobOrders", orders.size())
              .with("patchChange", patchChangeId);
      rowsByShare.forEach((share, shareRows) -> details.with(share.countKey(), shareRows.size()));
      auditRecorder.record(
          AuditEventType.INVENTORY_WIPED_BY_PATCH, patchChangeId, patchLabel, null, details);
      if (removed > 0) {
        liveSyncPublisher.publishAfterCommit(
            LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), STOCK_SECTIONS);
      }
      return new StockWipeResult(removed, holders, orders);
    });
  }
}
