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
import de.greluc.krt.profit.basetool.backend.catalogue.api.QuantityType;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInRule;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockBookingReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeEffects;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockConsumption;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockRow;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
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

  /** The prefix that keeps the lot locks apart from any other advisory lock (ADR-0229). */
  private static final String LOT_LOCK_PREFIX = "exchange-stock-lot|";

  /** The rest below which a taken row counts as depleted. */
  private static final double QUANTITY_EPSILON = 1e-4;

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
  public void lockLots(@NotNull UUID member, @NotNull Collection<String> lotKeys) {
    Set<Long> keys = new TreeSet<>();
    for (String lotKey : lotKeys) {
      keys.add(lotLockKey(member, lotKey));
    }
    keys.forEach(inventoryRepository::lockExchangeLot);
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
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest((LOT_LOCK_PREFIX + member + '|' + lotKey).getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull List<StockRow> lockLotRows(@NotNull UUID member, @NotNull StockLot lot) {
    List<InventoryItem> rows =
        new ArrayList<>(
            lot.material() != null
                ? inventoryRepository.lockMaterialLot(
                    member,
                    lot.material().getId(),
                    lot.location().getId(),
                    lot.quality() == null ? 0 : lot.quality(),
                    lot.stolen())
                : inventoryRepository.lockItemLot(
                    member,
                    Objects.requireNonNull(lot.gameItem()).getId(),
                    lot.location().getId(),
                    lot.stolen()));
    rows.sort(
        Comparator.comparingInt((InventoryItem r) -> Boolean.TRUE.equals(r.getPersonal()) ? 0 : 1)
            .thenComparingInt(r -> r.getOwningOrgUnit() == null ? 0 : 1)
            .thenComparing(
                InventoryItem::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    return new ArrayList<StockRow>(rows);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Optional<StockRow> lockRow(@NotNull UUID rowId) {
    return inventoryRepository.findByIdForUpdate(rowId).map(StockRow.class::cast);
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
  public void bookIn(@NotNull UUID member, @NotNull StockLot lot, double amount) {
    User user = userRepository.findById(member).orElseThrow();
    InventoryItem item = new InventoryItem();
    item.setUser(user);
    item.setOwningOrgUnit(null);
    item.setMaterial(lot.material());
    item.setGameItem(lot.gameItem());
    item.setLocation(lot.location());
    item.setQuality(lot.material() == null ? null : lot.quality() == null ? 0 : lot.quality());
    item.setAmount(ScuAmounts.roundToScuScale(amount));
    item.setPersonal(true);
    item.setStolen(lot.stolen());
    InventoryItem saved = inventoryRepository.save(item);
    auditRecorder.record(
        AuditEventType.INVENTORY_ITEM_CREATED,
        saved.getId(),
        InventoryAuditLabels.label(saved),
        member,
        AuditDetails.of("qty", saved.getAmount())
            .with("q", saved.getQuality())
            .with("personal", true));
    checkoutService.mergeStockIfRequested(saved, false);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull StockChangeEffects bookOutRow(
      @NotNull UUID member, @NotNull UUID rowId, double amount) {
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
        member);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void markStolen(
      @NotNull UUID member, @NotNull UUID rowId, boolean stolen, @Nullable Double amount) {
    InventoryItem row = managedRow(rowId);
    stolenMarkService.mark(
        row.getId(),
        new InventoryItemStolenMarkDto(row.getVersion(), stolen, amount),
        member,
        false);
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
    InventoryItem row = managedRow(rowId);
    final double remainingAmount = row.getAmount() - amount;
    if (remainingAmount <= QUANTITY_EPSILON) {
      InventoryDonationGuards.requireNoDonation(row);
      stockChangeObserver.beforeDelete(List.of(row.getId()), reason);
      inventoryRepository.delete(row);
      return true;
    }
    Map<UUID, Double> missionPlan =
        AllocationReductions.resolveReductionPlan(row, missionReductions, amount, false);
    InventoryAllocations.reduceJobOrder(row, jobOrderId, amount);
    AllocationReductions.applyPlan(row, missionPlan, false);
    row.setAmount(remainingAmount);
    InventoryDonationGuards.requireDonationFits(row);
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
    final List<StockConsumption> consumed = new ArrayList<>();
    double remainingToConsume = amount;
    if (remainingToConsume <= QUANTITY_EPSILON) {
      return consumed;
    }
    for (InventoryItem row :
        inventoryRepository.findGameItemRowsByJobOrderAndGameItemForUpdate(
            jobOrderId, gameItemId)) {
      if (remainingToConsume <= QUANTITY_EPSILON) {
        break;
      }
      var slice = InventoryAllocations.jobOrderSlice(row, jobOrderId);
      double sliceAmount = slice != null && slice.getAmount() != null ? slice.getAmount() : 0.0;
      if (sliceAmount <= QUANTITY_EPSILON) {
        continue;
      }
      double take = Math.min(remainingToConsume, sliceAmount);
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
        inventoryRepository.delete(row);
      } else {
        row.setAmount(rowRemaining);
        InventoryDonationGuards.requireDonationFits(row);
        inventoryRepository.save(row);
      }
      remainingToConsume -= take;
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
    requirePositive(amount);
    User user = userRepository.findById(holder).orElseThrow();
    InventoryItem item = poolRow(user, poolUnit, lot, amount);
    requireWholeUnits(item);
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
    requirePositive(amount);
    InventoryItem source = managedRow(sourceRowId);
    double moved = ScuAmounts.roundToScuScale(amount);
    double sourceAmount = source.getAmount() == null ? 0.0 : source.getAmount();
    if (moved > sourceAmount + QUANTITY_EPSILON) {
      throw new BadRequestException("Cannot move more than the available amount");
    }
    User user = userRepository.findById(holder).orElseThrow();
    StockLot targetLot =
        new StockLot(
            source.getMaterial(),
            source.getGameItem(),
            location,
            source.getQuality(),
            Boolean.TRUE.equals(source.getStolen()));
    InventoryItem target = poolRow(user, poolUnit, targetLot, moved);
    requireWholeUnits(target);
    final String sourceLabel = InventoryAuditLabels.label(source);
    UUID sourceId = source.getId();
    double remaining = ScuAmounts.roundToScuScale(sourceAmount - moved);
    Map<UUID, Double> orderPlan =
        AllocationReductions.resolveReductionPlan(source, null, moved, true);
    Map<UUID, Double> missionPlan =
        AllocationReductions.resolveReductionPlan(source, null, moved, false);
    AllocationReductions.applyPlan(source, orderPlan, true);
    AllocationReductions.applyPlan(source, missionPlan, false);
    if (remaining <= QUANTITY_EPSILON) {
      InventoryDonationGuards.requireNoDonation(source);
      stockChangeObserver.beforeDelete(List.of(sourceId), StockChangeReason.TRANSFER);
      inventoryRepository.delete(source);
    } else {
      source.setAmount(remaining);
      if (!InventoryAllocations.fits(source)) {
        throw new OverAllocationException();
      }
      inventoryRepository.saveAndFlush(source);
      stockChangeObserver.lower(sourceId, remaining, StockChangeReason.TRANSFER);
    }
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
            .with("depleted", remaining <= QUANTITY_EPSILON));
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
    boolean whole =
        item.getGameItem() != null
            || (item.getMaterial() != null
                && item.getMaterial().getQuantityType() == QuantityType.PIECE);
    if (whole && item.getAmount() % 1 != 0) {
      throw new BadRequestException("This stock is counted in whole units");
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void earmarkForDonation(@NotNull UUID rowId, @NotNull UUID donationId, double amount) {
    requirePositive(amount);
    InventoryItem row = managedRow(rowId);
    if (Boolean.TRUE.equals(row.getStolen())) {
      throw new BadRequestException("Stolen stock cannot be donated");
    }
    InventoryAllocations.addDonation(row, donationId, ScuAmounts.roundToScuScale(amount));
    if (!InventoryAllocations.fits(row)) {
      throw new OverAllocationException();
    }
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
    for (InventoryDonationAllocation slice :
        donationAllocationRepository.findByDonationId(donationId)) {
      InventoryItem row = slice.getInventoryItem();
      double released = slice.getAmount() == null ? 0.0 : slice.getAmount();
      row.getDonationAllocations().remove(slice);
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
    stockChangeObserver.lower(rowId, remaining, reason);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseJobOrderEarmarks(@NotNull UUID jobOrderId) {
    inventoryRepository.deleteJobOrderAllocationsByJobOrder(jobOrderId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseJobOrderEarmark(@NotNull UUID rowId, @NotNull UUID jobOrderId) {
    managedRow(rowId).getJobOrderAllocations().removeIf(a -> jobOrderId.equals(a.getJobOrderId()));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseJobOrderMaterialEarmarks(@NotNull UUID jobOrderId, @NotNull UUID materialId) {
    inventoryRepository.deleteJobOrderAllocationsByJobOrderAndMaterial(jobOrderId, materialId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseJobOrderGameItemEarmarks(@NotNull UUID jobOrderId, @NotNull UUID gameItemId) {
    inventoryRepository.deleteJobOrderAllocationsByJobOrderAndGameItem(jobOrderId, gameItemId);
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
    InventoryItem stockRow = new InventoryItem();
    stockRow.setUser(owner);
    stockRow.setOwningOrgUnit(owningOrgUnit);
    stockRow.setGameItem(gameItem);
    stockRow.setLocation(location);
    stockRow.setAmount((double) amount);
    stockRow.setPersonal(personal);
    if (earmark) {
      InventoryAllocations.addJobOrder(stockRow, orderId, (double) amount, false);
    }
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
      @Nullable Double amount,
      @Nullable String note,
      boolean personal,
      @Nullable UUID jobOrderId,
      @Nullable Integer jobOrderDisplayId,
      @Nullable UUID missionId) {
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
      InventoryAllocations.addJobOrder(item, jobOrderId, item.getAmount(), false);
    }
    if (!personal && missionId != null) {
      InventoryAllocations.addMission(item, missionId, item.getAmount());
    }

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
