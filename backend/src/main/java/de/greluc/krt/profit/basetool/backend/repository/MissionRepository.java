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

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Mission. */
@Repository
public interface MissionRepository
    extends JpaRepository<Mission, UUID>, MissionRepositoryAuthorizationFragment {

  /**
   * Returns slim {@link de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto}s for
   * the Lager mission pickers: every visible {@code PLANNED} / {@code ACTIVE} mission plus {@code
   * COMPLETED} / {@code CANCELLED} ones planned on or after {@code cutoff}, newest planned start
   * first.
   *
   * <p>Scoped by the org-unit scope-predicate triple; public missions stay visible across units,
   * and internal ownerless missions only to members or above.
   *
   * @param isAdminAllScope {@code true} iff the caller is admin without an active OrgUnit selection
   *     — disables the scope filter entirely.
   * @param activeOrgUnitId the single OrgUnit the caller is pinned to, or {@code null}.
   * @param memberOrgUnitIds the union of OrgUnits the caller belongs to (non-admin path); empty for
   *     admins and anonymous callers.
   * @param viewerIsMemberOrAbove {@code true} iff the caller is an organisation member or above;
   *     reveals internal ownerless missions.
   * @param cutoff inclusive lower bound on {@code plannedStartTime} for {@code COMPLETED} / {@code
   *     CANCELLED} missions; {@code PLANNED} / {@code ACTIVE} missions are returned regardless of
   *     it.
   * @return slim reference DTOs visible to the caller, ordered newest planned-start first.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto(m.id, m.name,
      m.status, m.plannedStartTime) FROM Mission m WHERE (
        m.status IN ('PLANNED', 'ACTIVE')
        OR (m.status IN ('COMPLETED', 'CANCELLED') AND m.plannedStartTime >= :cutoff)
      ) AND
      """
          + ScopeSpecifications.MISSION_SCOPE_PREDICATE
          + " ORDER BY m.plannedStartTime DESC NULLS LAST, m.name ASC")
  List<MissionReferenceDto> findAllActiveReference(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("viewerIsMemberOrAbove") boolean viewerIsMemberOrAbove,
      @Param("cutoff") Instant cutoff);

  /**
   * Loads a mission by id with its {@code participants} and their to-one associations fetched.
   *
   * <p>{@code assignedUnits} stays lazy to avoid a cartesian product; it batch-loads on first
   * access, which requires an open transaction.
   *
   * @param id the mission id
   * @return the mission with its participants pre-loaded, or empty when none exists
   */
  @Override
  @EntityGraph(
      attributePaths = {
        "participants",
        "participants.user",
        "participants.desiredMissionJobType",
        "participants.plannedMissionJobType"
      })
  Optional<Mission> findById(UUID id);

  /**
   * Initialises a mission's {@code assignedUnits} and every to-one the unit mapping reads in one
   * statement, into the current persistence context.
   *
   * <p>Call it after {@link #findById(UUID)}; it returns the same managed {@link Mission}.
   *
   * @param id the mission id
   * @return the mission (at most one element), with its unit graph initialised
   */
  @Query(
      "SELECT m FROM Mission m LEFT JOIN FETCH m.assignedUnits u"
          + " LEFT JOIN FETCH u.shipType ust LEFT JOIN FETCH ust.manufacturer"
          + " LEFT JOIN FETCH u.ship s LEFT JOIN FETCH s.shipType sst"
          + " LEFT JOIN FETCH sst.manufacturer LEFT JOIN FETCH s.location"
          + " LEFT JOIN FETCH s.owner LEFT JOIN FETCH u.responsibleUser"
          + " WHERE m.id = :id")
  List<Mission> fetchAssignedUnitGraph(@Param("id") UUID id);

  /**
   * Returns the next mission whose {@code plannedStartTime} is after {@code date} and whose status
   * is one of {@code statuses}.
   *
   * <p>Loads no collections; callers needing them re-fetch via {@link #findById(UUID)}.
   *
   * @param date exclusive lower bound on {@code plannedStartTime}
   * @param statuses the mission statuses to include (e.g. {@code PLANNED} / {@code ACTIVE})
   * @return the next matching mission, or empty when none upcoming
   */
  Optional<Mission> findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc(
      Instant date, Collection<String> statuses);

  /**
   * Returns upcoming missions owned by the caller's org units, soonest first, for the home-page
   * "next mission" banner (REQ-MISSION-008).
   *
   * <p>Narrows to {@code activeOrgUnitId} when set, else to {@code memberOrgUnitIds}; other units'
   * public missions are excluded, internal ones included. Loads no collections.
   *
   * @param now exclusive lower bound on {@code plannedStartTime}
   * @param statuses the mission statuses to include (e.g. {@code PLANNED} / {@code ACTIVE})
   * @param activeOrgUnitId the single pinned OrgUnit id, or {@code null} to use {@code
   *     memberOrgUnitIds}
   * @param memberOrgUnitIds the caller's effective (cascade-expanded) org-unit reach; consulted
   *     only when {@code activeOrgUnitId} is {@code null}
   * @param pageable limits the result to the head ({@code PageRequest.of(0, 1)})
   * @return the matching missions in soonest-first order (at most {@code pageable} size)
   */
  @Query(
      """
      SELECT m FROM Mission m WHERE m.plannedStartTime > :now AND m.status IN :statuses AND
      ((:activeOrgUnitId IS NOT NULL
      AND m.owningOrgUnit.id = :activeOrgUnitId) OR (:activeOrgUnitId IS NULL AND
      m.owningOrgUnit.id IN :memberOrgUnitIds)) ORDER BY m.plannedStartTime ASC
      """)
  List<Mission> findNextScopedMission(
      @Param("now") Instant now,
      @Param("statuses") Collection<String> statuses,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Paged mission search by text, date range, status and org-unit scope.
   *
   * <p>Filters are optional ({@code null} removes a clause) except {@code status}, which is always
   * applied. Scoped by the org-unit scope-predicate triple; public missions stay visible across
   * units, internal ownerless missions only to members or above. Fetches only {@code operation} and
   * {@code owningOrgUnit}, so pagination stays in SQL.
   */
  @EntityGraph(attributePaths = {"operation", "owningOrgUnit"})
  @Query(
      "SELECT m FROM Mission m WHERE "
          + ScopeSpecifications.MISSION_SCOPE_PREDICATE
          + " AND (CAST(:query AS string) IS NULL OR m.name ILIKE CONCAT('%', CAST(:query AS"
          + " string), '%') OR CAST(m.description AS string) ILIKE CONCAT('%', CAST(:query AS"
          + " string), '%')) AND (CAST(:start AS timestamp) IS NULL OR m.plannedStartTime >="
          + " :start) AND (CAST(:end AS timestamp) IS NULL OR m.plannedStartTime <= :end) AND"
          + " (m.status IN (:status)) AND (:isInternal IS NULL OR m.isInternal = :isInternal) AND"
          + " (CAST(:operationId AS uuid) IS NULL OR m.operation.id = :operationId)")
  Page<Mission> searchMissions(
      @Param("query") String query,
      @Param("start") Instant start,
      @Param("end") Instant end,
      @Param("status") List<String> status,
      @Param("isInternal") Boolean isInternal,
      @Param("operationId") UUID operationId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("viewerIsMemberOrAbove") boolean viewerIsMemberOrAbove,
      Pageable pageable);

  /**
   * Bulk-reassigns every mission owned by {@code oldUser} to {@code newUser}; used by the
   * user-merge flow so missions are preserved when two Keycloak accounts get consolidated.
   */
  @Modifying
  @Query("UPDATE Mission m SET m.owner = :newUser WHERE m.owner = :oldUser")
  void updateOwner(@NotNull User oldUser, @NotNull User newUser);

  /**
   * Removes the given user from every mission's manager set via direct delete on the join table.
   * Native query because Hibernate cannot bulk-delete a {@code @ManyToMany} association directly -
   * JPQL would require loading every mission first.
   */
  @Modifying
  @Query(value = "DELETE FROM mission_managers WHERE user_id = :userId", nativeQuery = true)
  void removeManager(@Param("userId") UUID userId);

  /**
   * Returns whether any mission of the operation lacks {@code actualStartTime} or {@code
   * actualEndTime}, i.e. whether the operation's payout figures are still preliminary.
   *
   * @param operationId the operation to inspect
   * @return {@code true} if at least one mission of the operation lacks {@code actualStartTime} or
   *     {@code actualEndTime}, {@code false} otherwise (including the empty-operation case)
   */
  @Query(
      """
      SELECT CASE WHEN COUNT(m) > 0 THEN TRUE ELSE FALSE END FROM Mission m WHERE m.operation.id =
      :operationId AND (m.actualStartTime IS NULL OR m.actualEndTime IS NULL)
      """)
  boolean existsByOperationIdWithUnfinishedActualTime(@Param("operationId") UUID operationId);

  /**
   * Atomically bumps {@code coreVersion} iff it still equals {@code expected}; the mission-core
   * section guard.
   *
   * @param id the mission id.
   * @param expected the core-section version the caller echoed back.
   * @return {@code 1} when the counter matched and was incremented, {@code 0} on a stale echo.
   */
  @Modifying
  @Query(
      "UPDATE Mission m SET m.coreVersion = m.coreVersion + 1 WHERE m.id = :id"
          + " AND m.coreVersion = :expected")
  int bumpCoreVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);

  /**
   * Atomically bumps {@code scheduleVersion} iff it still equals {@code expected}; the schedule
   * section guard.
   *
   * @param id the mission id.
   * @param expected the schedule-section version the caller echoed back.
   * @return {@code 1} when the counter matched and was incremented, {@code 0} on a stale echo.
   */
  @Modifying
  @Query(
      "UPDATE Mission m SET m.scheduleVersion = m.scheduleVersion + 1 WHERE m.id = :id"
          + " AND m.scheduleVersion = :expected")
  int bumpScheduleVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);

  /**
   * Atomically bumps {@code flagsVersion} iff it still equals {@code expected}; the flags section
   * guard.
   *
   * @param id the mission id.
   * @param expected the flags-section version the caller echoed back.
   * @return {@code 1} when the counter matched and was incremented, {@code 0} on a stale echo.
   */
  @Modifying
  @Query(
      "UPDATE Mission m SET m.flagsVersion = m.flagsVersion + 1 WHERE m.id = :id"
          + " AND m.flagsVersion = :expected")
  int bumpFlagsVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);

  /**
   * Atomically bumps {@code partyLeadVersion} iff it still equals {@code expected}; the party-lead
   * section guard.
   *
   * @param id the mission id.
   * @param expected the party-lead-section version the caller echoed back.
   * @return {@code 1} when the counter matched and was incremented, {@code 0} on a stale echo.
   */
  @Modifying
  @Query(
      "UPDATE Mission m SET m.partyLeadVersion = m.partyLeadVersion + 1 WHERE m.id = :id"
          + " AND m.partyLeadVersion = :expected")
  int bumpPartyLeadVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);

  /**
   * Atomically bumps {@code stepsVersion} iff it still equals {@code expected}; the Ablauf-steps
   * section guard that serialises concurrent step adds and reorders.
   *
   * @param id the mission id.
   * @param expected the steps-section version the caller echoed back.
   * @return {@code 1} when the counter matched and was incremented, {@code 0} on a stale echo.
   */
  @Modifying
  @Query(
      "UPDATE Mission m SET m.stepsVersion = m.stepsVersion + 1 WHERE m.id = :id"
          + " AND m.stepsVersion = :expected")
  int bumpStepsVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);

  /**
   * Atomically bumps {@code objectivesVersion} iff it still equals {@code expected}; the goals
   * (Ziele) section guard that serialises concurrent goal adds and reorders.
   *
   * @param id the mission id.
   * @param expected the goals-section version the caller echoed back.
   * @return {@code 1} when the counter matched and was incremented, {@code 0} on a stale echo.
   */
  @Modifying
  @Query(
      "UPDATE Mission m SET m.objectivesVersion = m.objectivesVersion + 1 WHERE m.id = :id"
          + " AND m.objectivesVersion = :expected")
  int bumpObjectivesVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);

  /**
   * Atomically bumps {@code owningOrgUnitVersion} iff it still equals {@code expected}; the
   * owning-org-unit reassignment guard.
   *
   * @param id the mission id.
   * @param expected the owning-org-unit-section version the caller echoed back.
   * @return {@code 1} when the counter matched and was incremented, {@code 0} on a stale echo.
   */
  @Modifying
  @Query(
      "UPDATE Mission m SET m.owningOrgUnitVersion = m.owningOrgUnitVersion + 1 WHERE m.id = :id"
          + " AND m.owningOrgUnitVersion = :expected")
  int bumpOwningOrgUnitVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);

  /**
   * Loads a mission with its {@code participants} under {@link
   * LockModeType#OPTIMISTIC_FORCE_INCREMENT} for the full-replace update ({@code PUT
   * /missions/{id}}), so two concurrent full overwrites conflict.
   *
   * @param id the mission id.
   * @return the mission with its participants loaded, under a forced version increment, or empty
   *     when unknown.
   */
  @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
  @EntityGraph(attributePaths = {"participants"})
  @Query("SELECT m FROM Mission m WHERE m.id = :id")
  Optional<Mission> findByIdForFullReplace(@Param("id") UUID id);
}
