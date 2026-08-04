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

import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Job Order. */
@Repository
public interface JobOrderRepository extends JpaRepository<JobOrder, UUID> {

  /**
   * Counts job orders in the given lifecycle status, backing the {@code basetool_job_order_open_*}
   * queue-depth gauge (REQ-OBS-011).
   *
   * @param status the bounded lifecycle status to count
   * @return the number of job orders in that status
   */
  long countByStatus(JobOrderStatus status);

  /**
   * Finds the creation timestamp of the oldest job order in the given status, for the "oldest open
   * job order age" gauge (REQ-OBS-011).
   *
   * @param status the bounded lifecycle status to scan (typically {@code OPEN})
   * @return the earliest {@code createdAt} in that status, or {@code null} when none exists
   */
  @Query("SELECT MIN(o.createdAt) FROM JobOrder o WHERE o.status = :status")
  Instant findOldestCreatedAtByStatus(@Param("status") JobOrderStatus status);

  /**
   * Derived Spring-Data query - returns entities matching {@code Id}. Eagerly fetches the
   * configured relations via {@code @EntityGraph}.
   */
  @EntityGraph(
      attributePaths = {
        "materials",
        "materials.material",
        "handovers",
        "handovers.items",
        "handovers.items.material",
        "assignees",
        "assignees.user",
        "responsibleOrgUnit",
        "requestingOrgUnit"
      })
  @Override
  Optional<JobOrder> findById(UUID id);

  /**
   * Loads every job-order in {@code OPEN} or {@code IN_PROGRESS} status together with the material
   * requirements the active-order lookup projects, ordered by ascending {@code priority}
   * (most-important first; orders without a priority sort last) and then by descending {@code
   * displayId} as a stable tiebreaker — mirroring the Auftragsverwaltung's default {@code
   * priority,asc} ranking so the warehouse (Lager) job-order filter and per-row pickers present the
   * same order. Eager-fetch path matches exactly what {@link
   * de.greluc.krt.profit.basetool.backend.service.JobOrderQueryService#findAllActiveReference()}
   * reads, so there is no N+1.
   *
   * <p>The item lines ({@code items} → {@code items.materials} → {@code items.materials.material})
   * are fetched so the picker can compute an ITEM order's required materials ({@code
   * JobOrderItemService.requiredMaterialIds}) without an N+1 per ITEM order; {@code items.gameItem}
   * (a to-one path, no row explosion) joins the graph so the requested-game-item set ({@code
   * JobOrderItemService.requiredGameItemIds}, REQ-INV-031) resolves N+1-free too. The {@code
   * materials} and {@code items.materials} branches never explode against each other: the two order
   * kinds are mutually exclusive, so for any given order exactly one branch is non-empty. Like
   * every other multi-collection fetch query here, the result relies on Hibernate's automatic
   * de-duplication of fetch-join roots, so each active order appears exactly once in the returned
   * list — no {@code DISTINCT} is needed (REQ-ORDERS-018).
   *
   * <p><strong>Handovers are deliberately NOT fetched here</strong> — neither the MATERIAL {@code
   * handovers} nor the ITEM {@code itemHandovers} side. The lookup projection reads only the order
   * handle, status, requesting org unit and the required-material/-game-item sets, never a
   * handover. Beyond being dead weight, a MATERIAL order legitimately carries both material lines
   * <em>and</em> handovers, so eager-fetching {@code handovers} alongside {@code materials} turned
   * the query into a {@code materials × handovers} cartesian product <em>at the SQL level</em> —
   * extra result-set rows Hibernate has to read and then discard while de-duplicating the roots
   * (Hibernate collapses them, so no duplicate root ever reached the picker, but the wasted rows
   * were real). Keep this graph free of any handover branch; the picker never needs it.
   */
  @EntityGraph(
      attributePaths = {
        "materials",
        "materials.material",
        "items",
        "items.gameItem",
        "items.materials",
        "items.materials.material",
        "responsibleOrgUnit",
        "requestingOrgUnit"
      })
  @Query(
      """
      SELECT o FROM JobOrder o WHERE o.status IN ('OPEN', 'IN_PROGRESS') ORDER BY o.priority ASC
      NULLS LAST, o.displayId DESC
      """)
  List<JobOrder> findAllActiveWithMaterials();

