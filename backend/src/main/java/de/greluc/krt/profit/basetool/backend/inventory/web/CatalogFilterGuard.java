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

import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.UUID;

/** Rejects a stack key that does not match the requested stock catalog with 400. */
final class CatalogFilterGuard {

  private CatalogFilterGuard() {}

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
