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

  @Mock private MissionSecurityService missionSecurityService;

  @Mock private org.springframework.security.core.Authentication authentication;

  @Mock private MissionParticipantRepository missionParticipantRepository;

  @Mock private JobTypeRepository jobTypeRepository;

  @Mock private UserRepository userRepository;

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
                null, // version — null skips the optimistic-lock check
                authentication)); // the manager path; the gate is answered inside the service

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsStartAfterEnd() {
    when(missionSecurityService.canManageLoadedMission(any(), any())).thenReturn(true);
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
                null,
                authentication));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsNonMissionArchetypeDesiredJobType() {
    when(missionSecurityService.canManageLoadedMission(any(), any())).thenReturn(true);
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
                null,
                authentication));

    verify(missionParticipantRepository, never()).saveAndFlush(any(MissionParticipant.class));
  }

  @Test
  void updateParticipantAttributes_rejectsNonMissionArchetypePlannedJobType() {
    when(missionSecurityService.canManageLoadedMission(any(), any())).thenReturn(true);
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
                null,
                authentication));

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

  /**
   * REQ-MISSION-013 / audit MEDIUM-9: the planned mission job type is the organisation's assignment
   * - it carries the Einsatzleiter designation - and is not part of a guest's payload.
   *
   * <p>Before this gate the block had no caller distinction at all, so a guest presenting their
   * row's capability token could designate themselves Einsatzleiter; the single-lead rule then
   * blocked the real leader with a 409 until somebody cleared the guest row.
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
                UUID.randomUUID(), // plannedMissionJobTypeId - manager-only
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
   * The symmetric half, which matters just as much: a {@code null} used to CLEAR the designation,
   * so an ordinary guest edit silently undid a manager's assignment. A caller who may not manage
   * the mission must leave the field exactly as it was.
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