  /**
   * Scoped, unpaged list of the orders in the given statuses together with <em>both</em> kinds'
   * material requirement branches — the read behind the cross-order material-demand overview
   * (REQ-ORDERS-034). It is the scoped sibling of {@link #findAllActiveWithMaterials()}: same
   * requirement fetch graph, but the caller's visibility scope is pushed into SQL via the shared
   * {@link ScopeSpecifications#JOB_ORDER_SCOPE_PREDICATE} (including the SK-public escape) instead
   * of being filtered row-by-row in memory afterwards, so a caller can never see demand from an
   * order they may not read.
   *
   * <p>The graph fetches the {@code MATERIAL} branch ({@code materials}) and the {@code ITEM}
   * branch ({@code items → materials}) side by side. That does <b>not</b> produce the cartesian
   * blow-up the lookup query's Javadoc warns about, because the two branches are mutually exclusive
   * per row: a {@code MATERIAL} order has no item lines and an {@code ITEM} order no material
   * lines. No handover branch is fetched — the overview reads requirements and linked stock only.
   *
   * <p>Deliberately unpaged: the result is folded into one aggregation row per {@code
   * (responsibleOrgUnit, material, quality)} bucket, so paging the orders would silently truncate
   * the sums it produces (ADR-0104, no silent caps). The set is naturally bounded by the caller
   * passing only the non-terminal statuses.
   *
   * @param statuses the statuses to keep; never bound empty (the service passes {@code OPEN} +
   *     {@code IN_PROGRESS}).
   * @param isAdminAllScope {@code true} iff the caller is an admin without an active pin — disables
   *     the scope filter entirely.
   * @param activeOrgUnitId the single OrgUnit the caller is pinned to, or {@code null}.
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin path); empty for
   *     admins and anonymous callers.
   * @return the scoped orders with their material requirement branches eagerly loaded, ordered by
   *     {@code displayId} so a bucket's contributing-order list is stable across requests.
   */
  @EntityGraph(
      attributePaths = {
        "materials",
        "materials.material",
        "items",
        "items.materials",
        "items.materials.material",
        "responsibleOrgUnit"
      })
  @Query(
      "SELECT o FROM JobOrder o WHERE "
          + ScopeSpecifications.JOB_ORDER_SCOPE_PREDICATE
          + " AND o.status IN :statuses ORDER BY o.displayId ASC")
  List<JobOrder> findScopedOrdersWithMaterialRequirements(
      @Param("statuses") java.util.Collection<JobOrderStatus> statuses,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds);

