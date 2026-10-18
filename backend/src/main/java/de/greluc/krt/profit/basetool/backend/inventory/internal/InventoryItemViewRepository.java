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
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryScope;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The filtered queries of the Lager items view over {@link InventoryItem} game-item rows
 * (REQ-INV-029, REQ-INV-063): the grouped stacks of both Lager pages and the select-all ids.
 */
public interface InventoryItemViewRepository extends Repository<InventoryItem, UUID> {

  /**
   * The item-attribute clauses of the Lager item view over the game item joined as {@code gi},
   * gated by the {@link ItemAttributeFilter} flags (REQ-INV-063).
   */
  String ITEM_ATTRIBUTE_CLAUSES =
      " AND (:hasKinds = false OR gi.kind IN :kinds)"
          + " AND (:hasWikiTypes = false OR gi.wikiType IN :wikiTypes)"
          + " AND (:hasSizes = false OR gi.sizeClass IN :sizes)"
          + " AND (:hasGrades = false OR TRIM(gi.grade) IN :grades)";

  /**
   * Aggregates the scoped, filtered non-personal game-item stock into one {@link
   * InventoryItemStackAggregate} per item stack key (REQ-INV-029); entries load via {@link
   * InventoryItemRepository#findGlobalItemStackEntries}.
   *
   * @param hasGameItems gates the {@code gameItemIds} clause.
   * @param gameItemIds the game items to narrow to; ignored when {@code hasGameItems} is false.
   * @param hasLocations gates the {@code locationIds} clause.
   * @param locationIds the storage locations to narrow to; ignored when {@code hasLocations} is
   *     false.
   * @param hasJobOrders gates the {@code jobOrderIds} clause.
   * @param jobOrderIds the earmarked orders to narrow to; ignored when {@code hasJobOrders} is
   *     false.
   * @param isAdminAllScope admin all-scopes mode (scope triple, REQ-ORG-003).
   * @param activeOrgUnitId the pinned active org unit, or {@code null}.
   * @param memberOrgUnitIds the caller's org-unit memberships.
   * @param stolenOnly {@code true} narrows to stock marked „gestohlen" (REQ-INV-053).
   * @param nonStolenOnly {@code true} narrows to stock not marked „gestohlen".
   * @param hasKinds gates the {@code kinds} clause (REQ-INV-063).
   * @param kinds the item categories to narrow to; ignored when {@code hasKinds} is false.
   * @param hasWikiTypes gates the {@code wikiTypes} clause.
   * @param wikiTypes the wiki types to narrow to; ignored when {@code hasWikiTypes} is false.
   * @param hasSizes gates the {@code sizes} clause.
   * @param sizes the component sizes to narrow to; ignored when {@code hasSizes} is false.
   * @param hasGrades gates the {@code grades} clause.
   * @param grades the trimmed grades to narrow to; ignored when {@code hasGrades} is false.
   * @return one aggregate per item stack in scope; never {@code null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemStackAggregate(gi, i.user, i.location, i.personal,
      i.stolen, oou, SUM(COALESCE(i.amount, 0.0)), COUNT(i)) FROM InventoryItem i
      LEFT JOIN i.gameItem gi
      LEFT JOIN i.owningOrgUnit oou
      WHERE i.personal = false AND i.gameItem IS NOT NULL AND
      """
          + InventoryScope.TRIPLE
          + " AND (:hasGameItems = false OR i.gameItem.id IN :gameItemIds) AND (:hasJobOrders ="
          + " false OR EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja WHERE"
          + " ja.inventoryItem = i AND ja.jobOrderId IN :jobOrderIds))"
          + " AND (:hasLocations = false OR i.location.id IN :locationIds)"
          + " AND (:stolenOnly = false OR i.stolen = true) AND (:nonStolenOnly = false OR"
          + " i.stolen = false)"
          + ITEM_ATTRIBUTE_CLAUSES
          + " GROUP BY gi, i.user, i.location, i.personal, i.stolen, oou")
  List<InventoryItemStackAggregate> findGlobalItemStacks(
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") @Nullable List<UUID> gameItemIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") @Nullable List<UUID> locationIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") @Nullable List<UUID> jobOrderIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("stolenOnly") boolean stolenOnly,
      @Param("nonStolenOnly") boolean nonStolenOnly,
      @Param("hasKinds") boolean hasKinds,
      @Param("kinds") @Nullable List<GameItemKind> kinds,
      @Param("hasWikiTypes") boolean hasWikiTypes,
      @Param("wikiTypes") @Nullable List<String> wikiTypes,
      @Param("hasSizes") boolean hasSizes,
      @Param("sizes") @Nullable List<Integer> sizes,
      @Param("hasGrades") boolean hasGrades,
      @Param("grades") @Nullable List<String> grades);

  /**
   * Aggregates the user's filtered game-item stock into one {@link InventoryItemStackAggregate} per
   * item stack key (REQ-INV-029); entries load via {@link
   * InventoryItemRepository#findUserItemStackEntries}.
   *
   * @param userId the owning user whose stacks to aggregate.
   * @param hasGameItems gates the {@code gameItemIds} clause.
   * @param gameItemIds the game items to narrow to; ignored when {@code hasGameItems} is false.
   * @param hasLocations gates the {@code locationIds} clause.
   * @param locationIds the storage locations to narrow to; ignored when {@code hasLocations} is
   *     false.
   * @param hasJobOrders gates the {@code jobOrderIds} clause.
   * @param jobOrderIds the earmarked orders to narrow to; ignored when {@code hasJobOrders} is
   *     false.
   * @param personalOnly {@code true} narrows to the caller's private stock rows.
   * @param nonPersonalOnly {@code true} narrows to the caller's shared stock rows.
   * @param stolenOnly {@code true} narrows to stock marked „gestohlen" (REQ-INV-053).
   * @param nonStolenOnly {@code true} narrows to stock not marked „gestohlen".
   * @param hasKinds gates the {@code kinds} clause (REQ-INV-063).
   * @param kinds the item categories to narrow to; ignored when {@code hasKinds} is false.
   * @param hasWikiTypes gates the {@code wikiTypes} clause.
   * @param wikiTypes the wiki types to narrow to; ignored when {@code hasWikiTypes} is false.
   * @param hasSizes gates the {@code sizes} clause.
   * @param sizes the component sizes to narrow to; ignored when {@code hasSizes} is false.
   * @param hasGrades gates the {@code grades} clause.
   * @param grades the trimmed grades to narrow to; ignored when {@code hasGrades} is false.
   * @return one aggregate per item stack the user owns; never {@code null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemStackAggregate(gi, i.user, i.location, i.personal,
      i.stolen, oou, SUM(COALESCE(i.amount, 0.0)), COUNT(i)) FROM InventoryItem i
      LEFT JOIN i.gameItem gi
      LEFT JOIN i.owningOrgUnit oou
      WHERE i.user.id = :userId AND i.gameItem IS NOT NULL
      AND (:personalOnly = false OR i.personal = true)
      AND (:nonPersonalOnly = false OR i.personal = false)
      AND (:stolenOnly = false OR i.stolen = true)
      AND (:nonStolenOnly = false OR i.stolen = false)
      AND (:hasGameItems = false OR i.gameItem.id IN :gameItemIds)
      AND (:hasJobOrders = false OR EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrderId IN :jobOrderIds))
      AND (:hasLocations = false OR i.location.id IN :locationIds)
      """
          + ITEM_ATTRIBUTE_CLAUSES
          + " GROUP BY gi, i.user, i.location, i.personal, i.stolen, oou")
  List<InventoryItemStackAggregate> findUserItemStacks(
      @Param("userId") UUID userId,
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") @Nullable List<UUID> gameItemIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") @Nullable List<UUID> locationIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") @Nullable List<UUID> jobOrderIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly,
      @Param("stolenOnly") boolean stolenOnly,
      @Param("nonStolenOnly") boolean nonStolenOnly,
      @Param("hasKinds") boolean hasKinds,
      @Param("kinds") @Nullable List<GameItemKind> kinds,
      @Param("hasWikiTypes") boolean hasWikiTypes,
      @Param("wikiTypes") @Nullable List<String> wikiTypes,
      @Param("hasSizes") boolean hasSizes,
      @Param("sizes") @Nullable List<Integer> sizes,
      @Param("hasGrades") boolean hasGrades,
      @Param("grades") @Nullable List<String> grades);

  /**
   * Returns the ids of every game-item entry the user owns that matches the {@link
   * #findUserItemStacks} filters, ordered by {@code createdAt} (REQ-INV-034).
   *
   * @param userId the owning user whose item entry ids to collect.
   * @param hasGameItems gates the {@code gameItemIds} clause.
   * @param gameItemIds the game items to narrow to; ignored when {@code hasGameItems} is false.
   * @param hasLocations gates the {@code locationIds} clause.
   * @param locationIds the storage locations to narrow to; ignored when {@code hasLocations} is
   *     false.
   * @param hasJobOrders gates the {@code jobOrderIds} clause.
   * @param jobOrderIds the earmarked orders to narrow to; ignored when {@code hasJobOrders} is
   *     false.
   * @param personalOnly {@code true} narrows to the caller's private stock rows.
   * @param nonPersonalOnly {@code true} narrows to the caller's shared stock rows.
   * @param stolenOnly {@code true} narrows to stock marked „gestohlen" (REQ-INV-053).
   * @param nonStolenOnly {@code true} narrows to stock not marked „gestohlen".
   * @param hasKinds gates the {@code kinds} clause (REQ-INV-063).
   * @param kinds the item categories to narrow to; ignored when {@code hasKinds} is false.
   * @param hasWikiTypes gates the {@code wikiTypes} clause.
   * @param wikiTypes the wiki types to narrow to; ignored when {@code hasWikiTypes} is false.
   * @param hasSizes gates the {@code sizes} clause.
   * @param sizes the component sizes to narrow to; ignored when {@code hasSizes} is false.
   * @param hasGrades gates the {@code grades} clause.
   * @param grades the trimmed grades to narrow to; ignored when {@code hasGrades} is false.
   * @return the ids of every matching game-item entry the user owns; never {@code null}.
   */
  @Query(
      """
      SELECT i.id FROM InventoryItem i
      JOIN i.gameItem gi
      WHERE i.user.id = :userId
      AND (:personalOnly = false OR i.personal = true)
      AND (:nonPersonalOnly = false OR i.personal = false)
      AND (:stolenOnly = false OR i.stolen = true)
      AND (:nonStolenOnly = false OR i.stolen = false)
      AND (:hasGameItems = false OR i.gameItem.id IN :gameItemIds)
      AND (:hasJobOrders = false OR EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrderId IN :jobOrderIds))
      AND (:hasLocations = false OR i.location.id IN :locationIds)
      """
          + ITEM_ATTRIBUTE_CLAUSES
          + " ORDER BY i.createdAt ASC")
  List<UUID> findUserItemEntryIds(
      @Param("userId") UUID userId,
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") @Nullable List<UUID> gameItemIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") @Nullable List<UUID> locationIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") @Nullable List<UUID> jobOrderIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly,
      @Param("stolenOnly") boolean stolenOnly,
      @Param("nonStolenOnly") boolean nonStolenOnly,
      @Param("hasKinds") boolean hasKinds,
      @Param("kinds") @Nullable List<GameItemKind> kinds,
      @Param("hasWikiTypes") boolean hasWikiTypes,
      @Param("wikiTypes") @Nullable List<String> wikiTypes,
      @Param("hasSizes") boolean hasSizes,
      @Param("sizes") @Nullable List<Integer> sizes,
      @Param("hasGrades") boolean hasGrades,
      @Param("grades") @Nullable List<String> grades);
}
