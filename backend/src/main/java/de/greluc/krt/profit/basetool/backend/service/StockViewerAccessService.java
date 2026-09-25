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

import de.greluc.krt.profit.basetool.backend.support.StockViewerAccess;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Implementation of {@link StockViewerAccess} that delegates entirely to {@link AccessGateService},
 * the same gate the write endpoints use.
 */
@Service
@RequiredArgsConstructor
public class StockViewerAccessService implements StockViewerAccess {

  private final AccessGateService accessGateService;

  /** {@inheritDoc} */
  @Override
  public boolean canEditInventoryItem(UUID inventoryItemId) {
    return inventoryItemId != null && accessGateService.canEditInventoryItem(inventoryItemId);
  }

  /** {@inheritDoc} */
  @Override
  public boolean mayEditJobOrder(UUID jobOrderId) {
    return jobOrderId != null && accessGateService.mayEditJobOrder(jobOrderId);
  }
}