  /**
   * Scoped, paged job-order list — the single entry point behind the {@code GET /api/v1/orders}
   * list endpoint. Combines three concerns in one query so the service layer never has to fork its
   * query builder:
   *
   * <ol>
   *   <li><b>Visibility scope (Phase 3, #343).</b> Job Orders are a <em>conditionally</em>
   *       staffel-scoped aggregate: an order whose {@code responsibleOrgUnit} is a Spezialkommando
   *       is public to every squadron, while a squadron-responsible order is private to that
   *       squadron + admins. The requester does NOT grant visibility. The scope is expressed with
   *       the standard org-unit predicate triple ({@code isAdminAllScope} / {@code activeOrgUnitId}
   *       / {@code memberOrgUnitIds}, see {@link
   *       de.greluc.krt.profit.basetool.backend.service.ScopePredicate}) plus the SK-public escape
   *       {@code TYPE(o.responsibleOrgUnit) = SpecialCommand}.
   *   <li><b>Status filter.</b> The order's status must be in {@code statuses}. The service passes
   *       the full enum set to disable status filtering (mirroring {@code searchMissions}), so the
   *       {@code IN} clause is never bound with an empty collection.
   *   <li><b>Optional squadron display filter.</b> A pure UI preference on top of the scope gate —
   *       the orders-index multi-squadron picker, matching responsible OR requesting side. When
   *       {@code noSquadronFilter} is {@code true} the filter is disabled (all scoped orders); else
   *       an order is kept iff its responsible OR requesting org unit is in {@code squadronIds}. It
   *       can only ever narrow the already-scoped result, never widen it past the security scope
   *       above.
   * </ol>
   *
   * @param statuses status values to keep; pass the full enum set to disable status filtering
   *     (never empty).
   * @param noSquadronFilter {@code true} to disable the squadron display filter (show all scoped
   *     orders); {@code false} to keep only orders matching {@code squadronIds}.
   * @param squadronIds the selected squadron ids to match (responsible OR requesting side); never
   *     bound empty — pass a non-empty placeholder when {@code noSquadronFilter} is {@code true}.
   * @param isAdminAllScope {@code true} iff the caller is an admin without an active selection —
   *     disables the scope filter entirely.
   * @param activeOrgUnitId the single OrgUnit the caller is pinned to, or {@code null}.
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin path); empty for
   *     admins and anonymous callers.
   * @param pageable page request.
   * @return paged job-orders visible to the caller, matching the optional status + squadron
   *     filters.
   */
  @EntityGraph(
      attributePaths = {
        "materials",
        "assignees",
        "assignees.user",
        "handovers",
        "handovers.items",
        "responsibleOrgUnit",
        "requestingOrgUnit"
      })
  @Query(
      "SELECT o FROM JobOrder o WHERE "
          + ScopeSpecifications.JOB_ORDER_SCOPE_PREDICATE
          + " AND o.status IN :statuses AND (:noSquadronFilter = TRUE OR o.responsibleOrgUnit.id IN"
          + " :squadronIds OR o.requestingOrgUnit.id IN :squadronIds)")
  Page<JobOrder> findScopedJobOrders(
      @Param("statuses") List<JobOrderStatus> statuses,
      @Param("noSquadronFilter") boolean noSquadronFilter,
      @Param("squadronIds") java.util.Collection<UUID> squadronIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Requester-side paged job-order list (REQ-ORDERS-023): every order whose {@code
   * requestingOrgUnit} is one of {@code requesterOrgUnitIds} and whose status is in {@code
   * statuses}. This is the deliberate counterpart to {@link #findScopedJobOrders}, which scopes
   * only on the <em>responsible</em> side: here the match is purely on the <em>requesting</em>
   * side, so a member of the ordering org unit sees the orders their unit placed even when a
   * foreign squadron processes them and even when the caller is not profit-eligible. The service
   * passes only the caller's own direct-membership org-unit ids, so this never leaks a foreign
   * unit's placed orders. Uses the same eager-fetch graph as the main list so the stock projection
   * has no N+1; the response is redacted for the requester at the controller boundary. Ordering is
   * supplied by the {@link Pageable} (default {@code priority,asc}), matching the main queue.
   *
   * @param statuses status values to keep; pass the full enum set to disable status filtering
   *     (never empty).
   * @param requesterOrgUnitIds the caller's direct-membership org-unit ids; the service
   *     short-circuits an empty set to an empty page before calling this.
   * @param pageable page request (carries the sort).
   * @return paged job-orders the caller's org unit(s) requested.
   */
  @EntityGraph(
      attributePaths = {
        "materials",
        "assignees",
        "assignees.user",
        "handovers",
        "handovers.items",
        "responsibleOrgUnit",
        "requestingOrgUnit"
      })
  @Query(
      "SELECT o FROM JobOrder o WHERE o.requestingOrgUnit.id IN :requesterOrgUnitIds AND o.status"
          + " IN :statuses")
  Page<JobOrder> findRequestedOrders(
      @Param("statuses") List<JobOrderStatus> statuses,
      @Param("requesterOrgUnitIds") java.util.Collection<UUID> requesterOrgUnitIds,
      Pageable pageable);

  /**
   * Loads a single job order together with its ordered item lines and, for each line, the chosen
   * blueprint and the requested game item, in one query. Backs the item blueprint-coverage view
   * ({@code JobOrderItemBlueprintOwnersService}), which reads {@code item.blueprint.outputName}
   * (the product-key source) and {@code item.gameItem.name} (the display label) for every line — so
   * the dedicated fetch join avoids the per-line N+1 that the default {@link #findById(UUID)}
   * entity graph (which does not fetch {@code items}) would incur. Empty for a {@code MATERIAL}
   * order, whose {@code items} set is empty.
   *
   * @param id the job-order id
   * @return the order with its items + their blueprint/game-item to-one relations eagerly loaded,
   *     or empty when the id is unknown
   */
  @Query(
      """
      SELECT o FROM JobOrder o
      LEFT JOIN FETCH o.items i
      LEFT JOIN FETCH i.blueprint
      LEFT JOIN FETCH i.gameItem
      WHERE o.id = :id
      """)
  Optional<JobOrder> findByIdWithItemBlueprints(@Param("id") UUID id);

  /**
   * Returns the current maximum priority across all job-orders (used to assign the next priority
   * slot when creating a new order); {@link Optional#empty} when the table is empty.
   */
  @Query("SELECT MAX(o.priority) FROM JobOrder o")
  Optional<Integer> findMaxPriority();

  /**
   * Acquires a {@link LockModeType#PESSIMISTIC_WRITE} on every job-order ordered by id. Used by the
   * bulk priority-reorder flow to serialise concurrent re-shuffles and avoid the optimistic-
   * locking conflicts that would otherwise fall out of the {@code @Version} bumps - see the
   * "Pessimistic locking for bulk reorders" note in CLAUDE.md.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT o FROM JobOrder o ORDER BY o.id")
  List<JobOrder> lockAllJobOrders();

  /**
   * Acquires a {@link LockModeType#PESSIMISTIC_WRITE} row lock on a single job order — the
   * material-claim upsert takes it before summing a bucket's existing claims so concurrent
   * claimants of the same order serialise and each reads the others' <em>committed</em> claim rows
   * (REQ-ORDERS-024, ADR-0092). Without it, two different squadrons lodging their first claim on
   * one bucket at once each read an empty already-claimed sum under {@code READ COMMITTED} (the
   * other's uncommitted INSERT is invisible), both pass the no-overclaim guard and both commit —
   * and because the unique index {@code uq_material_claim_bucket_org_unit} keys per claiming
   * squadron it never collides across distinct squadrons, so the upsert's own {@code REQUIRES_NEW}
   * retry (which only catches a same-{@code (bucket, squadron)} unique / {@code @Version}
   * violation) cannot catch the cross-squadron overclaim. A bare single-row lock (no join fetch) so
   * it serialises only this one order's claim writers, never an unrelated order or bucket.
   *
   * @param id the order to row-lock.
   * @return the locked order, or {@link Optional#empty} when the id is unknown.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT o FROM JobOrder o WHERE o.id = :id")
  Optional<JobOrder> lockForClaimUpsert(@Param("id") UUID id);

  /** Returns every job order the given user is an assignee of. */
  @Query("SELECT j FROM JobOrder j JOIN j.assignees a WHERE a.user.id = :userId")
  List<JobOrder> findByAssigneeId(@Param("userId") UUID userId);

  /**
   * Removes the given user from every job-order's assignee set via a direct delete on the join
   * table. Native query because a JPQL bulk-delete on a {@code @ManyToMany} association would
   * require loading every job-order first.
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      value = "DELETE FROM job_order_assignees WHERE user_id = :userId",
      nativeQuery = true)
  void removeAssignee(
      @org.springframework.data.repository.query.Param("userId") java.util.UUID userId);
}
