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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the {@code ResourceType} and entity GUIDs of a P4K recipe to the rows of one run
 * (REQ-P4K-024, REQ-P4K-027): a resource through the commodity join, else through the material an
 * admin assigned to its review case; an item through its P4K GUID.
 */
final class P4kRecipeLinks {

  /** The commodities the run matched, by P4K GUID. */
  @NotNull private final Map<UUID, P4kKeyedRow> commodities;

  /** The items the run matched or created, by P4K GUID. */
  @NotNull private final Map<UUID, P4kKeyedRow> items;

  /** The commodity join of the run's catalogue. */
  @NotNull private final P4kCommodityJoin join;

  /** Finds the materials. */
  @NotNull private final MaterialRepository materialRepository;

  /** Finds the items. */
  @NotNull private final GameItemRepository gameItemRepository;

  /** Finds an admin's assignment of a resource that joins no commodity or several. */
  @NotNull private final P4kReviewCaseRepository reviewCaseRepository;

  /**
   * Creates the links of one run.
   *
   * @param context the run, holding the rows it matched
   * @param join the commodity join of the run's catalogue
   * @param materialRepository finds the materials
   * @param gameItemRepository finds the items
   * @param reviewCaseRepository finds the admin's resource assignments
   */
  P4kRecipeLinks(
      @NotNull P4kImportContext context,
      @NotNull P4kCommodityJoin join,
      @NotNull MaterialRepository materialRepository,
      @NotNull GameItemRepository gameItemRepository,
      @NotNull P4kReviewCaseRepository reviewCaseRepository) {
    this.commodities = context.stampedRows(P4kKind.COMMODITY);
    this.items = context.stampedRows(P4kKind.ITEM);
    this.join = join;
    this.materialRepository = materialRepository;
    this.gameItemRepository = gameItemRepository;
    this.reviewCaseRepository = reviewCaseRepository;
  }

  /**
   * Resolves a {@code ResourceType}: the one commodity the join yields, else the material an admin
   * assigned to the resource's review case.
   *
   * @param resourceGuid the {@code ResourceType} GUID
   * @return the material, or {@code null}
   */
  @Nullable
  Material material(@NotNull UUID resourceGuid) {
    P4kCommodityDto commodity = join.single(resourceGuid.toString());
    UUID commodityGuid = commodity == null ? null : commodity.parsedGuid();
    if (commodityGuid != null) {
      P4kKeyedRow stamped = commodities.get(commodityGuid);
      if (stamped instanceof Material material) {
        return material;
      }
      return materialRepository.findByP4kUuid(commodityGuid).orElse(null);
    }
    return reviewCaseRepository
        .findFirstByKindAndGuidAndResolutionAndRowIdIsNotNull(
            P4kKind.COMMODITY, resourceGuid, P4kReviewResolution.ASSIGN)
        .map(P4kReviewCase::getRowId)
        .flatMap(materialRepository::findById)
        .orElse(null);
  }

  /**
   * Names a {@code ResourceType} whose material did not resolve, by the one commodity record the
   * join yields.
   *
   * @param resourceGuid the {@code ResourceType} GUID
   * @return the commodity's display name, or {@code null}
   */
  @Nullable
  String resourceName(@NotNull UUID resourceGuid) {
    P4kCommodityDto commodity = join.single(resourceGuid.toString());
    return commodity == null ? null : commodity.displayName();
  }

  /**
   * Resolves an entity GUID to the item the run matched or created, else to the stored item.
   *
   * @param itemGuid the entity GUID
   * @return the item, or {@code null}
   */
  @Nullable
  GameItem item(@NotNull UUID itemGuid) {
    P4kKeyedRow stamped = items.get(itemGuid);
    if (stamped instanceof GameItem item) {
      return item;
    }
    return gameItemRepository.findByP4kUuid(itemGuid).orElse(null);
  }
}
