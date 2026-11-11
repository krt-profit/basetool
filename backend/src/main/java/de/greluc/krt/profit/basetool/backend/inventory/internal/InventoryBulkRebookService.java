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
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.OfferedLocations;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.OverAllocationException;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryTransferNoticeService.TransferMove;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.ScuAmounts;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates validated bulk checkout and whole-row rebooking. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
@Slf4j
public class InventoryBulkRebookService {

  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final LocationRepository locationRepository;
  private final StockChangeObserver stockChangeObserver;
  private final OrgUnitStampingService orgUnitStampingService;
  private final AuditRecorder auditRecorder;
  private final InventoryMergeService mergeService;
  private final InventoryTransferNoticeService transferNotices;

  /**
   * Deletes all listed inventory items owned by the caller in one batch, after locking,
   * ownership-checking and donation-checking each row. Their job-order and mission earmarks are
   * removed by cascade.
   *
   * @param request the bulk checkout request containing item IDs
   * @param currentUserId the UUID of the authenticated user (JWT sub)
   * @throws NotFoundException when an id is unknown
   * @throws AccessDeniedException when a listed row belongs to another user
   * @throws OverAllocationException when a listed row still carries a pledged donation
   *     (REQ-INV-058); nothing is removed
   */
  @Transactional
  public void bulkCheckout(@NotNull BulkCheckoutRequest request, UUID currentUserId) {
    log.info(
        "Bulk checkout requested by user {} for {} items", currentUserId, request.itemIds().size());

    List<UUID> toDelete = new ArrayList<>();

    for (UUID itemId : request.itemIds()) {
      InventoryItem item =
          Entities.require(
              inventoryItemRepository.lockById(itemId),
              () -> "Inventory item not found: " + itemId);

      if (!item.getUser().getId().equals(currentUserId)) {
        log.warn(
            "User {} attempted to bulk-checkout item {} owned by {}",
            currentUserId,
            itemId,
            item.getUser().getId());
        throw new AccessDeniedException(
            "You are not allowed to check out inventory item: " + itemId);
      }
      InventoryDonationGuards.requireNoDonation(item);

      toDelete.add(itemId);
    }

    stockChangeObserver.beforeDelete(toDelete, StockChangeReason.BULK_CHECKOUT);
    inventoryItemRepository.deleteAllById(toDelete);
    log.info(
        "Bulk checkout completed: {} items removed for user {}", toDelete.size(), currentUserId);
    auditRecorder.record(
        AuditEventType.INVENTORY_BULK_CHECKED_OUT,
        null,
        null,
        currentUserId,
        AuditDetails.of("count", toDelete.size()));
  }

  /**
   * Moves every listed row of the caller's inventory in full, to another location or owner or
   * across the personal marker (Massen-Umbuchen, REQ-INV-036). Moved rows keep all their earmarks.
   *
   * <p>Rows already in the target state are skipped and counted; any other obstacle aborts the
   * whole transaction. Rows are locked pessimistically in sorted id order and validated before the
   * first write.
   *
   * @param request the selection, the mode and the mode's target fields
   * @param currentUserId the authenticated caller's user id; every listed row must belong to them
   * @return how many rows moved and how many were skipped as already-at-target
   * @throws NotFoundException when an id is unknown, or the target user / location does not exist
   * @throws AccessDeniedException when a listed row belongs to another user
   * @throws BadRequestException when a {@code LOCATION} rebooking carries neither a target user nor
   *     a target location, or when a {@code PERSONALIZE} selection contains a row earmarked for a
   *     job order or mission
   */
  @NotNull
  @Transactional
  public BulkRebookResultDto bulkRebook(@NotNull BulkRebookRequest request, UUID currentUserId) {
    log.info(
        "Bulk rebook ({}) requested by user {} for {} items",
        request.mode(),
        currentUserId,
        request.itemIds().size());

    final List<InventoryItem> rows = loadOwnRowsForRebook(request.itemIds(), currentUserId);
    final User owner = rows.getFirst().getUser();

    final boolean mergeStock = Boolean.TRUE.equals(request.mergeStock());
    final BulkRebookResultDto result =
        switch (request.mode()) {
          case LOCATION -> bulkRebookToTarget(rows, request, owner, mergeStock, currentUserId);
          case PERSONALIZE -> bulkRebookPersonalMarker(rows, request, true, mergeStock);
          case DEPERSONALIZE -> bulkRebookPersonalMarker(rows, request, false, mergeStock);
        };

    log.info(
        "Bulk rebook ({}) completed for user {}: {} rebooked, {} skipped",
        request.mode(),
        currentUserId,
        result.rebooked(),
        result.skipped());
    if (result.rebooked() > 0) {
      auditRecorder.record(
          AuditEventType.INVENTORY_BULK_REBOOKED,
          null,
          null,
          currentUserId,
          AuditDetails.of("mode", request.mode())
              .with("rebooked", result.rebooked())
              .with("skipped", result.skipped()));
    }
    return result;
  }

