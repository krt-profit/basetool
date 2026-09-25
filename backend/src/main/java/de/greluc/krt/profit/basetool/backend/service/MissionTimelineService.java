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

import static de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.enforceSectionVersion;

import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionObjective;
import de.greluc.krt.profit.basetool.backend.model.MissionObjectiveKind;
import de.greluc.krt.profit.basetool.backend.model.MissionStep;
import de.greluc.krt.profit.basetool.backend.repository.MissionObjectiveRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionStepRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import de.greluc.krt.profit.basetool.backend.support.MissionSectionVersions.MissionSection;
import de.greluc.krt.profit.basetool.backend.support.StringNormalization;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages a mission's Ablauf timeline: its ordered {@link MissionStep}s and its {@link
 * MissionObjective}s.
 *
 * <p>Steps and objectives each have their own section counter ({@code stepsVersion} / {@code
 * objectivesVersion}), so edits here never collide with other mission sections (REQ-ORG-018).
 */
@Service
@RequiredArgsConstructor
public class MissionTimelineService {

  /** Repository for the mission aggregate root (fetch-or-throw + section-counter writeback). */
  private final MissionRepository missionRepository;

  /** Repository used to persist a newly appended {@link MissionStep} child. */
  private final MissionStepRepository missionStepRepository;

  /** Repository used to persist a newly appended {@link MissionObjective} child. */
  private final MissionObjectiveRepository missionObjectiveRepository;

  /** Records the state-mutating timeline activities into the audit log (REQ-AUDIT-001). */
  private final AuditService auditService;

  /**
   * Appends an undone step to the end of the mission's Ablauf timeline and bumps {@code
   * stepsVersion}.
   *
   * @param missionId the mission id
   * @param title the required step title
   * @param meta the optional free-text time/place hint
   * @param expectedStepsVersion the steps-section version the caller last saw
   * @return the managed mission
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission is
   *     unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedStepsVersion} is stale
   */
  @Transactional
  public Mission addStep(
      @NotNull UUID missionId, String title, String meta, @NotNull Long expectedStepsVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.STEPS, expectedStepsVersion, missionId);

