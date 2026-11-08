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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** A catalogue kind the P4K import reads, with the name of its array in the catalogue. */
@RequiredArgsConstructor
public enum P4kKind {

  /** {@code manufacturers}, matched against {@code manufacturer}. */
  MANUFACTURER("manufacturers"),

  /** {@code items}, matched against {@code game_item}. */
  ITEM("items"),

  /** {@code ships}, matched against {@code ship_type}. */
  SHIP("ships"),

  /** {@code commodities}, matched against {@code material}. */
  COMMODITY("commodities"),

  /** {@code blueprints}, matched against {@code blueprint}. */
  BLUEPRINT("blueprints"),

  /** {@code starmapObjects}, imported into {@code starmap_object} (REQ-P4K-034). */
  STARMAP_OBJECT("starmapObjects"),

  /** {@code shops}, imported into {@code shop} by their unique {@code key} (REQ-P4K-042). */
  SHOP("shops"),

  /**
   * {@code resourceGroups}, the commodity categories, matched against {@code material_category}.
   */
  RESOURCE_GROUP("resourceGroups"),

  /**
   * {@code blueprintCategories}, the blueprint categories, imported into {@code
   * blueprint_category}.
   */
  BLUEPRINT_CATEGORY("blueprintCategories");

  /** The top-level field of the catalogue that holds this kind, also its completeness key. */
  @Getter @NotNull private final String catalogueField;
}
