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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionObjective;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionStep;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionSectionVersions.MissionSection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages a mission's Ablauf timeline: its ordered {@link MissionStep}s and its {@link
 * MissionObjective}s.
 *
 * <p>Steps and objectives each have their own section counter ({@code stepsVersion} / {@code
 * objectivesVersion}), so edits here never collide with other mission sections (REQ-ORG-018). Every
 * edit runs through {@link MissionEditor} behind the manage gate.
 */
@Service
@RequiredArgsConstructor
public class MissionTimelineService {

  /** Runs the load, gate, section check, save and audit of every timeline edit. */
  private final MissionEditor missionEditor;

  /** Repository used to persist a newly appended {@link MissionStep} child. */
  private final MissionStepRepository missionStepRepository;

  /** Repository used to persist a newly appended {@link MissionObjective} child. */
  private final MissionObjectiveRepository missionObjectiveRepository;

  /** Records the steps and goals a mission create seeds (REQ-AUDIT-001). */
  private final AuditRecorder auditRecorder;

  /**
   * Appends an undone step to the end of the mission's Ablauf timeline and bumps {@code
   * stepsVersion}.
   *
   * @param missionId the mission id
   * @param title the required step title
   * @param meta the optional free-text time/place hint
   * @param expectedStepsVersion the steps-section version the caller last saw
   * @return the managed mission
   */
  @Transactional
  public Mission addStep(
      @NotNull UUID missionId,
      String title,
      @Nullable String meta,
      @NotNull Long expectedStepsVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.STEPS,
        expectedStepsVersion,
        mission -> {
          MissionStep step = newStep(mission, title, meta, nextStepOrderIndex(mission));
          return MissionEdit.audited(
              AuditEventType.MISSION_STEP_ADDED, AuditDetails.of("step", step.getId()));
        });
  }

  /**
   * Creates a step within the mission's create transaction, without a version check or bump, and
   * records a {@code MISSION_STEP_ADDED} audit event.
   *
   * @param mission the managed, already-persisted mission to append the step to
   * @param title the required step title
   * @param meta the optional free-text time/place hint
   * @param orderIndex the 0-based position to assign this step
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void addStepAtCreate(
      @NotNull Mission mission, String title, @Nullable String meta, int orderIndex) {
    MissionStep step = newStep(mission, title, meta, orderIndex);
    auditRecorder.record(
        AuditEventType.MISSION_STEP_ADDED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("step", step.getId()));
  }

  /**
   * Edits an existing Ablauf step's title and time/place hint. Mutates the managed child via
   * dirty-checking (no explicit child save) and bumps {@code stepsVersion}.
   *
   * @param missionId the mission id
   * @param stepId the step to edit
   * @param title the new step title
   * @param meta the new time/place hint, or {@code null}
   * @param expectedStepsVersion the steps-section version the caller last saw
   * @return the managed mission
   * @throws NotFoundException when the step is not a child of the mission
   */
  @Transactional
  public Mission updateStep(
      @NotNull UUID missionId,
      @NotNull UUID stepId,
      String title,
      @Nullable String meta,
      @NotNull Long expectedStepsVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.STEPS,
        expectedStepsVersion,
        mission -> {
          MissionStep step = findStep(mission, stepId);
          step.setTitle(title == null ? null : title.trim());
          step.setMeta(StringNormalization.trimToNull(meta));
          return MissionEdit.audited(
              AuditEventType.MISSION_STEP_UPDATED, AuditDetails.of("step", stepId));
        });
  }

  /**
   * Removes an Ablauf step and re-packs the remaining steps' {@code orderIndex} to 0..n-1 so the
   * timeline stays contiguous. Bumps {@code stepsVersion}.
   *
   * @param missionId the mission id
   * @param stepId the step to remove
   * @param expectedStepsVersion the steps-section version the caller last saw
   * @return the managed mission
   * @throws NotFoundException when the step is not a child of the mission
   */
  @Transactional
  public Mission deleteStep(
      @NotNull UUID missionId, @NotNull UUID stepId, @NotNull Long expectedStepsVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.STEPS,
        expectedStepsVersion,
        mission -> {
          if (!mission.removeStep(stepId)) {
            throw new NotFoundException("MissionStep not found in this mission");
          }
          repackStepOrder(mission);
          return MissionEdit.audited(
              AuditEventType.MISSION_STEP_REMOVED, AuditDetails.of("step", stepId));
        });
  }

  /**
   * Reorders the mission's Ablauf steps, reassigning {@code orderIndex} 0..n-1 under the atomically
   * enforced {@code stepsVersion} guard, and records one reorder event.
   *
   * @param missionId the mission id
   * @param orderedStepIds every step id of the mission in the new order
   * @param expectedStepsVersion the steps-section version the caller last saw
   * @return the managed mission
   * @throws IllegalArgumentException when the id set does not match the mission's steps exactly
   */
  @Transactional
  public Mission reorderSteps(
      @NotNull UUID missionId,
      @NotNull List<UUID> orderedStepIds,
      @NotNull Long expectedStepsVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.STEPS,
        expectedStepsVersion,
        mission -> {
          Set<UUID> existingIds =
              mission.getSteps().stream().map(MissionStep::getId).collect(Collectors.toSet());
          if (orderedStepIds.size() != existingIds.size()
              || !existingIds.equals(new HashSet<>(orderedStepIds))) {
            throw new IllegalArgumentException(
                "Reorder id set must match the mission's steps exactly");
          }
          Map<UUID, MissionStep> byId =
              mission.getSteps().stream().collect(Collectors.toMap(MissionStep::getId, s -> s));
          for (int i = 0; i < orderedStepIds.size(); i++) {
            Objects.requireNonNull(byId.get(orderedStepIds.get(i))).setOrderIndex(i);
          }
          return MissionEdit.audited(
              AuditEventType.MISSION_STEP_REORDERED, AuditDetails.of("count", existingIds.size()));
        });
  }

  /**
   * Sets a step's {@code done} flag and bumps {@code stepsVersion}.
   *
   * @param missionId the mission id
   * @param stepId the step to toggle
   * @param done the new done flag
   * @param expectedStepsVersion the steps-section version the caller last saw
   * @return the managed mission
   * @throws NotFoundException when the step is not a child of the mission
   */
  @Transactional
  public Mission toggleStepDone(
      @NotNull UUID missionId,
      @NotNull UUID stepId,
      boolean done,
      @NotNull Long expectedStepsVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.STEPS,
        expectedStepsVersion,
        mission -> {
          findStep(mission, stepId).setDone(done);
          return MissionEdit.audited(
              AuditEventType.MISSION_STEP_DONE_CHANGED,
              AuditDetails.of("step", stepId).with("done", done));
        });
  }

  /**
   * Appends a goal (Ziel) to the end of the mission's goal list, bumps {@code objectivesVersion}
   * and records an audit event without the title.
   *
   * @param missionId the mission id
   * @param title the required goal text
   * @param kind the classification (primary / secondary / non-goal)
   * @param expectedObjectivesVersion the goals-section version the caller last saw
   * @return the managed mission
   */
  @Transactional
  public Mission addObjective(
      @NotNull UUID missionId,
      String title,
      @NotNull MissionObjectiveKind kind,
      @NotNull Long expectedObjectivesVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.OBJECTIVES,
        expectedObjectivesVersion,
        mission -> {
          MissionObjective objective =
              newObjective(mission, title, kind, nextObjectiveOrderIndex(mission));
          return MissionEdit.audited(
              AuditEventType.MISSION_OBJECTIVE_ADDED,
              AuditDetails.of("objective", objective.getId()).with("kind", kind));
        });
  }

  /**
   * Creates a goal within the mission's create transaction, without a version check or bump, and
   * records a {@code MISSION_OBJECTIVE_ADDED} audit event.
   *
   * @param mission the managed, already-persisted mission to append the goal to
   * @param title the required goal text
   * @param kind the classification (primary / secondary / non-goal)
   * @param orderIndex the 0-based position to assign this goal
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void addObjectiveAtCreate(
      @NotNull Mission mission, String title, @NotNull MissionObjectiveKind kind, int orderIndex) {
    MissionObjective objective = newObjective(mission, title, kind, orderIndex);
    auditRecorder.record(
        AuditEventType.MISSION_OBJECTIVE_ADDED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("objective", objective.getId()).with("kind", kind));
  }

  /**
   * Edits an existing goal's text and classification. Mutates the managed child via dirty-checking
   * (no explicit child save) and bumps {@code objectivesVersion}.
   *
   * @param missionId the mission id
   * @param objectiveId the goal to edit
   * @param title the new goal text
   * @param kind the new classification
   * @param expectedObjectivesVersion the goals-section version the caller last saw
   * @return the managed mission
   * @throws NotFoundException when the goal is not a child of the mission
   */
  @Transactional
  public Mission updateObjective(
      @NotNull UUID missionId,
      @NotNull UUID objectiveId,
      String title,
      @NotNull MissionObjectiveKind kind,
      @NotNull Long expectedObjectivesVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.OBJECTIVES,
        expectedObjectivesVersion,
        mission -> {
          MissionObjective objective = findObjective(mission, objectiveId);
          objective.setTitle(title == null ? null : title.trim());
          objective.setKind(kind);
          return MissionEdit.audited(
              AuditEventType.MISSION_OBJECTIVE_UPDATED,
              AuditDetails.of("objective", objectiveId).with("kind", kind));
        });
  }

  /**
   * Removes a goal and re-packs the remaining goals' {@code orderIndex} to 0..n-1 so the list stays
   * contiguous. Bumps {@code objectivesVersion}.
   *
   * @param missionId the mission id
   * @param objectiveId the goal to remove
   * @param expectedObjectivesVersion the goals-section version the caller last saw
   * @return the managed mission
   * @throws NotFoundException when the goal is not a child of the mission
   */
  @Transactional
  public Mission deleteObjective(
      @NotNull UUID missionId, @NotNull UUID objectiveId, @NotNull Long expectedObjectivesVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.OBJECTIVES,
        expectedObjectivesVersion,
        mission -> {
          if (!mission.removeObjective(objectiveId)) {
            throw new NotFoundException("MissionObjective not found in this mission");
          }
          repackObjectiveOrder(mission);
          return MissionEdit.audited(
              AuditEventType.MISSION_OBJECTIVE_REMOVED, AuditDetails.of("objective", objectiveId));
        });
  }

  /**
   * Reorders the mission's goals, reassigning {@code orderIndex} 0..n-1 under the atomically
   * enforced {@code objectivesVersion} guard, and records one reorder event.
   *
   * @param missionId the mission id
   * @param orderedObjectiveIds every goal id of the mission in the new order
   * @param expectedObjectivesVersion the goals-section version the caller last saw
   * @return the managed mission
   * @throws IllegalArgumentException when the id set does not match the mission's goals exactly
   */
  @Transactional
  public Mission reorderObjectives(
      @NotNull UUID missionId,
      @NotNull List<UUID> orderedObjectiveIds,
      @NotNull Long expectedObjectivesVersion) {
    return missionEditor.editSection(
        missionId,
        MissionGate.MANAGE,
        MissionSection.OBJECTIVES,
        expectedObjectivesVersion,
        mission -> {
          Set<UUID> existingIds =
              mission.getObjectives().stream()
                  .map(MissionObjective::getId)
                  .collect(Collectors.toSet());
          if (orderedObjectiveIds.size() != existingIds.size()
              || !existingIds.equals(new HashSet<>(orderedObjectiveIds))) {
            throw new IllegalArgumentException(
                "Reorder id set must match the mission's goals exactly");
          }
          Map<UUID, MissionObjective> byId =
              mission.getObjectives().stream()
                  .collect(Collectors.toMap(MissionObjective::getId, o -> o));
          for (int i = 0; i < orderedObjectiveIds.size(); i++) {
            Objects.requireNonNull(byId.get(orderedObjectiveIds.get(i))).setOrderIndex(i);
          }
          return MissionEdit.audited(
              AuditEventType.MISSION_OBJECTIVE_REORDERED,
              AuditDetails.of("count", existingIds.size()));
        });
  }

  private MissionStep newStep(
      @NotNull Mission mission, String title, @Nullable String meta, int orderIndex) {
    MissionStep step = new MissionStep();
    step.setTitle(title == null ? null : title.trim());
    step.setMeta(StringNormalization.trimToNull(meta));
    step.setDone(false);
    step.setOrderIndex(orderIndex);
    mission.addStep(step);
    missionStepRepository.save(step);
    return step;
  }

  private MissionObjective newObjective(
      @NotNull Mission mission, String title, @NotNull MissionObjectiveKind kind, int orderIndex) {
    MissionObjective objective = new MissionObjective();
    objective.setTitle(title == null ? null : title.trim());
    objective.setKind(kind);
    objective.setOrderIndex(orderIndex);
    mission.addObjective(objective);
    missionObjectiveRepository.save(objective);
    return objective;
  }

  /**
   * Finds a managed step by id within the mission, or throws.
   *
   * @throws NotFoundException when the step is not a child of the mission
   */
  private static MissionStep findStep(@NotNull Mission mission, @NotNull UUID stepId) {
    return Entities.require(
        mission.getSteps().stream()
            .filter(s -> s.getId() != null && s.getId().equals(stepId))
            .findFirst(),
        "MissionStep not found in this mission");
  }

  /** Returns the {@code orderIndex} to assign a newly appended step (max existing + 1, or 0). */
  private static int nextStepOrderIndex(@NotNull Mission mission) {
    int max = -1;
    for (MissionStep s : mission.getSteps()) {
      max = Math.max(max, s.getOrderIndex());
    }
    return max + 1;
  }

  /** Re-assigns the remaining steps' {@code orderIndex} to a contiguous 0..n-1 by current order. */
  private static void repackStepOrder(@NotNull Mission mission) {
    List<MissionStep> ordered = new ArrayList<>(mission.getSteps());
    ordered.sort(Comparator.comparingInt(MissionStep::getOrderIndex));
    for (int i = 0; i < ordered.size(); i++) {
      ordered.get(i).setOrderIndex(i);
    }
  }

  /**
   * Finds a managed goal by id within the mission, or throws.
   *
   * @throws NotFoundException when the goal is not a child of the mission
   */
  private static MissionObjective findObjective(
      @NotNull Mission mission, @NotNull UUID objectiveId) {
    return Entities.require(
        mission.getObjectives().stream()
            .filter(o -> o.getId() != null && o.getId().equals(objectiveId))
            .findFirst(),
        "MissionObjective not found in this mission");
  }

  /** Returns the {@code orderIndex} to assign a newly appended goal (max existing + 1, or 0). */
  private static int nextObjectiveOrderIndex(@NotNull Mission mission) {
    int max = -1;
    for (MissionObjective o : mission.getObjectives()) {
      max = Math.max(max, o.getOrderIndex());
    }
    return max + 1;
  }

  /** Re-assigns the remaining goals' {@code orderIndex} to a contiguous 0..n-1 by current order. */
  private static void repackObjectiveOrder(@NotNull Mission mission) {
    List<MissionObjective> ordered = new ArrayList<>(mission.getObjectives());
    ordered.sort(Comparator.comparingInt(MissionObjective::getOrderIndex));
    for (int i = 0; i < ordered.size(); i++) {
      ordered.get(i).setOrderIndex(i);
    }
  }
}
