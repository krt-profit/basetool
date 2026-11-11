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

import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.mission.internal.AddExternalParticipantRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.JoinMissionRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionAccessPolicy;
import de.greluc.krt.profit.basetool.backend.mission.internal.MissionParticipantService;
import de.greluc.krt.profit.basetool.backend.mission.internal.ParticipantTargetResolver;
import de.greluc.krt.profit.basetool.backend.mission.internal.ParticipantTargetResolver.ParticipantTarget;
import de.greluc.krt.profit.basetool.backend.mission.internal.SetPartyLeadRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdateParticipantRequest;
import de.greluc.krt.profit.basetool.backend.mission.internal.UpdatePayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mission roster, attendance and participant preference HTTP handlers. */
@RestController
@RequestMapping("/api/v1/missions")
@RequiredArgsConstructor
@Tag(name = "Missions", description = "Mission management endpoints")
@Transactional
public class MissionRosterController {

  private final MissionParticipantService missionParticipantService;
  private final UserService userService;
  private final MissionMapper missionMapper;
  private final MissionAccessPolicy missionAccessPolicy;
  private final AuthHelperService authHelperService;
  private final ParticipantTargetResolver participantTargetResolver;
  private final MissionHttpProjection projection;

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
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  public MissionDto joinMission(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody(required = false) @Valid JoinMissionRequest request) {
    MissionDto dto =
        missionMapper.toDto(
            missionParticipantService.addParticipant(
                id,
                userService.getUserIdFromJwt(jwt),
                null,
                request == null ? null : request.desiredJobTypeId(),
                null,
                null,
                request == null ? null : request.payoutPreference()));
    return projection.cleanupForPeer(dto);
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
  @PreAuthorize("isAuthenticated()")
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
    return projection.cleanupForPeer(
        missionMapper.toDto(
            missionParticipantService.setPartyLead(
                id, target.userId(), target.guestName(), request.version())));
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
   * Updates a participant and returns only the updated participant (slim).
   *
   * @param id mission id
   * @param participantId participant id
   * @param request participant payload (carries the expected participant version)
   * @return the updated participant DTO
   */
  @PutMapping("/{id}/participants/{participantId}")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Operation(
      summary = "Update a participant (slim response)",
      description = "Updates a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto updateParticipantSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody @Valid @NotNull UpdateParticipantRequest request,
      Authentication authentication) {
    var mission =
        missionParticipantService.updateParticipantAttributes(
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
    return projection.cleanupForPeer(missionMapper.toDto(findParticipant(mission, participantId)));
  }

  /**
   * Slim check-in. Stamps {@code startTime} on the participant.
   *
   * @param id mission id
   * @param participantId participant id
   * @return the updated participant DTO, redacted below Logistician (REQ-SEC-007)
   */
  @PostMapping("/{id}/participants/{participantId}/check-in")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Operation(
      summary = "Check in a participant (slim response)",
      description =
          "Checks in a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto checkInParticipantSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID participantId) {
    var mission = missionParticipantService.checkIn(id, participantId);
    return projection.cleanupForPeer(missionMapper.toDto(findParticipant(mission, participantId)));
  }

  /**
   * Slim check-out. Stamps {@code endTime} on the participant.
   *
   * @param id mission id
   * @param participantId participant id
   * @return the updated participant DTO, redacted below Logistician (REQ-SEC-007)
   */
  @PostMapping("/{id}/participants/{participantId}/check-out")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Operation(
      summary = "Check out a participant (slim response)",
      description =
          "Checks out a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto checkOutParticipantSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID participantId) {
    var mission = missionParticipantService.checkOut(id, participantId);
    return projection.cleanupForPeer(missionMapper.toDto(findParticipant(mission, participantId)));
  }

  /**
   * Updates a participant's payout preference; {@code DONATE} stays sticky for the whole operation.
   *
   * @param id mission id
   * @param participantId participant id
   * @param request payout preference payload
   * @return the updated participant DTO, redacted below Logistician (REQ-SEC-007)
   */
  @PutMapping("/{id}/participants/{participantId}/payout-preference")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Operation(
      summary = "Update payout preference for a participant (slim response)",
      description =
          "Updates the payout preference and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto updatePayoutPreferenceSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody @Valid @NotNull UpdatePayoutPreferenceRequest request) {
    var mission =
        missionParticipantService.updatePayoutPreference(id, participantId, request.preference());
    return projection.cleanupForPeer(missionMapper.toDto(findParticipant(mission, participantId)));
  }

  /**
   * Adds a participant, an explicit user or a guest name, and returns only the updated participant
   * list.
   *
   * @param id mission id
   * @param request add-participant payload (userId XOR guestName + meta)
   * @param authentication current Spring Security authentication
   * @return the updated participant list
   */
  @PostMapping("/{id}/participants")
  @Operation(
      summary = "Add a participant (slim response)",
      description =
          "Adds a participant and returns the updated participant list as slim DTOs. Mirrors the"
              + " public add-participant logic: explicit userId (autocomplete) or free-text"
              + " guestName (case-insensitive resolution against registered users). Callers may"
              + " always add themselves; adding anyone else is restricted to"
              + " managers/officers/admins.")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  public List<MissionParticipantDto> addParticipantSlim(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull AddExternalParticipantRequest request,
      Authentication authentication) {
    ParticipantTarget target = resolveParticipantTarget(id, request, authentication);
    var mission =
        missionParticipantService.addParticipant(
            id,
            target.userId(),
            target.guestName(),
            request.desiredJobTypeId(),
            request.comment(),
            request.orgUnitIds(),
            request.payoutPreference());
    return projection.cleanupParticipantsForPeer(
        mission.getParticipants().stream().map(missionMapper::toDto).toList());
  }

  /**
   * Removes a participant; returns 204.
   *
   * @param id mission id
   * @param participantId participant id
   * @return 204 No Content
   */
  @DeleteMapping("/{id}/participants/{participantId}")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Operation(
      summary = "Remove a participant (slim response)",
      description = "Removes a participant and returns 204 No Content.")
  public ResponseEntity<Void> removeParticipantSlim(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID participantId) {
    missionParticipantService.removeParticipant(id, participantId);
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
  @PreAuthorize("@missionAccessPolicy.canManageMission(#id, authentication)")
  @Operation(
      summary = "Get unassigned participants",
      description =
          "Returns all participants of a mission that are not yet assigned to any unit crew.")
  public List<MissionParticipantDto> getUnassignedParticipants(@PathVariable @NotNull UUID id) {
    return projection.cleanupParticipantsForPeer(
        missionParticipantService.getUnassignedParticipants(id).stream()
            .map(missionMapper::toDto)
            .toList());
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
        && !missionAccessPolicy.canManageMission(id, authentication)) {
      throw new AccessDeniedException("Only mission managers may add other users as participants.");
    }
    return target;
  }
}
