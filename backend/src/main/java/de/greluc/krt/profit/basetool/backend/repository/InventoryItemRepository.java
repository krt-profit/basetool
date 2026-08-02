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
 * Spring Data repository for Inventory Item. Since V220 the table is catalog-discriminated
 * (REQ-INV-029, ADR-0101): a row stocks either a material ({@code material_id} + {@code quality}
 * set) or a game item ({@code game_item_id} set, no quality). The read family therefore comes in
 * per-catalog variants — the historical material queries carry an explicit {@code i.material IS NOT
 * NULL} guard so item rows never leak into pre-item contracts, and the item variants key on {@code
 * gameItem} without the quality dimension. Queries keyed on a non-null id via {@code i.material.id
 * = :materialId} (an id-only FK-column dereference, no join) exclude item rows inherently, because
 * an equality never matches the item rows' {@code NULL} FK.
 */
@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

  /**
   * Pages the material stock rows owned by {@code user} — the flat "my inventory" list for {@code
   * catalog=MATERIAL}. The explicit {@code i.material IS NOT NULL} keeps game-item rows (V220,
   * REQ-INV-029) out of the pre-item flat contract; it also makes the caller-whitelisted {@code
   * material.name} sort safe, whose implicit inner join would otherwise decide row visibility per
   * chosen sort. Item rows are served by {@link #findItemRowsByUser(User, Pageable)}.
   *
   * @param user the owning user; never {@code null}.
   * @return the user's material rows with the display associations eagerly graphed.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.user = :user AND i.material IS NOT NULL")
  Page<InventoryItem> findMaterialRowsByUser(@Param("user") User user, Pageable pageable);

  /**
   * Game-item sibling of {@link #findMaterialRowsByUser(User, Pageable)} — the flat "my inventory"
   * list for {@code catalog=ITEM} (REQ-INV-029). Only rows stocking a game item are returned;
   * {@code gameItem.manufacturer} is graphed alongside because the item reference DTO renders the
   * manufacturer name.
   *
   * @param user the owning user; never {@code null}.
   * @return the user's game-item rows with the display associations eagerly graphed.
   */
  @EntityGraph(
      attributePaths = {"gameItem", "gameItem.manufacturer", "location", "user", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.user = :user AND i.gameItem IS NOT NULL")
  Page<InventoryItem> findItemRowsByUser(@Param("user") User user, Pageable pageable);

  /**
   * Loads an entry for a per-allocation write (add / change / remove a job-order or mission slice,
   * Variante C REQ-INV-027) under {@link LockModeType#OPTIMISTIC_FORCE_INCREMENT}. An allocation
   * lives on the inverse ({@code mappedBy}) side of the entry's {@code @OneToMany}, so cascading a
   * child insert/delete/amount-change through the collection dirties only the child rows and would
   * NOT bump the entry's own {@code @Version} on its own. Forcing the increment here makes the
   * entry's version the single concurrency token for both its splits — two concurrent allocation
   * writers (or an allocation write racing a scalar edit) serialise and the loser gets a clean 409
   * — and makes the response DTO carry the post-increment version the client must echo next. The
   * {@code material} is graphed because the write validates the amount against the material's
   * PIECE/SCU precision; the two allocation collections stay lazy (both are bags, so graphing them
   * together would raise {@code MultipleBagFetchException}) and load within the same transaction.
   *
   * @param id the inventory entry id.
   * @return the entry under a forced version increment, or empty when unknown.
   */
  @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
  @EntityGraph(attributePaths = {"material"})
  @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
  Optional<InventoryItem> findByIdForAllocationWrite(@Param("id") UUID id);

  /**
   * Lists every inventory item (shared and personal) linked to {@code missionId} — the
   * mission-detail Wirtschaft "Lagereinträge" table (#1138). Replaces the former eagerly embedded
   * {@code MissionDto.inventoryEntries} field with a dedicated read; the display associations the
   * table renders (material / location / user / job order) are graphed to avoid an N+1.
   * Deliberately unscoped among members (the shared mission-stockpile view), matching the removed
   * field's behaviour exactly.
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
   * Loads every non-personal (shared) inventory row owned by the given user as managed entities.
   * Used by {@link de.greluc.krt.profit.basetool.backend.service.InventoryOrgUnitReconciler} to
   * re-stamp and dedupe a user's shared stock when they gain their first or lose their last
   * org-unit membership. Private inventory ({@code personal = true}) is intentionally excluded: it
   * is owner-only regardless of org unit. The associations are loaded eagerly so the reconciler can
   * read their ids (the eighth-dimension natural key) without an N+1 per row.
   *
   * @param userId the owner whose shared inventory to load; never {@code null}.
   * @return the user's non-personal inventory rows; never {@code null}, possibly empty.
   */
  @EntityGraph(attributePaths = {"material", "location", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.user.id = :userId AND i.personal = false")
  List<InventoryItem> findByUserIdAndPersonalFalse(@Param("userId") UUID userId);

  /**
   * Pools the caller's entire "My Inventory" stock into one SCU total per (material, quality) pair,
   * across all storage locations, for the blueprint craftability calculation (#781). Scoped
   * strictly to the owning user ({@code i.user.id = :userId}) — both personal and shared rows the
   * user owns count, matching the default {@code /inventory/my} view — and never to an org unit,
   * because craftability answers "what can <em>I</em> craft from <em>my</em> stock". The quality is
   * kept in the grouping key (not collapsed) so the calculator can consume the best-quality slices
   * first. Material rows only: the explicit {@code i.material IS NOT NULL} keeps game-item rows
   * (V220, REQ-INV-029) from surfacing as a null-material slice — craftability consumes materials,
   * never finished items.
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
   * Squadron-scoped variant of {@link #findByMaterialAndPersonalFalse(Material, Pageable)}. Used by
   * the per-material drilldown so the Lager-direct path stays strictly staffel-isolated
   * (MULTI_SQUADRON_PLAN.md section 1: Inventory direct view = strict eigene Staffel). {@code
   * owningSquadronId} {@code null} = admin "all squadrons" mode (no filter applied); a non-null id
   * restricts to that squadron.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query(
      "SELECT i FROM InventoryItem i WHERE i.material = :material AND i.personal = false AND "
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE)
  Page<InventoryItem> findByMaterialAndPersonalFalseScoped(
      @Param("material") Material material,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Game-item sibling of {@link #findByMaterialAndPersonalFalseScoped} — the per-game-item
   * drilldown behind {@code GET /api/v1/inventory/game-item/{gameItemId}} (REQ-INV-029). Lists
   * every non-personal stock row of one game item under the same strict-staffel scope triple as the
   * material drilldown; the entity equality on {@code :gameItem} never matches material rows'
   * {@code NULL} FK. {@code gameItem.manufacturer} is graphed because the item reference DTO
   * renders the manufacturer name.
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
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /** Derived Spring-Data query - returns entities matching {@code PersonalFalse}. */
  Page<InventoryItem> findByPersonalFalse(Pageable pageable);

  /**
   * Optional multi-filter search across non-personal inventory items. Each filter is gated by a
   * boolean / nullable flag so callers can omit dimensions without building a dynamic query: {@code
   * hasMaterials}, {@code hasJobOrders} and {@code hasMissions} turn the corresponding {@code IN
   * :ids} clause on or off; a {@code null minQuality} skips the quality floor.
   *
   * <p>Multi-tenant: this method is the <em>Lager-View</em> entry point (MULTI_SQUADRON_PLAN.md
   * section 4.4). {@code owningSquadronId} restricts to the caller's squadron stock; {@code null}
   * means admin "all squadrons" mode. Items owned by another squadron NEVER surface here, even if
   * they are linked to a job order - the Job-Order-Kontext is a separate, intentionally ungated
   * lookup path served by {@link #findByJobOrderIdOrdered(UUID)}.
   *
   * <p>Material rows only ({@code catalog=MATERIAL}): the explicit {@code i.material IS NOT NULL}
   * keeps game-item rows (V220, REQ-INV-029) out of the pre-item flat contract and keeps the
   * default {@code material.name} sort's implicit inner join from deciding row visibility. The item
   * variant is {@link #findGlobalItemsByFilters}.
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
          + " :missionIds))")
  Page<InventoryItem> findGlobalByFilters(
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Per-user variant of {@link #findGlobalByFilters} - same optional filter contract, but scoped to
   * the items owned by {@code :user}. Used by the "my inventory" view to enforce isolation at the
   * data layer rather than relying on the controller alone. Material rows only — the explicit
   * {@code i.material IS NOT NULL} keeps game-item rows (V220, REQ-INV-029) out; the item variant
   * is {@link #findUserItemsByFilters}.
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
      """)
  Page<InventoryItem> findUserByFilters(
      @Param("user") User user,
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      Pageable pageable);

  /**
   * Game-item sibling of {@link #findGlobalByFilters} — the flat squadron-wide list for {@code
   * catalog=ITEM} (REQ-INV-029). Same scope-triple + gated-filter contract, reduced to the item
   * filter surface: {@code gameItemIds} and {@code jobOrderIds}. There is deliberately no quality
   * floor (items carry no quality) and no mission filter (item rows are never mission-allocated,
   * REQ-INV-031). {@code gameItem.manufacturer} is graphed because the item reference DTO renders
   * the manufacturer name.
   *
   * @param hasGameItems gates the {@code gameItemIds} clause.
   * @param gameItemIds the game items to narrow to; ignored when {@code hasGameItems} is false.
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
          + " ja.inventoryItem = i AND ja.jobOrder.id IN :jobOrderIds))")
  Page<InventoryItem> findGlobalItemsByFilters(
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") List<UUID> gameItemIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Group-on-read variant of {@link #findGlobalByFilters}: instead of returning the individual
   * rows, it collapses the scoped, filtered non-personal inventory into one {@link
   * InventoryStackAggregate} per stock identity (the inventory natural key) directly in SQL —
   * {@code SUM(amount)}, the amount-weighted quality sum, {@code MAX(quality)} and the entry count.
   * The underlying entries are never loaded here (append-only rows grow unboundedly per stack);
   * they are fetched lazily and paginated via {@link #findGlobalStackEntries}. The stack list
   * itself is bounded by the number of distinct stock identities, so it is returned unpaged. Same
   * scope-triple + optional-filter contract as {@link #findGlobalByFilters}.
   *
   * <p>Material rows only ({@code catalog=MATERIAL}): {@code material} joins explicitly ({@code
   * LEFT JOIN}, mirroring {@code owningOrgUnit}) and the {@code i.material IS NOT NULL} guard keeps
   * game-item rows (V220, REQ-INV-029) from surfacing as a null-material group that would NPE the
   * grouped assembly. The item variant is {@link #findGlobalItemStacks}.
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
          + " :missionIds)) GROUP BY m, i.user, i.location, i.quality, i.personal, oou")
  List<InventoryStackAggregate> findGlobalStacks(
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds);

  /**
   * Per-user group-on-read variant of {@link #findUserByFilters}: collapses the user's filtered
   * inventory (shared and personal alike) into one {@link InventoryStackAggregate} per stock
   * identity in SQL. Entries are fetched lazily via {@link #findUserStackEntries}. Same
   * optional-filter contract as {@link #findUserByFilters}, plus the mutually exclusive {@code
   * personalOnly} / {@code nonPersonalOnly} toggles: {@code personalOnly = true} narrows to the
   * caller's private stock ({@code personal = true} rows) and {@code nonPersonalOnly = true}
   * narrows to the shared stock ({@code personal = false} rows) — the "Mein Lager" personal- /
   * non-personal-entries-only filters. When both are {@code false} both shared and personal stacks
   * are returned as before; the UI keeps them mutually exclusive so they are never both {@code
   * true}, but were that to happen the two clauses simply intersect to the empty set.
   *
   * <p>Material rows only ({@code catalog=MATERIAL}): explicit {@code LEFT JOIN i.material} +
   * {@code i.material IS NOT NULL} keep game-item rows (V220, REQ-INV-029) from surfacing as a
   * null-material group. The item variant is {@link #findUserItemStacks}.
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
      :missionIds)) GROUP BY m, i.user, i.location, i.quality, i.personal, oou
      """)
  List<InventoryStackAggregate> findUserStacks(
      @Param("userId") UUID userId,
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly);

  /**
   * Game-item sibling of {@link #findGlobalStacks} (REQ-INV-029): collapses the scoped, filtered
   * non-personal game-item stock into one {@link InventoryItemStackAggregate} per item stack key —
   * user · gameItem · location · personal · owningOrgUnit, with <em>no</em> quality dimension
   * (items carry none) — directly in SQL. Entries are fetched lazily via {@link
   * #findGlobalItemStackEntries} (REQ-INV-005). Item filter surface only ({@code gameItemIds},
   * {@code jobOrderIds}); no quality floor, no mission filter (REQ-INV-031). Same scope-triple
   * contract as {@link #findGlobalStacks}.
   *
   * @param hasGameItems gates the {@code gameItemIds} clause.
   * @param gameItemIds the game items to narrow to; ignored when {@code hasGameItems} is false.
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
          + " GROUP BY gi, i.user, i.location, i.personal, oou")
  List<InventoryItemStackAggregate> findGlobalItemStacks(
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") List<UUID> gameItemIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds);

  /**
   * Game-item sibling of {@link #findUserStacks} (REQ-INV-029): collapses the calling user's
   * game-item stock (shared and personal alike) into one {@link InventoryItemStackAggregate} per
   * item stack key in SQL, with the same mutually exclusive {@code personalOnly} / {@code
   * nonPersonalOnly} narrowing toggles as the material variant. Entries are fetched lazily via
   * {@link #findUserItemStackEntries} (REQ-INV-005). Item filter surface only ({@code gameItemIds},
   * {@code jobOrderIds}); no quality floor, no mission filter (REQ-INV-031).
   *
   * @param userId the owning user whose stacks to aggregate.
   * @param hasGameItems gates the {@code gameItemIds} clause.
   * @param gameItemIds the game items to narrow to; ignored when {@code hasGameItems} is false.
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
      GROUP BY gi, i.user, i.location, i.personal, oou
      """)
  List<InventoryItemStackAggregate> findUserItemStacks(
      @Param("userId") UUID userId,
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") List<UUID> gameItemIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly);

  /**
   * Flat companion of {@link #findUserStacks} (REQ-INV-034): returns the ids of <em>every</em>
   * individual material {@link InventoryItem} the calling user owns that matches the "Mein Lager"
   * material filter surface — across every stack and unbounded by the lazy per-stack pagination —
   * so the frontend's "Alle markieren" (select-all) can drive a bulk check-out over the complete
   * filtered view rather than only the entries currently expanded on screen. The {@code WHERE}
   * clause is byte-for-byte the same optional-filter contract as {@link #findUserStacks} (same
   * material / min-quality / job-order / mission gates and the mutually exclusive {@code
   * personalOnly} / {@code nonPersonalOnly} toggles), minus the aggregation: it selects the raw
   * entry ids instead of the per-stack roll-up, so it can never widen the row set the grouped view
   * shows. Owner-scoped to {@code :userId} at the data layer (no impersonation). Ordered by {@code
   * createdAt} for a stable result.
   *
   * <p>Material rows only ({@code catalog=MATERIAL}): the {@code i.material IS NOT NULL} guard
   * keeps game-item rows (V220, REQ-INV-029) out; the item companion is {@link
   * #findUserItemEntryIds}.
   *
   * @param userId the owning user whose entry ids to collect.
   * @param hasMaterials gates the {@code materialIds} clause.
   * @param materialIds the materials to narrow to; ignored when {@code hasMaterials} is false.
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
      :missionIds)) ORDER BY i.createdAt ASC
      """)
  List<UUID> findUserEntryIds(
      @Param("userId") UUID userId,
      @Param("hasMaterials") boolean hasMaterials,
      @Param("materialIds") List<UUID> materialIds,
      @Param("minQuality") Integer minQuality,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("hasMissions") boolean hasMissions,
      @Param("missionIds") List<UUID> missionIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly);

  /**
   * Game-item companion of {@link #findUserEntryIds} (REQ-INV-034): returns the ids of every
   * individual game-item {@link InventoryItem} the calling user owns that matches the item-view
   * filter surface, so the "Alle markieren" select-all covers the whole filtered {@code view=items}
   * tree and not only the expanded stacks. Same optional-filter contract as {@link
   * #findUserItemStacks} (game-item / job-order gates and the mutually exclusive personal toggles);
   * no quality floor and no mission filter exist for items (REQ-INV-031). Owner-scoped to {@code
   * :userId}; ordered by {@code createdAt} for stability.
   *
   * @param userId the owning user whose item entry ids to collect.
   * @param hasGameItems gates the {@code gameItemIds} clause.
   * @param gameItemIds the game items to narrow to; ignored when {@code hasGameItems} is false.
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
      ORDER BY i.createdAt ASC
      """)
  List<UUID> findUserItemEntryIds(
      @Param("userId") UUID userId,
      @Param("hasGameItems") boolean hasGameItems,
      @Param("gameItemIds") List<UUID> gameItemIds,
      @Param("hasJobOrders") boolean hasJobOrders,
      @Param("jobOrderIds") List<UUID> jobOrderIds,
      @Param("personalOnly") boolean personalOnly,
      @Param("nonPersonalOnly") boolean nonPersonalOnly);

  /**
   * Lazily loads one global stack's underlying entries, oldest-first, paginated — the per-stack
   * drill-down for the squadron-wide Lager view. The stack is identified by its stock-identity
   * tuple (material, owner, location, quality, optional job order / mission, owning org-unit pool);
   * {@code null} job-order / mission / owning-org-unit arguments match rows where that association
   * is itself {@code null}. The same scope triple as {@link #findGlobalByFilters} is applied so the
   * drill-down can never widen visibility beyond the caller's org-unit slice. Only non-personal
   * stock is exposed here, mirroring the global grouped view.
   *
   * <p>Material-addressed by design ({@code catalog=MATERIAL}): the non-null {@code :materialId}
   * equality is an id-only FK-column dereference that never matches game-item rows' {@code NULL} FK
   * (V220, REQ-INV-029), so item rows cannot leak in. Item stacks drill down via {@link
   * #findGlobalItemStackEntries}.
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
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Lazily loads one of the caller's own stacks' entries, oldest-first, paginated — the per-stack
   * drill-down for the "my inventory" view. Scoped to {@code :user} (the caller) so isolation is
   * enforced at the data layer; the {@code personal} flag is part of the stock identity, so a
   * private and a shared stack at the same location/quality drill down separately. {@code null}
   * job-order / mission / owning-org-unit arguments match rows where that association is {@code
   * null}.
   *
   * <p>Material-addressed by design ({@code catalog=MATERIAL}): the non-null {@code :materialId}
   * equality never matches game-item rows' {@code NULL} FK (V220, REQ-INV-029). Item stacks drill
   * down via {@link #findUserItemStackEntries}.
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
   * Game-item sibling of {@link #findGlobalStackEntries} (REQ-INV-029): lazily loads one global
   * item stack's underlying entries, oldest-first, paginated (REQ-INV-005). The stack is addressed
   * by {@code gameItemId} with <em>no</em> quality key — items carry no quality dimension — plus
   * the shared identity dimensions (owner, location, optional owning org-unit pool; {@code null}
   * matches rows without one). The same scope triple as the grouped item view applies, and only
   * non-personal stock is exposed, mirroring the material variant. {@code gameItem.manufacturer} is
   * graphed because the item reference DTO renders the manufacturer name.
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
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Game-item sibling of {@link #findUserStackEntries} (REQ-INV-029): lazily loads one of the
   * caller's own item stacks' entries, oldest-first, paginated (REQ-INV-005). Addressed by {@code
   * gameItemId} with no quality key; owner-scoped to {@code :userId} at the data layer, with the
   * {@code personal} flag part of the stock identity exactly as for material stacks.
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
   * Aggregates non-personal inventory by {@code material}: total amount, plus an amount-weighted
   * mean quality (so 10 units at quality 800 plus 5 units at quality 600 land at {@code (10*800 +
   * 5*600) / 15}). Used by the global "aggregated inventory" view; returns raw {@code Object[]}
   * tuples - the service layer projects them into {@code AggregatedInventoryDto}.
   *
   * <p>Multi-tenant: {@code owningSquadronId} restricts to the caller's squadron stock. {@code
   * null} means admin "all squadrons" mode (aggregated across the whole org).
   *
   * <p>Material rows only ({@code catalog=MATERIAL}): the {@code i.material IS NOT NULL} guard
   * keeps game-item rows (V220, REQ-INV-029) from surfacing as a null group. Unlike the stack
   * projections this query deliberately keeps the implicit root path ({@code i.material}) instead
   * of an explicit {@code LEFT JOIN}: the caller-whitelisted {@code material.name} sort is appended
   * by Spring Data as the implicit path {@code i.material.name}, which Hibernate resolves onto the
   * <em>same</em> join as the {@code GROUP BY i.material} — an explicit join alias would make the
   * appended sort spawn a second, ungrouped join and fail PostgreSQL's functional-dependency check.
   * With the NOT-NULL guard in place the implicit inner join drops no rows.
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
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Game-item sibling of {@link #getAggregatedInventory} (REQ-INV-028/029): aggregates the scoped
   * non-personal game-item stock to one total per game item — no quality columns, because items
   * carry no quality dimension. Returns raw {@code Object[]} tuples ({@code [0]} the {@code
   * GameItem}, {@code [1]} the summed amount) exactly like the material variant so the service
   * layer projects both shapes the same way. Keeps the implicit root path ({@code i.gameItem})
   * rather than an explicit join for the same appended-{@code gameItem.name}-sort reason documented
   * on the material variant.
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
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
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
   * Lists every <em>material</em> inventory row allocated to the given job order, pre-sorted for
   * the order-detail Materialsammlung (owner, location, material name, quality desc, amount desc).
   * Deliberately material-only ({@code i.material IS NOT NULL}): the Materialsammlung is a material
   * surface whose consumers ({@code InventoryAggregationService.getMaterialCollection}, the
   * orphaned-link warning) dereference {@code material.getName()} unconditionally, and the
   * production auto-earmark creates game-item rows linked to orders on its default flow (V220,
   * REQ-INV-029) — without the guard every such earmark would 500 the order page. Item earmarks get
   * their own projection with the order-detail item-stock panel (design §11.3).
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
   * Game-item sibling of {@link #findByJobOrderIdOrdered(UUID)}: lists every <em>game-item</em>
   * inventory row allocated to the given job order (V220, REQ-INV-029), pre-sorted for display
   * (owner, location, game-item name, amount desc — no quality dimension). Serves the orphaned-link
   * warning (REQ-ORDERS-019), which must also flag item earmarks whose ITEM order no longer
   * requests the game item — the material-only seam above deliberately excludes item rows, so
   * without this query a stale item earmark would stay invisible forever — and the order-detail
   * Item-Bestand panel ({@code InventoryAggregationService.getItemStockForJobOrder},
   * REQ-ORDERS-028). {@code gameItem.manufacturer} is graphed because the item reference DTO
   * renders the manufacturer name.
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
   * Loads every <em>game-item</em> inventory row that carries {@code gameItemId} and is earmarked
   * to {@code jobOrderId}, oldest-first ({@code createdAt}, {@code id} tiebreak) and locked {@code
   * PESSIMISTIC_WRITE} ({@code FOR UPDATE}) — the consumption source of the best-effort
   * delivery-consumes-stock step (REQ-ORDERS-030). An item handover of {@code N} units of a line
   * draws down the order's own earmark on these rows oldest-first, so two racing writers against
   * the same order/game-item pool serialise on the row lock rather than both committing a
   * decrement.
   *
   * <p>{@code i.gameItem.id = :gameItemId} is an id-only dereference resolved straight from the FK
   * column (no join, no NULL-row surprise — game-item rows always carry a game item, ADR-0101), and
   * the {@code EXISTS} sub-select restricts the set to rows this order earmarks. Deliberately not
   * {@code @EntityGraph}-fetched: the caller reads each row's {@code jobOrderAllocations} slice
   * (batch-loaded) to cap the draw at this order's own earmark, and join-fetching a collection
   * under a pessimistic lock is avoided (mirrors {@link #findMergeGroupForUpdate}).
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
   * Returns the total quantity of one material <em>allocated</em> to one job-order whose entry
   * quality meets or exceeds the threshold; {@code 0.0} if there is no matching allocation. Since
   * Variante C (REQ-INV-027) the sum is over the per-entry job-order allocation amounts, so an
   * order is credited only its allocated share of a split entry, not the whole row. A {@code null}
   * minQuality (Keine) imposes no quality floor — all qualities count.
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
   * Batched counterpart to {@link #sumAmountByMaterialAndJobOrderAndMinQuality} for the paged
   * job-order list: returns every job-order-linked inventory row (one per item, carrying its
   * material, quality grade and amount) for all given orders in a single query, so the list path
   * can sum the per-(order, material) buckets in memory at each bucket's own quality floor instead
   * of firing one {@code SUM} aggregate per bucket per order (REQ-DATA-003). Only rows whose {@code
   * jobOrder} is one of {@code jobOrderIds} are returned; unlinked stock is excluded by the join.
   *
   * <p>Material allocations only: the {@code material IS NOT NULL} guard keeps game-item earmarks
   * (V220, REQ-INV-029 — created by the production auto-earmark) from emitting {@code materialId =
   * null} rows, which would NPE the consumer's {@code Collectors.groupingBy} and 500 the paged
   * order list. Item earmarks get their own projection when the order UI surfaces them.
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
  List<de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow>
      findMaterialStockRowsByJobOrderIds(@Param("jobOrderIds") Collection<UUID> jobOrderIds);

  /**
   * Drops every job-order allocation of the given order (Variante C, REQ-INV-027) so an order
   * activity that detaches stock releases only the order's allocated slice while the owning entries
   * survive as (partially) unassigned stock (R2). A plain bulk {@code DELETE}: {@code
   * a.jobOrder.id} is the allocation's own FK column, so no join is implied. Not needed on a
   * job-order <em>delete</em>, where the {@code job_order_id ON DELETE CASCADE} (V217) removes the
   * allocations for free.
   *
   * @param jobOrderId the order whose allocations to drop.
   */
  @Modifying
  @Query("DELETE FROM InventoryJobOrderAllocation a WHERE a.jobOrder.id = :jobOrderId")
  void deleteJobOrderAllocationsByJobOrder(@Param("jobOrderId") UUID jobOrderId);

  /**
   * Drops the job-order allocations of one specific material under the order (Variante C,
   * REQ-INV-027) — the allocation counterpart of {@link #unlinkJobOrderMaterial(UUID, UUID)}, run
   * alongside it in the handover / material-removal flows so released stock loses only that order's
   * slice while the entry (its other allocations and its amount) survives (R2). The material filter
   * is a subquery over {@link InventoryItem} because {@code a.inventoryItem.material.id} would
   * imply a join a bulk {@code DELETE} may not carry, whereas {@code a.inventoryItem.id} is the
   * allocation's own FK column. Carries {@code clearAutomatically = flushAutomatically = true} so a
   * subsequent {@code repository.save(entity)} in the same handover loop does not collide with a
   * stale {@code @Version} — the loop-bulk-update discipline (CLAUDE.md).
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
   * Game-item sibling of {@link #deleteJobOrderAllocationsByJobOrderAndMaterial(UUID, UUID)}: drops
   * the order's allocation slices on game-item rows stocking one specific game item (REQ-INV-031,
   * R2 semantics of REQ-INV-027) — run by the requester item-line edit when the rebuilt line set no
   * longer requests the game item, so released item stock loses only that order's earmark while the
   * entry survives. The game-item filter is a subquery over {@link InventoryItem} for the same
   * bulk-{@code DELETE}-join reason as the material variant, and it carries the same {@code
   * clearAutomatically = flushAutomatically = true} so a caller following the loop-bulk-update
   * discipline (CLAUDE.md) stays version-safe.
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
   * Loads one row of a bulk rebooking (Massen-Umbuchen, REQ-INV-036) under a pessimistic write
   * lock.
   *
   * <p>Same locking as {@link #findByIdForUpdate} — the bulk bar carries no {@code @Version} to
   * echo (its "Alle markieren" id set is resolved server-side), so the row lock, not an optimistic
   * token, is what serialises two concurrent writers. Unlike {@code findByIdForUpdate} the graph
   * also pulls {@code gameItem}: a rebooking copies the catalog reference <em>pair</em> onto the
   * moved row (design §4.4), so fetching only {@code material} would lazy-load the game item once
   * per row. Kept as its own method rather than widening {@code findByIdForUpdate}, whose
   * bulk-checkout caller never reads the catalog reference. The two allocation collections stay
   * lazy — both are bags, so graphing them together would raise {@code MultipleBagFetchException}.
   *
   * @param id the inventory row id.
   * @return the locked row, or empty when unknown.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = {"material", "gameItem", "user", "location", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
  Optional<InventoryItem> findByIdForRebook(@Param("id") UUID id);

  /**
   * Loads every warehouse row that shares the <em>physical</em> stock identity of a just-written
   * row, locked {@code PESSIMISTIC_WRITE} ({@code FOR UPDATE}) — the merge candidates for the
   * write-time stock merge (REQ-INV-026). Since Variante C (REQ-INV-027) the group key is the row's
   * physical identity only — user · catalog reference · location · quality · personal ·
   * owningOrgUnit; job-order / mission earmarks are NOT part of it, so matching rows are folded and
   * their allocations unioned (R1). {@code delivered} is likewise not part of the key (the merged
   * survivor resets to not-delivered).
   *
   * <p>Catalog-discriminated since V220 (REQ-INV-029, ADR-0101): the stack key carries exactly one
   * of {@code materialId} / {@code gameItemId}, and each keys with a NULL-branch — a material merge
   * group passes ({@code materialId}, {@code quality}, {@code gameItemId = null}) so item rows
   * never match; a game-item merge group passes ({@code gameItemId}, {@code materialId = null},
   * {@code quality = null}) and matches only rows whose material <em>and</em> quality are {@code
   * NULL}. Without the NULL-branches the former plain equalities silently matched nothing for item
   * rows, degenerating their merge to a permanent no-op.
   *
   * <p>Rows backing a {@link de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer} are
   * excluded via {@code NOT EXISTS} so a merge never deletes stock the Materialbörse still
   * references ({@code ON DELETE CASCADE}, V210) — the offer and its offered quantity stay
   * untouched. The pessimistic lock serialises two racing writers to the same stack: the merge
   * reads, sums and deletes siblings, which is exactly the read-add-write the append-only model
   * (ADR-0003) removed, so it re-introduces the lock only on this one path. Ordered oldest-first
   * for a deterministic survivor tie-break.
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
   * Material-stack convenience overload of {@link #findMergeGroupForUpdate(UUID, UUID, UUID, UUID,
   * Integer, Boolean, UUID)} preserving the pre-V220 six-argument call shape: passes {@code
   * gameItemId = null}, whose NULL-branch restricts the merge group to rows with no game item —
   * byte-for-byte the behaviour material callers relied on before the catalog split (REQ-INV-029).
   * Game-item merge groups call the full variant with ({@code materialId = null}, {@code quality =
   * null}) instead.
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
   * Bulk-deletes every non-personal inventory item (the "globales Lager" stock). Personal rows
   * ({@code personal = true}) are explicitly left untouched so the admin "clear global inventory"
   * action does not nuke individual users' private entries. The {@code job_order_handover_item ->
   * inventory_item} FK was removed in {@code V64} (the handover row already snapshots the relevant
   * material data), so a single bulk-delete is safe — no pre-cleanup loop is required.
   *
   * <p>Multi-tenant: uses the standard R6.c scope predicate triple. Admin all-scope wipes every
   * non-personal item; a specific active OrgUnit limits the wipe to that OrgUnit; non-admin
   * callers' membership union scopes the wipe to the caller's OrgUnits. Service-layer enforces the
   * access check before reaching this method.
   *
   * @param isAdminAllScope {@code true} iff the caller is admin without an active OrgUnit selection
   *     — wipes every non-personal item regardless of owner.
   * @param activeOrgUnitId the single OrgUnit to scope the wipe to (admin pinning), or {@code
   *     null}.
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin path); empty for
   *     admins and anonymous.
   * @return number of deleted rows
   */
  @Modifying
  @Query(
      "DELETE FROM InventoryItem i WHERE i.personal = false AND "
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE)
  int deleteAllNonPersonal(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds);

  /**
   * Deletes every warehouse row held by the given user — personal and shared alike — as part of the
   * hard account deletion (REQ-DATA-008). Replaces the former reassignment to a fallback admin: a
   * departing member's stock leaves the Lager with them rather than accumulating on an admin
   * account.
   *
   * <p>The three child tables that reference {@code inventory_item(id)} — {@code
   * inventory_job_order_allocation} and {@code inventory_mission_allocation} (V217) and {@code
   * material_exchange_offer} (V210) — all declare {@code ON DELETE CASCADE}, so the job-order and
   * mission links and any open Materialbörse offer are removed by the database along with the row.
   * The fourth, {@code job_order_handover_item.inventory_item_id}, is {@code ON DELETE SET NULL}
   * (V58) so the handover history survives with its snapshot intact. No pre-cleanup loop is
   * required.
   *
   * <p>Set-based by necessity, not for speed: {@code InventoryItem.user} is
   * {@code @ManyToOne(optional = false)}, so loading the rows as managed entities before {@code
   * userRepository.delete(user)} would abort the flush with {@code TransientPropertyValueException}
   * — the same failure class documented on {@link
   * OrgUnitMembershipRepository#findOrgUnitIdsByUserId(UUID)}. Never replace this with a
   * find-then-{@code deleteAll}.
   *
   * @param userId the owner whose warehouse rows are removed; never {@code null}.
   * @return the number of deleted rows, for the audit summary event.
   */
  @Modifying
  @Query("DELETE FROM InventoryItem i WHERE i.user.id = :userId")
  int deleteByUserId(@Param("userId") UUID userId);

  /**
   * Loads the caller's own Lager rows for the Materialbörse release picker — <b>both</b> material
   * rows ("Material anbieten") and game-item rows ("Item anbieten" from stock, REQ-MARKET-014,
   * design §8) — optionally filtered by a name fragment and capped by the {@link Pageable}.
   * Owner-scoped by {@code user.id} (a member may only offer their own stock), with material, game
   * item and location eager-loaded so the picker renders without an N+1, ordered by the row's
   * catalog name. Location is loaded here only because it is the owner's own picker — it is never
   * exposed on the public board.
   *
   * <p>Search and ordering go through explicit {@code LEFT JOIN}s on both {@code i.material} and
   * {@code i.gameItem} with a {@code COALESCE(m.name, gi.name)} name — the {@code
   * MaterialExchangeOfferRepository.findBoard} pattern — because attribute navigation ({@code
   * i.material.name}) would smuggle in an implicit <em>inner</em> join that silently drops the
   * other kind's rows (a game-item row has a {@code NULL} material, a material row a {@code NULL}
   * game item). Since stock-backed item offers shipped (design §8) the former {@code i.material IS
   * NOT NULL} guard is dropped so both kinds surface; the release service branches on the picked
   * row's kind (material offer vs stock-backed item offer).
   *
   * <p>The {@code includeMaterial} / {@code includeItem} flags gate the row <b>kind</b> so the
   * release dialog's Material/Item radio can restrict the picker to one kind (REQ-MARKET-002). The
   * split must happen here, inside the DB query and <em>before</em> the {@link Pageable} cap, not
   * by filtering the returned list — a post-cap client/service filter would drop every row of the
   * wanted kind that fell past the row cap, silently hiding the tail (the reachability guarantee
   * the server-side picker search exists to protect). A material row is discriminated by {@code
   * i.gameItem IS NULL} and a game-item row by {@code i.gameItem IS NOT NULL} — the same XOR the
   * service maps to the DTO kind. Passing both flags {@code true} returns both kinds (the
   * unfiltered default); both {@code false} returns nothing.
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
