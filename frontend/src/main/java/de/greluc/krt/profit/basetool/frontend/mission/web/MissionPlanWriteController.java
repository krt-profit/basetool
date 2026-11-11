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

package de.greluc.krt.profit.basetool.frontend.mission.web;

import static de.greluc.krt.profit.basetool.frontend.kernel.web.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.model.AddMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.AddMissionStepRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionObjectiveDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.MissionStepDto;
import de.greluc.krt.profit.basetool.frontend.mission.model.ReorderMissionObjectivesRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.ReorderMissionStepsRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.ToggleMissionStepRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.frontend.mission.model.UpdateMissionStepRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** Handles mission objectives and ordered execution steps. */
@Controller
@UsesLayoutModel
@RequestMapping("/missions")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MissionPlanWriteController {

  private final MissionBackendClient missionClient;

  /**
   * Appends an Ablauf step and returns the ordered step list.
   *
   * @param id the mission id
   * @param body the step payload (title, optional meta, expected stepsVersion)
   * @return the ordered step list, or the propagated backend error
   */
  @PostMapping(value = "/{id}/steps/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addStepAjax(
      @PathVariable @NotNull UUID id, @RequestBody AddMissionStepRequest body) {
    return relay(
        log,
        "add step (ajax) for mission " + id,
        () -> {
          List<MissionStepDto> result = missionClient.addStep(id, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX proxy: edits an Ablauf step's title / time-place hint and returns the ordered step list.
   *
   * @param id the mission id
   * @param stepId the step id
   * @param body the step payload (title, optional meta, expected stepsVersion)
   * @return the ordered step list, or the propagated backend error
   */
  @PutMapping(value = "/{id}/steps/{stepId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> updateStepAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestBody UpdateMissionStepRequest body) {
    return relay(
        log,
        "update step (ajax) for mission " + id + " step " + stepId,
        () -> {
          List<MissionStepDto> result = missionClient.updateStep(id, stepId, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Removes an Ablauf step and returns the remaining ordered step list.
   *
   * @param id the mission id
   * @param stepId the step id
   * @param stepsVersion the expected steps-section version, passed as a query parameter
   * @return the ordered step list, or the propagated backend error
   */
  @DeleteMapping(value = "/{id}/steps/{stepId}/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> deleteStepAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestParam @NotNull Long stepsVersion) {
    return relay(
        log,
        "delete step (ajax) for mission " + id + " step " + stepId,
        () -> {
          List<MissionStepDto> result = missionClient.deleteStep(id, stepId, stepsVersion);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX proxy: reorders the mission's Ablauf steps and returns the new ordered step list.
   *
   * @param id the mission id
   * @param body the desired step-id order + expected stepsVersion
   * @return the ordered step list, or the propagated backend error
   */
  @PutMapping(value = "/{id}/steps/reorder/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> reorderStepsAjax(
      @PathVariable @NotNull UUID id, @RequestBody ReorderMissionStepsRequest body) {
    return relay(
        log,
        "reorder steps (ajax) for mission " + id,
        () -> {
          List<MissionStepDto> result = missionClient.reorderSteps(id, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Sets an Ablauf step's shared done flag and returns the ordered step list.
   *
   * @param id the mission id
   * @param stepId the step id
   * @param body the new done state + expected stepsVersion
   * @return the ordered step list, or the propagated backend error
   */
  @PatchMapping(
      value = "/{id}/steps/{stepId}/done/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> toggleStepDoneAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestBody ToggleMissionStepRequest body) {
    return relay(
        log,
        "toggle step done (ajax) for mission " + id + " step " + stepId,
        () -> {
          List<MissionStepDto> result = missionClient.setStepDone(id, stepId, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Appends a goal (Ziel) and returns the ordered goal list.
   *
   * @param id the mission id
   * @param body the goal payload (title, kind, expected objectivesVersion)
   * @return the ordered goal list, or the propagated backend error
   */
  @PostMapping(value = "/{id}/objectives/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> addObjectiveAjax(
      @PathVariable @NotNull UUID id, @RequestBody AddMissionObjectiveRequest body) {
    return relay(
        log,
        "add objective (ajax) for mission " + id,
        () -> {
          List<MissionObjectiveDto> result = missionClient.addObjective(id, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX proxy: edits a goal's text / classification and returns the ordered goal list.
   *
   * @param id the mission id
   * @param objectiveId the goal id
   * @param body the goal payload (title, kind, expected objectivesVersion)
   * @return the ordered goal list, or the propagated backend error
   */
  @PutMapping(
      value = "/{id}/objectives/{objectiveId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> updateObjectiveAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @RequestBody UpdateMissionObjectiveRequest body) {
    return relay(
        log,
        "update objective (ajax) for mission " + id + " objective " + objectiveId,
        () -> {
          List<MissionObjectiveDto> result = missionClient.updateObjective(id, objectiveId, body);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * Removes a goal and returns the remaining ordered goal list.
   *
   * @param id the mission id
   * @param objectiveId the goal id
   * @param objectivesVersion the expected goals-section version, passed as a query parameter
   * @return the ordered goal list, or the propagated backend error
   */
  @DeleteMapping(
      value = "/{id}/objectives/{objectiveId}/ajax",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> deleteObjectiveAjax(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @RequestParam @NotNull Long objectivesVersion) {
    return relay(
        log,
        "delete objective (ajax) for mission " + id + " objective " + objectiveId,
        () -> {
          List<MissionObjectiveDto> result =
              missionClient.deleteObjective(id, objectiveId, objectivesVersion);
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX proxy: reorders the mission's goals and returns the new ordered goal list.
   *
   * @param id the mission id
   * @param body the desired goal-id order + expected objectivesVersion
   * @return the ordered goal list, or the propagated backend error
   */
  @PutMapping(value = "/{id}/objectives/reorder/ajax", produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public ResponseEntity<Object> reorderObjectivesAjax(
      @PathVariable @NotNull UUID id, @RequestBody ReorderMissionObjectivesRequest body) {
    return relay(
        log,
        "reorder objectives (ajax) for mission " + id,
        () -> {
          List<MissionObjectiveDto> result = missionClient.reorderObjectives(id, body);
          return ResponseEntity.ok(result);
        });
  }
}
