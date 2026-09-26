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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderGameItemStockRow;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryItemStackAggregate;
import de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate;
import de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link InventoryItem}.
 *
 * <p>A row stocks either a material ({@code material_id} and {@code quality} set) or a game item
 * ({@code game_item_id} set, no quality), so queries come in per-catalog variants (REQ-INV-029).
 */
@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

  /**
   * Pages the material stock rows owned by {@code user}; item rows are served by {@link
   * #findItemRowsByUser(User, Pageable)}.
   *
   * @param user the owning user; never {@code null}.
   * @return the user's material rows with the display associations eagerly graphed.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.user = :user AND i.material IS NOT NULL")
  Page<InventoryItem> findMaterialRowsByUser(@Param("user") User user, Pageable pageable);

  /**
   * Pages the game-item stock rows owned by {@code user} (REQ-INV-029).
   *
   * @param user the owning user; never {@code null}.
   * @return the user's game-item rows with the display associations eagerly graphed.
   */
  @EntityGraph(
      attributePaths = {"gameItem", "gameItem.manufacturer", "location", "user", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.user = :user AND i.gameItem IS NOT NULL")
  Page<InventoryItem> findItemRowsByUser(@Param("user") User user, Pageable pageable);

  /**
   * Loads an entry for an allocation write under {@link LockModeType#OPTIMISTIC_FORCE_INCREMENT},
   * so the entry's version guards its allocations too (REQ-INV-027).
   *
   * @param id the inventory entry id.
   * @return the entry under a forced version increment, or empty when unknown.
   */
  @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
  @EntityGraph(attributePaths = {"material"})
  @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
  Optional<InventoryItem> findByIdForAllocationWrite(@Param("id") UUID id);

  /**
   * Lists every inventory row, shared and personal, linked to {@code missionId}, without org-unit
   * scoping.
   *
   * @param missionId the mission whose linked inventory to load; never {@code null}.
   * @return the mission's inventory rows; never {@code null}, possibly empty.
   */
  @EntityGraph(attributePaths = {"material", "location", "user"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE EXISTS (SELECT 1 FROM InventoryMissionAllocation ma
      WHERE ma.inventoryItem = i AND ma.mission.id = :missionId)
      """)
  List<InventoryItem> findByMissionId(@Param("missionId") UUID missionId);

  /**
   * Lists the inventory rows linked to {@code missionId} within the caller's org-unit scope; used
   * by the mission-detail "Lagereinträge" panel (REQ-ORG-003).
   *
   * @param missionId the mission whose linked inventory to load; never {@code null}.
   * @param isAdminAllScope {@code true} for an unpinned admin (no org-unit filter).
   * @param activeOrgUnitId the pinned org unit, or {@code null} when unpinned.
   * @param memberOrgUnitIds the caller's memberships, consulted only when unpinned.
   * @return the mission's inventory rows within the caller's scope; never {@code null}.
   */
  @EntityGraph(attributePaths = {"material", "location", "user"})
  @Query(
      "SELECT i FROM InventoryItem i WHERE EXISTS (SELECT 1 FROM InventoryMissionAllocation ma"
          + " WHERE ma.inventoryItem = i AND ma.mission.id = :missionId) AND "
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE)
  List<InventoryItem> findByMissionIdScoped(
      @Param("missionId") UUID missionId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Loads every non-personal inventory row owned by the given user, for {@link
   * de.greluc.krt.profit.basetool.backend.service.InventoryOrgUnitReconciler}.
   *
   * @param userId the owner whose shared inventory to load; never {@code null}.
   * @return the user's non-personal inventory rows; never {@code null}, possibly empty.
   */
  @EntityGraph(attributePaths = {"material", "location", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.user.id = :userId AND i.personal = false")
  List<InventoryItem> findByUserIdAndPersonalFalse(@Param("userId") UUID userId);

  /**
   * Sums the user's own material stock, personal and shared, into one SCU total per (material,
   * quality) across all locations, for the craftability calculation.
   *
   * @param userId the owning user; never {@code null}
   * @return one slice per (material, quality) the user owns, with the summed SCU; never {@code
   *     null}
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice(i.material.id, i.quality, SUM(COALESCE(i.amount, 0.0))) FROM InventoryItem i
      WHERE i.user.id = :userId AND i.material IS NOT NULL GROUP BY i.material.id, i.quality
      """)
  List<OwnedStockSlice> sumOwnedStockByMaterialAndQuality(@Param("userId") UUID userId);

  /** Derived Spring-Data query - returns entities matching {@code MaterialAndPersonalFalse}. */
  Page<InventoryItem> findByMaterialAndPersonalFalse(Material material, Pageable pageable);

  /**
   * Pages the non-personal stock rows of one material within the caller's org-unit scope, for the
   * per-material drilldown.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query(
      "SELECT i FROM InventoryItem i WHERE i.material = :material AND i.personal = false AND "
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE)
  Page<InventoryItem> findByMaterialAndPersonalFalseScoped(
      @Param("material") Material material,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Pages the non-personal stock rows of one game item within the caller's org-unit scope, for the
   * per-game-item drilldown (REQ-INV-029).
   *
   * @param gameItem the game item to drill into; never {@code null}.
   * @param isAdminAllScope admin all-scopes mode (scope triple, REQ-ORG-003).
   * @param activeOrgUnitId the pinned active org unit, or {@code null}.
   * @param memberOrgUnitIds the caller's org-unit memberships.
   * @param pageable page request (whitelisted {@code location.name} / {@code amount} sort).
   * @return the scoped non-personal rows stocking that game item.
   */
  @EntityGraph(
      attributePaths = {"gameItem", "gameItem.manufacturer", "location", "user", "owningOrgUnit"})
  @Query(
      "SELECT i FROM InventoryItem i WHERE i.gameItem = :gameItem AND i.personal = false AND "
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE)
  Page<InventoryItem> findByGameItemAndPersonalFalseScoped(
      @Param("gameItem") GameItem gameItem,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /** Derived Spring-Data query - returns entities matching {@code PersonalFalse}. */
  Page<InventoryItem> findByPersonalFalse(Pageable pageable);

  /**
   * Pages non-personal material rows within the caller's org-unit scope, with optional material,
   * location, job-order, mission and minimum-quality filters, each switched by its flag.
   *
   * <p>The item variant is {@link #findGlobalItemsByFilters}.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query(
      "SELECT i FROM InventoryItem i WHERE i.personal = false AND i.material IS NOT NULL AND "
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " AND (:hasMaterials = false OR i.material.id IN :materialIds) AND (:minQuality IS"
          + " NULL OR i.quality >= :minQuality) AND (:hasJobOrders = false OR EXISTS (SELECT 1"
          + " FROM InventoryJobOrderAllocation ja WHERE ja.inventoryItem = i AND ja.jobOrder.id"
          + " IN :jobOrderIds)) AND (:hasMissions = false OR EXISTS (SELECT 1 FROM"
          + " InventoryMissionAllocation ma WHERE ma.inventoryItem = i AND ma.mission.id IN"
          + " :missionIds)) AND (:hasLocations = false OR i.location.id IN :locationIds)")
  Page<InventoryItem> findGlobalByFilters(
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Pages the material rows owned by {@code :user} with the same optional filters as {@link
   * #findGlobalByFilters}; the item variant is {@link #findUserItemsByFilters}.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.user = :user AND i.material IS NOT NULL AND
      (:hasMaterials = false OR
      i.material.id IN :materialIds) AND (:minQuality IS NULL OR i.quality >= :minQuality)
      AND (:hasJobOrders = false OR EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrder.id IN :jobOrderIds)) AND (:hasMissions =
      false OR EXISTS (SELECT 1 FROM InventoryMissionAllocation ma WHERE ma.inventoryItem =
      i AND ma.mission.id IN :missionIds))
      AND (:hasLocations = false OR i.location.id IN :locationIds)
      """)
  Page<InventoryItem> findUserByFilters(
      @Param("user") User user,
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      Pageable pageable);

  /**
   * Pages non-personal game-item rows within the caller's org-unit scope, with optional game-item,
   * location and job-order filters (REQ-INV-029).
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
   * @param pageable page request (whitelisted {@code gameItem.name} / {@code amount} sort).
   * @return the scoped non-personal game-item rows matching every active filter.
   */
  @EntityGraph(
      attributePaths = {"gameItem", "gameItem.manufacturer", "location", "user", "owningOrgUnit"})
  @Query(
      "SELECT i FROM InventoryItem i WHERE i.personal = false AND i.gameItem IS NOT NULL AND "
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " AND (:hasGameItems = false OR i.gameItem.id IN :gameItemIds) AND (:hasJobOrders ="
          + " false OR EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja WHERE"
          + " ja.inventoryItem = i AND ja.jobOrder.id IN :jobOrderIds)) AND (:hasLocations = false"
          + " OR i.location.id IN :locationIds)")
  Page<InventoryItem> findGlobalItemsByFilters(
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") List<UUID> gameItemIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Aggregates the scoped, filtered non-personal material stock into one {@link
   * InventoryStackAggregate} per stock identity, unpaged; entries load via {@link
   * #findGlobalStackEntries}.
   *
   * <p>Same filters as {@link #findGlobalByFilters}; the item variant is {@link
   * #findGlobalItemStacks}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate(m, i.user, i.location, i.quality, i.personal,
      oou, SUM(COALESCE(i.amount, 0.0)), SUM(COALESCE(i.amount, 0.0) *
      COALESCE(i.quality, 0)), MAX(COALESCE(i.quality, 0)), COUNT(i)) FROM InventoryItem i
      LEFT JOIN i.material m
      LEFT JOIN i.owningOrgUnit oou
      WHERE i.personal = false AND i.material IS NOT NULL AND
      """
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " AND (:hasMaterials = false OR i.material.id IN :materialIds) AND (:minQuality IS"
          + " NULL OR i.quality >= :minQuality) AND (:hasJobOrders = false OR EXISTS (SELECT 1"
          + " FROM InventoryJobOrderAllocation ja WHERE ja.inventoryItem = i AND ja.jobOrder.id"
          + " IN :jobOrderIds)) AND (:hasMissions = false OR EXISTS (SELECT 1 FROM"
          + " InventoryMissionAllocation ma WHERE ma.inventoryItem = i AND ma.mission.id IN"
          + " :missionIds)) AND (:hasLocations = false OR i.location.id IN :locationIds)"
          + " GROUP BY m, i.user, i.location, i.quality, i.personal, oou")
  List<InventoryStackAggregate> findGlobalStacks(
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Aggregates the user's filtered material stock into one {@link InventoryStackAggregate} per
   * stock identity; entries load via {@link #findUserStackEntries}.
   *
   * <p>Same filters as {@link #findUserByFilters}, plus {@code personalOnly} / {@code
   * nonPersonalOnly} to narrow to private or shared rows. The item variant is {@link
   * #findUserItemStacks}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate(m, i.user, i.location, i.quality, i.personal,
      oou, SUM(COALESCE(i.amount, 0.0)), SUM(COALESCE(i.amount, 0.0) *
      COALESCE(i.quality, 0)), MAX(COALESCE(i.quality, 0)), COUNT(i)) FROM InventoryItem i
      LEFT JOIN i.material m
      LEFT JOIN i.owningOrgUnit oou
      WHERE i.user.id = :userId AND i.material IS NOT NULL
      AND (:personalOnly = false OR i.personal = true)
      AND (:nonPersonalOnly = false OR i.personal = false)
      AND (:hasMaterials = false OR i.material.id IN :materialIds) AND (:minQuality IS NULL
      OR i.quality >= :minQuality) AND (:hasJobOrders = false OR EXISTS (SELECT 1 FROM
      InventoryJobOrderAllocation ja WHERE ja.inventoryItem = i AND ja.jobOrder.id IN
      :jobOrderIds)) AND (:hasMissions = false OR EXISTS (SELECT 1 FROM
      InventoryMissionAllocation ma WHERE ma.inventoryItem = i AND ma.mission.id IN
      :missionIds)) AND (:hasLocations = false OR i.location.id IN :locationIds)
      GROUP BY m, i.user, i.location, i.quality, i.personal, oou
      """)
  List<InventoryStackAggregate> findUserStacks(
      @Param("userId") UUID userId,
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly);

  /**
   * Aggregates the scoped, filtered non-personal game-item stock into one {@link
   * InventoryItemStackAggregate} per item stack key (REQ-INV-029); entries load via {@link
   * #findGlobalItemStackEntries}.
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
   * @return one aggregate per item stack in scope; never {@code null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.InventoryItemStackAggregate(gi, i.user, i.location, i.personal,
      oou, SUM(COALESCE(i.amount, 0.0)), COUNT(i)) FROM InventoryItem i
      LEFT JOIN i.gameItem gi
      LEFT JOIN i.owningOrgUnit oou
      WHERE i.personal = false AND i.gameItem IS NOT NULL AND
      """
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " AND (:hasGameItems = false OR i.gameItem.id IN :gameItemIds) AND (:hasJobOrders ="
          + " false OR EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja WHERE"
          + " ja.inventoryItem = i AND ja.jobOrder.id IN :jobOrderIds))"
          + " AND (:hasLocations = false OR i.location.id IN :locationIds)"
          + " GROUP BY gi, i.user, i.location, i.personal, oou")
  List<InventoryItemStackAggregate> findGlobalItemStacks(
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") List<UUID> gameItemIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Aggregates the user's filtered game-item stock into one {@link InventoryItemStackAggregate} per
   * item stack key (REQ-INV-029); entries load via {@link #findUserItemStackEntries}.
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
   * @return one aggregate per item stack the user owns; never {@code null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.InventoryItemStackAggregate(gi, i.user, i.location, i.personal,
      oou, SUM(COALESCE(i.amount, 0.0)), COUNT(i)) FROM InventoryItem i
      LEFT JOIN i.gameItem gi
      LEFT JOIN i.owningOrgUnit oou
      WHERE i.user.id = :userId AND i.gameItem IS NOT NULL
      AND (:personalOnly = false OR i.personal = true)
      AND (:nonPersonalOnly = false OR i.personal = false)
      AND (:hasGameItems = false OR i.gameItem.id IN :gameItemIds)
      AND (:hasJobOrders = false OR EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrder.id IN :jobOrderIds))
      AND (:hasLocations = false OR i.location.id IN :locationIds)
      GROUP BY gi, i.user, i.location, i.personal, oou
      """)
  List<InventoryItemStackAggregate> findUserItemStacks(
      @Param("userId") UUID userId,
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") List<UUID> gameItemIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly);

  /**
   * Returns the ids of every material entry the user owns that matches the {@link #findUserStacks}
   * filters, ordered by {@code createdAt}, for the "Alle markieren" bulk action (REQ-INV-034).
   *
   * @param userId the owning user whose entry ids to collect.
   * @param hasMaterials gates the {@code materialIds} clause.
   * @param materialIds the materials to narrow to; ignored when {@code hasMaterials} is false.
   * @param hasLocations gates the {@code locationIds} clause.
   * @param locationIds the storage locations to narrow to; ignored when {@code hasLocations} is
   *     false.
   * @param minQuality optional quality floor, or {@code null} for no floor.
   * @param hasJobOrders gates the {@code jobOrderIds} clause.
   * @param jobOrderIds the earmarked orders to narrow to; ignored when {@code hasJobOrders} is
   *     false.
   * @param hasMissions gates the {@code missionIds} clause.
   * @param missionIds the earmarked missions to narrow to; ignored when {@code hasMissions} is
   *     false.
   * @param personalOnly {@code true} narrows to the caller's private stock rows.
   * @param nonPersonalOnly {@code true} narrows to the caller's shared stock rows.
   * @return the ids of every matching material entry the user owns; never {@code null}.
   */
  @Query(
      """
      SELECT i.id FROM InventoryItem i
      WHERE i.user.id = :userId AND i.material IS NOT NULL
      AND (:personalOnly = false OR i.personal = true)
      AND (:nonPersonalOnly = false OR i.personal = false)
      AND (:hasMaterials = false OR i.material.id IN :materialIds) AND (:minQuality IS NULL
      OR i.quality >= :minQuality) AND (:hasJobOrders = false OR EXISTS (SELECT 1 FROM
      InventoryJobOrderAllocation ja WHERE ja.inventoryItem = i AND ja.jobOrder.id IN
      :jobOrderIds)) AND (:hasMissions = false OR EXISTS (SELECT 1 FROM
      InventoryMissionAllocation ma WHERE ma.inventoryItem = i AND ma.mission.id IN
      :missionIds)) AND (:hasLocations = false OR i.location.id IN :locationIds)
      ORDER BY i.createdAt ASC
      """)
  List<UUID> findUserEntryIds(
      @Param("userId") UUID userId,
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly);

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
   * @return the ids of every matching game-item entry the user owns; never {@code null}.
   */
  @Query(
      """
      SELECT i.id FROM InventoryItem i
      WHERE i.user.id = :userId AND i.gameItem IS NOT NULL
      AND (:personalOnly = false OR i.personal = true)
      AND (:nonPersonalOnly = false OR i.personal = false)
      AND (:hasGameItems = false OR i.gameItem.id IN :gameItemIds)
      AND (:hasJobOrders = false OR EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrder.id IN :jobOrderIds))
      AND (:hasLocations = false OR i.location.id IN :locationIds)
      ORDER BY i.createdAt ASC
      """)
  List<UUID> findUserItemEntryIds(
      @Param("userId") UUID userId,
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") List<UUID> gameItemIds,
      @Param("hasLocations") boolean hasLocations,
      @Param("locationIds") List<UUID> locationIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly);

  /**
   * Pages one non-personal material stack's entries, oldest first, within the caller's org-unit
   * scope; {@code null} job-order, mission or owning-org-unit arguments match rows without that
   * association.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.personal = false AND i.material.id = :materialId AND
      i.user.id = :userId AND i.location.id = :locationId AND ((:quality IS NULL AND
      i.quality IS NULL) OR i.quality = :quality) AND ((:owningOrgUnitId IS NULL AND
      i.owningOrgUnit IS NULL) OR i.owningOrgUnit.id = :owningOrgUnitId) AND
      """
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " ORDER BY i.createdAt ASC")
  Page<InventoryItem> findGlobalStackEntries(
      @Param("materialId") UUID materialId,
      @Param("userId") UUID userId,
      @Param("locationId") UUID locationId,
      @Param("quality") Integer quality,
      @Param("owningOrgUnitId") UUID owningOrgUnitId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Pages one of the caller's own material stacks' entries, oldest first; {@code null} job-order,
   * mission or owning-org-unit arguments match rows without that association.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.user.id = :userId AND i.material.id = :materialId AND
      i.location.id = :locationId AND ((:quality IS NULL AND i.quality IS NULL) OR
      i.quality = :quality) AND i.personal = :personal AND ((:owningOrgUnitId IS NULL
      AND i.owningOrgUnit IS NULL) OR i.owningOrgUnit.id = :owningOrgUnitId) ORDER BY
      i.createdAt ASC
      """)
  Page<InventoryItem> findUserStackEntries(
      @Param("userId") UUID userId,
      @Param("materialId") UUID materialId,
      @Param("locationId") UUID locationId,
      @Param("quality") Integer quality,
      @Param("personal") Boolean personal,
      @Param("owningOrgUnitId") UUID owningOrgUnitId,
      Pageable pageable);

  /**
   * Pages one non-personal game-item stack's entries, oldest first, within the caller's org-unit
   * scope (REQ-INV-029).
   *
   * @param gameItemId the stack's game item; never {@code null}.
   * @param userId the stack's owning user.
   * @param locationId the stack's storage location.
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null} to match rows with
   *     no owning org unit.
   * @param isAdminAllScope admin all-scopes mode (scope triple, REQ-ORG-003).
   * @param activeOrgUnitId the pinned active org unit, or {@code null}.
   * @param memberOrgUnitIds the caller's org-unit memberships.
   * @param pageable the page request (the query forces oldest-first by creation instant).
   * @return one page of the stack's entries, oldest-first.
   */
  @EntityGraph(
      attributePaths = {"gameItem", "gameItem.manufacturer", "location", "user", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.personal = false AND i.gameItem.id = :gameItemId AND
      i.user.id = :userId AND i.location.id = :locationId AND ((:owningOrgUnitId IS NULL AND
      i.owningOrgUnit IS NULL) OR i.owningOrgUnit.id = :owningOrgUnitId) AND
      """
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " ORDER BY i.createdAt ASC")
  Page<InventoryItem> findGlobalItemStackEntries(
      @Param("gameItemId") UUID gameItemId,
      @Param("userId") UUID userId,
      @Param("locationId") UUID locationId,
      @Param("owningOrgUnitId") UUID owningOrgUnitId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Pages one of the caller's own game-item stacks' entries, oldest first (REQ-INV-029).
   *
   * @param userId the calling owner whose stack to drill into.
   * @param gameItemId the stack's game item; never {@code null}.
   * @param locationId the stack's storage location.
   * @param personal whether the stack is private stock.
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null} to match rows with
   *     no owning org unit.
   * @param pageable the page request (the query forces oldest-first by creation instant).
   * @return one page of the stack's entries, oldest-first.
   */
  @EntityGraph(
      attributePaths = {"gameItem", "gameItem.manufacturer", "location", "user", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.user.id = :userId AND i.gameItem.id = :gameItemId AND
      i.location.id = :locationId AND i.personal = :personal AND ((:owningOrgUnitId IS NULL
      AND i.owningOrgUnit IS NULL) OR i.owningOrgUnit.id = :owningOrgUnitId) ORDER BY
      i.createdAt ASC
      """)
  Page<InventoryItem> findUserItemStackEntries(
      @Param("userId") UUID userId,
      @Param("gameItemId") UUID gameItemId,
      @Param("locationId") UUID locationId,
      @Param("personal") Boolean personal,
      @Param("owningOrgUnitId") UUID owningOrgUnitId,
      Pageable pageable);

  /**
   * Aggregates the scoped non-personal material stock per material into the total amount and the
   * amount-weighted mean quality, as raw {@code Object[]} tuples.
   */
  @Query(
      """
      SELECT i.material as material, CASE WHEN SUM(i.amount) > 0 THEN SUM(CAST(i.quality AS
      double) * i.amount) / SUM(i.amount) ELSE 0.0 END as quality, MAX(i.quality) as maxQuality,
      SUM(i.amount) as amount
      FROM InventoryItem i WHERE i.personal = false AND i.material IS NOT NULL AND
      """
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " GROUP BY i.material")
  Page<Object[]> getAggregatedInventory(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Aggregates the scoped non-personal game-item stock to one total per game item, as raw {@code
   * Object[]} tuples ({@code [0]} the {@code GameItem}, {@code [1]} the summed amount).
   *
   * @param isAdminAllScope admin all-scopes mode (scope triple, REQ-ORG-003).
   * @param activeOrgUnitId the pinned active org unit, or {@code null}.
   * @param memberOrgUnitIds the caller's org-unit memberships.
   * @param pageable page request (whitelisted {@code gameItem.name} / {@code amount} sort).
   * @return one tuple per game item in scope with the summed amount.
   */
  @Query(
      """
      SELECT i.gameItem as gameItem, SUM(i.amount) as amount
      FROM InventoryItem i WHERE i.personal = false AND i.gameItem IS NOT NULL AND
      """
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " GROUP BY i.gameItem")
  Page<Object[]> getAggregatedItemInventory(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Derived Spring-Data query - returns entities matching {@code JobOrderIdAndMaterialId}. Eagerly
   * fetches the configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"user", "location", "material", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.material.id = :materialId AND EXISTS (SELECT 1 FROM
      InventoryJobOrderAllocation ja WHERE ja.inventoryItem = i AND ja.jobOrder.id = :jobOrderId)
      """)
  List<InventoryItem> findByJobOrderIdAndMaterialId(
      @Param("jobOrderId") UUID jobOrderId, @Param("materialId") UUID materialId);

  /**
   * Lists every material inventory row allocated to the given job order, sorted by owner, location,
   * material name, quality desc and amount desc.
   *
   * @param jobOrderId the order whose allocated material rows to list.
   * @return the order's material rows, display associations graphed, never {@code null}.
   */
  @EntityGraph(attributePaths = {"user", "location", "material", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.material IS NOT NULL AND EXISTS
      (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrder.id = :jobOrderId) ORDER BY i.user.username
      ASC, i.location.name ASC, i.material.name ASC, i.quality DESC, i.amount DESC
      """)
  List<InventoryItem> findByJobOrderIdOrdered(@Param("jobOrderId") UUID jobOrderId);

  /**
   * Lists every game-item inventory row allocated to the given job order, sorted by owner,
   * location, game-item name and amount desc (REQ-ORDERS-028).
   *
   * @param jobOrderId the order whose allocated game-item rows to list.
   * @return the order's game-item rows, display associations graphed, never {@code null}.
   */
  @EntityGraph(
      attributePaths = {"user", "location", "gameItem", "gameItem.manufacturer", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.gameItem IS NOT NULL AND EXISTS
      (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrder.id = :jobOrderId) ORDER BY i.user.username
      ASC, i.location.name ASC, i.gameItem.name ASC, i.amount DESC
      """)
  List<InventoryItem> findGameItemRowsByJobOrderIdOrdered(@Param("jobOrderId") UUID jobOrderId);

  /**
   * Loads the game-item rows of {@code gameItemId} earmarked to {@code jobOrderId}, oldest first,
   * under a pessimistic write lock, as the stock a delivery consumes (REQ-ORDERS-030).
   *
   * @param jobOrderId the order whose earmarked game-item rows to consume from; never {@code null}.
   * @param gameItemId the game item being delivered; never {@code null}.
   * @return the order's game-item rows for that item, locked, oldest-first; never {@code null}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.gameItem.id = :gameItemId AND EXISTS
      (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrder.id = :jobOrderId)
      ORDER BY i.createdAt ASC, i.id ASC
      """)
  List<InventoryItem> findGameItemRowsByJobOrderAndGameItemForUpdate(
      @Param("jobOrderId") UUID jobOrderId, @Param("gameItemId") UUID gameItemId);

  /**
   * Sums the amounts of one material allocated to one job order from entries meeting the quality
   * floor, or {@code 0.0} when none; a {@code null} minQuality applies no floor (REQ-INV-027).
   */
  @Query(
      """
      SELECT COALESCE(SUM(a.amount), 0.0) FROM InventoryJobOrderAllocation a
      WHERE a.inventoryItem.material.id = :materialId AND a.jobOrder.id = :jobOrderId
      AND (:minQuality IS NULL OR a.inventoryItem.quality >= :minQuality)
      """)
  Double sumAmountByMaterialAndJobOrderAndMinQuality(
      @Param("materialId") UUID materialId,
      @Param("jobOrderId") UUID jobOrderId,
      @Param("minQuality") Integer minQuality);

  /**
   * Returns every material allocation of the given job orders as one row each (material, quality,
   * amount), in a single query (REQ-DATA-003).
   *
   * @param jobOrderIds the orders whose linked stock to project; an empty collection yields an
   *     empty list.
   * @return one {@link JobOrderMaterialStockRow} per material job-order allocation, never {@code
   *     null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow(a.jobOrder.id, a.inventoryItem.material.id, a.inventoryItem.quality, a.amount)
      FROM InventoryJobOrderAllocation a WHERE a.jobOrder.id IN :jobOrderIds
      AND a.inventoryItem.material IS NOT NULL
      """)
  List<JobOrderMaterialStockRow> findMaterialStockRowsByJobOrderIds(
      @Param("jobOrderIds") Collection<UUID> jobOrderIds);

  /**
   * Returns every game-item allocation of the given job orders as one row each, in a single query
   * (REQ-INV-039).
   *
   * @param jobOrderIds the orders whose earmarked item stock to project; an empty collection yields
   *     an empty list.
   * @return one {@link de.greluc.krt.profit.basetool.backend.model.dto.JobOrderGameItemStockRow}
   *     per game-item job-order allocation, never {@code null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.JobOrderGameItemStockRow(a.jobOrder.id, a.inventoryItem.gameItem.id, a.amount)
      FROM InventoryJobOrderAllocation a WHERE a.jobOrder.id IN :jobOrderIds
      AND a.inventoryItem.gameItem IS NOT NULL
      """)
  List<JobOrderGameItemStockRow> findGameItemStockRowsByJobOrderIds(
      @Param("jobOrderIds") Collection<UUID> jobOrderIds);

  /**
   * Deletes every job-order allocation of the given order; the inventory entries themselves stay
   * (REQ-INV-027).
   *
   * @param jobOrderId the order whose allocations to drop.
   */
  @Modifying
  @Query("DELETE FROM InventoryJobOrderAllocation a WHERE a.jobOrder.id = :jobOrderId")
  void deleteJobOrderAllocationsByJobOrder(@Param("jobOrderId") UUID jobOrderId);

  /**
   * Deletes the order's job-order allocations on entries of one material; the entries themselves
   * stay (REQ-INV-027). Flushes before and clears the persistence context after.
   *
   * @param jobOrderId the order whose allocations to drop.
   * @param materialId the material to restrict the drop to.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      DELETE FROM InventoryJobOrderAllocation a WHERE a.jobOrder.id = :jobOrderId AND
      a.inventoryItem.id IN (SELECT i.id FROM InventoryItem i WHERE i.material.id = :materialId)
      """)
  void deleteJobOrderAllocationsByJobOrderAndMaterial(
      @Param("jobOrderId") UUID jobOrderId, @Param("materialId") UUID materialId);

  /**
   * Deletes the order's job-order allocations on entries of one game item; the entries themselves
   * stay (REQ-INV-031). Flushes before and clears the persistence context after.
   *
   * @param jobOrderId the order whose allocations to drop.
   * @param gameItemId the game item to restrict the drop to.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      DELETE FROM InventoryJobOrderAllocation a WHERE a.jobOrder.id = :jobOrderId AND
      a.inventoryItem.id IN (SELECT i.id FROM InventoryItem i WHERE i.gameItem.id = :gameItemId)
      """)
  void deleteJobOrderAllocationsByJobOrderAndGameItem(
      @Param("jobOrderId") UUID jobOrderId, @Param("gameItemId") UUID gameItemId);

  /**
   * Derived Spring-Data query - returns entities matching {@code IdForUpdate}. Acquires a
   * pessimistic write lock for the duration of the surrounding transaction.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = {"material", "user", "location", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
  Optional<InventoryItem> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Loads one row of a bulk rebooking (REQ-INV-036) under a pessimistic write lock, with material
   * and game item fetched.
   *
   * @param id the inventory row id.
   * @return the locked row, or empty when unknown.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = {"material", "gameItem", "user", "location", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
  Optional<InventoryItem> findByIdForRebook(@Param("id") UUID id);

  /**
   * Loads the rows sharing a stock's physical identity (user, catalog reference, location, quality,
   * personal, owning org unit), oldest first and pessimistically locked, as merge candidates
   * (REQ-INV-026).
   *
   * <p>Earmarks and {@code delivered} are not part of the key. {@code null} material, game item or
   * quality match only rows where that column is {@code NULL}. Rows backing a {@link
   * de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer} are excluded.
   *
   * @param userId the owning user of the stack; never {@code null}.
   * @param materialId the stack's material, or {@code null} for a game-item stack (matches rows
   *     with no material).
   * @param gameItemId the stack's game item, or {@code null} for a material stack (matches rows
   *     with no game item).
   * @param locationId the stack's storage location; never {@code null}.
   * @param quality the stack's quality grade, or {@code null} for a game-item stack (matches rows
   *     with no quality).
   * @param personal the stack's personal flag; never {@code null}.
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null} to match rows with
   *     no owning org unit.
   * @return the locked matching rows (excluding offer-backed rows), oldest-first; never {@code
   *     null}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.user.id = :userId AND
      ((:materialId IS NULL AND i.material IS NULL) OR i.material.id = :materialId) AND
      ((:gameItemId IS NULL AND i.gameItem IS NULL) OR i.gameItem.id = :gameItemId) AND
      i.location.id = :locationId AND
      ((:quality IS NULL AND i.quality IS NULL) OR i.quality = :quality) AND
      i.personal = :personal AND
      ((:owningOrgUnitId IS NULL AND i.owningOrgUnit IS NULL) OR i.owningOrgUnit.id =
      :owningOrgUnitId) AND NOT EXISTS (SELECT 1 FROM MaterialExchangeOffer o WHERE
      o.inventoryItem = i) ORDER BY i.createdAt ASC, i.id ASC
      """)
  List<InventoryItem> findMergeGroupForUpdate(
      @Param("userId") UUID userId,
      @Param("materialId") UUID materialId,
      @Param("gameItemId") UUID gameItemId,
      @Param("locationId") UUID locationId,
      @Param("quality") Integer quality,
      @Param("personal") Boolean personal,
      @Param("owningOrgUnitId") UUID owningOrgUnitId);

  /**
   * Material-stack overload of {@link #findMergeGroupForUpdate(UUID, UUID, UUID, UUID, Integer,
   * Boolean, UUID)} that passes {@code gameItemId = null}.
   *
   * @param userId the owning user of the stack; never {@code null}.
   * @param materialId the stack's material; never {@code null} on this overload.
   * @param locationId the stack's storage location; never {@code null}.
   * @param quality the stack's quality grade; never {@code null} on this overload.
   * @param personal the stack's personal flag; never {@code null}.
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null} to match rows with
   *     no owning org unit.
   * @return the locked matching material rows (excluding offer-backed rows), oldest-first; never
   *     {@code null}.
   */
  default List<InventoryItem> findMergeGroupForUpdate(
      UUID userId,
      UUID materialId,
      UUID locationId,
      Integer quality,
      Boolean personal,
      UUID owningOrgUnitId) {
    return findMergeGroupForUpdate(
        userId, materialId, null, locationId, quality, personal, owningOrgUnitId);
  }

  /**
   * Bulk-deletes the non-personal inventory within the given org-unit scope; personal rows are
   * never touched. Callers must enforce access first.
   *
   * @param isAdminAllScope {@code true} for an admin without an active org unit; wipes every
   *     non-personal item
   * @param activeOrgUnitId the single org unit to scope the wipe to, or {@code null}
   * @param memberOrgUnitIds the caller's org units (non-admin path); empty for admins
   * @return number of deleted rows
   */
  @Modifying
  @Query(
      "DELETE FROM InventoryItem i WHERE i.personal = false AND "
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE)
  int deleteAllNonPersonal(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Deletes every inventory row of the given user, personal and shared, as part of the hard account
   * deletion (REQ-DATA-008).
   *
   * <p>Allocations and Materialbörse offers cascade; handover items keep their snapshot. Must stay
   * a bulk delete, since loading the rows as entities breaks the subsequent user delete.
   *
   * @param userId the owner whose warehouse rows are removed; never {@code null}.
   * @return the number of deleted rows, for the audit summary event.
   */
  @Modifying
  @Query("DELETE FROM InventoryItem i WHERE i.user.id = :userId")
  int deleteByUserId(@Param("userId") UUID userId);

  /**
   * Pages the caller's own material and game-item Lager rows for the Materialbörse release picker,
   * optionally filtered by name and ordered by catalog name (REQ-MARKET-014).
   *
   * <p>The kind flags are applied in the query before the page cap (REQ-MARKET-002); both {@code
   * false} returns nothing.
   *
   * @param userId the caller (the picker only ever shows the caller's own rows).
   * @param query a pre-lowercased {@code %fragment%} matched against the material or game-item
   *     name, or {@code null} for no filter.
   * @param includeMaterial whether material rows (a {@code NULL} game item) are included.
   * @param includeItem whether game-item rows (a non-{@code NULL} game item) are included.
   * @param pageable the cap on the number of picker rows.
   * @return the caller's matching Lager rows of the selected kind(s), never {@code null}.
   */
  @EntityGraph(attributePaths = {"material", "gameItem", "location"})
  @Query(
      "SELECT i FROM InventoryItem i LEFT JOIN i.material m LEFT JOIN i.gameItem gi "
          + "WHERE i.user.id = :userId "
          + "AND (:query IS NULL OR LOWER(m.name) LIKE :query OR LOWER(gi.name) LIKE :query) "
          + "AND ((:includeMaterial = TRUE AND i.gameItem IS NULL) "
          + "OR (:includeItem = TRUE AND i.gameItem IS NOT NULL)) "
          + "ORDER BY COALESCE(m.name, gi.name) ASC")
  List<InventoryItem> findReleasableForUser(
      @Param("userId") UUID userId,
      @Param("query") String query,
      @Param("includeMaterial") boolean includeMaterial,
      @Param("includeItem") boolean includeItem,
      Pageable pageable);
}
