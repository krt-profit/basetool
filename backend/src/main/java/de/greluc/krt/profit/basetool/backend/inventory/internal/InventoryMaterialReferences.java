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
import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueReconciliationBusyException;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialReferenceParticipant;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

/**
 * Reports the materials stock entries and stock floors reference (REQ-P4K-075) and moves them onto
 * the material a merge keeps (REQ-DATA-046): stock entries keep their amounts and allocations, and
 * a floor meeting one of the material that stays keeps the higher minimum and target.
 */
@Component
@RequiredArgsConstructor
public class InventoryMaterialReferences implements MaterialReferenceParticipant {

  /** The materials among {@code :ids} a stock entry or a stock floor references. */
  private static final String REFERENCED =
      "SELECT i.material.id FROM InventoryItem i WHERE i.material.id IN :ids"
          + " UNION SELECT l.material.id FROM StockLevel l WHERE l.material.id IN :ids";

  private final EntityManager entityManager;
  private final InventoryOrderWrites orderWrites;
  private final InventoryMaterialReassignmentRepository reassignments;
  private final InventoryItemRepository inventoryRepository;
  private final AuditRecorder auditRecorder;
  private final LiveSyncPublisher liveSyncPublisher;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Set<UUID> referencedMaterials(@NotNull Collection<UUID> materialIds) {
    return MaterialReferenceParticipant.referenced(entityManager, REFERENCED, materialIds);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int reassignMaterial(@NotNull UUID retiredId, @NotNull UUID survivorId) {
    lockLots(retiredId, survivorId);
    int changed =
        MaterialReferenceParticipant.recordMoved(
            auditRecorder,
            AuditEventType.INVENTORY_ITEM_CATALOGUE_REASSIGNED,
            reassignments.stock(retiredId, survivorId),
            retiredId,
            survivorId,
            "inventory_item");
    Map<UUID, InventoryItemReassignmentRepository.FloorState> before =
        floorStates(retiredId, survivorId);
    List<UUID> combined = reassignments.combineFloors(retiredId, survivorId);
    List<UUID> retired = reassignments.retireCombinedFloors(retiredId, survivorId);
    List<UUID> moved = reassignments.floors(retiredId, survivorId);
    Map<UUID, InventoryItemReassignmentRepository.FloorState> after =
        floorStates(retiredId, survivorId);
    for (List<UUID> floors : List.of(combined, retired, moved)) {
      changed += recordFloors(floors, retiredId, survivorId, before, after);
    }
    if (changed > 0) {
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.INVENTORY_ALL), List.of("stock", "stock-levels"));
      liveSyncPublisher.publishAfterCommit(
          LiveSyncTopic.global(LiveSyncTopicClass.MATERIALBOARD), List.of("board"));
    }
    return changed;
  }

  /**
   * Takes the eligibility lock and every lot lock of both materials without waiting.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @throws CatalogueReconciliationBusyException when a lot lock is held
   */
  private void lockLots(UUID retiredId, UUID survivorId) {
    orderWrites.lockEligibility();
    Set<Long> locks = new TreeSet<>();
    for (InventoryMaterialReassignmentRepository.MaterialLot lot :
        reassignments.lots(retiredId, survivorId)) {
      for (UUID material : List.of(retiredId, survivorId)) {
        locks.add(
            InventoryStockCommands.lotLockKey(
                lot.getMemberId(),
                StockLot.key(
                    material, null, lot.getLocationId(), lot.getQuality(), lot.getStolen())));
      }
    }
    if (!InventoryStockCommands.tryLockInOrder(inventoryRepository, locks)) {
      throw new CatalogueReconciliationBusyException();
    }
  }

  private Map<UUID, InventoryItemReassignmentRepository.FloorState> floorStates(
      UUID retiredId, UUID survivorId) {
    return reassignments.floorStates(retiredId, survivorId).stream()
        .collect(
            Collectors.toMap(
                InventoryItemReassignmentRepository.FloorState::getId, Function.identity()));
  }

  private int recordFloors(
      List<UUID> floors,
      UUID retiredId,
      UUID survivorId,
      Map<UUID, InventoryItemReassignmentRepository.FloorState> before,
      Map<UUID, InventoryItemReassignmentRepository.FloorState> after) {
    for (UUID id : floors) {
      InventoryItemReassignmentRepository.FloorState previous =
          Objects.requireNonNull(before.get(id));
      InventoryItemReassignmentRepository.FloorState current = after.get(id);
      AuditDetails details =
          AuditDetails.of("retiredMaterial", retiredId)
              .with("survivorMaterial", survivorId)
              .with("unit", previous.getUnitId())
              .with("minimumFrom", previous.getMinimum())
              .with("targetFrom", previous.getTargetAmount());
      if (current == null) {
        details.with("retired", true);
      } else {
        details.with("minimumTo", current.getMinimum()).with("targetTo", current.getTargetAmount());
      }
      auditRecorder.record(
          AuditEventType.MIN_STOCK_LEVEL_CATALOGUE_REASSIGNED, id, null, null, details);
    }
    return floors.size();
  }
}
