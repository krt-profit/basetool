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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInRule;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeEffects;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockCommands;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockConsumption;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.model.CheckoutType;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AllocationReductionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemBookOutDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
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
  private final EntityManager entityManager;

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
  public @NotNull List<InventoryItem> lockLotRows(@NotNull UUID member, @NotNull StockLot lot) {
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
        Comparator.comparing((InventoryItem r) -> Boolean.TRUE.equals(r.getPersonal()) ? 0 : 1)
            .thenComparing(r -> r.getOwningOrgUnit() == null ? 0 : 1)
            .thenComparing(
                InventoryItem::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
    return rows;
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
    item.setAmount(InventoryItem.roundToScuScale(amount));
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
      @NotNull UUID member, @NotNull InventoryItem row, double amount) {
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
      @NotNull UUID member, @NotNull InventoryItem row, boolean stolen, @Nullable Double amount) {
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
      @NotNull InventoryItem row,
      @NotNull UUID jobOrderId,
      double amount,
      @Nullable List<AllocationReductionDto> missionReductions,
      @NotNull StockChangeReason reason) {
    final double remainingAmount = row.getAmount() - amount;
    if (remainingAmount <= QUANTITY_EPSILON) {
      stockChangeObserver.beforeDelete(List.of(row.getId()), reason);
      inventoryRepository.delete(row);
      return true;
    }
    Map<UUID, Double> missionPlan =
        AllocationReductions.resolveReductionPlan(row, missionReductions, amount, false);
    InventoryAllocations.reduceJobOrder(row, jobOrderId, amount);
    AllocationReductions.applyPlan(row, missionPlan, false);
    row.setAmount(remainingAmount);
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
      double rowRemaining = InventoryItem.roundToScuScale(rowAmount - take);
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
        stockChangeObserver.beforeDelete(List.of(row.getId()), reason);
        inventoryRepository.delete(row);
      } else {
        row.setAmount(rowRemaining);
        inventoryRepository.save(row);
      }
      remainingToConsume -= take;
    }
    return consumed;
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
      InventoryAllocations.addJobOrder(stockRow, entityManager, orderId, (double) amount, false);
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
    item.setAmount(InventoryItem.roundToScuScale(amount));
    item.setNote(note);
    item.setPersonal(personal);
    if (jobOrderId != null) {
      InventoryAllocations.addJobOrder(item, entityManager, jobOrderId, item.getAmount(), false);
    }
    if (!personal && missionId != null) {
      InventoryAllocations.addMission(item, entityManager, missionId, item.getAmount());
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
