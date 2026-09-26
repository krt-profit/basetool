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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkOrgUnitChangeRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkOrgUnitChangeResultDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemOrgUnitChangeDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.InventoryAuditLabels;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changes the owning org unit of a member's own personal Lager rows after the booking
 * (REQ-INV-052), singly or for a selection. The target is one of the member's direct memberships of
 * any kind, or no unit at all; the change decides which unit's editors may see and edit the row
 * (REQ-ORG-004), and the row then follows the write-time merge rules (REQ-INV-026).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryOrgUnitChangeService {

  private final InventoryItemRepository inventoryItemRepository;
  private final OrgUnitRepository orgUnitRepository;
  private final OrgUnitMembershipQueryService membershipQueryService;
  private final InventoryCheckoutService inventoryCheckoutService;
  private final InventoryItemMapper inventoryItemMapper;
  private final AuditService auditService;

  /**
   * Changes the org unit of one personal row owned by the caller, then merges it into an existing
   * stack of the new unit where the merge rules allow. Leaving the unit as it is changes nothing
   * and records nothing.
   *
   * @param itemId the row
   * @param dto the version, the target unit or {@code null}, and the merge opt-in
   * @param callerId the authenticated caller
   * @return the row after the change, or the merge survivor it was folded into
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the row is
   *     unknown
   * @throws AccessDeniedException when the row belongs to another member
   * @throws BadRequestException when the row is not personal or the target is not one of the
   *     caller's direct memberships
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @Transactional
  public @NotNull InventoryItemDto changeOrgUnit(
      @NotNull UUID itemId, @NotNull InventoryItemOrgUnitChangeDto dto, @NotNull UUID callerId) {
    InventoryItem item =
        Entities.require(
            inventoryItemRepository.findByIdForRebook(itemId),
            () -> "Inventory item not found: " + itemId);
    OptimisticLock.checkOptionalClient(
        item.getVersion(), dto.version(), InventoryItem.class, itemId);
    requireOwnPersonalRow(item, callerId);
    OrgUnit target = resolveTarget(callerId, dto.targetOwningOrgUnitId());
    if (sameUnit(item.getOwningOrgUnit(), target)) {
      return inventoryItemMapper.toDto(item);
    }
    OrgUnit previous = item.getOwningOrgUnit();
    item.setOwningOrgUnit(target);
    InventoryItem saved = inventoryItemRepository.saveAndFlush(item);
    auditService.record(
        AuditEventType.INVENTORY_ORG_UNIT_CHANGED,
        saved.getId(),
        InventoryAuditLabels.label(saved),
        callerId,
        AuditDetails.of("fromOrgUnit", orgUnitRef(previous)).with("toOrgUnit", orgUnitRef(target)));
    InventoryItem result =
        inventoryCheckoutService.mergeStockIfRequested(
            saved, Boolean.TRUE.equals(dto.mergeStock()));
    return inventoryItemMapper.toDto(result);
  }

  /**
   * Changes the org unit of every listed personal row of the caller in one transaction. Rows are
   * locked pessimistically in sorted id order and validated before the first write; rows already
   * carrying the target unit are skipped and counted. Records one summary event when anything
   * changed.
   *
   * @param request the selection, the target unit or {@code null}, and the merge opt-in
   * @param callerId the authenticated caller
   * @return how many rows changed and how many were skipped
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when an id is unknown
   * @throws AccessDeniedException when a listed row belongs to another member
   * @throws BadRequestException when a listed row is not personal or the target is not one of the
   *     caller's direct memberships
   */
  @Transactional
  public @NotNull BulkOrgUnitChangeResultDto bulkChangeOrgUnit(
      @NotNull BulkOrgUnitChangeRequest request, @NotNull UUID callerId) {
    List<UUID> orderedIds = request.itemIds().stream().distinct().sorted().toList();
    List<InventoryItem> rows = new ArrayList<>(orderedIds.size());
    for (UUID itemId : orderedIds) {
      InventoryItem item =
          Entities.require(
              inventoryItemRepository.findByIdForRebook(itemId),
              () -> "Inventory item not found: " + itemId);
      requireOwnPersonalRow(item, callerId);
      rows.add(item);
    }
    OrgUnit target = resolveTarget(callerId, request.targetOwningOrgUnitId());
    boolean mergeStock = Boolean.TRUE.equals(request.mergeStock());

    int changed = 0;
    for (InventoryItem row : rows) {
      if (sameUnit(row.getOwningOrgUnit(), target)) {
        continue;
      }
      row.setOwningOrgUnit(target);
      InventoryItem saved = inventoryItemRepository.saveAndFlush(row);
      inventoryCheckoutService.mergeStockIfRequested(saved, mergeStock);
      changed++;
    }
    int skipped = rows.size() - changed;
    if (changed > 0) {
      auditService.record(
          AuditEventType.INVENTORY_BULK_ORG_UNIT_CHANGED,
          null,
          null,
          callerId,
          AuditDetails.of("changed", changed)
              .with("skipped", skipped)
              .with("toOrgUnit", orgUnitRef(target)));
    }
    log.info("Bulk org-unit change by user {}: {} changed, {} skipped", callerId, changed, skipped);
    return new BulkOrgUnitChangeResultDto(changed, skipped);
  }

  /**
   * Refuses a row that is not a personal row of the caller; shared rows move by rebooking.
   *
   * @param item the locked row
   * @param callerId the authenticated caller
   * @throws AccessDeniedException when the row belongs to another member
   * @throws BadRequestException when the row is not personal
   */
  private static void requireOwnPersonalRow(@NotNull InventoryItem item, @NotNull UUID callerId) {
    if (item.getUser() == null || !callerId.equals(item.getUser().getId())) {
      throw new AccessDeniedException(
          "Only the owner can change the org unit of inventory item: " + item.getId());
    }
    if (!Boolean.TRUE.equals(item.getPersonal())) {
      throw new BadRequestException(
          "Only a personal row can change its org unit this way; a shared row moves by rebooking");
    }
  }

  /**
   * Resolves the target unit, which must be one of the caller's direct memberships of any kind.
   *
   * @param callerId the authenticated caller
   * @param orgUnitId the requested unit, or {@code null} for no unit
   * @return the unit, or {@code null} for no unit
   * @throws BadRequestException when the caller is not a direct member of the unit
   */
  private @Nullable OrgUnit resolveTarget(@NotNull UUID callerId, @Nullable UUID orgUnitId) {
    if (orgUnitId == null) {
      return null;
    }
    if (!membershipQueryService.findDirectMembershipOrgUnitIds(callerId).contains(orgUnitId)) {
      throw new BadRequestException("The target org unit is not one of your memberships");
    }
    return Entities.require(orgUnitRepository.findById(orgUnitId), "Org unit not found");
  }

  /**
   * Tests whether two org units are the same unit, treating two absent units as equal.
   *
   * @param current the row's unit, or {@code null}
   * @param target the target unit, or {@code null}
   * @return {@code true} when both are absent or share an id
   */
  private static boolean sameUnit(@Nullable OrgUnit current, @Nullable OrgUnit target) {
    UUID currentId = current == null ? null : current.getId();
    UUID targetId = target == null ? null : target.getId();
    return Objects.equals(currentId, targetId);
  }

  /**
   * Formats an org unit for an audit payload as {@code kind:id}, or {@code none}.
   *
   * @param orgUnit the unit, or {@code null}
   * @return the bounded reference
   */
  private static @NotNull String orgUnitRef(@Nullable OrgUnit orgUnit) {
    return orgUnit == null ? "none" : orgUnit.getKind() + ":" + orgUnit.getId();
  }
}
