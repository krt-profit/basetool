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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionStep;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionStepRepository;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Unit tests for the Ablauf (procedure step) mutators on {@link MissionTimelineService}: add /
 * update / delete / reorder / toggle-done. Pins the {@code stepsVersion} optimistic-lock guard (409
 * on stale), the contiguous {@code orderIndex} re-pack on delete, the single reorder audit event,
 * and the no-free-text rule (a step title / meta never enters the audit details payload).
 */
@ExtendWith(MockitoExtension.class)
class MissionStepServiceTest {

  @Mock private MissionRepository missionRepository;
  @Mock private MissionStepRepository missionStepRepository;
  @Mock private AuditService auditService;
  @InjectMocks private MissionTimelineService timelineService;

  private UUID missionId;
  private Mission mission;
  private UUID step1Id;
  private UUID step2Id;

  @BeforeEach
  void setUp() {
    missionId = UUID.randomUUID();
    mission = new Mission();
    mission.setId(missionId);
    mission.setName("RIF — Steinekloppen");
    mission.setStepsVersion(3L);

    step1Id = UUID.randomUUID();
    step2Id = UUID.randomUUID();
    mission.setSteps(
        new LinkedHashSet<>(List.of(step(step1Id, "Briefing", 0), step(step2Id, "Mining", 1))));

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    // Since #1147 enforceSectionVersion runs the DB-enforced conditional bump; default it to "1 row
    // affected" (version matched) so happy paths pass. The stale-version test overrides it to 0.
    lenient()
        .when(missionRepository.bumpStepsVersionIfMatches(eq(missionId), anyLong()))
        .thenReturn(1);
  }

  private static MissionStep step(UUID id, String title, int orderIndex) {
    MissionStep s = new MissionStep();
    s.setId(id);
    s.setTitle(title);
    s.setOrderIndex(orderIndex);
    return s;
  }

  private List<MissionStep> orderedSteps() {
    return mission.getSteps().stream()
        .sorted(Comparator.comparingInt(MissionStep::getOrderIndex))
        .toList();
  }

  @Test
  void addStep_appendsAtEnd_bumpsVersion_andRecordsAudit() {
    // When
    Mission result = timelineService.addStep(missionId, "  Eskorte  ", "  TS 20:00 ", 3L);

    // Then
    assertEquals(3, result.getSteps().size());
    MissionStep added = orderedSteps().get(2);
    assertEquals("Eskorte", added.getTitle()); // trimmed
    assertEquals("TS 20:00", added.getMeta()); // trimmed
    assertEquals(2, added.getOrderIndex()); // appended after the two existing steps
    assertFalse(added.isDone());
    assertEquals(4L, result.getStepsVersion());
    verify(missionStepRepository).save(any(MissionStep.class));
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_STEP_ADDED),
            eq(missionId),
            eq(mission.getName()),
            isNull(),
            any());
  }

  @Test
  void addStep_throws409_whenStepsVersionStale() {
    when(missionRepository.bumpStepsVersionIfMatches(missionId, 2L)).thenReturn(0);
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> timelineService.addStep(missionId, "Eskorte", null, 2L));
  }

  @Test
  void updateStep_editsTitleAndMeta_bumpsVersion() {
    Mission result =
        timelineService.updateStep(missionId, step1Id, "Lagebesprechung", "TS 19:30", 3L);

    MissionStep edited = orderedSteps().get(0);
    assertEquals("Lagebesprechung", edited.getTitle());
    assertEquals("TS 19:30", edited.getMeta());
    assertEquals(4L, result.getStepsVersion());
    verify(auditService)
        .record(eq(AuditEventType.MISSION_STEP_UPDATED), eq(missionId), any(), isNull(), any());
  }

  @Test
  void updateStep_throwsNotFound_whenStepNotChildOfMission() {
    assertThrows(
        NotFoundException.class,
        () -> timelineService.updateStep(missionId, UUID.randomUUID(), "X", null, 3L));
  }

  @Test
  void deleteStep_removesAndRepacksOrderIndex_andRecordsAudit() {
    // When the first step is removed, the remaining step must re-pack to orderIndex 0.
    Mission result = timelineService.deleteStep(missionId, step1Id, 3L);

    assertEquals(1, result.getSteps().size());
    MissionStep remaining = orderedSteps().get(0);
    assertEquals(step2Id, remaining.getId());
    assertEquals(0, remaining.getOrderIndex()); // re-packed from 1 -> 0
    assertEquals(4L, result.getStepsVersion());
    verify(auditService)
        .record(eq(AuditEventType.MISSION_STEP_REMOVED), eq(missionId), any(), isNull(), any());
  }

  @Test
  void reorderSteps_reassignsOrderIndex_andRecordsSingleEvent() {
    Mission result = timelineService.reorderSteps(missionId, List.of(step2Id, step1Id), 3L);

    List<MissionStep> ordered = orderedSteps();
    assertEquals(step2Id, ordered.get(0).getId());
    assertEquals(step1Id, ordered.get(1).getId());
    assertEquals(4L, result.getStepsVersion());
    // Exactly one reorder event, carrying only a count (never a title).
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_STEP_REORDERED),
            eq(missionId),
            any(),
            isNull(),
            details.capture());
    assertEquals("count=2", details.getValue().toString());
  }

  @Test
  void reorderSteps_throwsIllegalArgument_whenIdSetDoesNotMatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> timelineService.reorderSteps(missionId, List.of(step1Id), 3L));
  }

  @Test
  void toggleStepDone_setsFlag_bumpsVersion_andRecordsAudit() {
    Mission result = timelineService.toggleStepDone(missionId, step1Id, true, 3L);

    assertTrue(orderedSteps().get(0).isDone());
    assertEquals(4L, result.getStepsVersion());
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_STEP_DONE_CHANGED), eq(missionId), any(), isNull(), any());
  }

  @Test
  void stepMutations_neverLeakTheStepTitleIntoTheAuditDetails() {
    // The title is user free text — REQ-AUDIT-001 forbids it (and any PII) in the details payload.
    timelineService.addStep(missionId, "TOP-SECRET RALLY POINT", "classified", 3L);

    ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_STEP_ADDED),
            eq(missionId),
            label.capture(),
            isNull(),
            details.capture());
    assertEquals(mission.getName(), label.getValue()); // subject label is the mission name snapshot
    assertFalse(details.getValue().toString().contains("TOP-SECRET"));
    assertFalse(details.getValue().toString().contains("classified"));
  }
}