  /**
   * Phase one of {@link #bulkRebook}: loads every distinct listed row under a pessimistic write
   * lock in sorted id order and asserts the caller owns it, before any write happens.
   *
   * @param itemIds the requested ids (may contain duplicates; deduplicated here)
   * @param currentUserId the authenticated caller's user id
   * @return the locked, owned rows in sorted id order; never empty (the DTO validates non-empty)
   * @throws NotFoundException when an id is unknown
   * @throws AccessDeniedException when a row belongs to another user
   */
  @NotNull
  private List<InventoryItem> loadOwnRowsForRebook(
      @NotNull List<UUID> itemIds, UUID currentUserId) {
    final List<UUID> orderedIds = itemIds.stream().distinct().sorted().toList();
    final List<InventoryItem> rows = new ArrayList<>(orderedIds.size());
    for (UUID itemId : orderedIds) {
      InventoryItem item =
          Entities.require(
              inventoryItemRepository.lockById(itemId),
              () -> "Inventory item not found: " + itemId);
      if (!item.getUser().getId().equals(currentUserId)) {
        log.warn(
            "User {} attempted to bulk-rebook item {} owned by {}",
            currentUserId,
            itemId,
            item.getUser().getId());
        throw new AccessDeniedException("You are not allowed to rebook inventory item: " + itemId);
      }
      rows.add(item);
    }
    return rows;
  }

  /**
   * The {@link BulkRebookMode#LOCATION} branch of {@link #bulkRebook}: moves every row to the
   * requested target user / location, keeping each row's own value for whichever of the two the
   * request left blank. Rows already sitting at the target are skipped. The rows that changed owner
   * are announced in one notification per recipient (REQ-INV-055).
   *
   * @param rows the locked, owned source rows
   * @param request the bulk request (read for the two targets and the org-unit pick)
   * @param owner the rows' owner, the membership gate when the request keeps the current owner
   * @param mergeStock the per-action stock-merge opt-in
   * @param actorId the member making the rebooking
   * @return the moved / skipped counts
   * @throws BadRequestException when neither a target user nor a target location was given
   * @throws NotFoundException when the target user or location is unknown
   */
  @NotNull
  private BulkRebookResultDto bulkRebookToTarget(
      List<InventoryItem> rows,
      BulkRebookRequest request,
      User owner,
      boolean mergeStock,
      @NotNull UUID actorId) {
    if (request.targetUserId() == null && request.targetLocationId() == null) {
      throw new BadRequestException("Bulk transfer requires a target user or a target location");
    }
    final User targetUser =
        request.targetUserId() == null
            ? null
            : Entities.require(
                userRepository.findById(request.targetUserId()), "Target user not found");
    final Location targetLocation =
        request.targetLocationId() == null
            ? null
            : OfferedLocations.require(
                locationRepository.findById(request.targetLocationId()),
                "Target location not found");

    final OrgUnit targetOwningOrgUnit =
        orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(
            targetUser != null ? targetUser : owner, request.targetOwningOrgUnitId());

    int rebooked = 0;
    int skipped = 0;
    final List<TransferMove> moves = new ArrayList<>();
    for (InventoryItem item : rows) {
      final User rowTargetUser = targetUser != null ? targetUser : item.getUser();
      final Location rowTargetLocation =
          targetLocation != null ? targetLocation : item.getLocation();
      if (InventoryStackIdentity.isSameStackTarget(item, rowTargetUser, rowTargetLocation)) {
        skipped++;
        continue;
      }
      moves.add(
          TransferMove.of(
              item,
              item.getId(),
              item.getUser(),
              rowTargetUser,
              ScuAmounts.roundToScuScale(item.getAmount() != null ? item.getAmount() : 0.0),
              item.getLocation(),
              rowTargetLocation));
      rebookWholeRow(
          item,
          rowTargetUser,
          rowTargetLocation,
          targetOwningOrgUnit,
          Boolean.TRUE.equals(item.getPersonal()),
          mergeStock);
      rebooked++;
    }
    transferNotices.publishTransferNotifications(actorId, moves);
    return new BulkRebookResultDto(rebooked, skipped);
  }

