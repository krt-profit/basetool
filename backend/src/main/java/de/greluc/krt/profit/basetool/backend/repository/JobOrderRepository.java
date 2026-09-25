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
   * Loads every {@code OPEN} or {@code IN_PROGRESS} job order with the material and item-line
   * requirements the active-order lookup reads, ordered by ascending {@code priority} (nulls last),
   * then descending {@code displayId}.
   *
   * <p>Fetches exactly what {@link
   * de.greluc.krt.profit.basetool.backend.service.JobOrderQueryService#findAllActiveReference()}
   * reads, each order exactly once (REQ-ORDERS-018). Handovers are not fetched, since they would
   * multiply the SQL rows.
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
   * Scoped, unpaged list of the orders in the given statuses with both kinds' material requirement
   * branches, for the cross-order material-demand overview (REQ-ORDERS-034). Scope is applied in
   * SQL via {@link ScopeSpecifications#JOB_ORDER_SCOPE_PREDICATE}, including the SK-public escape.
   *
   * <p>Unpaged because the result is aggregated into sums (ADR-0104); callers bound it by passing
   * only non-terminal statuses.
   *
   * @param statuses the statuses to keep; never empty.
   * @param isAdminAllScope {@code true} iff the caller is an admin without an active pin, which
   *     disables the scope filter.
   * @param activeOrgUnitId the single OrgUnit the caller is pinned to, or {@code null}.
   * @param memberOrgUnitIds the OrgUnits the caller belongs to (non-admin path); empty for admins
   *     and anonymous callers.
   * @return the scoped orders with their requirement branches loaded, ordered by {@code displayId}.
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
      @Param("statuses") Collection<JobOrderStatus> statuses,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds);

  /**
   * Scoped, paged job-order list behind {@code GET /api/v1/orders}, combining visibility scope,
   * status filter and optional squadron display filter.
   *
   * <p>An order responsible to a Spezialkommando is visible to everyone; a squadron-responsible one
   * only to that squadron and admins (see {@link
   * de.greluc.krt.profit.basetool.backend.service.ScopePredicate}). The squadron filter matches the
   * responsible or requesting side and only narrows the scoped result. Only the two org units are
   * fetched; collections batch-load (REQ-DATA-003).
   *
   * @param statuses status values to keep; pass the full enum set to disable status filtering
   *     (never empty).
   * @param noSquadronFilter {@code true} to disable the squadron display filter.
   * @param squadronIds the squadron ids to match on either side; never empty, pass a placeholder
   *     when {@code noSquadronFilter} is {@code true}.
   * @param isAdminAllScope {@code true} iff the caller is an admin without an active selection,
   *     which disables the scope filter.
   * @param activeOrgUnitId the single OrgUnit the caller is pinned to, or {@code null}.
   * @param memberOrgUnitIds the OrgUnits the caller belongs to (non-admin path); empty for admins
   *     and anonymous callers.
   * @param pageable page request.
   * @return paged job orders visible to the caller, matching the status and squadron filters.
   */
  @EntityGraph(attributePaths = {"responsibleOrgUnit", "requestingOrgUnit"})
  @Query(
      "SELECT o FROM JobOrder o WHERE "
          + ScopeSpecifications.JOB_ORDER_SCOPE_PREDICATE
          + " AND o.status IN :statuses AND (:noSquadronFilter = TRUE OR o.responsibleOrgUnit.id IN"
          + " :squadronIds OR o.requestingOrgUnit.id IN :squadronIds)")
  Page<JobOrder> findScopedJobOrders(
      @Param("statuses") List<JobOrderStatus> statuses,
      @Param("noSquadronFilter") boolean noSquadronFilter,
      @Param("squadronIds") Collection<UUID> squadronIds,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Requester-side paged job-order list (REQ-ORDERS-023): orders whose {@code requestingOrgUnit} is
   * one of {@code requesterOrgUnitIds} and whose status is in {@code statuses}, regardless of the
   * responsible side. Sorted by the {@link Pageable}.
   *
   * @param statuses status values to keep; pass the full enum set to disable status filtering
   *     (never empty).
   * @param requesterOrgUnitIds the caller's direct-membership org-unit ids; never empty.
   * @param pageable page request (carries the sort).
   * @return paged job orders the caller's org units requested.
   */
  @EntityGraph(attributePaths = {"responsibleOrgUnit", "requestingOrgUnit"})
  @Query(
      "SELECT o FROM JobOrder o WHERE o.requestingOrgUnit.id IN :requesterOrgUnitIds AND o.status"
          + " IN :statuses")
  Page<JobOrder> findRequestedOrders(
      @Param("statuses") List<JobOrderStatus> statuses,
      @Param("requesterOrgUnitIds") Collection<UUID> requesterOrgUnitIds,
      Pageable pageable);

  /**
   * Loads one job order with its item lines and each line's blueprint and game item in a single
   * query, for the item blueprint-coverage view. A {@code MATERIAL} order has no items.
   *
   * @param id the job-order id
   * @return the order with items, blueprints and game items loaded, or empty when the id is unknown
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
   * Returns the highest priority across all job orders, used to assign a new order's priority;
   * empty when there are no orders.
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
   * Takes a {@link LockModeType#PESSIMISTIC_WRITE} row lock on one job order so concurrent material
   * claims on it serialise and each sees the others' committed claims (REQ-ORDERS-024, ADR-0092).
   * Locks only this order's row.
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
  @Modifying
  @Query(value = "DELETE FROM job_order_assignees WHERE user_id = :userId", nativeQuery = true)
  void removeAssignee(@Param("userId") UUID userId);

  /**
   * Replaces a contact-person {@code handle} matching case-insensitively with the erasure sentinel
   * (REQ-SEC-062).
   *
   * <p>The column is free text with no user id, so it is matched on text alone and may over-match;
   * the admin reviews the Personensuche hits before granting.
   *
   * @param handle the contact spelling to erase; compared case-insensitively
   * @param sentinel {@code HandleAnonymisation#SENTINEL}
   * @return the number of rows rewritten
   */
  @Modifying
  @Query(
      "UPDATE JobOrder o SET o.handle = :sentinel"
          + " WHERE lower(o.handle) = lower(:handle) AND o.handle <> :sentinel")
  int anonymiseHandle(@Param("handle") String handle, @Param("sentinel") String sentinel);
}
