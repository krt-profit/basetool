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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Guard-branch coverage for {@link MissionParticipantService}: the {@code
 * updateParticipantAttributes} input-validation rejections (pre-start guard, start-after-end guard,
 * non-{@code MISSION}-archetype desired/planned job type) and the {@code addParticipant} hard
 * roster cap (Audit finding M-4). All happy paths and the concurrency writeback semantics are
 * covered elsewhere ({@code MissionTimeTest}, {@code MissionServicePayoutTest}); this class asserts
 * only that each guard actually throws and that no participant row is persisted when it does.
 */
@ExtendWith(MockitoExtension.class)
class MissionParticipantServiceValidationTest {

  @Mock private MissionRepository missionRepository;

  @Mock private MissionParticipantRepository missionParticipantRepository;

  @Mock private JobTypeRepository jobTypeRepository;

  @Mock private AuditService auditService;

  @InjectMocks private MissionParticipantService missionParticipantService;

  @Test
  void updateParticipantAttributes_rejectsStartBeforeMissionActualStart() {
    // Setting a participant start time while the mission was never activated (actualStartTime null)
    // would let the credited window begin before the mission existed. The pre-start guard must
    // reject it and persist nothing.
    // Given
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    // mission.actualStartTime stays null → the mission has never been activated.

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission); // guest participant (no user) → no membership resolution
    mission.getParticipants().add(p);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    // When / Then
    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                null, // desiredMissionJobTypeId
                null, // plannedMissionJobTypeId
                "comment",
                Instant.now(), // startTime set while the mission has no actual start time
                null, // endTime
                null, // orgUnitIds
                null, // payoutPreference
                null, // guestName
                null)); // version — null skips the optimistic-lock check

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsStartAfterEnd() {
    // An inverted window (start after end) would store a negative credited duration and corrupt the
    // payout breakdown. The start-after-end guard must reject it.
    // Given
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setActualStartTime(Instant.now().minusSeconds(7200)); // activated → pre-start guard OK

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    mission.getParticipants().add(p);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    Instant start = Instant.now();
    Instant end = start.minusSeconds(3600); // end one hour before start → inverted window

    // When / Then
    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                null,
                null,
                "comment",
                start,
                end,
                null,
                null,
                null,
                null));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsNonMissionArchetypeDesiredJobType() {
    // A desired mission role must be a MISSION-archetype job type. A CREW (or any other) archetype
    // must be rejected so a non-mission role is never stored as a participant's desired role.
    // Given
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    UUID desiredJobTypeId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    mission.getParticipants().add(p);

    JobType crewJobType = new JobType();
    crewJobType.setId(desiredJobTypeId);
    crewJobType.setName("Gunner");
    crewJobType.setArchetype(JobTypeArchetype.CREW);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(jobTypeRepository.findById(desiredJobTypeId)).thenReturn(Optional.of(crewJobType));

    // When / Then
    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                desiredJobTypeId,
                null,
                "comment",
                null,
                null,
                null,
                null,
                null,
                null));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsNonMissionArchetypePlannedJobType() {
    // A planned mission role must also be a MISSION-archetype job type — a CREW archetype planned
    // role must be rejected so it cannot corrupt the role model / isMissionLead constraint.
    // Given
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    UUID plannedJobTypeId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission); // guest participant → guest org-unit branch, no membership lookup
    mission.getParticipants().add(p);

    JobType crewJobType = new JobType();
    crewJobType.setId(plannedJobTypeId);
    crewJobType.setName("Gunner");
    crewJobType.setArchetype(JobTypeArchetype.CREW);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(jobTypeRepository.findById(plannedJobTypeId)).thenReturn(Optional.of(crewJobType));

    // When / Then
    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                null, // desiredMissionJobTypeId — null so the desired branch is skipped
                plannedJobTypeId,
                "comment",
                null,
                null,
                null,
                null,
                null,
                null));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void addParticipant_rejectsWhenRosterAtCap() {
    // Audit finding M-4: the roster is capped at MAX_PARTICIPANTS_PER_MISSION to close the guest
    // sign-up DoS vector. A mission already at the cap must reject a further add with a
    // BusinessConflictException (409) and persist no new row.
    // Given
    UUID missionId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    for (int i = 0; i < MissionService.MAX_PARTICIPANTS_PER_MISSION; i++) {
      MissionParticipant existing = new MissionParticipant();
      existing.setId(UUID.randomUUID());
      existing.setMission(mission);
      mission.getParticipants().add(existing);
    }

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    UUID userId = UUID.randomUUID();

    // When / Then
    assertThrows(
        BusinessConflictException.class,
        () -> missionParticipantService.addParticipant(missionId, userId));

    verify(missionParticipantRepository, never()).save(any(MissionParticipant.class));
  }
}
