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

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The materials and game items with at least one low level in the caller's scope, for the „niedrig"
 * marker of the aggregated Lager (REQ-INV-068).
 *
 * @param materialIds the materials
 * @param gameItemIds the game items
 */
public record LowStockSubjects(@NotNull Set<UUID> materialIds, @NotNull Set<UUID> gameItemIds) {

  /**
   * Tells whether a material has a low level.
   *
   * @param materialId the material, or {@code null} for a game-item row
   * @return {@code true} iff the material is given and one of its levels is low
   */
  public boolean lowMaterial(@Nullable UUID materialId) {
    return materialId != null && materialIds.contains(materialId);
  }

  /**
   * Tells whether a game item has a low level.
   *
   * @param gameItemId the game item, or {@code null} for a material row
   * @return {@code true} iff the item is given and its level is low
   */
  public boolean lowGameItem(@Nullable UUID gameItemId) {
    return gameItemId != null && gameItemIds.contains(gameItemId);
  }
}
