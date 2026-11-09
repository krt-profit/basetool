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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReassignmentParticipant;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemReconciliationBusyException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Preserves stock entries and their allocations while catalogue identities converge. */
@Component
@RequiredArgsConstructor
public class InventoryItemReassignment implements GameItemReassignmentParticipant {

  private final InventoryOrderWrites orderWrites;
  private final InventoryItemReassignmentRepository repository;
  private final InventoryItemRepository inventoryRepository;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSyncPublisher;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int reassignGameItem(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    orderWrites.lockEligibility();
    Set<Long> locks = new TreeSet<>();
    for (var lot : repository.lots(retiredId, survivorId)) {
      locks.add(
          InventoryStockCommands.lotLockKey(
              lot.getMemberId(),
              StockLot.key(null, retiredId, lot.getLocationId(), null, lot.getStolen())));
      locks.add(
          InventoryStockCommands.lotLockKey(
              lot.getMemberId(),
              StockLot.key(null, survivorId, lot.getLocationId(), null, lot.getStolen())));
    }
    if (!InventoryStockCommands.tryLockInOrder(inventoryRepository, locks)) {
      throw new GameItemReconciliationBusyException();
    }
    int changed =
        record(
            repository.stock(retiredId, survivorId),
            retiredId,
            survivorId,
            AuditEventType.INVENTORY_ITEM_CATALOGUE_REASSIGNED,
            "inventory_item");
    Map<UUID, InventoryItemReassignmentRepository.FloorState> before =
        floorStates(retiredId, survivorId);
    List<UUID> combined = repository.combineFloors(retiredId, survivorId);
    List<UUID> retired = repository.retireCombinedFloors(retiredId, survivorId);
    List<UUID> moved = repository.floors(retiredId, survivorId);
    Map<UUID, InventoryItemReassignmentRepository.FloorState> after =
        floorStates(retiredId, survivorId);
    changed += recordFloors(combined, retiredId, survivorId, before, after);
    changed += recordFloors(retired, retiredId, survivorId, before, after);
    changed += recordFloors(moved, retiredId, survivorId, before, after);
    if (changed > 0) {
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), List.of("stock", "stock-levels"));
    }
    return changed;
  }

  private Map<UUID, InventoryItemReassignmentRepository.FloorState> floorStates(
      UUID retiredId, UUID survivorId) {
    return repository.floorStates(retiredId, survivorId).stream()
        .collect(
            Collectors.toMap(
                InventoryItemReassignmentRepository.FloorState::getId, Function.identity()));
  }

  private int recordFloors(
      List<UUID> rows,
      UUID retiredId,
      UUID survivorId,
      Map<UUID, InventoryItemReassignmentRepository.FloorState> before,
      Map<UUID, InventoryItemReassignmentRepository.FloorState> after) {
    for (UUID id : rows) {
      InventoryItemReassignmentRepository.FloorState previous =
          java.util.Objects.requireNonNull(before.get(id));
      InventoryItemReassignmentRepository.FloorState current = after.get(id);
      AuditDetails details =
          AuditDetails.of("retiredItem", retiredId)
              .with("survivorItem", survivorId)
              .with("unit", previous.getUnitId())
              .with("minimumFrom", previous.getMinimum())
              .with("targetFrom", previous.getTargetAmount());
      if (current != null) {
        details =
            details
                .with("minimumTo", current.getMinimum())
                .with("targetTo", current.getTargetAmount());
      } else {
        details = details.with("retired", true);
      }
      auditRecorder.record(
          AuditEventType.MIN_STOCK_LEVEL_CATALOGUE_REASSIGNED, id, null, null, details);
    }
    return rows.size();
  }

  private int record(
      List<UUID> rows, UUID retiredId, UUID survivorId, AuditEventType type, String table) {
    for (UUID id : rows) {
      auditRecorder.record(
          type,
          id,
          null,
          null,
          "retiredItem=" + retiredId + ";survivorItem=" + survivorId + ";subject=" + table);
    }
    return rows.size();
  }
}
