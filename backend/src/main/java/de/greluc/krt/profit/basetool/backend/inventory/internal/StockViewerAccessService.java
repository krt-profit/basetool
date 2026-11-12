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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockViewerAccess;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link StockViewerAccess} built from {@link AccessGateService} and {@link
 * AuthHelperService}, the same gates the write endpoints use.
 */
@Service
@RequiredArgsConstructor
public class StockViewerAccessService implements StockViewerAccess {

  private final InventoryAccessPolicy inventoryAccessPolicy;
  private final AuthHelperService authHelperService;

  /** {@inheritDoc} */
  @Override
  public boolean mayEditInventoryItem(UUID inventoryItemId, @Nullable UUID ownerId) {
    return inventoryItemId != null
        && mayEdit(
            inventoryAccessPolicy.canEditInventoryItem(inventoryItemId),
            ownerId,
            () -> inventoryAccessPolicy.isLogisticianOfItem(inventoryItemId));
  }

  /**
   * Reports whether {@code userId} is the authenticated caller.
   *
   * @param userId the user to compare; {@code null} never matches.
   * @return {@code true} iff the caller is authenticated and has that id.
   */
  private boolean isCaller(@Nullable UUID userId) {
    return userId != null && authHelperService.currentUserId().filter(userId::equals).isPresent();
  }

  /** {@inheritDoc} */
  @Override
  public boolean mayEditInventoryIdentity(@Nullable UUID ownerId, @Nullable UUID owningOrgUnitId) {
    return mayEdit(
        inventoryAccessPolicy.canEditInventoryIdentity(ownerId, owningOrgUnitId),
        ownerId,
        () -> inventoryAccessPolicy.isLogisticianOfOwner(owningOrgUnitId));
  }

  /** Applies the full Inventory action predicate while retaining its short-circuiting gates. */
  private boolean mayEdit(boolean inScope, @Nullable UUID ownerId, BooleanSupplier logistician) {
    return inScope && (isCaller(ownerId) || logistician.getAsBoolean());
  }
}
