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
    // Given
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setActualStartTime(Instant.now().minusSeconds(3600));

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    mission.getParticipants().add(p);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    // When
    Mission updatedMission = missionParticipantService.checkIn(missionId, participantId);

    // Then
    MissionParticipant updatedParticipant = updatedMission.getParticipants().iterator().next();
    assertNotNull(updatedParticipant.getStartTime(), "Start time should be set");
    // Option A: parent Mission.version must NOT be bumped by a sub-section write.
    verify(missionRepository, never()).save(any(Mission.class));
    // #1135: check-in flushes so the slim DTO carries the committed participant @Version.
    verify(missionParticipantRepository).saveAndFlush(p);
  }

  @Test
  void repeatedCheckIn_preservesOriginalStartTime_andIsANoOp() {
    // #1134: a second check-in (stale crew board, duplicate delivery) must NOT reset startTime —
    // startTime feeds the credited-time payout breakdown, so a reset to a later now() would
    // silently
    // shrink the participant's credited duration and skew the money distribution.
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);
    mission.setActualStartTime(Instant.now().minusSeconds(7200));

    Instant originalArrival = Instant.now().minusSeconds(3600);
    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setStartTime(originalArrival); // already checked in earlier
    mission.getParticipants().add(p);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    // When — a second check-in arrives
    Mission updatedMission = missionParticipantService.checkIn(missionId, participantId);

    // Then — the original arrival time is preserved and the no-op neither persists nor re-audits
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
    // Given
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setStartTime(Instant.now().minusSeconds(3600));
    mission.getParticipants().add(p);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(missionParticipantRepository.findById(participantId)).thenReturn(Optional.of(p));

    // When
    Mission updatedMission = missionParticipantService.checkOut(missionId, participantId);

    // Then
    MissionParticipant updatedParticipant = updatedMission.getParticipants().iterator().next();
    assertNotNull(updatedParticipant.getEndTime(), "End time should be set");
    // Option A: parent Mission.version must NOT be bumped by a sub-section write.
    verify(missionRepository, never()).save(any(Mission.class));
    // #1135: check-out flushes so the slim DTO carries the committed participant @Version.
    verify(missionParticipantRepository).saveAndFlush(p);
  }

  @Test
  void shouldUpdatePayoutPreference() {
    // Given
    UUID missionId = UUID.randomUUID();
    UUID participantId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    MissionParticipant p = new MissionParticipant();
    p.setId(participantId);
    p.setMission(mission);
    p.setPayoutPreference(PayoutPreference.PAYOUT);
    mission.getParticipants().add(p);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));

    // When
    Mission updatedMission =
        missionParticipantService.updatePayoutPreference(
            missionId, participantId, PayoutPreference.DONATE);

    // Then
    MissionParticipant updatedParticipant = updatedMission.getParticipants().iterator().next();
    assertEquals(PayoutPreference.DONATE, updatedParticipant.getPayoutPreference());
    // Option A: parent Mission.version must NOT be bumped by a sub-section write.
    verify(missionRepository, never()).save(any(Mission.class));
    verify(missionParticipantRepository).save(p);
  }
}
