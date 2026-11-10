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
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderLinkedStockWriteObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolAllocationTarget;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands.StockBookInScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Applies the target-owned prerequisite to every affected order slice of a stock write. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryOrderWrites {

  private final InventoryItemRepository rows;
  private final OrderLinkedStockWriteObserver observer;

  /**
   * Applies explicit provision attribution through the mandatory target-owned observer.
   *
   * @param provision the real provision and its selected matching participation
   */
  public void recordProvision(@NotNull MaterialProvision provision) {
    observer.recordMaterialProvision(provision);
  }

  /** Takes the shared eligibility lock before any Inventory lot or row lock. */
  public void lockEligibility() {
    observer.beforeOrderLinkedStockWrite(List.of());
  }

  /**
   * Validates the order slices currently attached to a managed entry.
   *
   * @param row the stock entry about to change
   */
  public void assertRow(@NotNull InventoryItem row) {
    List<UUID> orders =
        row.getJobOrderAllocations().stream()
            .map(InventoryJobOrderAllocation::getJobOrderId)
            .toList();
    Planning plan = planning();
    if (row.getId() == null) {
      plan.created.add(row);
    }
    plan.stockPhase = true;
    assertOrders(orders);
    observer.assertOrderLinkedMaterialWrite(
        row.getMaterial() == null
            ? List.of()
            : orders.stream()
                .map(order -> new OrderMaterialScope(order, row.getMaterial().getId()))
                .toList());
  }

  /**
   * Locks all current material scopes of stock rows before their stock locks.
   *
   * @param rowIds the rows about to change
   */
  public void lockRows(@NotNull Collection<UUID> rowIds) {
    lockRowsAndTargets(rowIds, List.of(), List.of());
  }

  /**
   * Locks existing and newly requested allocation scopes together before loading a writable row.
   *
   * @param rowId the current row
   * @param targetOrder the requested job order, or null for another allocation dimension
   */
  public void lockAllocation(@NotNull UUID rowId, @Nullable UUID targetOrder) {
    lockRowsAndTargets(
        List.of(rowId),
        List.of(),
        targetOrder == null ? List.of() : List.of(new PoolAllocationTarget(rowId, targetOrder)));
  }

  /**
   * Locks initial allocation scopes on a material that has no stock row yet.
   *
   * @param materialId the material being provided
   * @param orderIds every requested target order
   */
  public void lockMaterialOrders(@NotNull UUID materialId, @NotNull Collection<UUID> orderIds) {
    observer.beforeOrderLinkedMaterialWrite(
        orderIds.stream().map(order -> new OrderMaterialScope(order, materialId)).toList());
  }

  /**
   * Locks current stock scopes and all batch targets in one globally ordered material pass.
   *
   * @param rowIds every source and merge destination row
   * @param requestedScopes initial book-in scopes
   * @param allocationTargets allocation targets resolved against current row materials
   */
  public void lockRowsAndTargets(
      @NotNull Collection<UUID> rowIds,
      @NotNull Collection<OrderMaterialScope> requestedScopes,
      @NotNull Collection<PoolAllocationTarget> allocationTargets) {
    lockEligibility();
    Planning plan = planning();
    if (!plan.stockPhase) {
      readSnapshots(rowIds).forEach(plan.rows::putIfAbsent);
    }
    List<UUID> orderIds = new ArrayList<>();
    List<OrderMaterialScope> scopes = new ArrayList<>(requestedScopes);
    if (!rowIds.isEmpty()) {
      orderIds.addAll(rows.findAllocatedOrderIds(rowIds));
      scopes.addAll(rows.findAllocatedMaterialScopes(rowIds));
    }
    if (!allocationTargets.isEmpty()) {
      Map<UUID, InventoryItemRepository.RowLot> targets =
          rows
              .findRowLots(
                  allocationTargets.stream().map(PoolAllocationTarget::entryId).distinct().toList())
              .stream()
              .collect(
                  Collectors.toMap(InventoryItemRepository.RowLot::getId, Function.identity()));
      for (PoolAllocationTarget target : allocationTargets) {
        InventoryItemRepository.RowLot row = targets.get(target.entryId());
        if (row != null && row.getMaterialId() != null) {
          scopes.add(new OrderMaterialScope(target.orderId(), row.getMaterialId()));
        }
      }
    }
    assertOrders(orderIds);
    observer.beforeOrderLinkedMaterialWrite(scopes.stream().distinct().toList());
  }

  /**
   * Prepares one new stock destination with its exact placement and merge policy.
   *
   * @param booking the new stock and every initial order target
   */
  public void lockBookIn(@NotNull StockBookInScope booking) {
    prepareWrites(List.of(), List.of(booking), List.of());
  }

  /**
   * Locks a complete physical action after planning every current and requested material scope.
   *
   * @param sourceRowIds every existing row the action changes
   * @param bookings every new stock destination
   * @param requiredScopes additional caller-owned material scopes
   */
  public void prepareWrites(
      @NotNull Collection<UUID> sourceRowIds,
      @NotNull Collection<StockBookInScope> bookings,
      @NotNull Collection<OrderMaterialScope> requiredScopes) {
    prepareWrites(sourceRowIds, bookings, requiredScopes, List.of(), List.of());
  }

  /**
   * Prepares a complete batch with explicit allocation targets and additional physical lots.
   *
   * @param sourceRowIds every existing row the action changes
   * @param bookings every exact initial stock destination
   * @param requiredScopes additional caller-owned material scopes
   * @param allocationTargets new allocations on existing physical rows
   * @param additionalLots further physical lot keys in the complete batch
   */
  public void prepareWrites(
      @NotNull Collection<UUID> sourceRowIds,
      @NotNull Collection<StockBookInScope> bookings,
      @NotNull Collection<OrderMaterialScope> requiredScopes,
      @NotNull Collection<PoolAllocationTarget> allocationTargets,
      @NotNull Collection<Long> additionalLots) {
    lockEligibility();
    Map<StockBookInScope, Set<UUID>> destinations = new HashMap<>();
    Set<UUID> affected = new HashSet<>(sourceRowIds);
    List<OrderMaterialScope> scopes = new ArrayList<>(requiredScopes);
    Set<Long> lotKeys = new TreeSet<>(additionalLots);
    Map<UUID, RowSnapshot> sources = readSnapshots(sourceRowIds);
    if (!planning().stockPhase) {
      sources.forEach(planning().rows::putIfAbsent);
    }
    for (RowSnapshot row : sources.values()) {
      lotKeys.add(
          InventoryLotLockRepository.lotKey(
              row.holder(),
              StockLot.key(
                  row.material(), row.gameItem(), row.location(), row.quality(), row.stolen())));
    }
    for (StockBookInScope booking : bookings) {
      Set<UUID> candidates = mergeCandidates(booking);
      destinations.put(booking, candidates);
      affected.addAll(candidates);
      lotKeys.add(InventoryLotLockRepository.lotKey(booking.holder(), booking.lot().key()));
      if (booking.lot().material() != null) {
        booking
            .orderIds()
            .forEach(order ->
                scopes.add(new OrderMaterialScope(order, booking.lot().material().getId())));
      }
    }
    lockRowsAndTargets(affected, scopes, allocationTargets);
    lockStockLots(lotKeys);
    if (!affected.isEmpty()) {
      rows.lockAllByIdOrderById(affected);
      validateRows(affected);
    }
    for (var destination : destinations.entrySet()) {
      if (!destination.getValue().equals(mergeCandidates(destination.getKey()))) {
        throw new ObjectOptimisticLockingFailureException(
            InventoryItem.class, destination.getKey().holder());
      }
    }
  }

  private Set<UUID> mergeCandidates(@NotNull StockBookInScope booking) {
    if (!booking.merge()) {
      return Set.of();
    }
    StockLot lot = booking.lot();
    return Set.copyOf(
        rows.findMergeCandidateIds(
            booking.holder(),
            lot.material() == null ? null : lot.material().getId(),
            lot.gameItem() == null ? null : lot.gameItem().getId(),
            lot.location().getId(),
            lot.material() == null ? null : lot.quality() == null ? 0 : lot.quality(),
            booking.personal(),
            lot.stolen(),
            booking.owningOrgUnitId()));
  }

  /**
   * Takes all physical lot locks after material planning and forbids later unplanned lots.
   *
   * @param lotKeys every physical lot key of the completed plan
   */
  public void lockStockLots(@NotNull Collection<Long> lotKeys) {
    Planning plan = planning();
    if (plan.stockPhase && !plan.lots.containsAll(lotKeys)) {
      throw new de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException(
          "error.inventory.concurrent_scope_changed");
    }
    observer.assertOrderLinkedMaterialWrite(List.of());
    plan.stockPhase = true;
    for (long key : new TreeSet<>(lotKeys)) {
      if (plan.lots.add(key)) {
        rows.lockExchangeLot(key);
      }
    }
  }

  /**
   * Locks source and destination merge scopes of a transformation in one material pass.
   *
   * @param rowIds every source row
   * @param relocations requested holder, location or stolen-marker changes
   */
  public void lockTransformRows(
      @NotNull Collection<UUID> rowIds, @NotNull Collection<PoolRelocation> relocations) {
    lockEligibility();
    Map<UUID, Set<String>> holderLots = new HashMap<>();
    Map<UUID, InventoryItemRepository.RowLot> sources =
        rows.findRowLots(rowIds).stream()
            .collect(Collectors.toMap(InventoryItemRepository.RowLot::getId, Function.identity()));
    for (InventoryItemRepository.RowLot row : sources.values()) {
      addHolderLot(holderLots, row, null);
    }
    for (PoolRelocation relocation : relocations) {
      InventoryItemRepository.RowLot row = sources.get(relocation.entryId());
      if (row != null) {
        addHolderLot(holderLots, row, relocation);
      }
    }
    Set<Long> lotKeys = new TreeSet<>();
    holderLots.forEach((holder, lots) ->
        lots.forEach(lot -> lotKeys.add(InventoryLotLockRepository.lotKey(holder, lot))));
    if (planning().stockPhase) {
      lockStockLots(lotKeys);
      validateRows(rowIds);
      return;
    }
    Set<UUID> affected = new HashSet<>(rowIds);
    holderLots.forEach((holder, holderLotKeys) ->
        affected.addAll(rows.findRowsInHolderLots(holder, holderLotKeys)));
    prepareWrites(affected, List.of(), List.of(), List.of(), lotKeys);
  }

  /**
   * Adds a current or requested physical holder lot without locking a stock row.
   *
   * @param holderLots the accumulated lots
   * @param row the current scalar row placement
   * @param relocation the requested placement, or null for the current one
   */
  private static void addHolderLot(
      @NotNull Map<UUID, Set<String>> holderLots,
      @NotNull InventoryItemRepository.RowLot row,
      @Nullable PoolRelocation relocation) {
    UUID holder =
        relocation != null && relocation.holder() != null ? relocation.holder() : row.getHolderId();
    UUID location =
        relocation != null && relocation.locationId() != null
            ? relocation.locationId()
            : row.getLocationId();
    boolean stolen =
        (relocation != null && relocation.stolen() != null)
            ? relocation.stolen()
            : Boolean.TRUE.equals(row.getStolen());
    holderLots
        .computeIfAbsent(holder, _ -> new HashSet<>())
        .add(
            StockLot.key(
                row.getMaterialId(), row.getGameItemId(), location, row.getQuality(), stolen));
  }

  /**
   * Locks current material scopes of every row in the given holder lots.
   *
   * @param holder the holder
   * @param lotKeys every lot a stock batch touches
   */
  public void lockHolderLots(@NotNull UUID holder, @NotNull Collection<String> lotKeys) {
    lockRows(lotKeys.isEmpty() ? List.of() : rows.findRowsInHolderLots(holder, lotKeys));
  }

  /**
   * Validates existing slices before a bulk deletion or rewrite.
   *
   * @param rowIds the stock rows about to change
   */
  public void assertRows(@NotNull Collection<UUID> rowIds) {
    if (!rowIds.isEmpty()) {
      validateRows(rowIds);
      assertOrders(rows.findAllocatedOrderIds(rowIds));
    }
  }

  /**
   * Revalidates planned existing row facts after actual lot or row locks without adding guards.
   *
   * @param rowIds every existing row now locked for the action
   */
  public void validateRows(@NotNull Collection<UUID> rowIds) {
    Planning plan = planning();
    Map<UUID, RowSnapshot> actual = readSnapshots(rowIds);
    for (UUID id : rowIds) {
      if (plan.validated.contains(id)
          || plan.created.stream().anyMatch(row -> id.equals(row.getId()))) {
        continue;
      }
      RowSnapshot expected = plan.rows.get(id);
      RowSnapshot current = actual.get(id);
      if (!Objects.equals(expected, current)) {
        throw new ObjectOptimisticLockingFailureException(InventoryItem.class, id);
      }
      plan.validated.add(id);
    }
    plan.stockPhase = true;
    observer.assertOrderLinkedMaterialWrite(
        actual.values().stream()
            .filter(row -> row.material() != null)
            .flatMap(row ->
                row.allocations().stream()
                    .map(allocation ->
                        new OrderMaterialScope(
                            allocation.order(), Objects.requireNonNull(row.material()))))
            .distinct()
            .toList());
  }

  private Map<UUID, RowSnapshot> readSnapshots(@NotNull Collection<UUID> rowIds) {
    if (rowIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Set<AllocationSnapshot>> allocations = new HashMap<>();
    for (InventoryOrderAllocationQueries.OrderAllocationFact fact :
        rows.findOrderAllocationFacts(rowIds)) {
      allocations
          .computeIfAbsent(fact.getRowId(), _ -> new HashSet<>())
          .add(new AllocationSnapshot(fact.getOrderId(), ScuAmounts.toQuanta(fact.getAmount())));
    }
    Map<UUID, RowSnapshot> result = new HashMap<>();
    for (InventoryItemRepository.RowLot row : rows.findRowLots(rowIds)) {
      result.put(
          row.getId(),
          new RowSnapshot(
              row.getHolderId(),
              row.getMaterialId(),
              row.getGameItemId(),
              row.getLocationId(),
              row.getQuality(),
              Boolean.TRUE.equals(row.getStolen()),
              Boolean.TRUE.equals(row.getPersonal()),
              row.getOwningOrgUnitId(),
              row.getVersion(),
              ScuAmounts.toQuanta(row.getAmount()),
              Set.copyOf(allocations.getOrDefault(row.getId(), Set.of()))));
    }
    return result;
  }

  private static Planning planning() {
    for (TransactionSynchronization synchronization :
        TransactionSynchronizationManager.getSynchronizations()) {
      if (synchronization instanceof Planning current) {
        return current;
      }
    }
    Planning current = new Planning();
    TransactionSynchronizationManager.registerSynchronization(current);
    return current;
  }

  /** A current allocation fact independent of managed entity collection state. */
  private record AllocationSnapshot(UUID order, long amount) {}

  /** The physical and allocation facts used to plan stock resource guards. */
  private record RowSnapshot(
      UUID holder,
      @Nullable UUID material,
      @Nullable UUID gameItem,
      UUID location,
      @Nullable Integer quality,
      boolean stolen,
      boolean personal,
      @Nullable UUID owningUnit,
      @Nullable Long version,
      long amount,
      Set<AllocationSnapshot> allocations) {}

  /** The stock plan and revalidated rows owned exclusively by one transaction. */
  private static final class Planning implements TransactionSynchronization {
    private final Map<UUID, RowSnapshot> rows = new HashMap<>();
    private final Set<UUID> validated = new HashSet<>();
    private final Set<Long> lots = new HashSet<>();
    private final Set<InventoryItem> created = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean stockPhase;
  }

  /**
   * Validates explicitly added or removed target identities.
   *
   * @param orderIds the affected target orders
   */
  public void assertOrders(@NotNull Collection<UUID> orderIds) {
    observer.beforeOrderLinkedStockWrite(orderIds);
  }

  /**
   * Validates every nonpersonal row affected by a scoped wipe.
   *
   * @param adminAll whether all rows are in scope
   * @param activeUnit the pinned unit, or null
   * @param memberUnits the caller's scoped units
   */
  public void assertWipe(
      boolean adminAll, @Nullable UUID activeUnit, Collection<UUID> memberUnits) {
    assertOrders(rows.findAllocatedOrdersInWipeScope(adminAll, activeUnit, memberUnits));
    observer.beforeOrderLinkedMaterialWrite(
        rows.findAllocatedMaterialScopesInWipeScope(adminAll, activeUnit, memberUnits));
  }
}
