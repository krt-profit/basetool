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

package de.greluc.krt.profit.basetool.backend.mission.api;

import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Mission. */
@Repository
public interface MissionRepository
    extends JpaRepository<Mission, UUID>, MissionRepositoryAuthorizationFragment {

  /**
   * Returns slim {@link de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto}s for
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
      SELECT new de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto(m.id, m.name,
      m.status, m.plannedStartTime) FROM Mission m WHERE (
        m.status IN ('PLANNED', 'ACTIVE')
        OR (m.status IN ('COMPLETED', 'CANCELLED') AND m.plannedStartTime >= :cutoff)
      ) AND
      """
          + MissionScope.PREDICATE
          + " ORDER BY m.plannedStartTime DESC NULLS LAST, m.name ASC")
  List<MissionReferenceDto> findAllActiveReference(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("viewerIsMemberOrAbove") boolean viewerIsMemberOrAbove,
      @Param("cutoff") Instant cutoff);

  /**
   * Projects the given missions to their reference DTOs in one read, whatever the caller's scope.
   *
   * @param ids the mission ids
   * @return the existing missions' references, in no particular order
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto(m.id, m.name,
      m.status, m.plannedStartTime) FROM Mission m WHERE m.id IN :ids
      """)
  List<MissionReferenceDto> findReferencesByIdIn(@Param("ids") Collection<UUID> ids);

  /**
   * Loads a mission by id with its {@code participants}, their to-one associations and their {@code
   * orgUnits} fetched.
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
        "participants.plannedMissionJobType",
        "participants.orgUnits"
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
      """
      SELECT m FROM Mission m LEFT JOIN FETCH m.assignedUnits u
      LEFT JOIN FETCH u.shipType ust LEFT JOIN FETCH ust.manufacturer
      LEFT JOIN FETCH u.ship s LEFT JOIN FETCH s.shipType sst
      LEFT JOIN FETCH sst.manufacturer LEFT JOIN FETCH s.location
      LEFT JOIN FETCH s.owner LEFT JOIN FETCH u.responsibleUser
      WHERE m.id = :id
      """)
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
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      Pageable pageable);

  /**
   * Paged mission search by text, date range, status and org-unit scope.
   *
   * <p>Filters are optional ({@code null} removes a clause) except {@code status}, which is always
   * applied. Scoped by the org-unit scope-predicate triple; public missions stay visible across
   * units, internal ownerless missions only to members or above. Fetches only {@code
   * owningOrgUnit}, so pagination stays in SQL.
   */
  @EntityGraph(attributePaths = {"owningOrgUnit"})
  @Query(
      "SELECT m FROM Mission m WHERE "
          + MissionScope.PREDICATE
          + " AND (CAST(:query AS string) IS NULL OR m.name ILIKE CONCAT('%', CAST(:query AS"
          + " string), '%') ESCAPE '\\' OR CAST(m.description AS string) ILIKE CONCAT('%',"
          + " CAST(:query AS string), '%') ESCAPE '\\') AND (CAST(:start AS timestamp) IS NULL OR"
          + " m.plannedStartTime >= :start) AND (CAST(:end AS timestamp) IS NULL OR"
          + " m.plannedStartTime <= :end) AND (m.status IN (:status)) AND (:isInternal IS NULL OR"
          + " m.isInternal = :isInternal) AND (CAST(:operationId AS uuid) IS NULL OR m.operationId"
          + " = :operationId)")
  Page<Mission> searchMissions(
      @Param("query") @Nullable String query,
      @Param("start") @Nullable Instant start,
      @Param("end") @Nullable Instant end,
      @Param("status") @Nullable List<String> status,
      @Param("isInternal") @Nullable Boolean isInternal,
      @Param("operationId") @Nullable UUID operationId,
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
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
   * Returns every mission linked to the operation, for the detach before the operation is deleted.
   *
   * @param operationId the operation whose missions are returned
   * @return the linked missions, empty when none is linked
   */
  @Query("SELECT m FROM Mission m WHERE m.operationId = :operationId")
  List<Mission> findAllLinkedToOperation(@Param("operationId") UUID operationId);

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
      SELECT CASE WHEN COUNT(m) > 0 THEN TRUE ELSE FALSE END FROM Mission m WHERE m.operationId =
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
      """
      UPDATE Mission m SET m.coreVersion = m.coreVersion + 1 WHERE m.id = :id
      AND m.coreVersion = :expected
      """)
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
      """
      UPDATE Mission m SET m.scheduleVersion = m.scheduleVersion + 1 WHERE m.id = :id
      AND m.scheduleVersion = :expected
      """)
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
      """
      UPDATE Mission m SET m.flagsVersion = m.flagsVersion + 1 WHERE m.id = :id
      AND m.flagsVersion = :expected
      """)
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
      """
      UPDATE Mission m SET m.partyLeadVersion = m.partyLeadVersion + 1 WHERE m.id = :id
      AND m.partyLeadVersion = :expected
      """)
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
      """
      UPDATE Mission m SET m.stepsVersion = m.stepsVersion + 1 WHERE m.id = :id
      AND m.stepsVersion = :expected
      """)
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
      """
      UPDATE Mission m SET m.objectivesVersion = m.objectivesVersion + 1 WHERE m.id = :id
      AND m.objectivesVersion = :expected
      """)
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
      """
      UPDATE Mission m SET m.owningOrgUnitVersion = m.owningOrgUnitVersion + 1 WHERE m.id = :id
      AND m.owningOrgUnitVersion = :expected
      """)
  int bumpOwningOrgUnitVersionIfMatches(@Param("id") UUID id, @Param("expected") long expected);

  /**
   * Returns the id of the member who owns a mission.
   *
   * @param id the mission
   * @return the owner's user id, or empty for an ownerless mission
   */
  @Query("SELECT m.owner.id FROM Mission m WHERE m.id = :id AND m.owner IS NOT NULL")
  Optional<UUID> findOwnerUserIdById(@Param("id") UUID id);

  /**
   * Returns the ids of the co-managers of a mission.
   *
   * @param id the mission
   * @return the managers' user ids; never {@code null}, possibly empty
   */
  @Query("SELECT u.id FROM Mission m JOIN m.managers u WHERE m.id = :id")
  Set<UUID> findManagerUserIdsById(@Param("id") UUID id);

  /**
   * Loads what the Lager shows of the given missions in one read (plan §7.5 P3-5d).
   *
   * @param ids the missions
   * @return the existing missions' id, name and planned start
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.inventory.api.MissionEarmark(
      m.id, m.name, m.plannedStartTime) FROM Mission m WHERE m.id IN :ids
      """)
  List<MissionEarmark> findEarmarks(@Param("ids") Collection<UUID> ids);

  /**
   * Returns the planned missions that reach the 24-hour mark in the given window and have not been
   * reminded of it yet (REQ-MISSION-022).
   *
   * @param from the earliest start time, inclusive
   * @param to the latest start time, inclusive
   * @return the missions; never {@code null}
   */
  @Query(
      """
      SELECT m FROM Mission m
      WHERE m.status = 'PLANNED' AND m.reminder24hSentAt IS NULL
        AND COALESCE(m.meetingTime, m.plannedStartTime) BETWEEN :from AND :to
      """)
  List<Mission> findDueForReminder24h(@Param("from") Instant from, @Param("to") Instant to);

  /**
   * Returns the planned missions that start within the given window and have not been reminded of
   * it yet (REQ-MISSION-022).
   *
   * @param from the earliest start time, exclusive
   * @param to the latest start time, inclusive
   * @return the missions; never {@code null}
   */
  @Query(
      """
      SELECT m FROM Mission m
      WHERE m.status = 'PLANNED' AND m.reminder1hSentAt IS NULL
        AND COALESCE(m.meetingTime, m.plannedStartTime) > :from
        AND COALESCE(m.meetingTime, m.plannedStartTime) <= :to
      """)
  List<Mission> findDueForReminder1h(@Param("from") Instant from, @Param("to") Instant to);

  /**
   * Returns the oldest active or completed missions whose planned end lies in {@code [notBefore,
   * cutoff)} and that have no actual end time and no never-ended notice yet (REQ-MISSION-025).
   *
   * @param notBefore the earliest planned end that is still announced, inclusive
   * @param cutoff the latest planned end that counts as overdue, exclusive
   * @param limit the most missions to return
   * @return the missions, oldest planned end first; never {@code null}
   */
  @Query(
      """
      SELECT m FROM Mission m
      WHERE m.status IN ('ACTIVE', 'COMPLETED') AND m.actualEndTime IS NULL
        AND m.neverEndedNotifiedAt IS NULL AND m.plannedEndTime IS NOT NULL
        AND m.plannedEndTime >= :notBefore AND m.plannedEndTime < :cutoff
      ORDER BY m.plannedEndTime, m.id
      """)
  List<Mission> findOverdueWithoutEnd(
      @Param("notBefore") Instant notBefore, @Param("cutoff") Instant cutoff, Limit limit);
}
