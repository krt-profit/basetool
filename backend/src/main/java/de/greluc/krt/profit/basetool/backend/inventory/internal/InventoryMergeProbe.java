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

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryProperties;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockMergeCutoff;
import de.greluc.krt.profit.basetool.backend.kernel.AppException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.scope.api.OrgUnitStampingService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Einbuchen merge probe (REQ-INV-026): whether a book-in of a material stock identity would
 * find a row to merge with, so the form offers the {@code SCU} merge opt-in only then.
 */
@Service
@RequiredArgsConstructor
public class InventoryMergeProbe {

  private final InventoryItemRepository inventoryItemRepository;
  private final UserRepository userRepository;
  private final OrgUnitStampingService orgUnitStampingService;
  private final InventoryAccessPolicy inventoryAccessPolicy;
  private final InventoryProperties inventoryProperties;
  private final StockMergeCutoff mergeCutoff;

  /**
   * Probes whether an Einbuchen with the given material stock identity would find a row to merge
   * with, resolving owner and owning org unit exactly as {@link
   * InventoryItemService#createInventoryItem} does; a row created before the booked member's merge
   * cutoff never counts (REQ-PATCH-004).
   *
   * <p>Runs without a surrounding transaction so a refused org-unit resolution answers {@code
   * false} instead of marking a shared transaction rollback-only.
   *
   * @param userId the member booked for, or {@code null} for the caller
   * @param currentUserId the caller's id
   * @param materialId the material being booked in
   * @param locationId the target location
   * @param quality the quality grade
   * @param personal whether the row would be personal
   * @param stolen whether the row would be marked „gestohlen"
   * @param owningOrgUnitId the picked owning org unit, or {@code null} for the auto-stamp
   * @return {@code true} when at least one mergeable row exists; {@code false} as well when the
   *     booking itself would be refused
   * @throws AccessDeniedException when the caller may not book for {@code userId}
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public boolean hasMergeCandidates(
      UUID userId,
      @NotNull UUID currentUserId,
      @NotNull UUID materialId,
      @NotNull UUID locationId,
      int quality,
      boolean personal,
      boolean stolen,
      UUID owningOrgUnitId) {
    UUID targetUserId = userId != null ? userId : currentUserId;
    boolean onBehalfOfSomeoneElse = !targetUserId.equals(currentUserId);
    if (onBehalfOfSomeoneElse && !inventoryAccessPolicy.canManageUserInventory(targetUserId)) {
      throw new AccessDeniedException(
          "You are not allowed to inspect inventory items of other users");
    }
    if ((onBehalfOfSomeoneElse && personal)
        || (stolen && !inventoryProperties.stolenMarkingEnabled())) {
      return false;
    }
    User user = userRepository.findById(targetUserId).orElse(null);
    if (user == null) {
      return false;
    }
    OrgUnit owningOrgUnit;
    try {
      owningOrgUnit =
          orgUnitStampingService.resolveOrgUnitForPickerOutputNullable(user, owningOrgUnitId);
    } catch (AppException refused) {
      return false;
    }
    return inventoryItemRepository.countMergeCandidates(
            targetUserId,
            materialId,
            locationId,
            quality,
            personal,
            stolen,
            owningOrgUnit != null ? owningOrgUnit.getId() : null,
            mergeCutoff.cutoffFor(targetUserId).orElse(null))
        > 0;
  }
}
