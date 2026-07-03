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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionObjective;
import de.greluc.krt.profit.basetool.backend.model.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.backend.model.MissionStep;
import de.greluc.krt.profit.basetool.backend.repository.MissionObjectiveRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionStepRepository;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the create-time timeline seeders on {@link MissionTimelineService}: {@code
 * addObjectiveAtCreate} / {@code addStepAtCreate}. These run inside the mission-create transaction
 * on the just-persisted (managed) mission, so — unlike the post-create add mutators — they take the
 * mission directly (never re-{@code findById} it), assign the caller-supplied contiguous {@code
 * orderIndex}, and neither check nor bump the section version (no concurrent editor exists yet).
 * They still record the per-item audit event carrying only the id (plus the goal kind), never the
 * user-supplied title (REQ-AUDIT-001, no free text / PII in the details payload).
 */
@ExtendWith(MockitoExtension.class)
class MissionTimelineCreateSeedTest {

  @Mock private MissionRepository missionRepository;
  @Mock private MissionObjectiveRepository missionObjectiveRepository;
  @Mock private MissionStepRepository missionStepRepository;
  @Mock private AuditService auditService;
  @InjectMocks private MissionTimelineService timelineService;

  private UUID missionId;
  private Mission mission;

  @BeforeEach
  void setUp() {
    missionId = UUID.randomUUID();
    mission = new Mission();
    mission.setId(missionId);
    mission.setName("RIF — Steinekloppen");
    mission.setObjectives(new LinkedHashSet<>());
    mission.setSteps(new LinkedHashSet<>());
  }

  private List<MissionObjective> orderedObjectives() {
    return mission.getObjectives().stream()
        .sorted(Comparator.comparingInt(MissionObjective::getOrderIndex))
        .toList();
  }

  private List<MissionStep> orderedSteps() {
    return mission.getSteps().stream()
        .sorted(Comparator.comparingInt(MissionStep::getOrderIndex))
        .toList();
  }

  @Test
  void addObjectiveAtCreate_appendsAtGivenIndex_withoutBumpingVersion_neverRefetches_andAudits() {
    Long versionBefore = mission.getObjectivesVersion();

    timelineService.addObjectiveAtCreate(
        mission, "  Erz sichern  ", MissionObjectiveKind.PRIMARY, 0);
    timelineService.addObjectiveAtCreate(
        mission, "Rückzug decken", MissionObjectiveKind.SECONDARY, 1);

    List<MissionObjective> ordered = orderedObjectives();
    assertEquals(2, ordered.size());
    assertEquals("Erz sichern", ordered.get(0).getTitle()); // trimmed
    assertEquals(MissionObjectiveKind.PRIMARY, ordered.get(0).getKind());
    assertEquals(0, ordered.get(0).getOrderIndex());
    assertEquals(1, ordered.get(1).getOrderIndex());
    assertEquals(versionBefore, mission.getObjectivesVersion()); // never bumped at create
    verify(missionObjectiveRepository, times(2)).save(any(MissionObjective.class));
    verify(missionRepository, never()).findById(any());
    verify(auditService, times(2))
        .record(
            eq(AuditEventType.MISSION_OBJECTIVE_ADDED),
            eq(missionId),
            eq(mission.getName()),
            isNull(),
            any());
  }

  @Test
  void addStepAtCreate_appendsNotDoneAtGivenIndex_withoutBumpingVersion_andAudits() {
    Long versionBefore = mission.getStepsVersion();

    timelineService.addStepAtCreate(mission, "  Briefing  ", "  TS 20:00 ", 0);

    List<MissionStep> ordered = orderedSteps();
    assertEquals(1, ordered.size());
    assertEquals("Briefing", ordered.get(0).getTitle()); // trimmed
    assertEquals("TS 20:00", ordered.get(0).getMeta()); // normalized / trimmed
    assertEquals(0, ordered.get(0).getOrderIndex());
    assertFalse(ordered.get(0).isDone());
    assertEquals(versionBefore, mission.getStepsVersion()); // never bumped at create
    verify(missionStepRepository).save(any(MissionStep.class));
    verify(missionRepository, never()).findById(any());
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_STEP_ADDED),
            eq(missionId),
            eq(mission.getName()),
            isNull(),
            any());
  }

  @Test
  void seeders_neverLeakTheUserTitleIntoTheAuditDetails_butKeepTheGoalKind() {
    timelineService.addObjectiveAtCreate(
        mission, "TOP-SECRET RALLY POINT", MissionObjectiveKind.PRIMARY, 0);
    timelineService.addStepAtCreate(mission, "MEET AT THE SECRET BASE", null, 0);

    ArgumentCaptor<CharSequence> goalDetails = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_OBJECTIVE_ADDED),
            eq(missionId),
            any(),
            isNull(),
            goalDetails.capture());
    assertTrue(goalDetails.getValue().toString().contains("PRIMARY")); // kind is allowed
    assertFalse(goalDetails.getValue().toString().contains("SECRET")); // title must not leak

    ArgumentCaptor<CharSequence> stepDetails = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_STEP_ADDED),
            eq(missionId),
            any(),
            isNull(),
            stepDetails.capture());
    assertFalse(stepDetails.getValue().toString().contains("SECRET")); // title must not leak
  }
}
