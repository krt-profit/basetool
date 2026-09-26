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

package de.greluc.krt.profit.basetool.backend.controller;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.Entities;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mapper.ShipMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionCrew;
import de.greluc.krt.profit.basetool.backend.model.MissionObjective;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.MissionStep;
import de.greluc.krt.profit.basetool.backend.model.MissionUnit;
import de.greluc.krt.profit.basetool.backend.model.dto.AddCrewRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.AddExternalParticipantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.AddParticipantByIdRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.AddUnitRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.JoinMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionCrewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionObjectiveDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionStepDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateCrewRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateParticipantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdatePayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateUnitRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.AddCustomFrequencyRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.AddFrequencyRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.AddMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.AddMissionStepRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionCoreRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionFlagsRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionScheduleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.ReorderMissionObjectivesRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.ReorderMissionStepsRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.SetPartyLeadRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.ToggleMissionStepRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateCustomFrequencyRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionObjectiveRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionOwnerRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionOwningOrgUnitRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionStepRequest;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.MissionSecurityService;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import de.greluc.krt.profit.basetool.backend.service.ParticipantTargetResolver;
import de.greluc.krt.profit.basetool.backend.service.ParticipantTargetResolver.ParticipantTarget;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.MissionPeerRedactor;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

/**
 * REST surface over the mission aggregate and its sub-aggregates (units, crew, participants,
 * frequencies, managers, ownership).
 *
 * <p>Section patches ({@code /core}, {@code /schedule}, {@code /flags}) are versioned
 * independently, and {@code .../slim} endpoints return only the affected sub-DTO. Responses for
 * callers below Logistician pass through {@link MissionPeerRedactor#cleanupMissionForPeer}
 * (REQ-SEC-007); authorisation is delegated to {@link MissionSecurityService}.
 */
@RestController
@RequestMapping("/api/v1/missions")
@RequiredArgsConstructor
@Tag(name = "Missions", description = "Mission management endpoints")
@Transactional
@Slf4j
public class MissionController {

  private final MissionService missionService;
  private final UserService userService;
  private final MissionMapper missionMapper;
  private final UserMapper userMapper;
  private final ShipMapper shipMapper;
  private final MissionSecurityService missionSecurityService;
  private final AuthHelperService authHelperService;
  private final MissionPeerRedactor missionPeerRedactor;
  private final ParticipantTargetResolver participantTargetResolver;

