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

package de.greluc.krt.profit.basetool.backend.support;

import java.util.UUID;

/**
 * Caller-aware seam the stock mappers use to fill the viewer-dependent {@code canEdit} field of
 * inventory-item and job-order DTOs.
 *
 * <p>Inverts the {@code mapper} to {@code service} dependency like {@link MissionViewerAccess}
 * (ADR-0047); the implementation lives in {@code service}.
 */
public interface StockViewerAccess {

  /**
   * Reports whether the current caller may write to one Lager row: they own it or hold edit rights
   * on its org unit.
   *
   * @param inventoryItemId the Lager row to test.
   * @return {@code true} iff the current caller may write to it.
   */
  boolean canEditInventoryItem(UUID inventoryItemId);

  /**
   * Reports whether the current caller may edit one job order, applying both the {@code
   * LOGISTICIAN} role and the org-unit scope check the write endpoints enforce.
   *
   * @param jobOrderId the order to test.
   * @return {@code true} iff the current caller may edit it.
   */
  boolean mayEditJobOrder(UUID jobOrderId);
}