    MissionStep step = new MissionStep();
    step.setTitle(title == null ? null : title.trim());
    step.setMeta(StringNormalization.trimToNull(meta));
    step.setDone(false);
    step.setOrderIndex(nextStepOrderIndex(mission));
    mission.addStep(step);
    missionStepRepository.save(step);

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_STEP_ADDED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("step", step.getId()));
    return mission;
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
  public void addStepAtCreate(@NotNull Mission mission, String title, String meta, int orderIndex) {
    MissionStep step = new MissionStep();
    step.setTitle(title == null ? null : title.trim());
    step.setMeta(StringNormalization.trimToNull(meta));
    step.setDone(false);
    step.setOrderIndex(orderIndex);
    mission.addStep(step);
    missionStepRepository.save(step);
    auditService.record(
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
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedStepsVersion} is stale
   */
  @Transactional
  public Mission updateStep(
      @NotNull UUID missionId,
      @NotNull UUID stepId,
      String title,
      String meta,
      @NotNull Long expectedStepsVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.STEPS, expectedStepsVersion, missionId);

    MissionStep step = findStep(mission, stepId);
    step.setTitle(title == null ? null : title.trim());
    step.setMeta(StringNormalization.trimToNull(meta));

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_STEP_UPDATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("step", stepId));
    return mission;
  }

  /**
   * Removes an Ablauf step and re-packs the remaining steps' {@code orderIndex} to 0..n-1 so the
   * timeline stays contiguous. Bumps {@code stepsVersion}.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the step is not
   *     a child of the mission
   */
  @Transactional
  public Mission deleteStep(
      @NotNull UUID missionId, @NotNull UUID stepId, @NotNull Long expectedStepsVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.STEPS, expectedStepsVersion, missionId);

    boolean removed = mission.removeStep(stepId);
    if (!removed) {
      throw new NotFoundException("MissionStep not found in this mission");
    }
    repackStepOrder(mission);

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_STEP_REMOVED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("step", stepId));
    return mission;
  }

  /**
   * Reorders the mission's Ablauf steps, reassigning {@code orderIndex} 0..n-1 under the atomically
   * enforced {@code stepsVersion} guard, and records one reorder event.
   *
   * @throws IllegalArgumentException when the id set does not match the mission's steps exactly
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedStepsVersion} is stale
   */
  @Transactional
  public Mission reorderSteps(
      @NotNull UUID missionId,
      @NotNull List<UUID> orderedStepIds,
      @NotNull Long expectedStepsVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.STEPS, expectedStepsVersion, missionId);

    Set<UUID> existingIds =
        mission.getSteps().stream().map(MissionStep::getId).collect(Collectors.toSet());
    if (orderedStepIds.size() != existingIds.size()
        || !existingIds.equals(new HashSet<>(orderedStepIds))) {
      throw new IllegalArgumentException("Reorder id set must match the mission's steps exactly");
    }

    Map<UUID, MissionStep> byId =
        mission.getSteps().stream().collect(Collectors.toMap(MissionStep::getId, s -> s));
    for (int i = 0; i < orderedStepIds.size(); i++) {
      byId.get(orderedStepIds.get(i)).setOrderIndex(i);
    }

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_STEP_REORDERED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("count", existingIds.size()));
    return mission;
  }

  /**
   * Sets a step's {@code done} flag and bumps {@code stepsVersion}.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the step is not
   *     a child of the mission
   */
  @Transactional
  public Mission toggleStepDone(
      @NotNull UUID missionId,
      @NotNull UUID stepId,
      boolean done,
      @NotNull Long expectedStepsVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository, mission, MissionSection.STEPS, expectedStepsVersion, missionId);

    MissionStep step = findStep(mission, stepId);
    step.setDone(done);

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_STEP_DONE_CHANGED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("step", stepId).with("done", done));
    return mission;
  }

  /**
   * Finds a managed step by id within the mission, or throws.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the step is not
   *     a child of the mission
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
   * Appends a goal (Ziel) to the end of the mission's goal list, bumps {@code objectivesVersion}
   * and records an audit event without the title.
   *
   * @param missionId the mission id
   * @param title the required goal text
   * @param kind the classification (primary / secondary / non-goal)
   * @param expectedObjectivesVersion the goals-section version the caller last saw
   * @return the managed mission
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the mission is
   *     unknown
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedObjectivesVersion} is stale
   */
  @Transactional
  public Mission addObjective(
      @NotNull UUID missionId,
      String title,
      @NotNull MissionObjectiveKind kind,
      @NotNull Long expectedObjectivesVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository,
        mission,
        MissionSection.OBJECTIVES,
        expectedObjectivesVersion,
        missionId);

    MissionObjective objective = new MissionObjective();
    objective.setTitle(title == null ? null : title.trim());
    objective.setKind(kind);
    objective.setOrderIndex(nextObjectiveOrderIndex(mission));
    mission.addObjective(objective);
    missionObjectiveRepository.save(objective);

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_OBJECTIVE_ADDED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("objective", objective.getId()).with("kind", kind));
    return mission;
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
    MissionObjective objective = new MissionObjective();
    objective.setTitle(title == null ? null : title.trim());
    objective.setKind(kind);
    objective.setOrderIndex(orderIndex);
    mission.addObjective(objective);
    missionObjectiveRepository.save(objective);
    auditService.record(
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
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedObjectivesVersion} is stale
   */
  @Transactional
  public Mission updateObjective(
      @NotNull UUID missionId,
      @NotNull UUID objectiveId,
      String title,
      @NotNull MissionObjectiveKind kind,
      @NotNull Long expectedObjectivesVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository,
        mission,
        MissionSection.OBJECTIVES,
        expectedObjectivesVersion,
        missionId);

    MissionObjective objective = findObjective(mission, objectiveId);
    objective.setTitle(title == null ? null : title.trim());
    objective.setKind(kind);

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_OBJECTIVE_UPDATED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("objective", objectiveId).with("kind", kind));
    return mission;
  }

  /**
   * Removes a goal and re-packs the remaining goals' {@code orderIndex} to 0..n-1 so the list stays
   * contiguous. Bumps {@code objectivesVersion}.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the goal is not
   *     a child of the mission
   */
  @Transactional
  public Mission deleteObjective(
      @NotNull UUID missionId, @NotNull UUID objectiveId, @NotNull Long expectedObjectivesVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository,
        mission,
        MissionSection.OBJECTIVES,
        expectedObjectivesVersion,
        missionId);

    boolean removed = mission.removeObjective(objectiveId);
    if (!removed) {
      throw new NotFoundException("MissionObjective not found in this mission");
    }
    repackObjectiveOrder(mission);

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_OBJECTIVE_REMOVED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("objective", objectiveId));
    return mission;
  }

  /**
   * Reorders the mission's goals, reassigning {@code orderIndex} 0..n-1 under the atomically
   * enforced {@code objectivesVersion} guard, and records one reorder event.
   *
   * @throws IllegalArgumentException when the id set does not match the mission's goals exactly
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when {@code
   *     expectedObjectivesVersion} is stale
   */
  @Transactional
  public Mission reorderObjectives(
      @NotNull UUID missionId,
      @NotNull List<UUID> orderedObjectiveIds,
      @NotNull Long expectedObjectivesVersion) {
    Mission mission = Entities.require(missionRepository.findById(missionId), "Mission not found");
    enforceSectionVersion(
        missionRepository,
        mission,
        MissionSection.OBJECTIVES,
        expectedObjectivesVersion,
        missionId);

    Set<UUID> existingIds =
        mission.getObjectives().stream().map(MissionObjective::getId).collect(Collectors.toSet());
    if (orderedObjectiveIds.size() != existingIds.size()
        || !existingIds.equals(new HashSet<>(orderedObjectiveIds))) {
      throw new IllegalArgumentException("Reorder id set must match the mission's goals exactly");
    }

    Map<UUID, MissionObjective> byId =
        mission.getObjectives().stream().collect(Collectors.toMap(MissionObjective::getId, o -> o));
    for (int i = 0; i < orderedObjectiveIds.size(); i++) {
      byId.get(orderedObjectiveIds.get(i)).setOrderIndex(i);
    }

    missionRepository.save(mission);
    auditService.record(
        AuditEventType.MISSION_OBJECTIVE_REORDERED,
        mission.getId(),
        mission.getName(),
        null,
        AuditDetails.of("count", existingIds.size()));
    return mission;
  }

  /**
   * Finds a managed goal by id within the mission, or throws.
   *
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the goal is not
   *     a child of the mission
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