  /**
   * Pages the missions visible to the calling member.
   *
   * @return paged mission list DTOs
   */
  @GetMapping
  @Operation(summary = "List all missions (paginated)")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Transactional(readOnly = true)
  public PageResponse<MissionListDto> getAllMissions(
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
            null,
            null,
            null,
            List.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED"),
            null,
            null,
            pageable);
    return PageResponse.of(withRegisteredCounts(pageResult));
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
   * Filtered, paged mission search scoped to the calling member.
   *
   * @param query free-text name fragment
   * @param start lower bound on planned start time
   * @param end upper bound on planned start time
   * @param status status filter (one or more)
   * @param operationId optional operation filter
   * @return paged mission list DTOs
   */
  @GetMapping("/search")
  @Operation(summary = "Search missions (paginated)")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Transactional(readOnly = true)
  public PageResponse<MissionListDto> searchMissions(
      @RequestParam(required = false) String query,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant end,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) UUID operationId,
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
        missionService.searchMissions(query, start, end, status, null, operationId, pageable);
    return PageResponse.of(withRegisteredCounts(pageResult));
  }

  /**
   * Returns a single mission, visible per {@code canSeeMission}; callers below Logistician get the
   * peer-redacted DTO (REQ-SEC-007).
   *
   * @param id mission id
   * @return the mission DTO
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get mission by ID")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#id)")
  @Transactional(readOnly = true)
  public MissionDto getMissionById(@PathVariable @NotNull UUID id) {
    return redactForPeer(missionMapper.toDto(missionService.getMissionById(id)));
  }

  /**
   * Returns the next upcoming {@code PLANNED} or {@code ACTIVE} mission, or 204 when none
   * (REQ-MISSION-003); callers below Logistician get the peer-redacted DTO.
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
        .map(m -> ResponseEntity.ok(redactForPeer(missionMapper.toDto(m))))
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
    return redactForPeer(missionMapper.toDto(missionService.createMission(request)));
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
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(summary = "Create a sub-mission")
  public MissionDto createSubMission(
      @PathVariable @NotNull UUID id, @RequestBody @Valid @NotNull CreateMissionRequest request) {
    return redactForPeer(missionMapper.toDto(missionService.addSubMission(id, request)));
  }

  /**
   * Full-replace update that bumps {@code Mission.version}; prefer the section patches ({@link
   * #patchMissionCore}, {@link #patchMissionSchedule}, {@link #patchMissionFlags}).
   *
   * @param id mission id
   * @param request update payload (carries the expected version; excludes server-managed fields)
   * @return the persisted DTO
   */
  @PutMapping("/{id}")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Update a mission (full replace)",
      description =
          "Replaces the entire mission in a single request. For a better multi-user experience, "
              + "prefer the section PATCH endpoints (/core, /schedule, /flags) instead, so "
              + "concurrent edits to other sections do not trigger optimistic-lock conflicts.")
  public MissionDto updateMission(
      @PathVariable @NotNull UUID id, @RequestBody @Valid @NotNull UpdateMissionRequest request) {
    return redactForPeer(missionMapper.toDto(missionService.updateMission(id, request)));
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
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Patch mission core section",
      description =
          "Patches only the core section (name, description, calendar link, status) of a mission."
              + " Other sections and sub-aggregates stay untouched. A version conflict returns HTTP"
              + " 409 (application/problem+json).")
  public MissionDto patchMissionCore(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull PatchMissionCoreRequest request) {
    return redactForPeer(
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
   * Patches the schedule section (meeting, planned and actual times, UTC) under its own section
   * version.
   *
   * @param id mission id
   * @param request schedule patch payload (carries the expected schedule-section version)
   * @return the persisted DTO
   */
  @PatchMapping("/{id}/schedule")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Patch mission schedule section",
      description =
          "Patches only the schedule section (meeting/planned/actual times) of a mission. Thanks "
              + "to decoupled sub-collections, concurrent edits to participants, units or finances "
              + "no longer cause a version conflict. Timestamps are in UTC.")
  public MissionDto patchMissionSchedule(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull PatchMissionScheduleRequest request) {
    return redactForPeer(
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
   * Patches the flags section (currently only {@code isInternal}). Independently versioned.
   *
   * @param id mission id
   * @param request flags patch payload (carries the expected flags-section version)
   * @return the persisted DTO
   */
  @PatchMapping("/{id}/flags")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Patch mission flags section",
      description = "Patches only the flags section (e.g. isInternal) of a mission.")
  public MissionDto patchMissionFlags(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull PatchMissionFlagsRequest request) {
    return redactForPeer(
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
   * Enrols the caller as a participant of the mission.
   *
   * <p>The optional body carries the desired Funktion and payout preference; a bodyless request
   * behaves as before (REQ-API-009).
   *
   * @param jwt caller's JWT
   * @param id mission id
   * @param request the optional sign-up answers; {@code null} when no body was sent
   * @return the persisted DTO
   */
  @PostMapping("/{id}/join")
  @Operation(
      summary = "Join a mission",
      description =
          "Self-enrolment: adds the caller as participant. The body is optional and carries the"
              + " desired job type and the payout preference; omitting it (or either field) keeps"
              + " the pre-2026-09-02 behaviour, including the profile-default payout chain of"
              + " REQ-MISSION-002.")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#id)")
  public MissionDto joinMission(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody(required = false) @Valid JoinMissionRequest request) {
    MissionDto dto =
        missionMapper.toDto(
            missionService.addParticipant(
                id,
                userService.getUserIdFromJwt(jwt),
                null,
                request == null ? null : request.desiredJobTypeId(),
                null,
                null,
                request == null ? null : request.payoutPreference()));
    return redactForPeer(dto);
  }

  /**
   * Adds a participant by {@code userId} or by free-text {@code guestName}.
   *
   * <p>A name resolves case-insensitively: a unique member match links that member, no match
   * records an external participant, several matches are a 409. Naming anyone other than the caller
   * requires {@code canManageMission}.
   *
   * @param id mission id
   * @param request add-participant payload (userId XOR guestName + comment + squadron)
   * @return the persisted parent DTO
   */
  @PostMapping("/{id}/participants/add")
  @Operation(
      summary = "Add a participant",
      description =
          "Adds a participant by explicit userId (from autocomplete) or by free-text guestName."
              + " Free-text names are resolved case-insensitively against existing users: a unique"
              + " match links the participant as a registered member; no match records an external"
              + " participant; multiple matches return 409 (ambiguous name).")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Participant added"),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(
        responseCode = "403",
        description = "Only mission managers may add somebody other than themselves"),
    @ApiResponse(
        responseCode = "409",
        description = "Participant name is ambiguous and matches more than one registered user")
  })
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#id)")
  public MissionDto addParticipantPublic(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull AddExternalParticipantRequest request,
      Authentication authentication) {
    ParticipantTarget target = resolveParticipantTarget(id, request, authentication);
    MissionDto dto =
        missionMapper.toDto(
            missionService.addParticipant(
                id,
                target.userId(),
                target.guestName(),
                request.desiredJobTypeId(),
                request.comment(),
                request.orgUnitIds(),
                request.payoutPreference()));
    return redactForPeer(dto);
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
  @PreAuthorize("@missionSecurityService.canChangeOwner(#id, authentication)")
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
    return redactForPeer(missionMapper.toDto(mission));
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
  @PreAuthorize("@missionSecurityService.canChangeOwner(#id, authentication)")
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
    return redactForPeer(missionMapper.toDto(mission));
  }

  /**
   * Assigns or clears the mission's party lead (Partyleiter), guarded by {@code partyLeadVersion}.
   *
   * <p>A free-text name resolves like a participant add (unique match links, none stores it as
   * external, several are a 409); neither id nor name clears the party lead.
   *
   * @param id mission id
   * @param request party-lead payload (userId XOR guestName + expected partyLeadVersion)
   * @return the updated mission DTO
   */
  @PutMapping("/{id}/party-lead")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Set or clear the party lead of a mission",
      description =
          "Assigns the mission's party lead by explicit userId (from autocomplete) or by free-text"
              + " guestName, mirroring the participant-add resolution: a free-text name is resolved"
              + " case-insensitively against registered members (unique match links the user,"
              + " multiple matches return 409, no match stores an external handle). Submitting"
              + " neither"
              + " clears the party lead. The version must match the mission's current"
              + " partyLeadVersion or 409 (application/problem+json) is returned.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Party lead updated"),
    @ApiResponse(responseCode = "400", description = "Validation error"),
    @ApiResponse(responseCode = "403", description = "Caller may not manage this mission"),
    @ApiResponse(responseCode = "404", description = "Mission or referenced user not found"),
    @ApiResponse(
        responseCode = "409",
        description = "Ambiguous party-lead name or stale partyLeadVersion")
  })
  public MissionDto setPartyLead(
      @PathVariable @NotNull UUID id, @RequestBody @Valid @NotNull SetPartyLeadRequest request) {
    ParticipantTarget target =
        participantTargetResolver.resolve(
            request.userId(), request.guestName(), "Party lead name is ambiguous.");
    return redactForPeer(
        missionMapper.toDto(
            missionService.setPartyLead(
                id, target.userId(), target.guestName(), request.version())));
  }

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
   * Locates a participant inside a mission aggregate by id, or throws {@link NotFoundException}.
   *
   * @param mission mission aggregate
   * @param participantId participant id to find
   * @return the matching participant
   */
  private MissionParticipant findParticipant(@NotNull Mission mission, UUID participantId) {
    return Entities.require(
        mission.getParticipants().stream().filter(p -> participantId.equals(p.getId())).findFirst(),
        "Participant not found");
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
  @PostMapping("/{id}/units/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Add a unit to a mission (slim response)",
      description =
          "Adds a new unit and returns the updated list of units as slim DTOs. "
              + "Preferred replacement for POST /api/v1/missions/{id}/units to support "
              + "multi-user concurrency on the mission detail page.")
  public List<MissionUnitDto> addUnitSlim(
      @PathVariable @NotNull UUID id, @Valid @RequestBody @NotNull AddUnitRequest request) {
    var mission =
        missionService.addUnitToMission(
            id,
            request.name(),
            request.shipTypeId(),
            request.shipId(),
            request.isHighValueUnit(),
            request.frequency(),
            request.responsibleUserId(),
            request.note());
    return redactUnitsForPeer(
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
  @PutMapping("/{id}/units/{unitId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Update a mission unit (slim response)",
      description = "Updates a unit and returns only the updated unit as a slim DTO.")
  public MissionUnitDto updateUnitSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @Valid @RequestBody @NotNull UpdateUnitRequest request) {
    var mission =
        missionService.updateMissionUnit(
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
    return redactForPeer(missionMapper.toDto(findUnit(mission, unitId)));
  }

  /**
   * Deletes a unit; returns 204.
   *
   * @param id mission id
   * @param unitId unit id
   * @return 204 No Content
   */
  @DeleteMapping("/{id}/units/{unitId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Delete a mission unit (slim response)",
      description = "Deletes a unit and returns 204 No Content.")
  public ResponseEntity<Void> deleteUnitSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID unitId) {
    missionService.removeMissionUnit(id, unitId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Appends an Ablauf step, guarded by the mission's {@code stepsVersion}, and returns the ordered
   * step list.
   *
   * @param id mission id
   * @param request the step payload (title, optional meta, expected stepsVersion)
   * @return the mission's ordered Ablauf steps after the add
   */
  @PostMapping("/{id}/steps/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Add an Ablauf step to a mission (slim response)",
      description =
          "Adds a procedure-timeline step and returns the mission's ordered step list as slim"
              + " DTOs.")
  public List<MissionStepDto> addStepSlim(
      @PathVariable @NotNull UUID id, @Valid @RequestBody @NotNull AddMissionStepRequest request) {
    var mission =
        missionService.addStep(id, request.title(), request.meta(), request.stepsVersion());
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
  @PutMapping("/{id}/steps/{stepId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Update an Ablauf step (slim response)",
      description = "Edits a step's title / time-place hint and returns the ordered step list.")
  public List<MissionStepDto> updateStepSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @Valid @RequestBody @NotNull UpdateMissionStepRequest request) {
    var mission =
        missionService.updateStep(
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
  @DeleteMapping("/{id}/steps/{stepId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Delete an Ablauf step (slim response)",
      description = "Removes a step, re-packs the order, and returns the ordered step list.")
  public List<MissionStepDto> deleteStepSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @RequestParam @NotNull Long stepsVersion) {
    var mission = missionService.deleteStep(id, stepId, stepsVersion);
    return toStepDtos(mission);
  }

  /**
   * Reorders the mission's Ablauf steps and returns the new ordered step list.
   *
   * @param id mission id
   * @param request the desired step-id order + expected stepsVersion
   * @return the mission's ordered Ablauf steps after the reorder
   */
  @PutMapping("/{id}/steps/reorder/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Reorder a mission's Ablauf steps (slim response)",
      description = "Reorders the procedure timeline and returns the ordered step list.")
  public List<MissionStepDto> reorderStepsSlim(
      @PathVariable @NotNull UUID id,
      @Valid @RequestBody @NotNull ReorderMissionStepsRequest request) {
    var mission = missionService.reorderSteps(id, request.stepIds(), request.stepsVersion());
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
  @PatchMapping("/{id}/steps/{stepId}/done/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Toggle an Ablauf step's done flag (slim response)",
      description = "Sets a step's shared done flag and returns the ordered step list.")
  public List<MissionStepDto> toggleStepDoneSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID stepId,
      @Valid @RequestBody @NotNull ToggleMissionStepRequest request) {
    var mission = missionService.toggleStepDone(id, stepId, request.done(), request.stepsVersion());
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
  @PostMapping("/{id}/objectives/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Add a goal (Ziel) to a mission (slim response)",
      description =
          "Adds a classified goal and returns the mission's ordered goal list as slim DTOs.")
  public List<MissionObjectiveDto> addObjectiveSlim(
      @PathVariable @NotNull UUID id,
      @Valid @RequestBody @NotNull AddMissionObjectiveRequest request) {
    var mission =
        missionService.addObjective(
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
  @PutMapping("/{id}/objectives/{objectiveId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Update a mission goal (slim response)",
      description = "Edits a goal's text / classification and returns the ordered goal list.")
  public List<MissionObjectiveDto> updateObjectiveSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @Valid @RequestBody @NotNull UpdateMissionObjectiveRequest request) {
    var mission =
        missionService.updateObjective(
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
  @DeleteMapping("/{id}/objectives/{objectiveId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Delete a mission goal (slim response)",
      description = "Removes a goal, re-packs the order, and returns the ordered goal list.")
  public List<MissionObjectiveDto> deleteObjectiveSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID objectiveId,
      @RequestParam @NotNull Long objectivesVersion) {
    var mission = missionService.deleteObjective(id, objectiveId, objectivesVersion);
    return toObjectiveDtos(mission);
  }

  /**
   * Reorders the mission's goals and returns the new ordered goal list.
   *
   * @param id mission id
   * @param request the desired goal-id order + expected objectivesVersion
   * @return the mission's ordered goals after the reorder
   */
  @PutMapping("/{id}/objectives/reorder/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Reorder a mission's goals (slim response)",
      description = "Reorders the goal list and returns the ordered goal list.")
  public List<MissionObjectiveDto> reorderObjectivesSlim(
      @PathVariable @NotNull UUID id,
      @Valid @RequestBody @NotNull ReorderMissionObjectivesRequest request) {
    var mission =
        missionService.reorderObjectives(id, request.objectiveIds(), request.objectivesVersion());
    return toObjectiveDtos(mission);
  }

  /** Projects a mission's goals into an ordered list of slim DTOs (by {@code orderIndex}). */
  private List<MissionObjectiveDto> toObjectiveDtos(@NotNull Mission m) {
    return m.getObjectives().stream()
        .sorted(Comparator.comparingInt(MissionObjective::getOrderIndex))
        .map(missionMapper::toDto)
        .toList();
  }

  /**
   * Lists the ships a unit of this mission may be crewed with: ships owned by registered
   * participants of any org unit plus ships already pinned to one of its units.
   *
   * @param id mission id
   * @return the candidate ships for this mission's unit ship pickers
   */
  @GetMapping("/{id}/unit-ship-options")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Transactional(readOnly = true)
  @Operation(
      summary = "List selectable ships for a mission's units",
      description =
          "Returns the ships a unit of this mission may be crewed with: ships owned by registered "
              + "participants (regardless of OrgUnit) plus ships already assigned to a unit of the "
              + "mission. Restricted to callers who may manage the mission.")
  public List<ShipDto> getUnitShipOptions(@PathVariable @NotNull UUID id) {
    return redactShipsForPeer(
        missionService.getSelectableUnitShips(id).stream().map(shipMapper::toDto).toList());
  }

  /**
   * Adds crew and returns only the affected unit's crew list (slim).
   *
   * @param id mission id
   * @param missionUnitId unit id
   * @param request crew payload (participant + job types)
   * @return the updated crew list of the unit
   */
  @PostMapping("/{id}/units/{missionUnitId}/crew/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
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
        missionService.addCrewToShip(id, missionUnitId, request.participantId(), jobTypeIds);
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
  @PutMapping("/{id}/units/{missionUnitId}/crew/{crewId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
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
        missionService.updateCrewInShip(id, missionUnitId, crewId, request.version(), jobTypeIds);
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
  @DeleteMapping("/{id}/units/{missionUnitId}/crew/{crewId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Remove crew from a mission unit (slim response)",
      description = "Removes a crew member and returns 204 No Content.")
  public ResponseEntity<Void> removeCrewSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID missionUnitId,
      @PathVariable @NotNull UUID crewId) {
    missionService.removeCrewFromShip(id, missionUnitId, crewId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Updates a participant and returns only the updated participant (slim).
   *
   * @param id mission id
   * @param participantId participant id
   * @param request participant payload (carries the expected participant version)
   * @return the updated participant DTO
   */
  @PutMapping("/{id}/participants/{participantId}/slim")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Update a participant (slim response)",
      description = "Updates a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto updateParticipantSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody @Valid @NotNull UpdateParticipantRequest request,
      Authentication authentication) {
    var mission =
        missionService.updateParticipantAttributes(
            id,
            participantId,
            request.desiredMissionJobTypeId(),
            request.plannedMissionJobTypeId(),
            request.comment(),
            request.startTime(),
            request.endTime(),
            request.orgUnitIds(),
            request.payoutPreference(),
            request.guestName(),
            request.version(),
            authentication);
    return redactForPeer(missionMapper.toDto(findParticipant(mission, participantId)));
  }

  /**
   * Slim check-in. Stamps {@code startTime} on the participant.
   *
   * @param id mission id
   * @param participantId participant id
   * @return the updated participant DTO, redacted below Logistician (REQ-SEC-007)
   */
  @PostMapping("/{id}/participants/{participantId}/check-in/slim")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Check in a participant (slim response)",
      description =
          "Checks in a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto checkInParticipantSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID participantId) {
    var mission = missionService.checkIn(id, participantId);
    return redactForPeer(missionMapper.toDto(findParticipant(mission, participantId)));
  }

  /**
   * Slim check-out. Stamps {@code endTime} on the participant.
   *
   * @param id mission id
   * @param participantId participant id
   * @return the updated participant DTO, redacted below Logistician (REQ-SEC-007)
   */
  @PostMapping("/{id}/participants/{participantId}/check-out/slim")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Check out a participant (slim response)",
      description =
          "Checks out a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto checkOutParticipantSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID participantId) {
    var mission = missionService.checkOut(id, participantId);
    return redactForPeer(missionMapper.toDto(findParticipant(mission, participantId)));
  }

  /**
   * Updates a participant's payout preference; {@code DONATE} stays sticky for the whole operation.
   *
   * @param id mission id
   * @param participantId participant id
   * @param request payout preference payload
   * @return the updated participant DTO, redacted below Logistician (REQ-SEC-007)
   */
  @PutMapping("/{id}/participants/{participantId}/payout-preference/slim")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Update payout preference for a participant (slim response)",
      description =
          "Updates the payout preference and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto updatePayoutPreferenceSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody @Valid @NotNull UpdatePayoutPreferenceRequest request) {
    var mission = missionService.updatePayoutPreference(id, participantId, request.preference());
    return redactForPeer(missionMapper.toDto(findParticipant(mission, participantId)));
  }

  /**
   * Adds a participant with the same rules as {@link #addParticipantPublic} and returns only the
   * updated participant list.
   *
   * @param id mission id
   * @param request add-participant payload (userId XOR guestName + meta)
   * @param authentication current Spring Security authentication
   * @return the updated participant list
   */
  @PostMapping("/{id}/participants/slim")
  @Operation(
      summary = "Add a participant (slim response)",
      description =
          "Adds a participant and returns the updated participant list as slim DTOs. Mirrors the"
              + " public add-participant logic: explicit userId (autocomplete) or free-text"
              + " guestName (case-insensitive resolution against registered users). Callers may"
              + " always add themselves; adding anyone else is restricted to"
              + " managers/officers/admins.")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @ownerScopeService.canSeeMission(#id)")
  public List<MissionParticipantDto> addParticipantSlim(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull AddExternalParticipantRequest request,
      Authentication authentication) {
    ParticipantTarget target = resolveParticipantTarget(id, request, authentication);
    var mission =
        missionService.addParticipant(
            id,
            target.userId(),
            target.guestName(),
            request.desiredJobTypeId(),
            request.comment(),
            request.orgUnitIds(),
            request.payoutPreference());
    return redactParticipantsForPeer(
        mission.getParticipants().stream().map(missionMapper::toDto).toList());
  }

  /**
   * Adds one registered member, named by {@code app_user} id, to the roster; manager-only
   * (REQ-MISSION-020).
   *
   * <p>Unlike {@link #addParticipantSlim} it accepts no free-text name, which is why it is admitted
   * on the public API vhost (ADR-0170).
   *
   * @param id mission id
   * @param request the member to add
   * @return the mission's participant list after the add, peer-redacted below Logistician
   */
  @PostMapping("/{id}/participants/by-id/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Add a registered member by id (manager-only, slim response)",
      description =
          "Adds the registered member named by userId as a participant and returns the updated"
              + " participant list as slim DTOs. Restricted to callers who may manage the mission;"
              + " takes no free-text name. The member's org units and payout default are stamped"
              + " exactly as on self-enrolment.")
  public List<MissionParticipantDto> addParticipantByIdSlim(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull AddParticipantByIdRequest request) {
    var mission = missionService.addParticipant(id, request.userId(), null, null, null, null, null);
    return redactParticipantsForPeer(
        mission.getParticipants().stream().map(missionMapper::toDto).toList());
  }

  /**
   * Removes a participant; returns 204.
   *
   * @param id mission id
   * @param participantId participant id
   * @param authentication current Spring Security authentication
   * @return 204 No Content
   */
  @DeleteMapping("/{id}/participants/{participantId}/slim")
  @PreAuthorize(
      "isAuthenticated() and @authHelperService.isMemberOrAbove()"
          + " and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Remove a participant (slim response)",
      description = "Removes a participant and returns 204 No Content.")
  public ResponseEntity<Void> removeParticipantSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      Authentication authentication) {
    missionService.removeParticipant(id, participantId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Participants of a mission that are not yet on any unit's crew roster — drives the "unassigned"
   * tray on the mission detail page.
   *
   * @param id mission id
   * @return unassigned participant DTOs
   */
  @GetMapping("/{id}/participants/unassigned")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(
      summary = "Get unassigned participants",
      description =
          "Returns all participants of a mission that are not yet assigned to any unit crew.")
  public List<MissionParticipantDto> getUnassignedParticipants(@PathVariable @NotNull UUID id) {
    return redactParticipantsForPeer(
        missionService.getUnassignedParticipants(id).stream().map(missionMapper::toDto).toList());
  }

  /**
   * Upserts a frequency by type; returns only the updated frequency list (slim).
   *
   * @param id mission id
   * @param request frequency payload (type + value)
   * @return the updated frequency list
   */
  @PostMapping("/{id}/frequencies/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
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
  @DeleteMapping("/{id}/frequencies/{frequencyId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
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
  @PostMapping("/{id}/frequencies/custom/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
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
  @PutMapping("/{id}/frequencies/custom/{frequencyId}/slim")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
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

  /**
   * Adds a manager; returns the updated manager list as {@link UserReferenceDto}s (id + label
   * only).
   *
   * @param id mission id
   * @param userId user id to add as manager
   * @return the updated manager list
   */
  @PostMapping("/{id}/managers/{userId}/slim")
  @PreAuthorize("@missionSecurityService.canManageManagers(#id, authentication)")
  @Operation(
      summary = "Add a manager to a mission (slim response)",
      description = "Adds a manager and returns the updated manager list as UserReferenceDto.")
  public List<UserReferenceDto> addManagerSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    var mission = missionService.addManager(id, userId);
    return mission.getManagers().stream().map(userMapper::toReferenceDto).toList();
  }

  /**
   * Removes a manager; returns 204.
   *
   * @param id mission id
   * @param userId user id to remove from managers
   * @return 204 No Content
   */
  @DeleteMapping("/{id}/managers/{userId}/slim")
  @PreAuthorize("@missionSecurityService.canManageManagers(#id, authentication)")
  @Operation(
      summary = "Remove a manager from a mission (slim response)",
      description = "Removes a manager and returns 204 No Content.")
  public ResponseEntity<Void> removeManagerSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    missionService.removeManager(id, userId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Resolves whom a participant add names and checks that the caller may name them.
   *
   * <ol>
   *   <li>Neither id nor name means the caller.
   *   <li>A free-text name is resolved through {@link ParticipantTargetResolver}.
   *   <li>Naming any registered member other than the caller requires {@code canManageMission}.
   * </ol>
   *
   * @param id the mission
   * @param request the submitted id, name and sign-up answers
   * @param authentication the caller, for the {@code canManageMission} evaluation
   * @return the resolved target the service records
   * @throws BusinessConflictException when the name matches more than one member
   * @throws AccessDeniedException when a non-manager names somebody other than themselves
   */
  private ParticipantTarget resolveParticipantTarget(
      @NotNull UUID id,
      @NotNull AddExternalParticipantRequest request,
      Authentication authentication) {
    UUID callerId = authHelperService.currentUserId().orElse(null);
    UUID requestedUserId = request.userId();
    String guestName = request.guestName();
    if (requestedUserId == null && (guestName == null || guestName.isBlank())) {
      requestedUserId = callerId;
    }

    ParticipantTarget target =
        participantTargetResolver.resolve(
            requestedUserId, guestName, "Participant name is ambiguous.");

    if (target.userId() != null
        && (callerId == null || !target.userId().equals(callerId))
        && !missionSecurityService.canManageMission(id, authentication)) {
      throw new AccessDeniedException("Only mission managers may add other users as participants.");
    }
    return target;
  }

  /**
   * Maps a page of missions to list rows, loading all registration counts in one grouped query
   * (REQ-DATA-003).
   *
   * @param missions the page as the service returned it.
   * @return the same page as list DTOs, each carrying its registration count.
   */
  private Page<MissionListDto> withRegisteredCounts(@NotNull Page<Mission> missions) {
    Map<UUID, Long> counts =
        missionService.registeredCounts(
            missions.getContent().stream().map(Mission::getId).toList());
    return missions.map(
        mission -> missionMapper.toListDto(mission, counts.getOrDefault(mission.getId(), 0L)));
  }

  /**
   * Applies the peer redaction to a mission DTO for callers below Logistician (REQ-SEC-007).
   *
   * @param dto the freshly mapped DTO
   * @return the same DTO for Logistician-and-above, the peer-redacted copy for everyone else
   */
  private MissionDto redactForPeer(MissionDto dto) {
    return authHelperService.isLogisticianOrAbove()
        ? dto
        : missionPeerRedactor.cleanupMissionForPeer(dto);
  }

  /**
   * Peer pass for a single unit DTO (the slim unit endpoints).
   *
   * @param dto the freshly mapped unit
   * @return the same DTO for Logistician-and-above, otherwise one whose nested ship owner is
   *     reduced to the public callsign tuple (REQ-SEC-040)
   */
  private MissionUnitDto redactForPeer(MissionUnitDto dto) {
    return authHelperService.isLogisticianOrAbove()
        ? dto
        : missionPeerRedactor.cleanupUnitForPeer(dto);
  }

  /**
   * Peer pass for a single participant row (the slim participant endpoints).
   *
   * @param dto the freshly mapped participant
   * @return the same DTO for Logistician-and-above, otherwise one whose nested user is reduced to
   *     the public callsign tuple — no e-mail, no real name (REQ-SEC-007)
   */
  private MissionParticipantDto redactForPeer(MissionParticipantDto dto) {
    return authHelperService.isLogisticianOrAbove()
        ? dto
        : missionPeerRedactor.cleanupParticipantForPeer(dto);
  }

  /**
   * Peer pass for a participant list.
   *
   * @param participants the freshly mapped roster rows
   * @return the same list for Logistician-and-above, otherwise one whose nested users are reduced
   *     to the public callsign tuple
   */
  private List<MissionParticipantDto> redactParticipantsForPeer(
      List<MissionParticipantDto> participants) {
    return authHelperService.isLogisticianOrAbove()
        ? participants
        : participants.stream().map(missionPeerRedactor::cleanupParticipantForPeer).toList();
  }

  /**
   * Peer pass for a unit list.
   *
   * @param units the freshly mapped units
   * @return the same list for Logistician-and-above, otherwise a redacted copy
   */
  private List<MissionUnitDto> redactUnitsForPeer(List<MissionUnitDto> units) {
    return authHelperService.isLogisticianOrAbove()
        ? units
        : units.stream().map(missionPeerRedactor::cleanupUnitForPeer).toList();
  }

  /**
   * Applies the peer redaction to the unit ship picker's options, hiding the owners' personal data
   * (REQ-SEC-040).
   *
   * @param ships the selectable ships
   * @return the same list for Logistician-and-above, otherwise a redacted copy
   */
  private List<ShipDto> redactShipsForPeer(List<ShipDto> ships) {
    return authHelperService.isLogisticianOrAbove()
        ? ships
        : ships.stream().map(missionPeerRedactor::cleanupShipForPeer).toList();
  }
}
