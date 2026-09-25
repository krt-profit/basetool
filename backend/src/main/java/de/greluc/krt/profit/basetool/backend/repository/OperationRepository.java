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

import de.greluc.krt.profit.basetool.backend.model.Operation;
import de.greluc.krt.profit.basetool.backend.model.OperationStatus;
import de.greluc.krt.profit.basetool.backend.model.dto.OperationReferenceDto;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Operation. */
@Repository
public interface OperationRepository extends JpaRepository<Operation, UUID> {

  /**
   * Counts operations in the given lifecycle status, backing the {@code basetool_operation_open_*}
   * queue-depth gauge (REQ-OBS-011).
   *
   * @param status the bounded lifecycle status to count
   * @return the number of operations in that status
   */
  long countByStatus(OperationStatus status);

  /**
   * Finds the creation timestamp of the oldest operation in the given status, for the "oldest
   * un-started operation age" gauge (REQ-OBS-011).
   *
   * @param status the bounded lifecycle status to scan (typically {@code PLANNED})
   * @return the earliest {@code createdAt} in that status, or {@code null} when none exists
   */
  @Query("SELECT MIN(o.createdAt) FROM Operation o WHERE o.status = :status")
  Instant findOldestCreatedAtByStatus(@Param("status") OperationStatus status);

  /**
   * Pre-fetches the missions of an operation, their participants, and the participants' user
   * references in a single query. Used by the payout calculation, which would otherwise trip the
   * lazy collection at every level (1 + N missions + N*M participants).
   */
  @EntityGraph(attributePaths = {"missions", "missions.participants", "missions.participants.user"})
  Optional<Operation> findWithMissionsAndParticipantsById(UUID id);

  /**
   * Scoped variant of {@link #findAll(org.springframework.data.domain.Pageable)}: every operation
   * owned by an OrgUnit in the caller's scope, or all of them when {@code isAdminAllScope} is
   * {@code true}. Two read-only escapes apply:
   *
   * <ul>
   *   <li><b>Ownerless leadership operation</b> ({@code owning_org_unit_id IS NULL}) — visible to
   *       organisation members-or-above (REQ-ORG-009).
   *   <li><b>Participant escape</b> — visible to a user who participated in one of the operation's
   *       linked missions.
   * </ul>
   *
   * @param viewerIsMemberOrAbove {@code true} iff the caller is an organisation member-or-above;
   *     gates the ownerless branch
   * @param viewerUserId the caller's user id, or {@code null} when no subject resolves; gates the
   *     participant escape
   */
  @EntityGraph(attributePaths = {"owningOrgUnit"})
  @Query("SELECT o FROM Operation o WHERE " + ScopeSpecifications.OPERATION_SCOPE_PREDICATE)
  Page<Operation> findAllScoped(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("viewerIsMemberOrAbove") boolean viewerIsMemberOrAbove,
      @Param("viewerUserId") UUID viewerUserId,
      Pageable pageable);

