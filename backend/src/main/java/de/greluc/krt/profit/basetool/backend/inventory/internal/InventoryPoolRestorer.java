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
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolEntryState;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRestoreResult;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryPoolRestoreDecisions.Resolved;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Executes confirmed historical entry restorations through the owning physical stock writer. */
@Service
@RequiredArgsConstructor
class InventoryPoolRestorer {
  private static final double EPSILON = AllocationReductions.REDUCTION_EPSILON;

  private final InventoryOrderWrites orderWrites;
  private final InventoryItemRepository inventoryRepository;
  private final InventoryPoolRestoreFacts restoreFacts;
  private final InventoryPoolRestoreDecisions decisions;
  private final StockChangeObserver stockChangeObserver;
  private final AuditRecorder auditRecorder;

  /**
   * Executes one exact confirmed prefix after validating its fresh physical and owning decisions.
   *
   * @param expected the immutable program admitted by the complete first resource plan
   * @return the owning result of each historical request in its confirmed order
   */
  @Transactional(propagation = Propagation.MANDATORY)
  List<PoolRestoreResult> restorePrepared(InventoryPoolRestoreProgram expected) {
    var requests = expected.steps().stream().map(InventoryPoolRestoreProgram.Step::intent).toList();
    var current =
        restoreFacts.read(requests.stream().map(step -> step.request().entryId()).toList());
    var actual = InventoryPoolRestoreProgram.plan(requests, current, decisions);
    orderWrites.revalidateObservedResources(
        actual.resources(), actual.lotKeys(), actual.targetScopes());
    for (int i = 0; i < expected.steps().size(); i++) {
      if (!expected.steps().get(i).sameSelection(actual.steps().get(i))) {
        throw new ObjectOptimisticLockingFailureException(
            InventoryItem.class, requests.get(i).request().entryId());
      }
    }
    orderWrites.requireRestorationEvidence(
        InventoryLotRestoreProofs.identities(
            actual.steps().stream()
                .flatMap(step -> InventoryLotRestoreProofs.additions(step).parts().stream())
                .toList()));
    orderWrites.assertRows(actual.resources());
    orderWrites.assertObservedTargets(actual.targetOrders(), actual.targetScopes());
    for (var step : actual.steps()) {
      if (!step.decision().changes()) {
        continue;
      }
      var request = step.intent().request();
      PoolEntryState target = step.decision().targetState();
      if (target == null) {
        remove(existing(request.entryId()));
      } else if (step.before().isEmpty()) {
        recreate(target, Objects.requireNonNull(step.decision().target()));
      } else {
        rewrite(
            existing(request.entryId()), target, Objects.requireNonNull(step.decision().target()));
      }
    }
    inventoryRepository.flush();
    return actual.results();
  }

  /** Loads only a declared, already guarded and locked physical identity for the owning writer. */
  private InventoryItem existing(UUID id) {
    return inventoryRepository
        .findById(id)
        .orElseThrow(() -> new ObjectOptimisticLockingFailureException(InventoryItem.class, id));
  }

  /**
   * Removes an entry the client's writes created, with its earmarks and the offers on it.
   *
   * @param row the entry
   */
  private void remove(@NotNull InventoryItem row) {
    final String label = InventoryAuditLabels.label(row);
    final UUID holder = row.getUser().getId();
    UUID id = row.getId();
    stockChangeObserver.beforeDelete(List.of(id), StockChangeReason.STOCK);
    orderWrites.assertRow(row);
    inventoryRepository.delete(row);
    inventoryRepository.flush();
    audit(id, label, holder, 0.0, true);
  }

  /**
   * Recreates an entry the client's writes removed, under its own id.
   *
   * @param target the state to restore
   * @param resolved what it names
   */
  private void recreate(@NotNull PoolEntryState target, @NotNull Resolved resolved) {
    inventoryRepository.insertSharedEntry(
        target.id(),
        target.holder(),
        resolved.material() == null ? null : resolved.material().getId(),
        resolved.gameItem() == null ? null : resolved.gameItem().getId(),
        resolved.location().getId(),
        resolved.quality(),
        ScuAmounts.roundToScuScale(target.amount()),
        resolved.stolen(),
        target.poolUnitId());
    InventoryItem row =
        inventoryRepository
            .findById(target.id())
            .orElseThrow(() -> new IllegalStateException("The recreated entry is missing"));
    row.replaceQuantity(target.amount(), Objects.requireNonNull(target.completeQuantity()));
    syncEarmarks(row, target);
    orderWrites.assertRow(row);
    inventoryRepository.saveAndFlush(row);
    audit(row.getId(), InventoryAuditLabels.label(row), target.holder(), target.amount(), false);
  }

