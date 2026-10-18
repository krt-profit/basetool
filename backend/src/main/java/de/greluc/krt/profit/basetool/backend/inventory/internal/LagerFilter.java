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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentClass;
import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipComponentType;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Every filter of a Lager read, bound once from the query string and passed down in place of loose
 * parameters (REQ-INV-029, REQ-INV-031, REQ-INV-040, REQ-INV-053, REQ-INV-063).
 *
 * <p>The record owns the defaults (no list narrows, {@code catalog} falls back to {@code
 * MATERIAL}), the contradictory flag pairs and the filters the requested catalog cannot honour. A
 * read applies the dimensions that belong to it and ignores the others: the squadron-wide reads
 * ignore the personal flags and the flat lists ignore the stolen flags.
 *
 * @param materialIds the materials to narrow to ({@code MATERIAL} only)
 * @param gameItemIds the game items to narrow to ({@code ITEM} only)
 * @param locationIds the storage locations to narrow to, valid for both catalogs
 * @param minQuality the quality floor ({@code MATERIAL} only)
 * @param jobOrderIds the earmarking job orders to narrow to
 * @param missionIds the earmarking missions to narrow to ({@code MATERIAL} only)
 * @param personalOnly whether only the caller's private rows count
 * @param nonPersonalOnly whether only the caller's shared rows count
 * @param stolenOnly whether only stock marked „gestohlen" counts
 * @param nonStolenOnly whether only stock not marked „gestohlen" counts
 * @param kinds the item categories to narrow to ({@code ITEM} only)
 * @param componentTypes the ship component types to narrow to ({@code ITEM} only)
 * @param sizes the component sizes to narrow to ({@code ITEM} only)
 * @param grades the component grades to narrow to ({@code ITEM} only)
 * @param itemClasses the component classes to narrow to ({@code ITEM} only)
 * @param catalog the stock catalog the read targets
 */
