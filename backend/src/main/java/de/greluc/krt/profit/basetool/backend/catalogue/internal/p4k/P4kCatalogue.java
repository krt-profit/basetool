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
 * The parts of a version-2 catalogue the import holds in memory: the head, the five base kinds, the
 * starmap objects, the shops, the curated ship store slugs, the paints and the resource groups. The
 * loadouts are streamed in a pass of their own ({@link P4kCatalogueReader#readLoadouts}); every
 * other kind of the file is skipped while reading.
 *
 * @param head the head
 * @param manufacturers the manufacturers, empty when the file has none
 * @param items the items, empty when the file has none
 * @param ships the ships, empty when the file has none
 * @param commodities the commodities, empty when the file has none
 * @param blueprints the blueprints, empty when the file has none
 * @param starmapObjects the starmap objects, empty when the file has none
 * @param shops the shops, empty when the file has none
 * @param shipStoreSlugs the curated ship store slugs, empty when the file has none
 * @param paints the paints, empty when the file has none
 * @param resourceGroups the resource groups, empty when the file has none
 */
public record P4kCatalogue(
    @NotNull P4kCatalogueHead head,
    @NotNull @Unmodifiable List<P4kManufacturerDto> manufacturers,
    @NotNull @Unmodifiable List<P4kItemDto> items,
    @NotNull @Unmodifiable List<P4kShipDto> ships,
    @NotNull @Unmodifiable List<P4kCommodityDto> commodities,
    @NotNull @Unmodifiable List<P4kBlueprintDto> blueprints,
    @NotNull @Unmodifiable List<P4kStarmapObjectDto> starmapObjects,
    @NotNull @Unmodifiable List<P4kShopDto> shops,
    @NotNull @Unmodifiable List<P4kShipStoreSlug> shipStoreSlugs,
    @NotNull @Unmodifiable List<P4kPaintDto> paints,
    @NotNull @Unmodifiable List<P4kResourceGroupDto> resourceGroups) {

  /** The top-level field holding the paints, also its completeness key. */
  public static final String PAINTS = "paints";

  /** The top-level field holding the loadouts, also its completeness key. */
  public static final String LOADOUTS = "loadouts";

  /**
   * Copies every list.
   *
   * @param head the head
   * @param manufacturers the manufacturers
   * @param items the items
   * @param ships the ships
   * @param commodities the commodities
   * @param blueprints the blueprints
   * @param starmapObjects the starmap objects
   * @param shops the shops
   * @param shipStoreSlugs the ship store slugs
   * @param paints the paints
   * @param resourceGroups the resource groups
   */
  public P4kCatalogue {
    manufacturers = List.copyOf(manufacturers);
    items = List.copyOf(items);
    ships = List.copyOf(ships);
    commodities = List.copyOf(commodities);
    blueprints = List.copyOf(blueprints);
    starmapObjects = List.copyOf(starmapObjects);
    shops = List.copyOf(shops);
    shipStoreSlugs = List.copyOf(shipStoreSlugs);
    paints = List.copyOf(paints);
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
      case STARMAP_OBJECT -> starmapObjects;
      case SHOP -> shops;
      case RESOURCE_GROUP -> resourceGroups;
    };
  }
}
