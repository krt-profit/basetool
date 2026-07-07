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
import de.greluc.krt.profit.basetool.backend.model.MissionObjective;
import de.greluc.krt.profit.basetool.backend.model.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.backend.repository.MissionObjectiveRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
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
 * Unit tests for the goal (Ziel) mutators on {@link MissionTimelineService}: add / update / delete
 * / reorder. Pins the {@code objectivesVersion} optimistic-lock guard (409 on stale), the
 * contiguous {@code orderIndex} re-pack on delete, the single reorder audit event, and the
 * no-free-text rule (a goal title never enters the audit details payload, though the non-personal
 * kind enum may).
 */
@ExtendWith(MockitoExtension.class)
class MissionObjectiveServiceTest {

  @Mock private MissionRepository missionRepository;
  @Mock private MissionObjectiveRepository missionObjectiveRepository;
  @Mock private AuditService auditService;
  @InjectMocks private MissionTimelineService timelineService;

  private UUID missionId;
  private Mission mission;
  private UUID goal1Id;
  private UUID goal2Id;

  @BeforeEach
  void setUp() {
    missionId = UUID.randomUUID();
    mission = new Mission();
    mission.setId(missionId);
    mission.setName("RIF — Steinekloppen");
    mission.setObjectivesVersion(3L);

    goal1Id = UUID.randomUUID();
    goal2Id = UUID.randomUUID();
    mission.setObjectives(
        new LinkedHashSet<>(
            List.of(
                goal(goal1Id, "Erz sichern", MissionObjectiveKind.PRIMARY, 0),
                goal(goal2Id, "Keine Eskalation", MissionObjectiveKind.NON_GOAL, 1))));

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    // Since #1147 enforceSectionVersion runs the DB-enforced conditional bump; default it to "1 row
    // affected" (version matched) so happy paths pass. The stale-version test overrides it to 0.
    lenient()
        .when(missionRepository.bumpObjectivesVersionIfMatches(eq(missionId), anyLong()))
        .thenReturn(1);
  }

  private static MissionObjective goal(
      UUID id, String title, MissionObjectiveKind kind, int order) {
    MissionObjective o = new MissionObjective();
    o.setId(id);
    o.setTitle(title);
    o.setKind(kind);
    o.setOrderIndex(order);
    return o;
  }

  private List<MissionObjective> orderedObjectives() {
    return mission.getObjectives().stream()
        .sorted(Comparator.comparingInt(MissionObjective::getOrderIndex))
        .toList();
  }

  @Test
  void addObjective_appendsAtEnd_bumpsVersion_andRecordsAudit() {
    // When
    Mission result =
        timelineService.addObjective(
            missionId, "  Schutz der Crew  ", MissionObjectiveKind.SECONDARY, 3L);

    // Then
    assertEquals(3, result.getObjectives().size());
    MissionObjective added = orderedObjectives().get(2);
    assertEquals("Schutz der Crew", added.getTitle()); // trimmed
    assertEquals(MissionObjectiveKind.SECONDARY, added.getKind());
    assertEquals(2, added.getOrderIndex()); // appended after the two existing goals
    assertEquals(4L, result.getObjectivesVersion());
    verify(missionObjectiveRepository).save(any(MissionObjective.class));
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_OBJECTIVE_ADDED),
            eq(missionId),
            eq(mission.getName()),
            isNull(),
            any());
  }

  @Test
  void addObjective_throws409_whenObjectivesVersionStale() {
    when(missionRepository.bumpObjectivesVersionIfMatches(missionId, 2L)).thenReturn(0);
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> timelineService.addObjective(missionId, "Ziel", MissionObjectiveKind.PRIMARY, 2L));
  }

  @Test
  void updateObjective_editsTitleAndKind_bumpsVersion() {
    Mission result =
        timelineService.updateObjective(
            missionId, goal1Id, "Erz vollständig sichern", MissionObjectiveKind.SECONDARY, 3L);

    MissionObjective edited = orderedObjectives().get(0);
    assertEquals("Erz vollständig sichern", edited.getTitle());
    assertEquals(MissionObjectiveKind.SECONDARY, edited.getKind()); // reclassified
    assertEquals(4L, result.getObjectivesVersion());
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_OBJECTIVE_UPDATED), eq(missionId), any(), isNull(), any());
  }

  @Test
  void updateObjective_throwsNotFound_whenGoalNotChildOfMission() {
    assertThrows(
        NotFoundException.class,
        () ->
            timelineService.updateObjective(
                missionId, UUID.randomUUID(), "X", MissionObjectiveKind.PRIMARY, 3L));
  }

  @Test
  void deleteObjective_removesAndRepacksOrderIndex_andRecordsAudit() {
    // When the first goal is removed, the remaining goal must re-pack to orderIndex 0.
    Mission result = timelineService.deleteObjective(missionId, goal1Id, 3L);

    assertEquals(1, result.getObjectives().size());
    MissionObjective remaining = orderedObjectives().get(0);
    assertEquals(goal2Id, remaining.getId());
    assertEquals(0, remaining.getOrderIndex()); // re-packed from 1 -> 0
    assertEquals(4L, result.getObjectivesVersion());
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_OBJECTIVE_REMOVED), eq(missionId), any(), isNull(), any());
  }

  @Test
  void reorderObjectives_reassignsOrderIndex_andRecordsSingleEvent() {
    Mission result = timelineService.reorderObjectives(missionId, List.of(goal2Id, goal1Id), 3L);

    List<MissionObjective> ordered = orderedObjectives();
    assertEquals(goal2Id, ordered.get(0).getId());
    assertEquals(goal1Id, ordered.get(1).getId());
    assertEquals(4L, result.getObjectivesVersion());
    // Exactly one reorder event, carrying only a count (never a title).
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_OBJECTIVE_REORDERED),
            eq(missionId),
            any(),
            isNull(),
            details.capture());
    assertEquals("count=2", details.getValue().toString());
  }

  @Test
  void reorderObjectives_throwsIllegalArgument_whenIdSetDoesNotMatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> timelineService.reorderObjectives(missionId, List.of(goal1Id), 3L));
  }

  @Test
  void objectiveMutations_neverLeakTheGoalTitleIntoTheAuditDetails() {
    // The title is user free text — REQ-AUDIT-001 forbids it (and any PII) in the details payload.
    // The kind enum is a non-personal classification and IS allowed.
    timelineService.addObjective(
        missionId, "TOP-SECRET RALLY POINT", MissionObjectiveKind.PRIMARY, 3L);

    ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.MISSION_OBJECTIVE_ADDED),
            eq(missionId),
            label.capture(),
            isNull(),
            details.capture());
    assertEquals(mission.getName(), label.getValue()); // subject label is the mission name snapshot
    assertTrue(details.getValue().toString().contains("PRIMARY")); // kind is allowed
    assertTrue(!details.getValue().toString().contains("TOP-SECRET")); // title must not leak
    assertTrue(!details.getValue().toString().contains("RALLY"));
  }
}
