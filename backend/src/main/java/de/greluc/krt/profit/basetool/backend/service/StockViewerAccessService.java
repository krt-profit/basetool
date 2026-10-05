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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockViewerAccess;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link StockViewerAccess} built from {@link AccessGateService} and {@link
 * AuthHelperService}, the same gates the write endpoints use.
 */
@Service
@RequiredArgsConstructor
public class StockViewerAccessService implements StockViewerAccess {

  private final AccessGateService accessGateService;
  private final AuthHelperService authHelperService;

  /** {@inheritDoc} */
  @Override
  public boolean mayEditInventoryItem(UUID inventoryItemId, UUID ownerId) {
    return inventoryItemId != null
        && accessGateService.canEditInventoryItem(inventoryItemId)
        && (isCaller(ownerId) || authHelperService.isLogisticianOrAbove());
  }

  /** {@inheritDoc} */
  @Override
  public boolean mayEditJobOrder(UUID jobOrderId) {
    return jobOrderId != null && accessGateService.mayEditJobOrder(jobOrderId);
  }

  /**
   * Reports whether {@code userId} is the authenticated caller.
   *
   * @param userId the user to compare; {@code null} never matches.
   * @return {@code true} iff the caller is authenticated and has that id.
   */
  private boolean isCaller(UUID userId) {
    return userId != null && authHelperService.currentUserId().filter(userId::equals).isPresent();
  }
}
