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

import de.greluc.krt.profit.basetool.backend.identity.api.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionListDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionParticipantService;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionService;
import de.greluc.krt.profit.basetool.backend.mission.internal.PatchMissionCoreRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.PatchMissionFlagsRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateMissionOwnerRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateMissionOwningOrgUnitRequest;
import de.greluc.krt.profit.basetool.backend.orgunit.api.UserMapper;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Mission queries, core fields, ownership and manager HTTP handlers. */
@RestController
@RequestMapping("/api/v1/missions")
@RequiredArgsConstructor
@Tag(name = "Missions", description = "Mission management endpoints")
@Transactional
public class MissionController {

  private final MissionService missionService;
  private final MissionParticipantService missionParticipantService;
  private final MissionMapper missionMapper;
  private final UserMapper userMapper;
  private final MissionHttpProjection projection;

  /**
   * Pages the missions visible to the calling member, optionally narrowed by a name fragment, a
   * planned-start window, statuses and an operation; without filters every status is listed.
   *
   * @param q free-text name fragment
   * @param start lower bound on planned start time
   * @param end upper bound on planned start time
   * @param status status filter (one or more)
   * @param operationId optional operation filter
   * @param orgUnitId optional owning org unit filter; the caller's visibility still applies
   * @return paged mission list DTOs, each saying whether the caller is signed up
   */
  @GetMapping
  @Operation(summary = "List missions (paginated, filterable)")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Transactional(readOnly = true)
  public PageResponse<MissionListDto> getAllMissions(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant end,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) UUID operationId,
      @RequestParam(required = false) UUID orgUnitId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(
            page,
            size,
            sort,
            Set.of("plannedStartTime", "name", "status", "id"),
            "plannedStartTime");
    Page<Mission> pageResult =
        missionService.searchMissions(
            q, start, end, status, null, operationId, orgUnitId, pageable);
    return PageResponse.of(toListRows(pageResult));
  }

  /**
   * Lightweight projection (id + label) of the missions offered by the warehouse mission picker:
   * every active mission plus the {@code COMPLETED} / {@code CANCELLED} ones from the last three
   * months. See {@link MissionService#findAllActiveReference()}.
   *
   * @return picker-visible missions as reference DTOs
   */
  @GetMapping("/lookup")
  @Operation(
      summary = "Lookup missions for the warehouse picker",
      description =
          "Returns a reference list of missions for the inventory mission picker: all PLANNED /"
              + " ACTIVE missions plus COMPLETED / CANCELLED missions whose planned start is within"
              + " the last three months.")
  @PreAuthorize("isAuthenticated()")
  @Transactional(readOnly = true)
  public List<MissionReferenceDto> lookupMissions() {
    return missionService.findAllActiveReference();
  }

  /**
   * Returns a single mission, visible per {@code canSeeMission}; non-admin callers get the
   * peer-redacted DTO (REQ-SEC-007).
   *
   * @param id mission id
   * @return the mission DTO
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get mission by ID")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionAccessPolicy.canSeeMission(#id)")
  @Transactional(readOnly = true)
  public MissionDto getMissionById(@PathVariable @NotNull UUID id) {
    return projection.cleanupForPeer(missionMapper.toDto(missionService.getMissionById(id)));
  }

  /**
   * Returns the next upcoming {@code PLANNED} or {@code ACTIVE} mission, or 204 when none
   * (REQ-MISSION-003); non-admin callers get the peer-redacted DTO.
   *
   * @return mission DTO or 204 No Content
   */
  @GetMapping("/next")
  @Operation(summary = "Get next upcoming mission")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Transactional(readOnly = true)
  public ResponseEntity<MissionDto> getNextMission() {
    return missionService
        .getNextMission()
        .map(m -> ResponseEntity.ok(projection.cleanupForPeer(missionMapper.toDto(m))))
        .orElse(ResponseEntity.noContent().build());
  }

  /**
   * Creates a new mission owned by the caller; server-managed fields are stamped by {@link
   * MissionService#createMission}, not taken from the {@link CreateMissionRequest}.
   *
   * @param request create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Operation(summary = "Create a new mission")
  public MissionDto createMission(@RequestBody @Valid @NotNull CreateMissionRequest request) {
    return projection.cleanupForPeer(missionMapper.toDto(missionService.createMission(request)));
  }

  /**
   * Attaches a new sub-mission to a parent; {@code parent} and {@code owningSquadron} are stamped
   * from the parent.
   *
   * @param id parent mission id
   * @param request create payload for the sub-mission
   * @return the persisted parent DTO with the new sub-mission attached
   */
  @PostMapping("/{id}/sub-missions")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(summary = "Create a sub-mission")
  public MissionDto createSubMission(
      @PathVariable @NotNull UUID id, @RequestBody @Valid @NotNull CreateMissionRequest request) {
    return projection.cleanupForPeer(missionMapper.toDto(missionService.addSubMission(id, request)));
  }

  /**
   * Patches the core section (name, description, calendar link, status) under its own section
   * version.
   *
   * @param id mission id
   * @param request core patch payload (carries the expected core-section version)
   * @return the persisted DTO
   */
  @PatchMapping("/{id}/core")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Patch mission core section",
      description =
          "Patches only the core section (name, description, calendar link, status) of a mission."
              + " Other sections and sub-aggregates stay untouched. A version conflict returns HTTP"
              + " 409 (application/problem+json).")
  public MissionDto patchMissionCore(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull PatchMissionCoreRequest request) {
    return projection.cleanupForPeer(
        missionMapper.toDto(
            missionService.updateCoreSection(
                id,
                request.name(),
                request.description(),
                request.calendarLink(),
                request.status(),
                request.operationId(),
                request.meetingPoint(),
                request.version())));
  }

  /**
   * Patches the flags section (currently only {@code isInternal}). Independently versioned.
   *
   * @param id mission id
   * @param request flags patch payload (carries the expected flags-section version)
   * @return the persisted DTO
   */
  @PatchMapping("/{id}/flags")
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Patch mission flags section",
      description = "Patches only the flags section (e.g. isInternal) of a mission.")
  public MissionDto patchMissionFlags(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull PatchMissionFlagsRequest request) {
    return projection.cleanupForPeer(
        missionMapper.toDto(
            missionService.updateFlagsSection(id, request.isInternal(), request.version())));
  }

  /**
   * ADMIN-only mission delete. Cascades through participants, units, frequencies and finance
   * entries.
   *
   * @param id mission id
   * @return 204 No Content
   */
  @DeleteMapping("/{id}")
  @PreAuthorize(Roles.HAS_ROLE_ADMIN)
  @Operation(summary = "Delete a mission")
  public ResponseEntity<Void> deleteMission(@PathVariable @NotNull UUID id) {
    missionService.deleteMission(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Changes the mission owner via the {@code MissionOwnership} aggregate, guarded by the ownership
   * version; {@code Mission.version} stays untouched.
   *
   * @param id mission id
   * @param request owner-change payload (new owner id + expected ownership version)
   * @return the persisted DTO
   */
  @PutMapping("/{id}/owner")
  @PreAuthorize("@missionAccessPolicy.canChangeOwner(#id, authentication)")
  @Operation(
      summary = "Change the owner of a mission (version-checked)",
      description =
          "Updates the mission owner through the dedicated MissionOwnership aggregate. "
              + "The version field in the request body must match the current ownership version "
              + "(NOT the parent Mission.version) to prevent lost updates on concurrent owner "
              + "changes. Changing the owner does NOT bump Mission.version, so other users' "
              + "open forms on the same mission remain valid (Option A / multi-user concurrency).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Owner updated"),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "403", description = "Forbidden"),
    @ApiResponse(responseCode = "404", description = "Mission or user not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Ownership version conflict (application/problem+json)")
  })
  public MissionDto updateMissionOwner(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull UpdateMissionOwnerRequest request) {
    var mission = missionService.updateMissionOwner(id, request.userId(), request.version());
    return projection.cleanupForPeer(missionMapper.toDto(mission));
  }

  /**
   * Reassigns the mission's owning org unit, guarded by {@code owningOrgUnitVersion} without
   * bumping {@code Mission.version} (REQ-ORG-018).
   *
   * <p>The target must be within the caller's assignable-org-unit scope; {@code null} makes it an
   * ownerless leadership mission.
   *
   * @param id mission id
   * @param request reassignment payload (target org-unit id or {@code null} + expected {@code
   *     owningOrgUnitVersion})
   * @return the persisted DTO
   */
  @PutMapping("/{id}/owning-org-unit")
  @PreAuthorize("@missionAccessPolicy.canChangeOwner(#id, authentication)")
  @Operation(
      summary = "Reassign the owning org unit of a mission (version-checked)",
      description =
          "Re-homes the mission to a different org unit (Staffel/Spezialkommando/Bereich/OL) or to"
              + " an ownerless leadership mission (null owningOrgUnitId). The version field in the"
              + " request body must match the current owningOrgUnitVersion (NOT the parent"
              + " Mission.version) to prevent lost updates on concurrent reassignments. The target"
              + " is validated against the caller's assignable-org-unit scope: a non-admin may only"
              + " pick a unit they belong to or may edit, and may only choose null when"
              + " membershipless.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Owning org unit updated"),
    @ApiResponse(responseCode = "400", description = "Validation error or unknown target org unit"),
    @ApiResponse(
        responseCode = "403",
        description = "Forbidden (caller may not change owner or may not assign to target)"),
    @ApiResponse(responseCode = "404", description = "Mission not found"),
    @ApiResponse(
        responseCode = "409",
        description = "owningOrgUnitVersion conflict (application/problem+json)")
  })
  public MissionDto updateMissionOwningOrgUnit(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull UpdateMissionOwningOrgUnitRequest request) {
    var mission =
        missionService.updateOwningOrgUnit(id, request.owningOrgUnitId(), request.version());
    return projection.cleanupForPeer(missionMapper.toDto(mission));
  }

  /**
   * Adds a manager; returns the updated manager list as {@link UserReferenceDto}s (id + label
   * only).
   *
   * @param id mission id
   * @param userId user id to add as manager
   * @return the updated manager list
   */
  @PostMapping("/{id}/managers/{userId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Add a manager to a mission (slim response)",
      description = "Adds a manager and returns the updated manager list as UserReferenceDto.")
  public List<UserReferenceDto> addManagerSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    var mission = missionParticipantService.addManager(id, userId);
    return mission.getManagers().stream().map(userMapper::toReferenceDto).toList();
  }

  /**
   * Removes a manager; returns 204.
   *
   * @param id mission id
   * @param userId user id to remove from managers
   * @return 204 No Content
   */
  @DeleteMapping("/{id}/managers/{userId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(
      summary = "Remove a manager from a mission (slim response)",
      description = "Removes a manager and returns 204 No Content.")
  public ResponseEntity<Void> removeManagerSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    missionParticipantService.removeManager(id, userId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Maps a page of missions to list rows, loading all registration counts in one grouped query and
   * the caller's own sign-ups in one more (REQ-DATA-003, REQ-MISSION-012).
   *
   * @param missions the page as the service returned it.
   * @return the same page as list DTOs, each carrying its registration count and whether the caller
   *     is signed up.
   */
  private Page<MissionListDto> toListRows(@NotNull Page<Mission> missions) {
    List<UUID> missionIds = missions.getContent().stream().map(Mission::getId).toList();
    Map<UUID, Long> counts = missionService.registeredCounts(missionIds);
    Set<UUID> signedUp = missionService.signedUpMissionIds(missionIds);
    return missions.map(mission ->
        missionMapper.toListDto(
            mission, counts.getOrDefault(mission.getId(), 0L), signedUp.contains(mission.getId())));
  }
}
