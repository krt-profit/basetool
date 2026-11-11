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

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipDto;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipMapper;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionCrew;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionCrewDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnitDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.AddCrewRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.AddUnitRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionStructureService;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateCrewRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateUnitRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mission unit, ship and crew HTTP handlers. */
@RestController
@RequestMapping("/api/v1/missions")
@RequiredArgsConstructor
@Tag(name = "Missions", description = "Mission management endpoints")
@Transactional
public class MissionStructureController {

  private final MissionStructureService missionStructureService;
  private final MissionMapper missionMapper;
  private final ShipMapper shipMapper;
  private final MissionHttpProjection projection;

  /**
   * Finds a unit inside a mission aggregate by id.
   *
   * @param mission mission aggregate
   * @param unitId unit id to find
   * @return the matching unit
   */
  private MissionUnit findUnit(@NotNull Mission mission, UUID unitId) {
    return Entities.require(
        mission.getAssignedUnits().stream().filter(u -> unitId.equals(u.getId())).findFirst(),
        "Mission unit not found");
  }

  /**
   * Locates a crew entry inside a unit by id, or throws {@link NotFoundException}.
   *
   * @param unit unit aggregate
   * @param crewId crew entry id to find
   * @return the matching crew entry
   */
  private MissionCrew findCrew(@NotNull MissionUnit unit, UUID crewId) {
    return Entities.require(
        unit.getCrew().stream().filter(c -> crewId.equals(c.getId())).findFirst(),
        "Crew member not found");
  }

  /**
   * Adds a unit and returns only the updated unit list.
   *
   * @param id mission id
   * @param request unit payload
   * @return the updated unit list
   */
  @PostMapping("/{id}/units")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Add a unit to a mission (slim response)",
      description =
          "Adds a new unit and returns the updated list of units as slim DTOs. "
              + "Preferred replacement for POST /api/v1/missions/{id}/units to support "
              + "multi-user concurrency on the mission detail page.")
  public List<MissionUnitDto> addUnitSlim(
      @PathVariable @NotNull UUID id, @Valid @RequestBody @NotNull AddUnitRequest request) {
    var mission =
        missionStructureService.addUnitToMission(
            id,
            request.name(),
            request.shipTypeId(),
            request.shipId(),
            request.isHighValueUnit(),
            request.frequency(),
            request.responsibleUserId(),
            request.note());
    return projection.cleanupUnitsForPeer(
        mission.getAssignedUnits().stream().map(missionMapper::toDto).toList());
  }

  /**
   * Updates a unit and returns only the updated unit (slim).
   *
   * @param id mission id
   * @param unitId unit id
   * @param request unit payload
   * @return the updated unit DTO
   */
  @PutMapping("/{id}/units/{unitId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Update a mission unit (slim response)",
      description = "Updates a unit and returns only the updated unit as a slim DTO.")
  public MissionUnitDto updateUnitSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @Valid @RequestBody @NotNull UpdateUnitRequest request) {
    var mission =
        missionStructureService.updateMissionUnit(
            id,
            unitId,
            request.version(),
            request.name(),
            request.shipTypeId(),
            request.shipId(),
            request.isHighValueUnit(),
            request.frequency(),
            request.responsibleUserId(),
            request.note());
    return projection.cleanupForPeer(missionMapper.toDto(findUnit(mission, unitId)));
  }

  /**
   * Deletes a unit; returns 204.
   *
   * @param id mission id
   * @param unitId unit id
   * @return 204 No Content
   */
  @DeleteMapping("/{id}/units/{unitId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Delete a mission unit (slim response)",
      description = "Deletes a unit and returns 204 No Content.")
  public ResponseEntity<Void> deleteUnitSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID unitId) {
    missionStructureService.removeMissionUnit(id, unitId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Lists the ships a unit of this mission may be crewed with: ships owned by registered
   * participants of any org unit plus ships already pinned to one of its units.
   *
   * @param id mission id
   * @return the candidate ships for this mission's unit ship pickers
   */
  @GetMapping("/{id}/unit-ship-options")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Transactional(readOnly = true)
  @Operation(
      summary = "List selectable ships for a mission's units",
      description =
          "Returns the ships a unit of this mission may be crewed with: ships owned by registered "
              + "participants (regardless of OrgUnit) plus ships already assigned to a unit of the "
              + "mission. Restricted to callers who may manage the mission.")
  public List<ShipDto> getUnitShipOptions(@PathVariable @NotNull UUID id) {
    return projection.cleanupShipsForPeer(
        missionStructureService.getSelectableUnitShips(id).stream()
            .map(shipMapper::toDto)
            .toList());
  }

  /**
   * Adds crew and returns only the affected unit's crew list (slim).
   *
   * @param id mission id
   * @param missionUnitId unit id
   * @param request crew payload (participant + job types)
   * @return the updated crew list of the unit
   */
  @PostMapping("/{id}/units/{missionUnitId}/crew")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Add crew to a mission unit (slim response)",
      description =
          "Adds a crew member and returns the updated crew list of the affected unit as slim DTOs.")
  public List<MissionCrewDto> addCrewSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID missionUnitId,
      @RequestBody @Valid @NotNull AddCrewRequest request) {
    Set<UUID> jobTypeIds =
        request.jobTypeIds() != null ? request.jobTypeIds() : Collections.emptySet();
    var mission =
        missionStructureService.addCrewToShip(
            id, missionUnitId, request.participantId(), jobTypeIds);
    return missionMapper.toDto(findUnit(mission, missionUnitId)).crew();
  }

  /**
   * Updates a crew entry and returns only the updated entry (slim).
   *
   * @param id mission id
   * @param missionUnitId unit id
   * @param crewId crew entry id
   * @param request crew payload (job-type set)
   * @return the updated crew DTO
   */
  @PutMapping("/{id}/units/{missionUnitId}/crew/{crewId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Update crew in a mission unit (slim response)",
      description = "Updates a crew member and returns only the updated crew entry as a slim DTO.")
  public MissionCrewDto updateCrewSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID missionUnitId,
      @PathVariable @NotNull UUID crewId,
      @RequestBody @Valid @NotNull UpdateCrewRequest request) {
    Set<UUID> jobTypeIds =
        request.jobTypeIds() != null ? request.jobTypeIds() : Collections.emptySet();
    var mission =
        missionStructureService.updateCrewInShip(
            id, missionUnitId, crewId, request.version(), jobTypeIds);
    return missionMapper.toDto(findCrew(findUnit(mission, missionUnitId), crewId));
  }

  /**
   * Removes a crew entry; returns 204.
   *
   * @param id mission id
   * @param missionUnitId unit id
   * @param crewId crew entry id
   * @return 204 No Content
   */
  @DeleteMapping("/{id}/units/{missionUnitId}/crew/{crewId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Remove crew from a mission unit (slim response)",
      description = "Removes a crew member and returns 204 No Content.")
  public ResponseEntity<Void> removeCrewSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID missionUnitId,
      @PathVariable @NotNull UUID crewId) {
    missionStructureService.removeCrewFromShip(id, missionUnitId, crewId);
    return ResponseEntity.noContent().build();
  }
}
