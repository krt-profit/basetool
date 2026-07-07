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
 * Owns the mission "Ablauf" timeline: the ordered {@link MissionStep}s (procedure phases) and the
 * {@link MissionObjective}s (Ziele). Extracted from {@code MissionService} (L1 step 2, #920) so the
 * steps/objectives responsibility no longer shares that god-class's dependencies.
 *
 * <p>Both sub-aggregates are edited under their own fine-grained optimistic-lock section counters
 * ({@code stepsVersion} / {@code objectivesVersion}) through {@code MissionSectionVersions}, so an
 * edit here never 409s a concurrent core/schedule/flags/participant edit (REQ-ORG-018). {@code
 * MissionService} keeps its public step/objective methods as thin delegations to this service, so
 * the controller and transaction boundaries are unchanged. Each mutator bumps only its own section
 * counter and mutates managed children via dirty-checking (no bulk {@code clearAutomatically}
 * detach), preserving the no-double-{@code @Version}-bump invariant.
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

  // --- Ablauf steps (procedure timeline) ---

  /**
   * Appends a step to the mission's Ablauf timeline. The new step lands at the end ({@code
   * orderIndex = max + 1}) and is initially not done. Validates and bumps the dedicated {@code
   * stepsVersion} section counter so a concurrent step edit surfaces as a 409 while never colliding
   * with a parallel core / schedule / flags edit.
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    enforceSectionVersion(
        missionRepository, mission, MissionSection.STEPS, expectedStepsVersion, missionId);

    MissionStep step = new MissionStep();
    step.setTitle(title == null ? null : title.trim());
    step.setMeta(normalizeStepMeta(meta));
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
   * Creates a step (Ablauf-Schritt) as part of the mission's initial create — no optimistic-lock
   * version check or bump, because the mission has just been persisted and has no concurrent editor
   * yet. The caller assigns a contiguous {@code orderIndex} (0..n-1 in create order). Mirrors
   * {@link #addStep} minus the section guard and runs inside the create transaction ({@code
   * MANDATORY}). Records a {@code MISSION_STEP_ADDED} audit event carrying the step id only — never
   * the title (user free text) — exactly like {@link #addStep}.
   *
   * @param mission the managed, already-persisted mission to append the step to
   * @param title the required step title
   * @param meta the optional free-text time/place hint
   * @param orderIndex the 0-based position to assign this step, in create order
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void addStepAtCreate(@NotNull Mission mission, String title, String meta, int orderIndex) {
    MissionStep step = new MissionStep();
    step.setTitle(title == null ? null : title.trim());
    step.setMeta(normalizeStepMeta(meta));
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
    enforceSectionVersion(
        missionRepository, mission, MissionSection.STEPS, expectedStepsVersion, missionId);

    MissionStep step = findStep(mission, stepId);
    step.setTitle(title == null ? null : title.trim());
    step.setMeta(normalizeStepMeta(meta));

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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
   * Reorders the mission's Ablauf steps. {@code orderedStepIds} must be exactly the mission's step
   * ids in the desired order; {@code orderIndex} is reassigned 0..n-1 by dirty-checking the managed
   * children (no per-child save, no bulk {@code clearAutomatically} query — so no detach/merge
   * double-version bump). Since #1147 the {@code stepsVersion} guard is DB-enforced: {@code
   * enforceSectionVersion} runs an atomic conditional {@code UPDATE … WHERE stepsVersion = ?} that
   * row-locks the mission, so concurrent reorders are genuinely serialised (the loser blocks,
   * re-reads the bumped counter and 409s) — making a pessimistic lock unnecessary — and the
   * deferrable unique {@code (mission_id, order_index)} index (V208) backstops any duplicate
   * ordinal. Records a single reorder event (count only, no titles).
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
   * Toggles a step's shared {@code done} flag to the requested state and bumps {@code
   * stepsVersion}. The single "current phase" (first not-done step) is derived on read, never
   * stored.
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
    return mission.getSteps().stream()
        .filter(s -> s.getId() != null && s.getId().equals(stepId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("MissionStep not found in this mission"));
  }

  /**
   * Normalises a step's optional time/place hint: trims surrounding whitespace and collapses blank
   * input to {@code null}.
   */
  private static String normalizeStepMeta(String meta) {
    return meta == null || meta.isBlank() ? null : meta.trim();
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

  // --- Mission goals (Ziele) ---

  /**
   * Appends a goal (Ziel) to a mission at the end of the list (next {@code orderIndex}) and bumps
   * {@code objectivesVersion}. Guarded by the dedicated goals-section counter so editing the goals
   * never collides with a concurrent core / schedule / flags / Ablauf edit. Records an audit event
   * carrying the goal id and kind only — never the title (user free text).
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
   * Creates a goal (Ziel) as part of the mission's initial create — no optimistic-lock version
   * check or bump, because the mission has just been persisted and has no concurrent editor yet.
   * The caller assigns a contiguous {@code orderIndex} (0..n-1 in create order). Mirrors {@link
   * #addObjective} minus the section guard and runs inside the create transaction ({@code
   * MANDATORY}). Records a {@code MISSION_OBJECTIVE_ADDED} audit event carrying the goal id and
   * kind only — never the title (user free text) — exactly like {@link #addObjective}.
   *
   * @param mission the managed, already-persisted mission to append the goal to
   * @param title the required goal text
   * @param kind the classification (primary / secondary / non-goal)
   * @param orderIndex the 0-based position to assign this goal, in create order
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
   * Reorders the mission's goals. {@code orderedObjectiveIds} must be exactly the mission's goal
   * ids in the desired order; {@code orderIndex} is reassigned 0..n-1 by dirty-checking the managed
   * children (no per-child save, no bulk {@code clearAutomatically} query — so no detach/merge
   * double-version bump). Since #1147 the {@code objectivesVersion} guard is DB-enforced: {@code
   * enforceSectionVersion} runs an atomic conditional {@code UPDATE … WHERE objectivesVersion = ?}
   * that row-locks the mission, so concurrent reorders are genuinely serialised (the loser blocks,
   * re-reads the bumped counter and 409s) — making a pessimistic lock unnecessary — and the
   * deferrable unique {@code (mission_id, order_index)} index (V208) backstops any duplicate
   * ordinal. Records a single reorder event (count only).
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
    Mission mission =
        missionRepository
            .findById(missionId)
            .orElseThrow(() -> new NotFoundException("Mission not found"));
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
    return mission.getObjectives().stream()
        .filter(o -> o.getId() != null && o.getId().equals(objectiveId))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("MissionObjective not found in this mission"));
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