@Builder
public record LagerFilter(
    @Unmodifiable List<UUID> materialIds,
    @Unmodifiable List<UUID> gameItemIds,
    @Unmodifiable List<UUID> locationIds,
    @Nullable Integer minQuality,
    @Unmodifiable List<UUID> jobOrderIds,
    @Unmodifiable List<UUID> missionIds,
    Boolean personalOnly,
    Boolean nonPersonalOnly,
    Boolean stolenOnly,
    Boolean nonStolenOnly,
    @Unmodifiable List<GameItemKind> kinds,
    @Unmodifiable List<ShipComponentType> componentTypes,
    @Unmodifiable List<Integer> sizes,
    @Unmodifiable List<String> grades,
    @Unmodifiable List<ShipComponentClass> itemClasses,
    InventoryCatalog catalog) {

  /** Narrows nothing, material catalog. */
  public static final LagerFilter NONE = LagerFilter.builder().build();

  /**
   * Normalises the bound values: {@code null} lists become empty, {@code null} entries are dropped
   * and {@code catalog} falls back to {@code MATERIAL}.
   *
   * @param materialIds the materials, may be {@code null}
   * @param gameItemIds the game items, may be {@code null}
   * @param locationIds the storage locations, may be {@code null}
   * @param minQuality the quality floor, may be {@code null}
   * @param jobOrderIds the job orders, may be {@code null}
   * @param missionIds the missions, may be {@code null}
   * @param personalOnly the private-rows flag
   * @param nonPersonalOnly the shared-rows flag
   * @param stolenOnly the stolen-only flag
   * @param nonStolenOnly the not-stolen-only flag
   * @param kinds the item categories, may be {@code null}
   * @param componentTypes the ship component types, may be {@code null}
   * @param sizes the component sizes, may be {@code null}
   * @param grades the component grades, may be {@code null}
   * @param itemClasses the component classes, may be {@code null}
   * @param catalog the stock catalog, may be {@code null}
   */
  public LagerFilter(
      @Nullable List<UUID> materialIds,
      @Nullable List<UUID> gameItemIds,
      @Nullable List<UUID> locationIds,
      @Nullable Integer minQuality,
      @Nullable List<UUID> jobOrderIds,
      @Nullable List<UUID> missionIds,
      Boolean personalOnly,
      Boolean nonPersonalOnly,
      Boolean stolenOnly,
      Boolean nonStolenOnly,
      @Nullable List<GameItemKind> kinds,
      @Nullable List<ShipComponentType> componentTypes,
      @Nullable List<Integer> sizes,
      @Nullable List<String> grades,
      @Nullable List<ShipComponentClass> itemClasses,
      @Nullable InventoryCatalog catalog) {
    this.materialIds = withoutNulls(materialIds);
    this.gameItemIds = withoutNulls(gameItemIds);
    this.locationIds = withoutNulls(locationIds);
    this.minQuality = minQuality;
    this.jobOrderIds = withoutNulls(jobOrderIds);
    this.missionIds = withoutNulls(missionIds);
    this.personalOnly = Boolean.TRUE.equals(personalOnly);
    this.nonPersonalOnly = Boolean.TRUE.equals(nonPersonalOnly);
    this.stolenOnly = Boolean.TRUE.equals(stolenOnly);
    this.nonStolenOnly = Boolean.TRUE.equals(nonStolenOnly);
    this.kinds = withoutNulls(kinds);
    this.componentTypes = withoutNulls(componentTypes);
    this.sizes = withoutNulls(sizes);
    this.grades = withoutNulls(grades);
    this.itemClasses = withoutNulls(itemClasses);
    this.catalog = catalog == null ? InventoryCatalog.MATERIAL : catalog;
  }

  /**
   * The item-attribute filters as the value the item queries read.
   *
   * @return the normalised category, component-type, size, grade and class filters
   */
  public @NotNull ItemAttributeFilter attributes() {
    return new ItemAttributeFilter(kinds, componentTypes, sizes, grades, itemClasses);
  }

  /**
   * Whether the requested flags exclude each other: private and shared rows, or stolen and
   * not-stolen stock. No row can match such a pair, so a read answers empty.
   *
   * @return {@code true} when the filter cannot match any row
   */
  public boolean contradictory() {
    return (personalOnly && nonPersonalOnly) || stolenContradiction();
  }

  /**
   * Whether the stolen and not-stolen flags are both set, which no stock row satisfies; the
   * squadron-wide reads, which ignore the personal flags, check only this pair.
   *
   * @return {@code true} when {@code stolenOnly} and {@code nonStolenOnly} are both set
   */
  public boolean stolenContradiction() {
    return stolenOnly && nonStolenOnly;
  }

  /**
   * Whether a material filter narrows the result.
   *
   * @return whether {@code materialIds} is non-empty
   */
  public boolean hasMaterials() {
    return !materialIds.isEmpty();
  }

  /**
   * The material ids as a query parameter.
   *
   * @return the ids, or {@code null} when none narrows the result
   */
  public @Nullable List<UUID> materialIdsOrNull() {
    return orNull(materialIds);
  }

  /**
   * Whether a game-item filter narrows the result.
   *
   * @return whether {@code gameItemIds} is non-empty
   */
  public boolean hasGameItems() {
    return !gameItemIds.isEmpty();
  }

  /**
   * The game-item ids as a query parameter.
   *
   * @return the ids, or {@code null} when none narrows the result
   */
  public @Nullable List<UUID> gameItemIdsOrNull() {
    return orNull(gameItemIds);
  }

  /**
   * Whether a location filter narrows the result.
   *
   * @return whether {@code locationIds} is non-empty
   */
  public boolean hasLocations() {
    return !locationIds.isEmpty();
  }

  /**
   * The location ids as a query parameter.
   *
   * @return the ids, or {@code null} when none narrows the result
   */
  public @Nullable List<UUID> locationIdsOrNull() {
    return orNull(locationIds);
  }

  /**
   * Whether a job-order filter narrows the result.
   *
   * @return whether {@code jobOrderIds} is non-empty
   */
  public boolean hasJobOrders() {
    return !jobOrderIds.isEmpty();
  }

  /**
   * The job-order ids as a query parameter.
   *
   * @return the ids, or {@code null} when none narrows the result
   */
  public @Nullable List<UUID> jobOrderIdsOrNull() {
    return orNull(jobOrderIds);
  }

  /**
   * Whether a mission filter narrows the result.
   *
   * @return whether {@code missionIds} is non-empty
   */
  public boolean hasMissions() {
    return !missionIds.isEmpty();
  }

  /**
   * The mission ids as a query parameter.
   *
   * @return the ids, or {@code null} when none narrows the result
   */
  public @Nullable List<UUID> missionIdsOrNull() {
    return orNull(missionIds);
  }

  /**
   * Rejects a filter the requested catalog cannot honour with 400 (REQ-INV-029/031): the material
   * catalog takes no game-item or attribute filter, the item catalog no quality, mission or
   * material filter.
   *
   * @throws BadRequestException when a filter of the other catalog is present
   */
  public void requireMatchingCatalog() {
    if (catalog == InventoryCatalog.ITEM) {
      rejectMaterialOnlyFilters();
    } else {
      rejectItemOnlyFilters();
    }
  }

  private void rejectMaterialOnlyFilters() {
    if (minQuality != null) {
      throw new BadRequestException("minQuality is not supported for catalog=ITEM");
    }
    if (hasMissions()) {
      throw new BadRequestException("missionIds is not supported for catalog=ITEM");
    }
    if (hasMaterials()) {
      throw new BadRequestException("materialIds is not supported for catalog=ITEM");
    }
  }

  private void rejectItemOnlyFilters() {
    if (hasGameItems()) {
      throw new BadRequestException("gameItemIds is not supported for catalog=MATERIAL");
    }
    if (!attributes().isEmpty()) {
      throw new BadRequestException(
          "kinds, componentTypes, sizes, grades and itemClasses are not supported for"
              + " catalog=MATERIAL");
    }
  }

  private static <T> @Unmodifiable List<T> withoutNulls(@Nullable List<T> values) {
    return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
  }

  private static <T> @Nullable List<T> orNull(@NotNull List<T> values) {
    return values.isEmpty() ? null : values;
  }
}
