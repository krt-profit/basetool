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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Covers the manager-only check-in and check-out of every participant (REQ-MISSION-032). */
@ExtendWith(MockitoExtension.class)
class MissionParticipantBulkCheckInTest {

  private static final long ONE_HOUR_SECONDS = 3600;

  @Mock private MissionRepository missionRepository;
  @Mock private MissionEditor missionEditor;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private AuditService auditService;
  @Mock private MissionNotificationPublisher notificationPublisher;
  @InjectMocks private MissionParticipantService missionParticipantService;

  private final UUID missionId = UUID.randomUUID();
  private Mission mission;

  @BeforeEach
  void setUp() {
    MissionEditorFixture.delegate(missionEditor, missionRepository, auditService);
    mission = new Mission();
    mission.setId(missionId);
    mission.setName("Op");
  }

  @Test
  void checkInAll_stampsOnlyParticipantsNotYetCheckedIn_withOneSharedTime() {
    startMission();
    Instant earlier = Instant.now().minusSeconds(ONE_HOUR_SECONDS / 2);
    MissionParticipant first = participant(null, null);
    MissionParticipant second = participant(null, null);
    MissionParticipant alreadyIn = participant(earlier, null);

    missionParticipantService.checkInAll(missionId);

    assertThat(first.getStartTime()).isNotNull().isEqualTo(second.getStartTime());
    assertThat(alreadyIn.getStartTime()).isSameAs(earlier);
    verify(missionParticipantRepository).save(first);
    verify(missionParticipantRepository).save(second);
    verify(missionParticipantRepository, never()).save(alreadyIn);
    verify(missionParticipantRepository).flush();
    verify(notificationPublisher).checkedIn(mission, first);
    verify(notificationPublisher).checkedIn(mission, second);
    verify(auditService, times(2))
        .record(
            eq(AuditEventType.MISSION_PARTICIPANT_CHECKED_IN),
            eq(missionId),
            eq("Op"),
            any(),
            argThat(details -> details.toString().contains("bulk")));
  }

  @Test
  void checkOutAll_stampsOnlyCheckedInParticipantsWithoutEndTime() {
    startMission();
    Instant arrived = Instant.now().minusSeconds(ONE_HOUR_SECONDS / 2);
    Instant left = Instant.now().minusSeconds(ONE_HOUR_SECONDS / 4);
    MissionParticipant present = participant(arrived, null);
    MissionParticipant alsoPresent = participant(arrived, null);
    MissionParticipant neverCame = participant(null, null);
    MissionParticipant alreadyOut = participant(arrived, left);

    missionParticipantService.checkOutAll(missionId);

    assertThat(present.getEndTime()).isNotNull().isEqualTo(alsoPresent.getEndTime());
    assertThat(neverCame.getEndTime()).isNull();
    assertThat(alreadyOut.getEndTime()).isSameAs(left);
    verify(missionParticipantRepository, never()).save(neverCame);
    verify(missionParticipantRepository, never()).save(alreadyOut);
    verify(auditService, times(2))
        .record(
            eq(AuditEventType.MISSION_PARTICIPANT_CHECKED_OUT),
            eq(missionId),
            eq("Op"),
            any(),
            argThat(details -> details.toString().contains("bulk")));
  }

  @Test
  void bulkCheckInAndCheckOut_runBehindTheManageGate() {
    startMission();

    missionParticipantService.checkInAll(missionId);
    missionParticipantService.checkOutAll(missionId);

    verify(missionEditor, times(2)).editEach(eq(missionId), eq(MissionGate.MANAGE), any());
  }

  @Test
  void checkInAll_refusesAMissionThatHasNotStarted() {
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    MissionParticipant waiting = participant(null, null);

    assertThatThrownBy(() -> missionParticipantService.checkInAll(missionId))
        .isInstanceOf(BusinessConflictException.class);

    assertThat(waiting.getStartTime()).isNull();
    verifyNoInteractions(missionParticipantRepository, auditService, notificationPublisher);
  }

  @Test
  void checkOutAll_refusesAMissionThatHasEnded() {
    startMission();
    mission.setActualEndTime(Instant.now().minusSeconds(ONE_HOUR_SECONDS / 4));
    MissionParticipant present =
        participant(Instant.now().minusSeconds(ONE_HOUR_SECONDS / 2), null);

    assertThatThrownBy(() -> missionParticipantService.checkOutAll(missionId))
        .isInstanceOf(BusinessConflictException.class);

    assertThat(present.getEndTime()).isNull();
    verifyNoInteractions(missionParticipantRepository, auditService);
  }

  private void startMission() {
    mission.setActualStartTime(Instant.now().minusSeconds(ONE_HOUR_SECONDS));
    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
  }

  private @NotNull MissionParticipant participant(@Nullable Instant start, @Nullable Instant end) {
    MissionParticipant participant = new MissionParticipant();
    participant.setId(UUID.randomUUID());
    participant.setMission(mission);
    participant.setStartTime(start);
    participant.setEndTime(end);
    mission.getParticipants().add(participant);
    return participant;
  }
}
