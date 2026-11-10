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

import de.greluc.krt.profit.basetool.backend.mission.api.MissionDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.internal.AddCustomFrequencyRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.AddFrequencyRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import de.greluc.krt.profit.basetool.backend.mission.internal.PatchMissionScheduleRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateCustomFrequencyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mission schedule and radio-frequency HTTP handlers. */
@RestController
@RequestMapping("/api/v1/missions")
@RequiredArgsConstructor
@Tag(name = "Missions", description = "Mission management endpoints")
@Transactional
public class MissionScheduleController {

  private final MissionService missionService;
  private final MissionMapper missionMapper;
  private final MissionHttpProjection projection;

  /**
   * Patches the schedule section (meeting, planned and actual times, UTC) under its own section
   * version.
   *
   * @param id mission id
   * @param request schedule patch payload (carries the expected schedule-section version)
   * @return the persisted DTO
   */
  @PatchMapping("/{id}/schedule")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Patch mission schedule section",
      description =
          "Patches only the schedule section (meeting/planned/actual times) of a mission. Thanks "
              + "to decoupled sub-collections, concurrent edits to participants, units or finances "
              + "no longer cause a version conflict. Timestamps are in UTC.")
  public MissionDto patchMissionSchedule(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull PatchMissionScheduleRequest request) {
    return projection.cleanupForPeer(
        missionMapper.toDto(
            missionService.updateScheduleSection(
                id,
                request.meetingTime(),
                request.plannedStartTime(),
                request.plannedEndTime(),
                request.actualStartTime(),
                request.actualEndTime(),
                request.version())));
  }

  /**
   * Upserts a frequency by type; returns only the updated frequency list (slim).
   *
   * @param id mission id
   * @param request frequency payload (type + value)
   * @return the updated frequency list
   */
  @PostMapping("/{id}/frequencies")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Add or update a frequency for a mission (slim response)",
      description =
          "Adds or updates a frequency and returns the updated frequency list as slim DTOs.")
  public List<MissionFrequencyDto> addOrUpdateFrequencySlim(
      @PathVariable @NotNull UUID id, @NotNull @RequestBody @Valid AddFrequencyRequest request) {
    var mission =
        missionService.addOrUpdateMissionFrequency(id, request.frequencyTypeId(), request.value());
    return mission.getFrequencies().stream().map(missionMapper::toDto).toList();
  }

  /**
   * Removes a frequency; returns 204.
   *
   * @param id mission id
   * @param frequencyId frequency id
   * @return 204 No Content
   */
  @DeleteMapping("/{id}/frequencies/{frequencyId}")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Remove a frequency from a mission (slim response)",
      description = "Removes a frequency and returns 204 No Content.")
  public ResponseEntity<Void> removeFrequencySlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID frequencyId) {
    missionService.removeMissionFrequency(id, frequencyId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Adds a custom (mission-specific) frequency with a free-text label and value (REQ-MISSION-014).
   *
   * @param id mission id
   * @param request the custom-frequency payload (name + value)
   * @return the updated frequency list
   */
  @PostMapping("/{id}/frequencies/custom")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Add a custom (mission-specific) frequency (slim response)",
      description =
          "Adds a free-text mission frequency and returns the updated frequency list as slim DTOs.")
  public List<MissionFrequencyDto> addCustomFrequencySlim(
      @PathVariable @NotNull UUID id,
      @NotNull @RequestBody @Valid AddCustomFrequencyRequest request) {
    var mission = missionService.addCustomMissionFrequency(id, request.name(), request.value());
    return mission.getFrequencies().stream().map(missionMapper::toDto).toList();
  }

  /**
   * Updates a custom frequency's label and value, guarded by the row's own version
   * (REQ-MISSION-014).
   *
   * @param id mission id
   * @param frequencyId the custom frequency row id
   * @param request the custom-frequency payload (name + value + version)
   * @return the updated frequency list
   */
  @PutMapping("/{id}/frequencies/custom/{frequencyId}")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Update a custom (mission-specific) frequency (slim response)",
      description =
          "Updates a free-text mission frequency and returns the updated frequency list as slim"
              + " DTOs.")
  public List<MissionFrequencyDto> updateCustomFrequencySlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID frequencyId,
      @NotNull @RequestBody @Valid UpdateCustomFrequencyRequest request) {
    var mission =
        missionService.updateCustomMissionFrequency(
            id, frequencyId, request.name(), request.value(), request.version());
    return mission.getFrequencies().stream().map(missionMapper::toDto).toList();
  }
}