  /**
   * Slim id + name projection of every operation visible to the caller, sorted by name, for the
   * mission-detail operation picker.
   *
   * <p>Uses the same scope predicate as {@link #findAllScoped}. {@code PLANNED} / {@code ACTIVE}
   * operations are always returned, {@code COMPLETED} / {@code CANCELED} ones only when created on
   * or after {@code cutoff}.
   *
   * @param isAdminAllScope {@code true} iff the caller is admin without an active OrgUnit selection
   * @param activeOrgUnitId the OrgUnit the caller is pinned to, or {@code null}
   * @param memberOrgUnitIds the caller's OrgUnits (non-admin path); empty for admins and anonymous
   *     callers
   * @param viewerIsMemberOrAbove {@code true} iff the caller is an organisation member-or-above;
   *     surfaces ownerless leadership operations
   * @param viewerUserId the caller's user id, or {@code null}; surfaces operations the caller
   *     participated in
   * @param cutoff inclusive lower bound on {@code createdAt} for {@code COMPLETED} / {@code
   *     CANCELED} operations
   * @return slim reference DTOs, sorted by name ascending
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.OperationReferenceDto(o.id,
      o.name) FROM Operation o WHERE (
        o.status IN ('PLANNED', 'ACTIVE')
        OR (o.status IN ('COMPLETED', 'CANCELED') AND o.createdAt >= :cutoff)
      ) AND
      """
          + ScopeSpecifications.OPERATION_SCOPE_PREDICATE
          + " ORDER BY o.name ASC")
  List<OperationReferenceDto> findAllReferenceScoped(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("viewerIsMemberOrAbove") boolean viewerIsMemberOrAbove,
      @Param("viewerUserId") UUID viewerUserId,
      @Param("cutoff") Instant cutoff);

  /**
   * Free-text, status, time-range and scope search across operations.
   *
   * <p>A {@code null} argument drops its clause; {@code status} is always applied. The time range
   * applies to the span of the operation's linked missions ({@code MIN(plannedStartTime)} to {@code
   * MAX(plannedEndTime)}), so an operation without missions is excluded whenever a bound is set.
   * Scope is strict-staffel, except that ownerless leadership operations are visible to
   * members-or-above (REQ-ORG-009).
   *
   * @param query free-text name/description fragment, may be {@code null}
   * @param start inclusive lower bound on the earliest mission's planned start, or {@code null}
   * @param end inclusive upper bound on the latest mission's planned end, or {@code null}
   * @param status status names of {@code OperationStatus}; always applied
   * @param isAdminAllScope {@code true} iff the caller is admin without an active selection
   * @param activeOrgUnitId pinned OrgUnit id, or {@code null}
   * @param memberOrgUnitIds the caller's OrgUnits (non-admin path)
   * @param viewerIsMemberOrAbove {@code true} iff the caller is an organisation member-or-above;
   *     surfaces ownerless leadership operations
   * @param viewerUserId the caller's user id, or {@code null}; surfaces operations the caller
   *     participated in
   * @param pageable page request
   * @return paged matching operations
   */
  @EntityGraph(attributePaths = {"owningOrgUnit"})
  @Query(
      "SELECT o FROM Operation o WHERE "
          + ScopeSpecifications.OPERATION_SCOPE_PREDICATE
          + " AND (CAST(:query AS string) IS NULL OR o.name ILIKE CONCAT('%', CAST(:query AS"
          + " string), '%') OR CAST(o.description AS string) ILIKE CONCAT('%', CAST(:query AS"
          + " string), '%')) AND (CAST(o.status AS string) IN (:status)) AND (CAST(:start AS"
          + " timestamp) IS NULL OR (SELECT MIN(m.plannedStartTime) FROM Mission m WHERE"
          + " m.operation = o) >= :start) AND (CAST(:end AS timestamp) IS NULL OR (SELECT"
          + " MAX(m.plannedEndTime) FROM Mission m WHERE m.operation = o) <= :end)")
  Page<Operation> searchOperations(
      @Param("query") String query,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("status") List<String> status,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("viewerIsMemberOrAbove") boolean viewerIsMemberOrAbove,
      @Param("viewerUserId") UUID viewerUserId,
      Pageable pageable);

  /**
   * Whether the given user participated in at least one mission linked to the operation; backs the
   * participant escape of {@code OwnerScopeService.canSeeOperation}. Guest participants never
   * match.
   *
   * @param operationId the operation to test; never {@code null}
   * @param userId the caller's {@code app_user.id}; never {@code null}
   * @return {@code true} iff {@code userId} participated in any of the operation's missions
   */
  @Query(
      """
      SELECT COUNT(p) > 0 FROM MissionParticipant p WHERE p.mission.operation.id = :operationId
      AND p.user.id = :userId
      """)
  boolean existsParticipantUserInOperation(
      @Param("operationId") UUID operationId, @Param("userId") UUID userId);
}
