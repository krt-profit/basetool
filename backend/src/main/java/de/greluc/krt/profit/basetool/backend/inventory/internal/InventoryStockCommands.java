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
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInRule;
import de.greluc.krt.profit.basetool.backend.inventory.api.OrderMaterialScope;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.PoolEntryCommands.PoolRelocation;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeEffects;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockConsumption;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockQuantityComposition;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Lager's stock commands for a member's lots, with the ADR-0229 lot-lock protocol: one
 * transaction-scoped advisory lock per lot, its key the first eight bytes of the SHA-256 of the
 * member and the lot key under a fixed prefix, taken in ascending key order before any row is read.
 */
@Service
@RequiredArgsConstructor
public class InventoryStockCommands implements StockCommands {

  /** The rest below which a taken row counts as depleted. */
  private static final double QUANTITY_EPSILON = InventoryItemConsumptionPrefix.DEPLETION_EPSILON;

  private final InventoryOrderWrites orderWrites;
  private final InventoryItemRepository inventoryRepository;
  private final UserRepository userRepository;
  private final InventoryCheckoutService checkoutService;
  private final InventoryStolenMarkService stolenMarkService;
  private final AuditRecorder auditRecorder;
  private final BookInPolicy bookInPolicy;
  private final StockChangeObserver stockChangeObserver;
  private final InventoryDonationAllocationRepository donationAllocationRepository;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockBookInScopes(@NotNull Collection<StockBookInScope> bookings) {
    prepareWrites(List.of(), bookings, List.of());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void prepareWrites(
      @NotNull Collection<UUID> sourceRowIds,
      @NotNull Collection<StockBookInScope> bookings,
      @NotNull Collection<OrderMaterialScope> requiredScopes) {
    orderWrites.prepareWrites(sourceRowIds, bookings, requiredScopes);
  }

  /**
   * Attempts globally ordered lot locks without waiting; a false result requires rollback.
   *
   * @param repository the advisory-lock repository
   * @param keys every lot key needed by the transaction
   * @return whether every lock was acquired
   */
  static boolean tryLockInOrder(
      @NotNull InventoryItemRepository repository, @NotNull Collection<Long> keys) {
    for (long key : new TreeSet<>(keys)) {
      if (!repository.tryLockExchangeLot(key)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Derives a lot's 64-bit advisory lock key: the first eight bytes of the SHA-256 of the member
   * and the lot key under the fixed prefix.
   *
   * @param member the member
   * @param lotKey the lot's key
   * @return the lock key
   */
  static long lotLockKey(@NotNull UUID member, @NotNull String lotKey) {
    return InventoryLotLockRepository.lotKey(member, lotKey);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void prepareWritesRequiringSources(
      @NotNull Collection<UUID> selected,
      @NotNull Collection<UUID> additional,
      @NotNull Collection<StockBookInScope> bookings,
      @NotNull Collection<OrderMaterialScope> requiredScopes) {
    orderWrites.prepareWritesRequiringSources(selected, additional, bookings, requiredScopes);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void prepareDonationReceipt(
      @NotNull UUID sourceRowId, @NotNull StockBookInScope destination) {
    orderWrites.prepareSingleRowWrite(sourceRowId, List.of(destination), false);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Optional<StockRow> lockRow(@NotNull UUID rowId) {
    orderWrites.prepareSingleRowWrite(rowId, List.of(), true);
    return inventoryRepository.lockById(rowId).map(StockRow.class::cast);
  }

  @Override
  public void requireRowVersion(@NotNull UUID rowId, @Nullable Long clientVersion) {
    OptimisticLock.check(managedRow(rowId).getVersion(), clientVersion, InventoryItem.class, rowId);
  }

  /**
   * Returns the managed row of an id: the instance the caller's earlier lock or read put into the
   * persistence context, so a command acts on the very row the caller holds.
   *
   * @param rowId the row
   * @return the managed row
   */
  private @NotNull InventoryItem managedRow(@NotNull UUID rowId) {
    return inventoryRepository.findById(rowId).orElseThrow();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull UUID bookIn(@NotNull UUID member, @NotNull StockLot lot, double amount) {
    return bookIn(member, lot, amount, null);
  }

  /** Uses the one owning personal booking with optional complete restoration evidence. */
  private @NotNull UUID bookIn(
      UUID member, StockLot lot, double amount, @Nullable StockQuantityComposition restored) {
    orderWrites.lockEligibility();
    orderWrites.lockBookIn(
        new StockBookInScope(
            member,
            lot,
            List.of(),
            true,
            null,
            InventoryItem.countsWholeUnits(lot.material(), lot.gameItem())));
    User user = userRepository.findById(member).orElseThrow();
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setOwningOrgUnit(null);
    item.setMaterial(lot.material());
    item.setGameItem(lot.gameItem());
    item.setLocation(lot.location());
    item.setQuality(lot.material() == null ? null : lot.quality() == null ? 0 : lot.quality());
    if (restored == null) {
      item.setAmount(ScuAmounts.roundToScuScale(amount));
    } else {
      item.replaceQuantity(amount, restored);
    }
    item.setPersonal(true);
    item.setStolen(lot.stolen());
    orderWrites.assertRow(item);
    InventoryItem saved = inventoryRepository.save(item);
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        saved.getId(),
        InventoryAuditLabels.label(saved),
        member,
        AuditDetails.of("qty", saved.getAmount())
            .with("q", saved.getQuality())
            .with("personal", true));
    return checkoutService.mergeStockIfRequested(saved, false).getId();
  }

  /** Books confirmed restored quantity through the one owning personal stock writer. */
  @Transactional(propagation = Propagation.MANDATORY)
  @NotNull
  UUID bookInRestoredQuantity(
      @NotNull UUID member, @NotNull StockLot lot, @NotNull StockQuantityComposition quantity) {
    if (quantity.totalQuanta() <= 0L) {
      throw new IllegalArgumentException("A restored booking requires positive physical quantity");
    }
    orderWrites.requireRestorationEvidence(InventoryLotRestoreProofs.identities(quantity.parts()));
    return bookIn(member, lot, ScuAmounts.fromQuanta(quantity.totalQuanta()), quantity);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull StockChangeEffects bookOutRow(
      @NotNull UUID member, @NotNull UUID rowId, double amount) {
    return bookOutRow(member, rowId, amount, null);
  }

  /** Uses the one owning discard path after a complete source-row plan. */
  private @NotNull StockChangeEffects bookOutRow(
      UUID member, UUID rowId, double amount, @Nullable StockQuantityComposition exactRemaining) {
    orderWrites.lockEligibility();
    orderWrites.prepareWrites(List.of(rowId), List.of(), List.of());
    InventoryItem row = managedRow(rowId);
    return checkoutService.bookOutForClient(
        row.getId(),
        new InventoryItemBookOutDto(
            amount,
            null,
            null,
            CheckoutType.DISCARD,
            null,
            null,
            row.getVersion(),
            null,
            null,
            null,
            null),
        member,
        exactRemaining);
  }

  /** Removes confirmed exact parts through the one owning discard writer. */
  @Transactional(propagation = Propagation.MANDATORY)
  @NotNull
  StockChangeEffects bookOutExactQuantity(
      @NotNull UUID member, @NotNull UUID rowId, @NotNull StockQuantityComposition removed) {
    if (removed.parts().isEmpty()) {
      return StockChangeEffects.NONE;
    }
    orderWrites.lockEligibility();
    orderWrites.prepareWrites(List.of(rowId), List.of(), List.of());
    InventoryItem row = managedRow(rowId);
    StockQuantityComposition current = row.getQuantityComposition();
    StockQuantityComposition remaining = InventoryQuantityComposition.subtract(current, removed);
    if (Math.subtractExact(current.totalQuanta(), remaining.totalQuanta()) != removed.totalQuanta()
        || current.totalQuanta() != ScuAmounts.toQuanta(row.getAmount())) {
      throw new BusinessConflictException("error.inventory.concurrent_scope_changed");
    }
    return bookOutRow(member, rowId, ScuAmounts.fromQuanta(removed.totalQuanta()), remaining);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull UUID markStolen(
      @NotNull UUID member, @NotNull UUID rowId, boolean stolen, @Nullable Double amount) {
    orderWrites.lockEligibility();
    InventoryItem row = managedRow(rowId);
    return stolenMarkService
        .mark(
            row.getId(),
            new InventoryItemStolenMarkDto(row.getVersion(), stolen, amount),
            member,
            false)
        .id();
  }

  @Override
  public void requireBookIn(
      @Nullable UUID ownerUserId,
      @Nullable UUID callerId,
      boolean callerIsLogistician,
      boolean personal,
      @NotNull BookInRule rule) {
    if (ownerUserId == null) {
      if (rule.ownerlessNeedsLogistician() && !callerIsLogistician) {
        throw new AccessDeniedException(rule.ownerRefusal());
      }
      return;
    }
    if (ownerUserId.equals(callerId)) {
      return;
    }
    if (!bookInPolicy.mayBookInFor(ownerUserId)) {
      throw new AccessDeniedException(rule.ownerRefusal());
    }
    if (rule.personalForOtherRefused() && personal) {
      throw new AccessDeniedException(rule.personalRefusal());
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean takeFromEarmarkedRow(
      @NotNull UUID rowId,
      @NotNull UUID jobOrderId,
      double amount,
      @Nullable List<AllocationReductionDto> missionReductions,
      @NotNull StockChangeReason reason) {
    orderWrites.lockEligibility();
    orderWrites.assertOrders(List.of(jobOrderId));
    orderWrites.prepareWrites(List.of(rowId), List.of(), List.of());
    InventoryItem row = managedRow(rowId);
    orderWrites.validateRows(List.of(rowId));
    final double remainingAmount = row.getAmount() - amount;
    if (remainingAmount <= QUANTITY_EPSILON) {
      InventoryDonationGuards.requireNoDonation(row);
      stockChangeObserver.beforeDelete(List.of(row.getId()), reason);
      orderWrites.assertRow(row);
      inventoryRepository.delete(row);
      return true;
    }
    Map<UUID, Double> missionPlan =
        AllocationReductions.resolveReductionPlan(row, missionReductions, amount, false);
    InventoryAllocations.reduceJobOrder(row, jobOrderId, amount);
    AllocationReductions.applyPlan(row, missionPlan, false);
    row.setAmount(remainingAmount);
    InventoryDonationGuards.requireDonationFits(row);
    orderWrites.assertRow(row);
    inventoryRepository.save(row);
    return false;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull List<StockConsumption> consumeEarmarkedItems(
      @NotNull UUID jobOrderId,
      @NotNull UUID gameItemId,
      double amount,
      @NotNull StockChangeReason reason) {
    orderWrites.lockEligibility();
    orderWrites.assertOrders(List.of(jobOrderId));
    final List<StockConsumption> consumed = new ArrayList<>();
    if (amount <= QUANTITY_EPSILON) {
      return consumed;
    }
    Map<UUID, Double> requested = Map.of(gameItemId, amount);
    var selected =
        InventoryItemConsumptionPrefix.select(
            inventoryRepository.findItemConsumptionFacts(jobOrderId, requested.keySet()),
            requested);
    if (selected.takes().isEmpty()) {
      return consumed;
    }
    orderWrites.prepareWrites(selected.rowIds(), List.of(), List.of());
    var locked =
        InventoryItemConsumptionPrefix.select(
            inventoryRepository.findItemConsumptionFacts(jobOrderId, requested.keySet()),
            requested);
    if (!selected.equals(locked)) {
      throw new ObjectOptimisticLockingFailureException(InventoryItem.class, jobOrderId);
    }
    for (var planned : selected.takes()) {
      InventoryItem row =
          inventoryRepository
              .lockById(planned.rowId())
              .orElseThrow(() ->
                  new ObjectOptimisticLockingFailureException(
                      InventoryItem.class, planned.rowId()));
      double take = planned.amount();
      double rowAmount = row.getAmount() != null ? row.getAmount() : 0.0;
      double rowRemaining = ScuAmounts.roundToScuScale(rowAmount - take);
      boolean depleted = rowRemaining <= QUANTITY_EPSILON;
      String gameItemName = row.getGameItem() != null ? row.getGameItem().getName() : "—";
      consumed.add(
          new StockConsumption(
              row.getId(),
              InventoryAuditLabels.label(row),
              gameItemName,
              take,
              depleted ? 0.0 : rowRemaining,
              depleted));

      InventoryAllocations.reduceJobOrder(row, jobOrderId, take);
      if (depleted) {
        InventoryDonationGuards.requireNoDonation(row);
        stockChangeObserver.beforeDelete(List.of(row.getId()), reason);
        orderWrites.assertRow(row);
        inventoryRepository.delete(row);
      } else {
        row.setAmount(rowRemaining);
        InventoryDonationGuards.requireDonationFits(row);
        orderWrites.assertRow(row);
        inventoryRepository.save(row);
      }
    }
    return consumed;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull UUID bookIntoPool(
      @NotNull UUID holder,
      @NotNull OrgUnit poolUnit,
      @NotNull StockLot lot,
      double amount,
      @NotNull StockBookingReason reason) {
    orderWrites.lockEligibility();
    requirePositive(amount);
    orderWrites.lockBookIn(
        new StockBookInScope(holder, lot, List.of(), false, poolUnit.getId(), true));
    User user = userRepository.findById(holder).orElseThrow();
    InventoryItem item = poolRow(user, poolUnit, lot, amount);
    requireWholeUnits(item);
    orderWrites.assertRow(item);
    InventoryItem saved = inventoryRepository.save(item);
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        saved.getId(),
        InventoryAuditLabels.label(saved),
        holder,
        AuditDetails.of("qty", saved.getAmount())
            .with("q", saved.getQuality())
            .with("personal", false)
            .with("pool", poolUnit.getId())
            .with("reason", reason.name()));
    return checkoutService.mergeStockIfRequested(saved, true).getId();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull UUID transferToPool(
      @NotNull UUID sourceRowId,
      double amount,
      @NotNull OrgUnit poolUnit,
      @NotNull UUID holder,
      @NotNull Location location,
      @NotNull StockBookingReason reason) {
    orderWrites.lockEligibility();
    requirePositive(amount);
    orderWrites.lockTransformRows(
        List.of(sourceRowId),
        List.of(new PoolRelocation(sourceRowId, holder, location.getId(), null)));
    InventoryItem source = managedRow(sourceRowId);
    orderWrites.validateRows(List.of(sourceRowId));
    double moved = ScuAmounts.roundToScuScale(amount);
    double sourceAmount = source.getAmount() == null ? 0.0 : source.getAmount();
    if (moved > sourceAmount + QUANTITY_EPSILON) {
      throw new BadRequestException("Cannot move more than the available amount");
    }
    User user = userRepository.findById(holder).orElseThrow();
    InventoryItem target =
        LotRows.split(
            source,
            moved,
            LotRows.Placement.of(source)
                .withUser(user)
                .withLocation(location)
                .withOwningOrgUnit(poolUnit)
                .withPersonal(false));
    requireWholeUnits(target);
    final String sourceLabel = InventoryAuditLabels.label(source);
    final UUID sourceId = source.getId();
    double remaining = ScuAmounts.roundToScuScale(sourceAmount - moved);
    Map<UUID, Double> orderPlan =
        AllocationReductions.resolveReductionPlan(source, null, moved, true);
    Map<UUID, Double> missionPlan =
        AllocationReductions.resolveReductionPlan(source, null, moved, false);
    AllocationReductions.applyPlan(source, orderPlan, true);
    AllocationReductions.applyPlan(source, missionPlan, false);
    boolean depleted =
        LotRows.lower(
                inventoryRepository,
                stockChangeObserver,
                source,
                remaining,
                StockChangeReason.TRANSFER)
            .depleted();
    orderWrites.assertRow(target);
    InventoryItem saved = inventoryRepository.save(target);
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_TRANSFERRED,
        sourceId,
        sourceLabel,
        holder,
        AuditDetails.of("amount", moved)
            .with("toLoc", location.getName())
            .with("newRow", saved.getId())
            .with("pool", poolUnit.getId())
            .with("reason", reason.name())
            .with("depleted", depleted));
    return checkoutService.mergeStockIfRequested(saved, true).getId();
  }

  /**
   * Builds the unsaved pool row of a lot: shared, held by the member and stamped with the pool's
   * org unit.
   *
   * @param holder the holding member
   * @param poolUnit the pool's org unit
   * @param lot the lot
   * @param amount the amount
   * @return the new row
   */
  private static @NotNull InventoryItem poolRow(
      @NotNull User holder, @NotNull OrgUnit poolUnit, @NotNull StockLot lot, double amount) {
    InventoryItem item = new InventoryItem();
    item.setUser(holder);
    item.setOwningOrgUnit(poolUnit);
    item.setMaterial(lot.material());
    item.setGameItem(lot.gameItem());
    item.setLocation(lot.location());
    item.setQuality(lot.material() == null ? null : lot.quality() == null ? 0 : lot.quality());
    item.setAmount(ScuAmounts.roundToScuScale(amount));
    item.setPersonal(false);
    item.setStolen(lot.stolen());
    return item;
  }

  /**
   * Refuses a non-positive amount.
   *
   * @param amount the amount
   * @throws BadRequestException when the amount is not positive
   */
  private static void requirePositive(double amount) {
    if (amount <= 0.0) {
      throw new BadRequestException("The amount must be positive");
    }
  }

  /**
   * Refuses a fractional amount on a row that counts whole units.
   *
   * @param item the row with its amount
   * @throws BadRequestException when a game item or piece-counted material row holds a fraction
   */
  private static void requireWholeUnits(@NotNull InventoryItem item) {
    if (item.countsWholeUnits() && item.getAmount() % 1 != 0) {
      throw new BadRequestException("This stock is counted in whole units");
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void earmarkForDonation(@NotNull UUID rowId, @NotNull UUID donationId, double amount) {
    orderWrites.lockEligibility();
    requirePositive(amount);
    InventoryItem row = managedRow(rowId);
    if (Boolean.TRUE.equals(row.getStolen())) {
      throw new BadRequestException("Stolen stock cannot be donated");
    }
    InventoryAllocations.addDonation(row, donationId, ScuAmounts.roundToScuScale(amount));
    if (!InventoryAllocations.fits(row)) {
      throw new OverAllocationException();
    }
    orderWrites.assertRow(row);
    inventoryRepository.saveAndFlush(row);
    auditRecorder.record(
        AuditEventType.INVENTORY_ALLOCATION_ADDED,
        row.getId(),
        InventoryAuditLabels.label(row),
        row.getUser().getId(),
        AuditDetails.of("dim", "DONATION")
            .with("ref", donationId)
            .with("qty", ScuAmounts.roundToScuScale(amount)));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseDonationEarmark(@NotNull UUID donationId) {
    orderWrites.lockEligibility();
    List<UUID> rowIds = List.copyOf(inventoryRepository.findDonationAllocationRowIds(donationId));
    if (rowIds.isEmpty()) {
      return;
    }
    prepareWrites(rowIds, List.of(), List.of());
    if (!rowIds.equals(inventoryRepository.findDonationAllocationRowIds(donationId))) {
      throw new ObjectOptimisticLockingFailureException(
          InventoryDonationAllocation.class, donationId);
    }
    for (InventoryDonationAllocation slice :
        donationAllocationRepository.findSlicesByDonationIdAndRowIds(donationId, rowIds)) {
      InventoryItem row = slice.getInventoryItem();
      final double released = slice.getAmount() == null ? 0.0 : slice.getAmount();
      row.removeDonationAllocation(slice);
      orderWrites.assertRow(row);
      inventoryRepository.saveAndFlush(row);
      auditRecorder.record(
          AuditEventType.INVENTORY_ALLOCATION_REMOVED,
          row.getId(),
          InventoryAuditLabels.label(row),
          row.getUser().getId(),
          AuditDetails.of("dim", "DONATION").with("ref", donationId).with("qty", released));
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void lowered(@NotNull UUID rowId, double remaining, @NotNull StockChangeReason reason) {
    orderWrites.lockEligibility();
    stockChangeObserver.lower(rowId, remaining, reason);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseJobOrderEarmarks(@NotNull UUID jobOrderId) {
    orderWrites.lockEligibility();
    orderWrites.assertOrders(List.of(jobOrderId));
    releasePreparedEarmarks(jobOrderId, prepareAllocationRelease(jobOrderId, null, null));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseJobOrderEarmark(@NotNull UUID rowId, @NotNull UUID jobOrderId) {
    orderWrites.lockEligibility();
    orderWrites.assertOrders(List.of(jobOrderId));
    orderWrites.prepareWrites(List.of(rowId), List.of(), List.of());
    releasePreparedEarmarks(jobOrderId, List.of(rowId));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseJobOrderMaterialEarmarks(@NotNull UUID jobOrderId, @NotNull UUID materialId) {
    orderWrites.lockEligibility();
    orderWrites.assertOrders(List.of(jobOrderId));
    releasePreparedEarmarks(jobOrderId, prepareAllocationRelease(jobOrderId, materialId, null));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseJobOrderGameItemEarmarks(@NotNull UUID jobOrderId, @NotNull UUID gameItemId) {
    orderWrites.lockEligibility();
    orderWrites.assertOrders(List.of(jobOrderId));
    releasePreparedEarmarks(jobOrderId, prepareAllocationRelease(jobOrderId, null, gameItemId));
  }

  private void releasePreparedEarmarks(@NotNull UUID jobOrderId, @NotNull List<UUID> rowIds) {
    if (rowIds.isEmpty()) {
      return;
    }
    List<InventoryItem> rows = inventoryRepository.findAllForJobOrderAllocationRelease(rowIds);
    if (!Set.copyOf(rowIds).equals(Set.copyOf(rows.stream().map(InventoryItem::getId).toList()))) {
      throw new ObjectOptimisticLockingFailureException(InventoryItem.class, jobOrderId);
    }
    boolean changed = false;
    for (InventoryItem row : rows) {
      List<InventoryJobOrderAllocation> removed =
          row.getJobOrderAllocations().stream()
              .filter(slice -> jobOrderId.equals(slice.getJobOrderId()))
              .toList();
      if (removed.isEmpty()) {
        continue;
      }
      row.getJobOrderAllocations().removeAll(removed);
      row.setUpdatedAt(Instant.now());
      orderWrites.assertRow(row);
      for (InventoryJobOrderAllocation slice : removed) {
        auditRecorder.record(
            AuditEventType.INVENTORY_ALLOCATION_REMOVED,
            row.getId(),
            InventoryAuditLabels.label(row),
            row.getUser().getId(),
            AuditDetails.of("dim", "JOB_ORDER")
                .with("ref", jobOrderId)
                .with("qty", slice.getAmount()));
      }
      changed = true;
    }
    if (changed) {
      inventoryRepository.flush();
    }
  }

  /** Plans the exact existing release sources and rejects a changed post-lock target set. */
  private List<UUID> prepareAllocationRelease(
      UUID orderId, @Nullable UUID materialId, @Nullable UUID gameItemId) {
    boolean allRows = materialId == null && gameItemId == null;
    List<UUID> materialIds = materialId == null ? List.of() : List.of(materialId);
    List<UUID> gameItemIds = gameItemId == null ? List.of() : List.of(gameItemId);
    List<UUID> ids =
        inventoryRepository.findAllocationReleaseRowIds(orderId, allRows, materialIds, gameItemIds);
    if (ids.isEmpty()) {
      return List.of();
    }
    orderWrites.prepareWrites(ids, List.of(), List.of());
    if (!Set.copyOf(ids)
        .equals(
            Set.copyOf(
                inventoryRepository.findAllocationReleaseRowIds(
                    orderId, allRows, materialIds, gameItemIds)))) {
      throw new ObjectOptimisticLockingFailureException(InventoryItem.class, orderId);
    }
    return List.copyOf(ids);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void bookInFromProduction(
      @NotNull UUID orderId,
      @Nullable Integer orderDisplayId,
      @NotNull GameItem gameItem,
      int amount,
      @NotNull User owner,
      @Nullable OrgUnit owningOrgUnit,
      @NotNull Location location,
      boolean personal,
      boolean earmark) {
    orderWrites.lockEligibility();
    orderWrites.lockBookIn(
        new StockBookInScope(
            owner.getId(),
            new StockLot(null, gameItem, location, null, false),
            earmark ? List.of(orderId) : List.of(),
            personal,
            owningOrgUnit == null ? null : owningOrgUnit.getId(),
            true));
    InventoryItem stockRow = new InventoryItem();
    stockRow.setUser(owner);
    stockRow.setOwningOrgUnit(owningOrgUnit);
    stockRow.setGameItem(gameItem);
    stockRow.setLocation(location);
    stockRow.setAmount((double) amount);
    stockRow.setPersonal(personal);
    if (earmark) {
      orderWrites.assertOrders(List.of(orderId));
      InventoryAllocations.addJobOrder(stockRow, orderId, (double) amount, false);
    }
    orderWrites.assertRow(stockRow);
    InventoryItem saved = inventoryRepository.save(stockRow);
    InventoryItem merged = checkoutService.mergeStockIfRequested(saved, false);
    auditRecorder.record(
        AuditEventType.INVENTORY_RECEIVED_FROM_PRODUCTION,
        merged.getId(),
        InventoryAuditLabels.label(merged),
        owner.getId(),
        AuditDetails.of("jobOrder", "#" + orderDisplayId)
            .with("gameItemId", gameItem.getId())
            .with("amount", amount)
            .with("locationId", location.getId()));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void bookInFromRefinery(
      @NotNull UUID refineryOrderId,
      @NotNull User assignee,
      @Nullable OrgUnit owningOrgUnit,
      @NotNull Material material,
      @NotNull Location location,
      @Nullable Integer quality,
      double amount,
      @Nullable String note,
      boolean personal,
      @Nullable UUID jobOrderId,
      @Nullable Integer jobOrderDisplayId,
      @Nullable UUID missionId) {
    orderWrites.lockEligibility();
    orderWrites.lockBookIn(
        new StockBookInScope(
            assignee.getId(),
            new StockLot(material, null, location, quality, false),
            jobOrderId == null ? List.of() : List.of(jobOrderId),
            personal,
            owningOrgUnit == null ? null : owningOrgUnit.getId(),
            false));
    InventoryItem item = new InventoryItem();
    item.setUser(assignee);
    item.setOwningOrgUnit(owningOrgUnit);
    item.setMaterial(material);
    item.setLocation(location);
    item.setQuality(quality);
    item.setAmount(ScuAmounts.roundToScuScale(amount));
    item.setNote(note);
    item.setPersonal(personal);
    if (jobOrderId != null) {
      orderWrites.assertOrders(List.of(jobOrderId));
      InventoryAllocations.addJobOrder(item, jobOrderId, item.getAmount(), false);
    }
    if (!personal && missionId != null) {
      InventoryAllocations.addMission(item, missionId, item.getAmount());
    }

    orderWrites.assertRow(item);

    inventoryRepository.save(item);
    auditRecorder.record(
        AuditEventType.INVENTORY_RECEIVED_FROM_REFINERY,
        item.getId(),
        material.getName() + " @ " + location.getName(),
        assignee.getId(),
        AuditDetails.of("source", "REFINERY")
            .with("refineryOrder", refineryOrderId)
            .with("material", material.getName())
            .with("amount", item.getAmount())
            .with("q", quality)
            .with("personal", personal)
            .with("jobOrder", jobOrderId != null ? "#" + jobOrderDisplayId : "-"));
  }
}
