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

import de.greluc.krt.profit.basetool.backend.blueprint.api.CraftabilitySlice;
import de.greluc.krt.profit.basetool.backend.blueprint.api.CraftableStockSource;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tells the blueprint craftability what stock a member owns in the Lager. */
@Component
@RequiredArgsConstructor
public class InventoryCraftableStock implements CraftableStockSource {

  private final InventoryAggregationService inventoryAggregationService;

  @Override
  @Transactional(readOnly = true)
  public @NotNull List<CraftabilitySlice> ownedStockSlices(@NotNull UUID userId) {
    return inventoryAggregationService.getOwnedStockSlices(userId).stream()
        .map(s -> new CraftabilitySlice(s.materialId(), s.quality(), s.totalScu()))
        .toList();
  }
}
