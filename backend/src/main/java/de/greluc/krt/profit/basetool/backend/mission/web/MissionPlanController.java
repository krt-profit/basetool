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

package de.greluc.krt.profit.basetool.backend.mission.web;

import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionObjective;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionObjectiveDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionStep;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionStepDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.AddMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.AddMissionStepRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionTimelineService;
import de.greluc.krt.profit.basetool.backend.mission.internal.ReorderMissionObjectivesRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.ReorderMissionStepsRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.ToggleMissionStepRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateMissionStepRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Mission step and objective HTTP handlers. */
@RestController
@RequestMapping("/api/v1/missions")
@RequiredArgsConstructor
@Tag(name = "Missions", description = "Mission management endpoints")
@Transactional
public class MissionPlanController {

  private final MissionTimelineService missionTimelineService;
  private final MissionMapper missionMapper;

  /**
   * Appends an Ablauf step, guarded by the mission's {@code stepsVersion}, and returns the ordered
   * step list.
   *
   * @param id mission id
   * @param request the step payload (title, optional meta, expected stepsVersion)
   * @return the mission's ordered Ablauf steps after the add
   */
  @PostMapping("/{id}/steps")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Add an Ablauf step to a mission (slim response)",
      description =
          "Adds a procedure-timeline step and returns the mission's ordered step list as slim"
              + " DTOs.")
  public List<MissionStepDto> addStepSlim(
      @PathVariable @NotNull UUID id, @Valid @RequestBody @NotNull AddMissionStepRequest request) {
    var mission =
        missionTimelineService.addStep(id, request.title(), request.meta(), request.stepsVersion());
    return toStepDtos(mission);
  }

  /**
   * Edits an Ablauf step's title / time-place hint and returns the mission's ordered step list.
   *
   * @param id mission id
   * @param stepId step id
   * @param request the step payload (title, optional meta, expected stepsVersion)
   * @return the mission's ordered Ablauf steps after the edit
   */
  @PutMapping("/{id}/steps/{stepId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Update an Ablauf step (slim response)",
      description = "Edits a step's title / time-place hint and returns the ordered step list.")
  public List<MissionStepDto> updateStepSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @Valid @RequestBody @NotNull UpdateMissionStepRequest request) {
    var mission =
        missionTimelineService.updateStep(
            id, stepId, request.title(), request.meta(), request.stepsVersion());
    return toStepDtos(mission);
  }

  /**
   * Removes an Ablauf step and returns the mission's remaining ordered step list.
   *
   * @param id mission id
   * @param stepId step id
   * @param stepsVersion the expected mission steps-section version (optimistic-lock guard)
   * @return the mission's ordered Ablauf steps after the removal
   */
  @DeleteMapping("/{id}/steps/{stepId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Delete an Ablauf step (slim response)",
      description = "Removes a step, re-packs the order, and returns the ordered step list.")
  public List<MissionStepDto> deleteStepSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestParam @NotNull Long stepsVersion) {
    var mission = missionTimelineService.deleteStep(id, stepId, stepsVersion);
    return toStepDtos(mission);
  }

  /**
   * Reorders the mission's Ablauf steps and returns the new ordered step list.
   *
   * @param id mission id
   * @param request the desired step-id order + expected stepsVersion
   * @return the mission's ordered Ablauf steps after the reorder
   */
  @PutMapping("/{id}/steps/reorder")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Reorder a mission's Ablauf steps (slim response)",
      description = "Reorders the procedure timeline and returns the ordered step list.")
  public List<MissionStepDto> reorderStepsSlim(
      @PathVariable @NotNull UUID id,
      @Valid @RequestBody @NotNull ReorderMissionStepsRequest request) {
    var mission =
        missionTimelineService.reorderSteps(id, request.stepIds(), request.stepsVersion());
    return toStepDtos(mission);
  }

  /**
   * Toggles an Ablauf step's shared done flag and returns the mission's ordered step list.
   *
   * @param id mission id
   * @param stepId step id
   * @param request the new done state + expected stepsVersion
   * @return the mission's ordered Ablauf steps after the toggle
   */
  @PatchMapping("/{id}/steps/{stepId}/done")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Toggle an Ablauf step's done flag (slim response)",
      description = "Sets a step's shared done flag and returns the ordered step list.")
  public List<MissionStepDto> toggleStepDoneSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @Valid @RequestBody @NotNull ToggleMissionStepRequest request) {
    var mission =
        missionTimelineService.toggleStepDone(id, stepId, request.done(), request.stepsVersion());
    return toStepDtos(mission);
  }

  /**
   * Projects a mission's Ablauf steps into an ordered list of slim DTOs (by {@code orderIndex}).
   */
  private List<MissionStepDto> toStepDtos(@NotNull Mission m) {
    return m.getSteps().stream()
        .sorted(Comparator.comparingInt(MissionStep::getOrderIndex))
        .map(missionMapper::toDto)
        .toList();
  }

  /**
   * Appends a goal (Ziel), guarded by the mission's {@code objectivesVersion}, and returns the
   * ordered goal list.
   *
   * @param id mission id
   * @param request the goal payload (title, kind, expected objectivesVersion)
   * @return the mission's ordered goals after the add
   */
  @PostMapping("/{id}/objectives")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Add a goal (Ziel) to a mission (slim response)",
      description =
          "Adds a classified goal and returns the mission's ordered goal list as slim DTOs.")
  public List<MissionObjectiveDto> addObjectiveSlim(
      @PathVariable @NotNull UUID id,
      @Valid @RequestBody @NotNull AddMissionObjectiveRequest request) {
    var mission =
        missionTimelineService.addObjective(
            id, request.title(), request.kind(), request.objectivesVersion());
    return toObjectiveDtos(mission);
  }

  /**
   * Edits a goal's text / classification and returns the mission's ordered goal list.
   *
   * @param id mission id
   * @param objectiveId goal id
   * @param request the goal payload (title, kind, expected objectivesVersion)
   * @return the mission's ordered goals after the edit
   */
  @PutMapping("/{id}/objectives/{objectiveId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Update a mission goal (slim response)",
      description = "Edits a goal's text / classification and returns the ordered goal list.")
  public List<MissionObjectiveDto> updateObjectiveSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @Valid @RequestBody @NotNull UpdateMissionObjectiveRequest request) {
    var mission =
        missionTimelineService.updateObjective(
            id, objectiveId, request.title(), request.kind(), request.objectivesVersion());
    return toObjectiveDtos(mission);
  }

  /**
   * Removes a goal and returns the mission's remaining ordered goal list.
   *
   * @param id mission id
   * @param objectiveId goal id
   * @param objectivesVersion the expected mission goals-section version (optimistic-lock guard)
   * @return the mission's ordered goals after the removal
   */
  @DeleteMapping("/{id}/objectives/{objectiveId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Delete a mission goal (slim response)",
      description = "Removes a goal, re-packs the order, and returns the ordered goal list.")
  public List<MissionObjectiveDto> deleteObjectiveSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @RequestParam @NotNull Long objectivesVersion) {
    var mission = missionTimelineService.deleteObjective(id, objectiveId, objectivesVersion);
    return toObjectiveDtos(mission);
  }

  /**
   * Reorders the mission's goals and returns the new ordered goal list.
   *
   * @param id mission id
   * @param request the desired goal-id order + expected objectivesVersion
   * @return the mission's ordered goals after the reorder
   */
  @PutMapping("/{id}/objectives/reorder")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Reorder a mission's goals (slim response)",
      description = "Reorders the goal list and returns the ordered goal list.")
  public List<MissionObjectiveDto> reorderObjectivesSlim(
      @PathVariable @NotNull UUID id,
      @Valid @RequestBody @NotNull ReorderMissionObjectivesRequest request) {
    var mission =
        missionTimelineService.reorderObjectives(
            id, request.objectiveIds(), request.objectivesVersion());
    return toObjectiveDtos(mission);
  }

  /** Projects a mission's goals into an ordered list of slim DTOs (by {@code orderIndex}). */
  private List<MissionObjectiveDto> toObjectiveDtos(@NotNull Mission m) {
    return m.getObjectives().stream()
        .sorted(Comparator.comparingInt(MissionObjective::getOrderIndex))
        .map(missionMapper::toDto)
        .toList();
  }
}
