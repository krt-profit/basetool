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

import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estimates the sale value of the entries a member marked in "Mein Lager" (REQ-INV-072): it reads
 * the member's own marked material entries and values them like the shown groups.
 */
@Service
@RequiredArgsConstructor
public class InventorySelectionValuation {

  private final InventoryItemRepository inventoryItemRepository;
  private final InventorySaleValuation saleValuation;

  /**
   * Values the member's marked entries; an id of another member's entry, of a game-item entry or of
   * no entry adds nothing.
   *
   * @param userId the member, who owns every counted entry
   * @param itemIds the marked entry ids; never empty
   * @return the selection's total at the member's price basis
   */
  @NotNull
  @Transactional(readOnly = true)
  public InventorySelectionSaleValueDto valueOf(
      @NotNull UUID userId, @NotNull Collection<UUID> itemIds) {
    return saleValuation.valuedSelection(
        inventoryItemRepository.findSaleSlices(userId, itemIds), userId);
  }
}
