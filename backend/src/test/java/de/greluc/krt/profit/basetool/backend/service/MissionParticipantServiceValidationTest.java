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

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Guard tests for {@link MissionParticipantService}: the input validation of {@code
 * updateParticipantAttributes} and the roster cap of {@code addParticipant}, each throwing without
 * persisting a row.
 */
@ExtendWith(MockitoExtension.class)
class MissionParticipantServiceValidationTest {

  @Mock private MissionRepository missionRepository;

  @Mock private MissionSecurityService missionSecurityService;

  @Mock private org.springframework.security.core.Authentication authentication;

  @Mock private MissionParticipantRepository missionParticipantRepository;

  @Mock private JobTypeRepository jobTypeRepository;

  @Mock private UserRepository userRepository;

  @Mock private AuditService auditService;

  @InjectMocks private MissionParticipantService missionParticipantService;

  @Test
  void updateParticipantAttributes_rejectsStartBeforeMissionActualStart() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    mission.getParticipants().add(p);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                null,
                null,
                "comment",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                authentication));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsStartAfterEnd() {
    when(missionSecurityService.canManageLoadedMission(any(), any())).thenReturn(true);
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setActualStartTime(Instant.now().minusSeconds(7200));

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    mission.getParticipants().add(p);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    Instant start = Instant.now();
    Instant end = start.minusSeconds(3600);

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
                null,
                authentication));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsNonMissionArchetypeDesiredJobType() {
    when(missionSecurityService.canManageLoadedMission(any(), any())).thenReturn(true);
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
                null,
                authentication));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsNonMissionArchetypePlannedJobType() {
    when(missionSecurityService.canManageLoadedMission(any(), any())).thenReturn(true);
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    UUID plannedJobTypeId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    mission.getParticipants().add(p);

    JobType crewJobType = new JobType();
    crewJobType.setId(plannedJobTypeId);
    crewJobType.setName("Gunner");
    crewJobType.setArchetype(JobTypeArchetype.CREW);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(jobTypeRepository.findById(plannedJobTypeId)).thenReturn(Optional.of(crewJobType));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                null,
                plannedJobTypeId,
                "comment",
                null,
                null,
                null,
                null,
                null,
                null,
                authentication));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void addParticipant_rejectsWhenRosterAtCap() {
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

    assertThrows(
        BusinessConflictException.class,
        () ->
            missionParticipantService.addParticipant(
                missionId, userId, null, null, null, null, null));

    verify(missionParticipantRepository, never()).save(any(MissionParticipant.class));
  }

  /**
   * A caller who cannot manage the mission may not set the planned job type, which carries the
   * Einsatzleiter designation (REQ-MISSION-013).
   */
  @Test
  void updateParticipantAttributes_refusesPlannedJobTypeFromACallerWhoCannotManageTheMission() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    mission.getParticipants().add(p);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    assertThrows(
        org.springframework.security.access.AccessDeniedException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                null,
                UUID.randomUUID(),
                "comment",
                null,
                null,
                null,
                null,
                null,
                null,
                null));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  /**
   * A caller who cannot manage the mission leaves the planned job type unchanged, including when
   * sending {@code null}.
   */
  @Test
  void updateParticipantAttributes_doesNotClearThePlannedJobTypeForANonManagingCaller() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    JobType lead = new JobType();
    lead.setId(UUID.randomUUID());
    lead.setArchetype(JobTypeArchetype.MISSION);
    lead.setMissionLead(true);
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setPlannedMissionJobType(lead);
    p.setMissionLeadParticipant(true);
    mission.getParticipants().add(p);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionParticipantRepository.saveAndFlush(any(MissionParticipant.class)))
        .thenAnswer(i -> i.getArgument(0));

    missionParticipantService.updateParticipantAttributes(
        missionId, participantId, null, null, "comment", null, null, null, null, null, null, null);

    assertSame(lead, p.getPlannedMissionJobType());
    assertTrue(p.isMissionLeadParticipant());
  }

  /**
   * Audit MEDIUM-10: both anonymous CREATE paths refuse a guest name that resolves to a registered
   * member; the UPDATE path did not, so a guest could sign up under a throwaway name and then
   * rename the row to a member's byte-exact callsign.
   */
  @Test
  void updateParticipantAttributes_refusesRenamingAGuestRowOntoARegisteredMember() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setGuestName("zz-throwaway");
    mission.getParticipants().add(p);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(userRepository.findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase(
            "Bob Officer", "Bob Officer"))
        .thenReturn(java.util.List.of(new de.greluc.krt.profit.basetool.backend.model.User()));

    assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                null,
                null,
                "comment",
                null,
                null,
                null,
                null,
                "Bob Officer",
                null,
                null));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  /** ... and it must not collide with another guest of the same mission either. */
  @Test
  void updateParticipantAttributes_refusesRenamingOntoAnotherGuestOfTheSameMission() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    MissionParticipant mine = new MissionParticipant();
    mine.setId(participantId);
    mine.setMission(mission);
    mine.setGuestName("zz-throwaway");
    MissionParticipant other = new MissionParticipant();
    other.setId(UUID.randomUUID());
    other.setMission(mission);
    other.setGuestName("Dusty");
    mission.getParticipants().add(mine);
    mission.getParticipants().add(other);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(userRepository.findAllByUsernameIgnoreCaseOrDisplayNameIgnoreCase("Dusty", "Dusty"))
        .thenReturn(java.util.List.of());

    assertThrows(
        de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                participantId,
                null,
                null,
                "comment",
                null,
                null,
                null,
                null,
                "Dusty",
                null,
                null));
  }
}
