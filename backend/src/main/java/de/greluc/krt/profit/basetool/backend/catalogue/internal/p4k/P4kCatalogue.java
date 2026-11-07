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

import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The parts of a version-2 catalogue the import reads: the head, the five base kinds and the
 * resource groups. Every other kind of the file is skipped while reading.
 *
 * @param head the head
 * @param manufacturers the manufacturers, empty when the file has none
 * @param items the items, empty when the file has none
 * @param ships the ships, empty when the file has none
 * @param commodities the commodities, empty when the file has none
 * @param blueprints the blueprints, empty when the file has none
 * @param resourceGroups the resource groups, empty when the file has none
 */
public record P4kCatalogue(
    @NotNull P4kCatalogueHead head,
    @NotNull @Unmodifiable List<P4kManufacturerDto> manufacturers,
    @NotNull @Unmodifiable List<P4kItemDto> items,
    @NotNull @Unmodifiable List<P4kShipDto> ships,
    @NotNull @Unmodifiable List<P4kCommodityDto> commodities,
    @NotNull @Unmodifiable List<P4kBlueprintDto> blueprints,
    @NotNull @Unmodifiable List<P4kResourceGroupDto> resourceGroups) {

  /**
   * Copies every list.
   *
   * @param head the head
   * @param manufacturers the manufacturers
   * @param items the items
   * @param ships the ships
   * @param commodities the commodities
   * @param blueprints the blueprints
   * @param resourceGroups the resource groups
   */
  public P4kCatalogue {
    manufacturers = List.copyOf(manufacturers);
    items = List.copyOf(items);
    ships = List.copyOf(ships);
    commodities = List.copyOf(commodities);
    blueprints = List.copyOf(blueprints);
    resourceGroups = List.copyOf(resourceGroups);
  }

  /**
   * Returns the records of one kind.
   *
   * @param kind the kind
   * @return its records, empty when the file has none
   */
  @NotNull
  @Unmodifiable
  public List<? extends P4kRecord> records(@NotNull P4kKind kind) {
    return switch (kind) {
      case MANUFACTURER -> manufacturers;
      case ITEM -> items;
      case SHIP -> ships;
      case COMMODITY -> commodities;
      case BLUEPRINT -> blueprints;
      case RESOURCE_GROUP -> resourceGroups;
    };
  }
}