  /**
   * The {@link BulkRebookMode#PERSONALIZE} / {@link BulkRebookMode#DEPERSONALIZE} branch of {@link
   * #bulkRebook}: moves every row to the requested personal state, skipping rows already there.
   * Personalizing is refused for the whole selection when any row carries an earmark.
   *
   * @param rows the locked, owned source rows
   * @param request the bulk request (read for the org-unit pick)
   * @param targetPersonal {@code true} to personalize, {@code false} to move into the shared pool
   * @param mergeStock the per-action stock-merge opt-in
   * @return the moved / skipped counts
   * @throws BadRequestException when personalizing a selection that contains an earmarked row
   */
  @NotNull
  private BulkRebookResultDto bulkRebookPersonalMarker(
      @NotNull List<InventoryItem> rows,
      BulkRebookRequest request,
      boolean targetPersonal,
      boolean mergeStock) {
    final List<InventoryItem> movable =
        rows.stream()
            .filter(row -> Boolean.TRUE.equals(row.getPersonal()) != targetPersonal)
            .toList();
    if (targetPersonal) {
      final long earmarked =
          movable.stream()
              .filter(row ->
                  !row.getJobOrderAllocations().isEmpty() || !row.getMissionAllocations().isEmpty())
              .count();
      if (earmarked > 0) {
        throw new BadRequestException(
            "Stock assigned to a job order or mission cannot be marked personal: "
                + earmarked
                + " of the selected entries are assigned");
      }
    }

    final OrgUnit sharedTargetOrgUnit =
        targetPersonal || movable.isEmpty()
            ? null
            : orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(
                movable.getFirst().getUser(), request.targetOwningOrgUnitId());

    for (InventoryItem item : movable) {
      rebookWholeRow(
          item,
          item.getUser(),
          item.getLocation(),
          targetPersonal ? item.getOwningOrgUnit() : sharedTargetOrgUnit,
          targetPersonal,
          mergeStock);
    }
    return new BulkRebookResultDto(movable.size(), rows.size() - movable.size());
  }

  /**
   * Moves one row of a bulk rebooking in full: inserts a target row with the source's whole
   * quantity and earmarks, optionally merges it via {@link
   * InventoryMergeService#mergeStockIfRequested}, and deletes the source.
   *
   * @param source the locked source row
   * @param targetUser the destination owner
   * @param targetLocation the destination location
   * @param targetOwningOrgUnit the pool to stamp onto the new row, or {@code null} for an ownerless
   *     row
   * @param targetPersonal the personal marker the new row carries
   * @param mergeStock the per-action stock-merge opt-in
   */
  private void rebookWholeRow(
      @NotNull InventoryItem source,
      User targetUser,
      Location targetLocation,
      @Nullable OrgUnit targetOwningOrgUnit,
      boolean targetPersonal,
      boolean mergeStock) {
    InventoryDonationGuards.requireNoDonation(source);
    final double amount = source.getAmount() != null ? source.getAmount() : 0.0;

    InventoryItem newItem = new InventoryItem();
    newItem.setUser(targetUser);
    newItem.setOwningOrgUnit(targetOwningOrgUnit);
    newItem.setMaterial(source.getMaterial());
    newItem.setGameItem(source.getGameItem());
    newItem.setLocation(targetLocation);
    newItem.setQuality(source.getQuality());
    newItem.setAmount(ScuAmounts.roundToScuScale(amount));
    newItem.setPersonal(targetPersonal);
    newItem.setStolen(source.getStolen());
    newItem.setNote(source.getNote());

    Map<UUID, Double> orderReductions =
        AllocationReductions.resolveReductionPlan(source, null, amount, true);
    Map<UUID, Double> missionReductions =
        AllocationReductions.resolveReductionPlan(source, null, amount, false);
    InventoryAllocations.applyTransferInherit(source, newItem, orderReductions, missionReductions);
    final InventoryItem savedNew = inventoryItemRepository.save(newItem);
    stockChangeObserver.beforeDelete(List.of(source.getId()), StockChangeReason.REBOOK);
    inventoryItemRepository.delete(source);

    mergeService.mergeStockIfRequested(savedNew, mergeStock);
  }
}
