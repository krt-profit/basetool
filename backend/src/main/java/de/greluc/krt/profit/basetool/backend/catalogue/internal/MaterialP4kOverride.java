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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * A field of a material the P4K leads that an admin may override (REQ-P4K-024, REQ-P4K-025): while
 * the override is set, a P4K APPLY keeps the admin's value and reports a differing P4K value.
 */
@RequiredArgsConstructor
public enum MaterialP4kOverride {

  /** The resource group, {@code material.category_id}. */
  RESOURCE_GROUP("category_id"),

  /** The refined-from link, {@code material.refined_material_id}. */
  REFINED_MATERIAL("refined_material_id");

  /** The column the field is stored in, as the P4K reports name it. */
  @Getter @NotNull private final String column;

  /**
   * Whether the material holds an admin override of this field.
   *
   * @param material the material
   * @return {@code true} when a P4K APPLY keeps the field's value
   */
  public boolean isSet(@NotNull Material material) {
    return switch (this) {
      case RESOURCE_GROUP -> material.isResourceGroupManual();
      case REFINED_MATERIAL -> material.isRefinedMaterialManual();
    };
  }

  /**
   * Sets or clears the override of this field on a material.
   *
   * @param material the material
   * @param manual {@code true} to keep the admin's value, {@code false} to follow the P4K again
   */
  public void set(@NotNull Material material, boolean manual) {
    switch (this) {
      case RESOURCE_GROUP -> material.setResourceGroupManual(manual);
      case REFINED_MATERIAL -> material.setRefinedMaterialManual(manual);
      case null -> throw new NullPointerException("override field");
    }
  }
}
