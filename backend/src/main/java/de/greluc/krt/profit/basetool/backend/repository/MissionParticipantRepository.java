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
import de.greluc.krt.profit.basetool.backend.model.projection.MissionParticipantCount;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for Mission Participant. */
public interface MissionParticipantRepository extends JpaRepository<MissionParticipant, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code MissionIdAndUserId}. */
  Optional<MissionParticipant> findByMissionIdAndUserId(UUID missionId, UUID userId);

  /**
   * Returns whether any participant is affiliated with the given org unit (Staffel or
   * Spezialkommando).
   *
   * @param orgUnitId the org-unit id to check for any participant affiliation.
   * @return {@code true} iff at least one participant references the org unit.
   */
  @Query(
      "SELECT COUNT(mp) > 0 FROM MissionParticipant mp JOIN mp.orgUnits ou WHERE ou.id ="
          + " :orgUnitId")
  boolean existsByOrgUnitId(@Param("orgUnitId") UUID orgUnitId);

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
  @Modifying
  @Query("UPDATE MissionParticipant mp SET mp.user = null WHERE mp.user.id = :userId")
  void unlinkUser(@Param("userId") UUID userId);

  /**
   * Clears the {@code is_mission_lead_participant} flag on every participant whose planned job type
   * is the given job type, once it no longer carries the Einsatzleiter designation.
   *
   * @param jobTypeId the job type that is no longer the mission lead.
   * @return the number of participant rows whose flag was cleared.
   */
  @Modifying
  @Query(
      "UPDATE MissionParticipant p SET p.missionLeadParticipant = false "
          + "WHERE p.plannedMissionJobType.id = :jobTypeId AND p.missionLeadParticipant = true")
  int clearMissionLeadFlagForJobType(@Param("jobTypeId") UUID jobTypeId);

  /**
   * Clamps the {@code endTime} of every checked-in participant of a mission to {@code end} in one
   * statement, bumping each touched row's {@code version}.
   *
   * <p>Touches only rows with a {@code startTime} whose {@code endTime} is unset or later than
   * {@code end}. Flushes first and keeps the persistence context.
   *
   * @param missionId the mission whose checked-in participants to clamp.
   * @param end the mission's actual end time to clamp late/open check-outs to.
   * @return the number of participant rows clamped.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      "UPDATE MissionParticipant p SET p.endTime = :end, p.version = p.version + 1 "
          + "WHERE p.mission.id = :missionId AND p.startTime IS NOT NULL "
          + "AND (p.endTime IS NULL OR p.endTime > :end)")
  int clampCheckedInEndTimes(@Param("missionId") UUID missionId, @Param("end") Instant end);

  /**
   * Returns participant counts for a page of missions in one grouped query.
   *
   * <p>Missions without participants produce no row.
   *
   * @param missionIds the missions on the page; an empty collection yields an empty list.
   * @return one count row per mission that has at least one participant.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.MissionParticipantCount(
        p.mission.id, COUNT(p))
      FROM MissionParticipant p WHERE p.mission.id IN :missionIds GROUP BY p.mission.id
      """)
  List<MissionParticipantCount> countByMissions(@Param("missionIds") Collection<UUID> missionIds);
}
