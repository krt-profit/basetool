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

import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for Mission Participant. */
public interface MissionParticipantRepository extends JpaRepository<MissionParticipant, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code MissionIdAndUserId}. */
  Optional<MissionParticipant> findByMissionIdAndUserId(UUID missionId, UUID userId);

  /**
   * Returns {@code true} iff at least one participant is affiliated with the org unit (Staffel or
   * Spezialkommando) of the given id, traversing the {@code orgUnits} many-to-many. Replaces the
   * former {@code existsBySquadronId} after the participant affiliation moved from the single
   * {@code squadron_id} FK to the {@code mission_participant_org_unit} join.
   *
   * @param orgUnitId the org-unit id to check for any participant affiliation.
   * @return {@code true} iff at least one participant references the org unit.
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT COUNT(mp) > 0 FROM MissionParticipant mp JOIN mp.orgUnits ou WHERE ou.id ="
          + " :orgUnitId")
  boolean existsByOrgUnitId(
      @org.springframework.data.repository.query.Param("orgUnitId") UUID orgUnitId);

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * DesiredMissionJobTypeId}.
   */
  boolean existsByDesiredMissionJobTypeId(UUID jobTypeId);

  /** Derived Spring-Data query - returns entities matching {@code DesiredMissionJobTypeId}. */
  List<MissionParticipant> findByDesiredMissionJobTypeId(UUID jobTypeId);

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * PlannedMissionJobTypeId}.
   */
  boolean existsByPlannedMissionJobTypeId(UUID jobTypeId);

  /** Derived Spring-Data query - returns entities matching {@code PlannedMissionJobTypeId}. */
  List<MissionParticipant> findByPlannedMissionJobTypeId(UUID jobTypeId);

  /**
   * Bulk-clears the {@code user} reference on every mission participant linked to the given user;
   * used by the user-delete flow so mission history (guest name, status) survives but the personal
   * link is removed.
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE MissionParticipant mp SET mp.user = null WHERE mp.user.id = :userId")
  void unlinkUser(@org.springframework.data.repository.query.Param("userId") java.util.UUID userId);

  /**
   * Clears the derived {@code is_mission_lead_participant} flag on every participant whose planned
   * job type is the given (now no-longer-mission-lead) job type. Called when an admin moves or
   * removes the single Einsatzleiter designation from a job type ({@code
   * JobTypeService.applyMissionLeadDesignation}) so a stale flag on a demoted type cannot falsely
   * trip the {@code uq_mission_participant_single_lead} partial unique index and block a legitimate
   * new lead assignment (#1113). Bulk update — the participant collections it touches are not
   * loaded in the job-type transaction, so no persistence-context reconciliation is needed.
   *
   * @param jobTypeId the job type that is no longer the mission lead.
   * @return the number of participant rows whose flag was cleared.
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "UPDATE MissionParticipant p SET p.missionLeadParticipant = false "
          + "WHERE p.plannedMissionJobType.id = :jobTypeId AND p.missionLeadParticipant = true")
  int clearMissionLeadFlagForJobType(
      @org.springframework.data.repository.query.Param("jobTypeId") UUID jobTypeId);

  /**
   * Atomically clamps the {@code endTime} of every checked-in participant of a mission to {@code
   * end} in a single set-based statement, bumping each touched row's {@code @Version} so it stays
   * consistent. Used by the schedule-close path when {@code actualEndTime} is set, replacing the
   * per-row entity loop that (a) 409'd the whole schedule write if any of up-to-500 rows was
   * concurrently modified and (b) 409'd concurrent check-outs at their own commit (#1146, the
   * CLAUDE.md bulk-update rule). Only rows still checked in ({@code startTime} set) and either not
   * yet checked out or checked out later than {@code end} are touched. {@code flushAutomatically}
   * so a pending schedule-scalar change lands first; deliberately NOT {@code clearAutomatically} —
   * the managed mission is still needed by the caller after this runs.
   *
   * @param missionId the mission whose checked-in participants to clamp.
   * @param end the mission's actual end time to clamp late/open check-outs to.
   * @return the number of participant rows clamped.
   */
  @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
  @org.springframework.data.jpa.repository.Query(
      "UPDATE MissionParticipant p SET p.endTime = :end, p.version = p.version + 1 "
          + "WHERE p.mission.id = :missionId AND p.startTime IS NOT NULL "
          + "AND (p.endTime IS NULL OR p.endTime > :end)")
  int clampCheckedInEndTimes(
      @org.springframework.data.repository.query.Param("missionId") UUID missionId,
      @org.springframework.data.repository.query.Param("end") java.time.Instant end);
}
