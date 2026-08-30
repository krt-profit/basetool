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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.model.JobType;
import de.greluc.krt.profit.basetool.backend.model.JobTypeArchetype;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.repository.JobTypeRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the "only one Einsatzleiter per mission" rule (REQ-MISSION-013): assigning the designated
 * mission-lead planned job type to a second participant is rejected with HTTP 409 ({@link
 * BusinessConflictException}); assigning it while no one else holds it succeeds.
 */
@ExtendWith(MockitoExtension.class)
class MissionEinsatzleiterConstraintTest {

  @Mock private MissionRepository missionRepository;

  @Mock private MissionSecurityService missionSecurityService;

  @Mock private org.springframework.security.core.Authentication authentication;
  @Mock private JobTypeRepository jobTypeRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private AuditService auditService;
  @InjectMocks private MissionParticipantService missionParticipantService;

  private UUID missionId;
  private Mission mission;
  private JobType einsatzleiter;
  private MissionParticipant p1;
  private MissionParticipant p2;

  @BeforeEach
  void setUp() {
    missionId = UUID.randomUUID();
    einsatzleiter = new JobType();
    einsatzleiter.setId(UUID.randomUUID());
    einsatzleiter.setName("Einsatzleiter");
    einsatzleiter.setArchetype(JobTypeArchetype.MISSION);
    einsatzleiter.setLeadershipRole(true);
    einsatzleiter.setMissionLead(true);

    // p1 is a guest already holding the Einsatzleiter role; p2 is the guest being edited.
    p1 = new MissionParticipant();
    p1.setId(UUID.randomUUID());
    p1.setPlannedMissionJobType(einsatzleiter);
    p2 = new MissionParticipant();
    p2.setId(UUID.randomUUID());
    p2.setVersion(0L);

    mission = new Mission();
    mission.setId(missionId);
    mission.setParticipants(new HashSet<>(java.util.List.of(p1, p2)));
  }

  @Test
  void assigningEinsatzleiterToASecondParticipant_throws409() {
    when(missionSecurityService.canManageLoadedMission(any(), any())).thenReturn(true);
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(jobTypeRepository.findById(einsatzleiter.getId())).thenReturn(Optional.of(einsatzleiter));

    assertThrows(
        BusinessConflictException.class,
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                p2.getId(),
                null,
                einsatzleiter.getId(),
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                authentication)); // the Einsatzleiter designation is manager-only
  }

  @Test
  void assigningEinsatzleiterWhenNobodyElseHoldsIt_succeeds() {
    when(missionSecurityService.canManageLoadedMission(any(), any())).thenReturn(true);
    p1.setPlannedMissionJobType(null); // nobody is the Einsatzleiter yet
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(jobTypeRepository.findById(einsatzleiter.getId())).thenReturn(Optional.of(einsatzleiter));

    assertDoesNotThrow(
        () ->
            missionParticipantService.updateParticipantAttributes(
                missionId,
                p2.getId(),
                null,
                einsatzleiter.getId(),
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                authentication)); // the Einsatzleiter designation is manager-only
  }
}
