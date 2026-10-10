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
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAllocations;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkStolenMarkRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkStolenMarkResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemStolenMarkDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks Lager stock as „gestohlen" and removes the marker, for a whole row, a part of it or a
 * selection of the caller's own rows (REQ-INV-053).
 *
 * <p>A partial change splits the row: the changed part becomes a new row with the other marker and
 * everything else of the stock identity unchanged. The marker is part of the stack identity, so the
 * new row merges into an existing stack of the same identity where the merge rules allow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryStolenMarkService {

  /** Amounts closer than this count as equal. */
  private static final double QUANTITY_EPSILON = 1e-9;

  private final InventoryItemRepository inventoryItemRepository;
  private final StockOfferLookup stockOfferLookup;
  private final InventoryCheckoutService inventoryCheckoutService;
  private final InventoryItemMapper inventoryItemMapper;
  private final InventoryProperties inventoryProperties;
  private final AuditRecorder auditRecorder;

  /**
   * Sets or removes the marker on a row or on part of it. A row that already carries the requested
   * marker is left as it is and nothing is recorded.
   *
   * @param itemId the row; the caller's scope on it is checked by the controller
   * @param dto the version, the requested marker and the amount to change, {@code null} for the
   *     whole row
   * @param callerId the authenticated caller, recorded as the actor
   * @param isLogistician whether the caller is a logistician or above and so may mark another
   *     member's row
   * @return the row carrying the requested marker, or the merge survivor it was folded into
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the row is
   *     unknown
   * @throws AccessDeniedException when the caller neither owns the row nor is a logistician
   * @throws BusinessConflictException when marking is switched off, or a split would leave the row
   *     below the amount it offers on the Materialbörse
   * @throws BadRequestException when the amount is not positive, exceeds the row or is fractional
   *     on whole-unit stock
   * @throws OverAllocationException when the remaining part could no longer carry its earmarks
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public @NotNull InventoryItemDto mark(
      @NotNull UUID itemId,
      @NotNull InventoryItemStolenMarkDto dto,
      @NotNull UUID callerId,
      boolean isLogistician) {
    requireEnabled();
    InventoryItem item =
        Entities.require(
            inventoryItemRepository.findByIdForRebook(itemId),
            () -> "Inventory item not found: " + itemId);
    boolean isOwner = item.getUser() != null && callerId.equals(item.getUser().getId());
    if (!isOwner && !isLogistician) {
      throw new AccessDeniedException("You are not allowed to mark this inventory item: " + itemId);
    }
    OptimisticLock.checkOptionalClient(
        item.getVersion(), dto.version(), InventoryItem.class, itemId);
    boolean target = Boolean.TRUE.equals(dto.stolen());
    if (Boolean.TRUE.equals(item.getStolen()) == target) {
      return inventoryItemMapper.toDto(item);
    }
    double rowAmount = item.getAmount() != null ? item.getAmount() : 0.0;
    Double requested = dto.amount();
    if (requested == null || Math.abs(requested - rowAmount) <= QUANTITY_EPSILON) {
      return inventoryItemMapper.toDto(markWholeRow(item, target, callerId));
    }
    return inventoryItemMapper.toDto(markPart(item, requested, target, callerId));
  }

  /**
   * Sets or removes the marker on every listed row of the caller, whole rows only. Rows are locked
   * in sorted id order and validated before the first write; rows already carrying the requested
   * marker are skipped and counted. Records one summary event when anything changed.
   *
   * @param request the selection and the requested marker
   * @param callerId the authenticated caller
   * @return how many rows changed and how many were skipped
   * @throws BusinessConflictException when marking is switched off
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when an id is unknown
   * @throws AccessDeniedException when a listed row belongs to another member
   */
  @Transactional
  public @NotNull BulkStolenMarkResultDto bulkMark(
      @NotNull BulkStolenMarkRequest request, @NotNull UUID callerId) {
    requireEnabled();
    List<UUID> orderedIds = request.itemIds().stream().distinct().sorted().toList();
    List<InventoryItem> rows = new ArrayList<>(orderedIds.size());
    for (UUID itemId : orderedIds) {
      InventoryItem item =
          Entities.require(
              inventoryItemRepository.findByIdForRebook(itemId),
              () -> "Inventory item not found: " + itemId);
      if (item.getUser() == null || !callerId.equals(item.getUser().getId())) {
        throw new AccessDeniedException(
            "Only the owner can mark a selection of inventory items: " + itemId);
      }
      rows.add(item);
    }
    boolean target = Boolean.TRUE.equals(request.stolen());
    int changed = 0;
    for (InventoryItem row : rows) {
      if (Boolean.TRUE.equals(row.getStolen()) == target) {
        continue;
      }
      row.setStolen(target);
      InventoryItem saved = inventoryItemRepository.saveAndFlush(row);
      inventoryCheckoutService.mergeStockIfRequested(saved, false);
      changed++;
    }
    int skipped = rows.size() - changed;
    if (changed > 0) {
      auditRecorder.record(
          AuditEventType.INVENTORY_BULK_STOLEN_CHANGED,
          null,
          null,
          callerId,
          AuditDetails.of("stolen", target).with("changed", changed).with("skipped", skipped));
    }
    log.info(
        "Bulk stolen marker by user {}: stolen={}, {} changed, {} skipped",
        callerId,
        target,
        changed,
        skipped);
    return new BulkStolenMarkResultDto(changed, skipped);
  }

  /**
   * Flips the marker of the whole row and merges it into a stack of its new identity where the
   * merge rules allow.
   *
   * @param item the locked row
   * @param target the requested marker
   * @param callerId the authenticated caller
   * @return the row, or the merge survivor it was folded into
   */
  private @NotNull InventoryItem markWholeRow(
      @NotNull InventoryItem item, boolean target, @NotNull UUID callerId) {
    item.setStolen(target);
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    auditRecorder.record(
        target ? AuditEventType.INVENTORY_STOLEN_MARKED : AuditEventType.INVENTORY_STOLEN_UNMARKED,
        saved.getId(),
        InventoryAuditLabels.label(saved),
        callerId,
        AuditDetails.of("amount", saved.getAmount()).with("split", false));
    return inventoryCheckoutService.mergeStockIfRequested(saved, false);
  }

  /**
   * Splits off {@code amount} as a new row with the requested marker; the source keeps the rest,
   * its earmarks and its Materialbörse offer.
   *
   * @param item the locked source row
   * @param amount the part to change
   * @param target the requested marker
   * @param callerId the authenticated caller
   * @return the new row, or the merge survivor it was folded into
   */
  private @NotNull InventoryItem markPart(
      @NotNull InventoryItem item, double amount, boolean target, @NotNull UUID callerId) {
    double rowAmount = item.getAmount() != null ? item.getAmount() : 0.0;
    if (amount <= 0) {
      throw new BadRequestException("error.inventory.stolen.amountPositive");
    }
    if (amount > rowAmount) {
      throw new BadRequestException("error.inventory.stolen.amountTooLarge");
    }
    if (requiresWholeUnits(item) && amount % 1 != 0) {
      throw new BadRequestException("error.inventory.stolen.wholeUnits");
    }
    double remaining = InventoryItem.roundToScuScale(rowAmount - amount);
    double offered = stockOfferLookup.activeOfferedAmount(item.getId());
    if (remaining + QUANTITY_EPSILON < offered) {
      throw new BusinessConflictException("error.inventory.stolen.belowOffer");
    }

    InventoryItem part = new InventoryItem();
    part.setUser(item.getUser());
    part.setOwningOrgUnit(item.getOwningOrgUnit());
    part.setMaterial(item.getMaterial());
    part.setGameItem(item.getGameItem());
    part.setLocation(item.getLocation());
    part.setQuality(item.getQuality());
    part.setAmount(InventoryItem.roundToScuScale(amount));
    part.setPersonal(item.getPersonal());
    part.setStolen(target);
    final InventoryItem savedPart = inventoryItemRepository.save(part);

    item.setAmount(remaining);
    if (!InventoryAllocations.fits(item)) {
      throw new OverAllocationException();
    }
    inventoryItemRepository.saveAndFlush(item);

    auditRecorder.record(
        target ? AuditEventType.INVENTORY_STOLEN_MARKED : AuditEventType.INVENTORY_STOLEN_UNMARKED,
        item.getId(),
        InventoryAuditLabels.label(item),
        callerId,
        AuditDetails.of("amount", amount).with("split", true).with("newRow", savedPart.getId()));
    return inventoryCheckoutService.mergeStockIfRequested(savedPart, false);
  }

  /**
   * Whether amounts on this row must be whole numbers: game-item rows and {@code PIECE} materials.
   *
   * @param item the row
   * @return {@code true} for whole-unit stock
   */
  private static boolean requiresWholeUnits(@NotNull InventoryItem item) {
    return item.getGameItem() != null
        || (item.getMaterial() != null
            && item.getMaterial().getQuantityType() == QuantityType.PIECE);
  }

  /**
   * Refuses any change of the marker while it is switched off.
   *
   * @throws BusinessConflictException when marking is switched off
   */
  private void requireEnabled() {
    if (!inventoryProperties.stolenMarkingEnabled()) {
      throw new BusinessConflictException("error.inventory.stolen.disabled");
    }
  }
}
