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
   * Multi-tenant variant of {@link #findAll(org.springframework.data.domain.Pageable)}: returns
   * every operation whose owning OrgUnit is in the caller's scope, or every operation when {@code
   * isAdminAllScope} is {@code true} (admin "all squadrons" mode). Operations are a strict-staffel
   * aggregate with two read-only escapes:
   *
   * <ul>
   *   <li><b>Ownerless leadership operation</b> ({@code owning_org_unit_id IS NULL}, V145) —
   *       surfaces to organisation members-or-above via {@code viewerIsMemberOrAbove} (operations
   *       have no public escape; see REQ-ORG-009).
   *   <li><b>Participant escape</b> (#500) — any authenticated user who participated in one of the
   *       operation's linked missions sees the operation regardless of owning OrgUnit, gated by a
   *       non-null {@code viewerUserId} matching a {@code mission_participant.user_id}. Anonymous
   *       callers ({@code viewerUserId == null}) never match.
   * </ul>
   *
   * @param viewerIsMemberOrAbove {@code true} iff the caller is an authenticated organisation
   *     member-or-above ({@code AuthHelperService.isMemberOrAbove()}); gates the ownerless branch
   *     so guests/anonymous never see ownerless operations.
   * @param viewerUserId the caller's user id ({@code AuthHelperService.currentUserId()}), or {@code
   *     null} for an anonymous caller; gates the participant escape.
   */
  @EntityGraph(attributePaths = {"owningOrgUnit"})
  @org.springframework.data.jpa.repository.Query(
      "SELECT o FROM Operation o WHERE " + ScopeSpecifications.OPERATION_SCOPE_PREDICATE)
  org.springframework.data.domain.Page<Operation> findAllScoped(
      @org.springframework.data.repository.query.Param("isAdminAllScope") boolean isAdminAllScope,
      @org.springframework.data.repository.query.Param("activeOrgUnitId") UUID activeOrgUnitId,
      @org.springframework.data.repository.query.Param("memberOrgUnitIds")
          java.util.Collection<UUID> memberOrgUnitIds,
      @org.springframework.data.repository.query.Param("viewerIsMemberOrAbove")
          boolean viewerIsMemberOrAbove,
      @org.springframework.data.repository.query.Param("viewerUserId") UUID viewerUserId,
      org.springframework.data.domain.Pageable pageable);

  /**
   * Slim id + name projection of every operation visible to the caller, sorted by name. Drives the
   * {@code /lookup} endpoint that feeds the mission-detail page's operation-picker dropdown -
   * pulling the full {@code OperationDto} payload via the regular list endpoint with {@code
   * size=1000} was the previous shape and exhausted DB and serialisation budget on every mission
   * page render. Uses the same R6.c scope-predicate triple as {@link #findAllScoped}.
   *
   * @param isAdminAllScope {@code true} iff the caller is admin without an active OrgUnit selection
   *     — disables the scope filter entirely.
   * @param activeOrgUnitId the single OrgUnit the caller is pinned to, or {@code null}.
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin path); empty for
   *     admins and anonymous callers.
   * @param viewerIsMemberOrAbove {@code true} iff the caller is an authenticated organisation
   *     member-or-above; surfaces ownerless leadership operations ({@code owning_org_unit_id IS
   *     NULL}, V145) in the picker for members-or-above only.
   * @param viewerUserId the caller's user id, or {@code null} for an anonymous caller; surfaces
   *     operations the caller participated in (#500) in the picker.
   * @return slim reference DTOs, sorted by name ascending
   */
  @org.springframework.data.jpa.repository.Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.OperationReferenceDto(o.id,
      o.name) FROM Operation o WHERE
      """
          + ScopeSpecifications.OPERATION_SCOPE_PREDICATE
          + " ORDER BY o.name ASC")
  List<OperationReferenceDto> findAllReferenceScoped(
      @org.springframework.data.repository.query.Param("isAdminAllScope") boolean isAdminAllScope,
      @org.springframework.data.repository.query.Param("activeOrgUnitId") UUID activeOrgUnitId,
      @org.springframework.data.repository.query.Param("memberOrgUnitIds")
          java.util.Collection<UUID> memberOrgUnitIds,
      @org.springframework.data.repository.query.Param("viewerIsMemberOrAbove")
          boolean viewerIsMemberOrAbove,
      @org.springframework.data.repository.query.Param("viewerUserId") UUID viewerUserId);

  /**
   * Free-text + status + time-range + scope search across operations. Mirrors the contract of
   * {@code MissionRepository.searchMissions} within the limits of the operation aggregate. {@code
   * query} is optional - a {@code null} cast removes the corresponding clause; the {@code status IN
   * (:status)} list is always applied (pass the full enum set to disable status filtering).
   *
   * <p><strong>Time-range filter.</strong> An operation has no {@code plannedStartTime} of its own
   * — that field lives on the underlying missions — so its effective span is derived from its
   * linked missions: the operation "starts" at the planned start of its earliest mission ({@code
   * MIN(plannedStartTime)}) and "ends" at the planned end of its latest mission ({@code
   * MAX(plannedEndTime)}). The {@code start} bound (inclusive) keeps operations whose earliest
   * mission starts at or after it; the {@code end} bound (inclusive) keeps operations whose latest
   * mission ends at or before it. Both are optional ({@code null} cast removes the clause) and are
   * evaluated via correlated subqueries so the main query still returns one row per operation and
   * SQL-level pagination is preserved. An operation with no linked missions yields {@code NULL} for
   * both aggregates and is therefore excluded whenever either bound is supplied — consistent with
   * how the missions search drops missions with a {@code null plannedStartTime}.
   *
   * <p>Operations are a strict-staffel aggregate: the scope triple restricts the result to
   * operations owned by the caller's OrgUnit(s); {@code isAdminAllScope} means "all squadrons"
   * (admin mode). Unlike missions, there is no cross-staffel <em>public</em> escape - an owned
   * operation of another squadron is never visible to non-admins. The one exception is an
   * <em>ownerless leadership operation</em> ({@code owning_org_unit_id IS NULL}, V145), which
   * surfaces to organisation members-or-above via {@code viewerIsMemberOrAbove} (see REQ-ORG-009).
   *
   * <p>Status values are passed as strings to keep the contract consistent with the missions
   * search; the JPA layer matches them against the {@code OperationStatus} enum's string
   * representation.
   *
   * @param query free-text name/description fragment, may be {@code null}
   * @param start inclusive lower bound on the operation's earliest mission planned start ({@code
   *     MIN(plannedStartTime)}), or {@code null} to disable
   * @param end inclusive upper bound on the operation's latest mission planned end ({@code
   *     MAX(plannedEndTime)}), or {@code null} to disable
   * @param status status list (string names of {@code OperationStatus}); always applied
   * @param isAdminAllScope {@code true} iff the caller is admin without an active selection
   * @param activeOrgUnitId pinned OrgUnit id, or {@code null}
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin path)
   * @param viewerIsMemberOrAbove {@code true} iff the caller is an authenticated organisation
   *     member-or-above; surfaces ownerless leadership operations ({@code owning_org_unit_id IS
   *     NULL}, V145) for members-or-above only
   * @param viewerUserId the caller's user id, or {@code null} for an anonymous caller; surfaces
   *     operations the caller participated in (#500)
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
      @Param("memberOrgUnitIds") java.util.Collection<UUID> memberOrgUnitIds,
      @Param("viewerIsMemberOrAbove") boolean viewerIsMemberOrAbove,
      @Param("viewerUserId") UUID viewerUserId,
      Pageable pageable);

  /**
   * {@code true} iff the given user is a participant of at least one mission linked to the
   * operation (#500). Backs the participant-visibility escape in {@code
   * OwnerScopeService.canSeeOperation}: an authenticated user who flew in one of the operation's
   * missions may view the operation (and their payout) regardless of its owning OrgUnit. Guest-name
   * participants (no {@code user}) never match.
   *
   * @param operationId the operation to test; never {@code null}.
   * @param userId the caller's user id ({@code AuthHelperService.currentUserId()} == {@code
   *     app_user.id}); never {@code null} (the caller guards the anonymous case).
   * @return {@code true} iff {@code userId} participated in any of the operation's missions.
   */
  @Query(
      """
      SELECT COUNT(p) > 0 FROM MissionParticipant p WHERE p.mission.operation.id = :operationId
      AND p.user.id = :userId
      """)
  boolean existsParticipantUserInOperation(
      @Param("operationId") UUID operationId, @Param("userId") UUID userId);
}
