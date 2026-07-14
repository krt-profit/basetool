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

import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.User;
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

/** Spring Data repository for Inventory Item. */
@Repository
public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

  /**
   * Derived Spring-Data query - returns entities matching {@code User}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  Page<InventoryItem> findByUser(User user, Pageable pageable);

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
   * first.
   *
   * @param userId the owning user; never {@code null}
   * @return one slice per (material, quality) the user owns, with the summed SCU; never {@code
   *     null}
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.OwnedStockSlice(i.material.id, i.quality, SUM(COALESCE(i.amount, 0.0))) FROM InventoryItem i
      WHERE i.user.id = :userId GROUP BY i.material.id, i.quality
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
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query(
      "SELECT i FROM InventoryItem i WHERE i.personal = false AND "
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
   * data layer rather than relying on the controller alone.
   */
  @EntityGraph(attributePaths = {"material", "location", "user", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.user = :user AND (:hasMaterials = false OR
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
   * Group-on-read variant of {@link #findGlobalByFilters}: instead of returning the individual
   * rows, it collapses the scoped, filtered non-personal inventory into one {@link
   * InventoryStackAggregate} per stock identity (the inventory natural key) directly in SQL —
   * {@code SUM(amount)}, the amount-weighted quality sum, {@code MAX(quality)} and the entry count.
   * The underlying entries are never loaded here (append-only rows grow unboundedly per stack);
   * they are fetched lazily and paginated via {@link #findGlobalStackEntries}. The stack list
   * itself is bounded by the number of distinct stock identities, so it is returned unpaged. Same
   * scope-triple + optional-filter contract as {@link #findGlobalByFilters}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate(i.material, i.user, i.location, i.quality, i.personal,
      oou, SUM(COALESCE(i.amount, 0.0)), SUM(COALESCE(i.amount, 0.0) *
      COALESCE(i.quality, 0)), MAX(COALESCE(i.quality, 0)), COUNT(i)) FROM InventoryItem i
      LEFT JOIN i.owningOrgUnit oou
      WHERE i.personal = false AND
      """
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " AND (:hasMaterials = false OR i.material.id IN :materialIds) AND (:minQuality IS"
          + " NULL OR i.quality >= :minQuality) AND (:hasJobOrders = false OR EXISTS (SELECT 1"
          + " FROM InventoryJobOrderAllocation ja WHERE ja.inventoryItem = i AND ja.jobOrder.id"
          + " IN :jobOrderIds)) AND (:hasMissions = false OR EXISTS (SELECT 1 FROM"
          + " InventoryMissionAllocation ma WHERE ma.inventoryItem = i AND ma.mission.id IN"
          + " :missionIds)) GROUP BY i.material, i.user, i.location, i.quality, i.personal, oou")
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
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.InventoryStackAggregate(i.material, i.user, i.location, i.quality, i.personal,
      oou, SUM(COALESCE(i.amount, 0.0)), SUM(COALESCE(i.amount, 0.0) *
      COALESCE(i.quality, 0)), MAX(COALESCE(i.quality, 0)), COUNT(i)) FROM InventoryItem i
      LEFT JOIN i.owningOrgUnit oou
      WHERE i.user.id = :userId AND (:personalOnly = false OR i.personal = true)
      AND (:nonPersonalOnly = false OR i.personal = false)
      AND (:hasMaterials = false OR i.material.id IN :materialIds) AND (:minQuality IS NULL
      OR i.quality >= :minQuality) AND (:hasJobOrders = false OR EXISTS (SELECT 1 FROM
      InventoryJobOrderAllocation ja WHERE ja.inventoryItem = i AND ja.jobOrder.id IN
      :jobOrderIds)) AND (:hasMissions = false OR EXISTS (SELECT 1 FROM
      InventoryMissionAllocation ma WHERE ma.inventoryItem = i AND ma.mission.id IN
      :missionIds)) GROUP BY i.material, i.user, i.location, i.quality, i.personal, oou
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
   * Lazily loads one global stack's underlying entries, oldest-first, paginated — the per-stack
   * drill-down for the squadron-wide Lager view. The stack is identified by its stock-identity
   * tuple (material, owner, location, quality, optional job order / mission, owning org-unit pool);
   * {@code null} job-order / mission / owning-org-unit arguments match rows where that association
   * is itself {@code null}. The same scope triple as {@link #findGlobalByFilters} is applied so the
   * drill-down can never widen visibility beyond the caller's org-unit slice. Only non-personal
   * stock is exposed here, mirroring the global grouped view.
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
   * Aggregates non-personal inventory by {@code material}: total amount, plus an amount-weighted
   * mean quality (so 10 units at quality 800 plus 5 units at quality 600 land at {@code (10*800 +
   * 5*600) / 15}). Used by the global "aggregated inventory" view; returns raw {@code Object[]}
   * tuples - the service layer projects them into {@code AggregatedInventoryDto}.
   *
   * <p>Multi-tenant: {@code owningSquadronId} restricts to the caller's squadron stock. {@code
   * null} means admin "all squadrons" mode (aggregated across the whole org).
   */
  @Query(
      """
      SELECT i.material as material, CASE WHEN SUM(i.amount) > 0 THEN SUM(CAST(i.quality AS
      double) * i.amount) / SUM(i.amount) ELSE 0.0 END as quality, MAX(i.quality) as maxQuality,
      SUM(i.amount) as amount
      FROM InventoryItem i WHERE i.personal = false AND
      """
          + ScopeSpecifications.INVENTORY_ITEM_SCOPE_TRIPLE
          + " GROUP BY i.material")
  Page<Object[]> getAggregatedInventory(
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

  /** Derived Spring-Data query - returns entities matching {@code JobOrderIdOrdered}. */
  @EntityGraph(attributePaths = {"user", "location", "material", "owningOrgUnit"})
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE EXISTS (SELECT 1 FROM InventoryJobOrderAllocation ja
      WHERE ja.inventoryItem = i AND ja.jobOrder.id = :jobOrderId) ORDER BY i.user.username
      ASC, i.location.name ASC, i.material.name ASC, i.quality DESC, i.amount DESC
      """)
  List<InventoryItem> findByJobOrderIdOrdered(@Param("jobOrderId") UUID jobOrderId);

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
   * @param jobOrderIds the orders whose linked stock to project; an empty collection yields an
   *     empty list.
   * @return one {@link JobOrderMaterialStockRow} per job-order allocation, never {@code null}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.JobOrderMaterialStockRow(a.jobOrder.id, a.inventoryItem.material.id, a.inventoryItem.quality, a.amount)
      FROM InventoryJobOrderAllocation a WHERE a.jobOrder.id IN :jobOrderIds
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
   * Bulk-reassigns every inventory item owned by {@code oldUser} to {@code newUser}; used by the
   * user-deletion cascade so stock is preserved when an account is removed.
   *
   * @param oldUser the previous owner
   * @param newUser the new owner (the fallback admin)
   * @return the number of inventory rows reassigned
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE InventoryItem i SET i.user = :newUser WHERE i.user = :oldUser")
  int updateOwner(
      @org.jetbrains.annotations.NotNull de.greluc.krt.profit.basetool.backend.model.User oldUser,
      @org.jetbrains.annotations.NotNull de.greluc.krt.profit.basetool.backend.model.User newUser);

  /**
   * Derived Spring-Data query - returns entities matching {@code IdForUpdate}. Acquires a
   * pessimistic write lock for the duration of the surrounding transaction.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @EntityGraph(attributePaths = {"material", "user", "location", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.id = :id")
  Optional<InventoryItem> findByIdForUpdate(@Param("id") UUID id);

  /**
   * Loads every warehouse row that shares the stock identity of a just-written row — the merge
   * candidates for the write-time stock merge (REQ-INV-026): a {@code PIECE} write merges
   * automatically, an {@code SCU} write when the caller opted in. The identity is the append-only
   * stack key <em>minus</em> {@code delivered} (the "Geliefert" marker is intentionally not part of
   * the key; the merged survivor is reset to not-delivered), so a delivered and a non-delivered row
   * of the same stock are merge candidates. The three nullable dimensions ({@code jobOrder}, {@code
   * mission}, {@code owningOrgUnit}) match on {@code NULL = NULL}. Rows backing a {@link
   * de.greluc.krt.profit.basetool.backend.model.MaterialExchangeOffer} are excluded via {@code NOT
   * EXISTS} so a merge never deletes stock the Materialbörse still references ({@code ON DELETE
   * CASCADE}, V210) — the offer and its offered quantity stay untouched.
   *
   * <p>The rows are locked {@code PESSIMISTIC_WRITE} ({@code FOR UPDATE}) for the surrounding
   * transaction so two racing writers to the same stack serialise: the merge reads, sums and
   * deletes siblings, which is exactly the read-add-write the append-only model (ADR-0003) removed,
   * so it re-introduces the lock only on this one path. Ordered oldest-first for a deterministic
   * survivor tie-break.
   *
   * <p>Since Variante C (REQ-INV-027) the group key is the row's <em>physical</em> identity only —
   * user · material · location · quality · personal · owningOrgUnit; the job-order / mission
   * earmarks are NOT part of it, so matching rows are folded and their allocations unioned (R1).
   *
   * @param userId the owning user of the stack; never {@code null}.
   * @param materialId the stack's material; never {@code null}.
   * @param locationId the stack's storage location; never {@code null}.
   * @param quality the stack's quality grade; never {@code null}.
   * @param personal the stack's personal flag; never {@code null}.
   * @param owningOrgUnitId the stack's owning org-unit pool id, or {@code null} to match rows with
   *     no owning org unit.
   * @return the locked matching rows (excluding offer-backed rows), oldest-first; never {@code
   *     null}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      SELECT i FROM InventoryItem i WHERE i.user.id = :userId AND i.material.id = :materialId AND
      i.location.id = :locationId AND i.quality = :quality AND i.personal = :personal AND
      ((:owningOrgUnitId IS NULL AND i.owningOrgUnit IS NULL) OR i.owningOrgUnit.id =
      :owningOrgUnitId) AND NOT EXISTS (SELECT 1 FROM MaterialExchangeOffer o WHERE
      o.inventoryItem = i) ORDER BY i.createdAt ASC, i.id ASC
      """)
  List<InventoryItem> findMergeGroupForUpdate(
      @Param("userId") UUID userId,
      @Param("materialId") UUID materialId,
      @Param("locationId") UUID locationId,
      @Param("quality") Integer quality,
      @Param("personal") Boolean personal,
      @Param("owningOrgUnitId") UUID owningOrgUnitId);

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
   * Loads the caller's own Lager rows for the Materialbörse "Material anbieten" item picker,
   * optionally filtered by a material-name fragment and capped by the {@link Pageable}.
   * Owner-scoped by {@code user.id} (a member may only offer their own stock), with material and
   * location eager-loaded so the picker renders without an N+1, ordered by material name. Location
   * is loaded here only because it is the owner's own picker — it is never exposed on the public
   * board.
   *
   * @param userId the caller (the picker only ever shows the caller's own rows).
   * @param query a pre-lowercased {@code %fragment%} matched against the material name, or {@code
   *     null} for no filter.
   * @param pageable the cap on the number of picker rows.
   * @return the caller's matching Lager rows, never {@code null}.
   */
  @EntityGraph(attributePaths = {"material", "location"})
  @Query(
      "SELECT i FROM InventoryItem i WHERE i.user.id = :userId "
          + "AND (:query IS NULL OR LOWER(i.material.name) LIKE :query) "
          + "ORDER BY i.material.name ASC")
  List<InventoryItem> findReleasableForUser(
      @Param("userId") UUID userId, @Param("query") String query, Pageable pageable);
}
