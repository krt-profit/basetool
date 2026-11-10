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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Owns current stock authority and exact connected corrections of known physical facts. */
public interface StockQuantityCorrections {
  /**
   * Reads editable current rows carrying any of the known physical intervals.
   *
   * @param quantity the known original physical quantity
   * @return current independently versioned correction resources
   */
  List<InventoryQuantityCorrectionOptionDto> currentRows(
      Collection<StockQuantityFragment> quantity);

  /**
   * Corrects false known quantity together with every affected retained stock slice.
   *
   * @param removed exact known intervals to remove
   * @param versions independently observed affected row versions
   * @param requestedScopes every additional target-owned material scope of this correction
   * @param factId the connected historical provision identity for privacy-safe audit
   */
  void removeKnownQuantity(
      Collection<StockQuantityFragment> removed,
      List<InventoryRowVersionDto> versions,
      Collection<OrderMaterialScope> requestedScopes,
      UUID factId);

  /**
   * Admits an explicitly corrected extra quantity on one retained connected physical row.
   *
   * @param connected the retained original fact intervals
   * @param additionalQuanta the explicitly declared extra quantity
   * @param selectedRowId the chosen connected placement
   * @param versions observed current row versions
   * @param provisionScope original order and material of the false fact
   * @param factId connected provision identity for audit
   * @return only the newly admitted physical intervals
   */
  List<StockQuantityFragment> addKnownQuantity(
      Collection<StockQuantityFragment> connected,
      long additionalQuanta,
      UUID selectedRowId,
      List<InventoryRowVersionDto> versions,
      OrderMaterialScope provisionScope,
      UUID factId);
}