  /**
   * Gives an existing entry back its earlier holder, pool, lot, amount and earmarks; an entry that
   * already has them is left untouched.
   *
   * @param row the locked entry
   * @param target the state to restore
   * @param resolved what it names
   */
  private void rewrite(
      @NotNull InventoryItem row, @NotNull PoolEntryState target, @NotNull Resolved resolved) {
    if (InventoryPoolRestoreDecisions.sameContent(
        InventoryPoolEntryCommands.stateOf(row), target)) {
      return;
    }
    final double previous = InventoryPoolEntryCommands.held(row);
    double amount = ScuAmounts.roundToScuScale(target.amount());
    row.setUser(resolved.holder());
    row.setOwningOrgUnit(resolved.pool());
    row.setMaterial(resolved.material());
    row.setGameItem(resolved.gameItem());
    row.setLocation(resolved.location());
    row.setQuality(resolved.quality());
    row.setStolen(resolved.stolen());
    row.replaceQuantity(amount, Objects.requireNonNull(target.completeQuantity()));
    syncEarmarks(row, target);
    orderWrites.assertRow(row);
    inventoryRepository.saveAndFlush(row);
    if (amount + EPSILON < previous) {
      stockChangeObserver.lower(row.getId(), amount, StockChangeReason.STOCK);
    }
    audit(row.getId(), InventoryAuditLabels.label(row), target.holder(), amount, false);
  }

  /**
   * Makes an entry's job-order and mission earmarks those of a state: removes the others, sets the
   * amounts and adds the missing ones; a job-order earmark kept keeps its delivered flag.
   *
   * @param row the entry
   * @param target the state to restore
   */
  private void syncEarmarks(@NotNull InventoryItem row, @NotNull PoolEntryState target) {
    orderWrites.assertRow(row);
    orderWrites.assertOrders(target.jobOrders().keySet());
    Map<UUID, Double> orders = new HashMap<>(target.jobOrders());
    row.getJobOrderAllocations().removeIf(slice -> !orders.containsKey(slice.getJobOrderId()));
    for (InventoryJobOrderAllocation slice : row.getJobOrderAllocations()) {
      slice.setAmount(rounded(Objects.requireNonNull(orders.remove(slice.getJobOrderId()))));
    }
    orders.forEach(
        (order, amount) -> InventoryAllocations.addJobOrder(row, order, rounded(amount), false));
    Map<UUID, Double> missions = new HashMap<>(target.missions());
    row.getMissionAllocations().removeIf(slice -> !missions.containsKey(slice.getMissionId()));
    for (InventoryMissionAllocation slice : row.getMissionAllocations()) {
      slice.setAmount(rounded(Objects.requireNonNull(missions.remove(slice.getMissionId()))));
    }
    missions.forEach(
        (mission, amount) -> InventoryAllocations.addMission(row, mission, rounded(amount)));
  }

  /**
   * Rounds an earmark's journaled amount to the SCU scale.
   *
   * @param amount the amount
   * @return the rounded amount
   */
  private static double rounded(double amount) {
    return ScuAmounts.roundToScuScale(amount);
  }

  /**
   * Audits one restored entry.
   *
   * @param id the entry
   * @param label the entry's {@code material @ location} label
   * @param holder the entry's holder
   * @param amount the amount it holds afterwards, {@code 0} when it was removed
   * @param removed whether the restore removed it
   */
  private void audit(
      @NotNull UUID id,
      @NotNull String label,
      @NotNull UUID holder,
      double amount,
      boolean removed) {
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_RESTORED,
        id,
        label,
        holder,
        AuditDetails.of("qty", amount).with("removed", removed));
  }
}
