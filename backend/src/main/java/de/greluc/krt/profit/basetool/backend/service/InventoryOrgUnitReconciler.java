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

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the {@code owning_org_unit} stamp of a user's shared inventory in line with their org-unit
 * membership.
 *
 * <ul>
 *   <li>First membership gained: ownerless shared rows adopt that org unit.
 *   <li>Last membership lost: org-stamped shared rows fall back to {@code NULL}.
 * </ul>
 *
 * <p>Rows are only re-stamped, never merged; private stock is never touched. Every method requires
 * an open transaction ({@link Propagation#MANDATORY}) from {@link OrgUnitMembershipService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryOrgUnitReconciler {

  private final InventoryItemRepository inventoryItemRepository;
  private final AuditService auditService;

  /**
   * Moves the user's ownerless shared inventory into their first org unit so it appears in its
   * Lager view.
   *
   * @param userId the owner whose shared inventory to promote; never {@code null}
   * @param firstOrgUnit the org unit the user just joined; never {@code null}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void onUserGainedFirstOrgUnit(@NotNull UUID userId, @NotNull OrgUnit firstOrgUnit) {
    int restamped = restamp(userId, firstOrgUnit, false);
    if (restamped > 0) {
      auditService.record(
          AuditEventType.INVENTORY_ORG_RESTAMPED,
          null,
          null,
          userId,
          AuditDetails.of("trigger", "GAINED_FIRST")
              .with("orgUnit", firstOrgUnit.getId())
              .with("rows", restamped));
    }
  }

  /**
   * Resets the user's org-stamped shared inventory to ownerless ({@code owning_org_unit = NULL}),
   * visible only to the owner.
   *
   * @param userId the owner whose shared inventory to demote; never {@code null}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void onUserLostLastOrgUnit(@NotNull UUID userId) {
    int restamped = restamp(userId, null, true);
    if (restamped > 0) {
      auditService.record(
          AuditEventType.INVENTORY_ORG_RESTAMPED,
          null,
          null,
          userId,
          AuditDetails.of("trigger", "LOST_LAST").with("orgUnit", "NULL").with("rows", restamped));
    }
  }

  /**
   * Re-stamps the user's non-personal inventory in place via dirty checking, without merging rows.
   *
   * @param userId the owner whose inventory to reconcile
   * @param newOrgForOwnerlessRows the org unit for {@code NULL}-org rows (ignored when demoting)
   * @param demoteAllToNull {@code true} to clear every org stamp, {@code false} to promote only
   *     {@code NULL}-org rows
   * @return the number of rows re-stamped
   */
  private int restamp(
      UUID userId, @Nullable OrgUnit newOrgForOwnerlessRows, boolean demoteAllToNull) {
    List<InventoryItem> rows = inventoryItemRepository.findByUserIdAndPersonalFalse(userId);
    if (rows.isEmpty()) {
      return 0;
    }

    int restamped = 0;
    for (InventoryItem row : rows) {
      if (demoteAllToNull) {
        if (row.getOwningOrgUnit() != null) {
          row.setOwningOrgUnit(null);
          restamped++;
        }
      } else if (row.getOwningOrgUnit() == null) {
        row.setOwningOrgUnit(newOrgForOwnerlessRows);
        restamped++;
      }
    }
    if (restamped > 0) {
      log.info(
          "Reconciled inventory org stamp for user {}: {} row(s) re-stamped", userId, restamped);
    }
    return restamped;
  }
}
