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

package de.greluc.krt.profit.basetool.backend.inventory.web;

import de.greluc.krt.profit.basetool.backend.inventory.internal.ItemAttributeFilter;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Rejects a Lager filter or stack key that does not match the requested stock catalog with 400. */
final class CatalogFilterGuard {

  private CatalogFilterGuard() {}

  /**
   * Rejects the material-only filters {@code minQuality}, {@code missionIds} and {@code
   * materialIds} on a {@code catalog=ITEM} read (REQ-INV-029/031).
   *
   * @param minQuality the quality floor; must be {@code null} for the item catalog
   * @param missionIds the mission filter; must be {@code null} or empty for the item catalog
   * @param materialIds the material filter; must be {@code null} or empty for the item catalog
   * @throws BadRequestException when any material-only filter is present
   */
  static void rejectMaterialOnlyFilters(
      Integer minQuality, List<UUID> missionIds, List<UUID> materialIds) {
    if (minQuality != null) {
      throw new BadRequestException("minQuality is not supported for catalog=ITEM");
    }
    if (missionIds != null && !missionIds.isEmpty()) {
      throw new BadRequestException("missionIds is not supported for catalog=ITEM");
    }
    if (materialIds != null && !materialIds.isEmpty()) {
      throw new BadRequestException("materialIds is not supported for catalog=ITEM");
    }
  }

  /**
   * Rejects the item-only filters on a {@code catalog=MATERIAL} read (REQ-INV-030, REQ-INV-063).
   *
   * @param gameItemIds the game-item filter; must be {@code null} or empty for the material catalog
   * @param attributes the item-attribute filters; must narrow nothing for the material catalog
   * @throws BadRequestException when an item-only filter is present
   */
  static void rejectItemOnlyFilters(
      List<UUID> gameItemIds, @NotNull ItemAttributeFilter attributes) {
    if (gameItemIds != null && !gameItemIds.isEmpty()) {
      throw new BadRequestException("gameItemIds is not supported for catalog=MATERIAL");
    }
    if (!attributes.isEmpty()) {
      throw new BadRequestException(
          "kinds, componentTypes, sizes, grades and itemClasses are not supported for"
              + " catalog=MATERIAL");
    }
  }

  /**
   * Validates the stack address of a {@code catalog=ITEM} stack-entries drill-down (REQ-INV-005/
   * 029): the stack is keyed by {@code gameItemId}, and a {@code quality} parameter is rejected
   * because item stacks carry no quality dimension.
   *
   * @param gameItemId the item stack key; must be present
   * @param quality the material-only quality key; must be absent
   * @throws BadRequestException when the item key is missing or a quality key is supplied
   */
  static void requireItemStackKey(UUID gameItemId, Integer quality) {
    if (gameItemId == null) {
      throw new BadRequestException("gameItemId is required for catalog=ITEM");
    }
    if (quality != null) {
      throw new BadRequestException("quality is not supported for catalog=ITEM");
    }
  }

  /**
   * Validates that a {@code catalog=MATERIAL} stack-entries drill-down names its {@code materialId}
   * stack key.
   *
   * @param materialId the material stack key; must be present
   * @throws BadRequestException when the material key is missing
   */
  static void requireMaterialStackKey(UUID materialId) {
    if (materialId == null) {
      throw new BadRequestException("materialId is required for catalog=MATERIAL");
    }
  }
}
