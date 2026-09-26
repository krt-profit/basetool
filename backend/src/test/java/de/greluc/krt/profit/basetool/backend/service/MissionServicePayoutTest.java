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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.repository.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MissionServicePayoutTest {

  @Mock private MissionRepository missionRepository;

  @Mock private MissionParticipantRepository missionParticipantRepository;

  @Mock private AuditService auditService;
  @InjectMocks private MissionParticipantService missionParticipantService;

  @Test
  void shouldCheckInParticipant() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setActualStartTime(Instant.now().minusSeconds(3600));

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    mission.getParticipants().add(p);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    Mission updatedMission = missionParticipantService.checkIn(missionId, participantId);

    MissionParticipant updatedParticipant = updatedMission.getParticipants().iterator().next();
    assertNotNull(updatedParticipant.getStartTime(), "Start time should be set");
    verify(missionRepository, never()).save(any(Mission.class));
    verify(missionParticipantRepository).saveAndFlush(p);
  }

  @Test
  void repeatedCheckIn_preservesOriginalStartTime_andIsANoOp() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setActualStartTime(Instant.now().minusSeconds(7200));

    Instant originalArrival = Instant.now().minusSeconds(3600);
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setStartTime(originalArrival);
    mission.getParticipants().add(p);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    Mission updatedMission = missionParticipantService.checkIn(missionId, participantId);

    MissionParticipant updatedParticipant = updatedMission.getParticipants().iterator().next();
    assertSame(
        originalArrival,
        updatedParticipant.getStartTime(),
        "repeated check-in must preserve the original arrival timestamp");
    verify(missionParticipantRepository, never()).save(any(MissionParticipant.class));
    verifyNoInteractions(auditService);
  }

  @Test
  void shouldCheckOutParticipant() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setStartTime(Instant.now().minusSeconds(3600));
    mission.getParticipants().add(p);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    Mission updatedMission = missionParticipantService.checkOut(missionId, participantId);

    MissionParticipant updatedParticipant = updatedMission.getParticipants().iterator().next();
    assertNotNull(updatedParticipant.getEndTime(), "End time should be set");
    verify(missionRepository, never()).save(any(Mission.class));
    verify(missionParticipantRepository).saveAndFlush(p);
  }

  @Test
  void shouldUpdatePayoutPreference() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setPayoutPreference(PayoutPreference.PAYOUT);
    mission.getParticipants().add(p);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    Mission updatedMission =
        missionParticipantService.updatePayoutPreference(
            missionId, participantId, PayoutPreference.DONATE);

    MissionParticipant updatedParticipant = updatedMission.getParticipants().iterator().next();
    assertEquals(PayoutPreference.DONATE, updatedParticipant.getPayoutPreference());
    verify(missionRepository, never()).save(any(Mission.class));
    verify(missionParticipantRepository).save(p);
  }

  @Test
  void checkOut_clampsEndTimeToActualEndTime_whenMissionAlreadyEnded() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Instant missionEnd = Instant.now().minusSeconds(3600);
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setActualEndTime(missionEnd);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setStartTime(Instant.now().minusSeconds(3 * 3600));
    mission.getParticipants().add(p);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    Mission updatedMission = missionParticipantService.checkOut(missionId, participantId);

    MissionParticipant updated = updatedMission.getParticipants().iterator().next();
    assertEquals(
        missionEnd,
        updated.getEndTime(),
        "late check-out must clamp end time to the mission's actual end time, not now()");
    verify(missionRepository, never()).save(any(Mission.class));
    verify(missionParticipantRepository).saveAndFlush(p);
  }

  @Test
  void checkOut_setsEndTimeToStartTime_whenParticipantStartedAfterMissionEnd() {
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setActualEndTime(Instant.now().minusSeconds(2 * 3600));

    Instant startAfterEnd = Instant.now().minusSeconds(3600);
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setStartTime(startAfterEnd);
    mission.getParticipants().add(p);

    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    Mission updatedMission = missionParticipantService.checkOut(missionId, participantId);

    MissionParticipant updated = updatedMission.getParticipants().iterator().next();
    assertEquals(
        startAfterEnd,
        updated.getEndTime(),
        "end time must never be set before the participant's own start time");
    verify(missionRepository, never()).save(any(Mission.class));
    verify(missionParticipantRepository).saveAndFlush(p);
  }
}
