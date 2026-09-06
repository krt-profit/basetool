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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mapper.ShipMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.AddCrewRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.AddParticipantPublicRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.AddParticipantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.AddUnitRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.JoinMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionCrewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionObjectiveDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionStepDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionUnitDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.ShipDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateCrewRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateParticipantRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdatePayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UpdateUnitRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.MissionSecurityService;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.MissionPeerRedactor;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
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
 * REST surface over the mission aggregate — the squadron's planning and execution view. The surface
 * is intentionally large because missions have many sub-aggregates (units, crew, participants,
 * frequencies, managers, ownership) and Option A / multi-user concurrency required a second family
 * of "slim" endpoints alongside the legacy MissionDto-returning ones.
 *
 * <p>Two endpoint families live side-by-side:
 *
 * <ul>
 *   <li><b>Section patches</b> ({@code /core}, {@code /schedule}, {@code /flags}) split the mission
 *       header into independently versioned sections so two managers editing different sections do
 *       not collide on {@code Mission.version}.
 *   <li><b>Slim sub-resource endpoints</b> ({@code .../slim}) return only the affected sub-DTO
 *       instead of the full {@link MissionDto}. Behaviour is identical to the legacy
 *       MissionDto-returning sibling; only the response shape differs. Legacy endpoints carry
 *       {@code @Deprecated(forRemoval=true)} with sunset {@value #SLIM_DEPRECATION_SUNSET}.
 * </ul>
 *
 * <p>Guest reads are heavily redacted: internal and past missions are hidden, and {@link
 * MissionPeerRedactor#cleanupMissionForPeer} strips names, emails, internal inventory and refinery
 * orders before the DTO leaves the controller. {@code addParticipantPublic} additionally resolves
 * free-text guest names against registered users to prevent impersonation.
 *
 * <p>Authorisation is delegated to {@link MissionSecurityService} via SpEL ({@code
 * canManageMission}, {@code canAccessParticipant}, {@code canManageManagers}, {@code
 * canChangeOwner}). Owner changes use the dedicated {@code MissionOwnership} aggregate with its own
 * version, so they do not invalidate other users' open mission forms.
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

  /** Sunset date for legacy sub-section endpoints that still return the full MissionDto. */
  private static final String SLIM_DEPRECATION_SUNSET = "2026-10-20";

  /**
   * Paged mission list, scoped to the calling member.
   *
   * <p>There used to be a second branch here for mission outsiders — anonymous callers and
   * role-less {@code GUEST} accounts — silently restricted to {@code PLANNED}+{@code ACTIVE}
   * non-internal missions. Neither caller can reach this endpoint any more (ADR-0159), so the
   * restriction has no audience and the branch is gone.
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
    Page<Mission> pageResult;
    {
      // Every caller MUST go through searchMissions so the org-unit scope (own Staffel OR
      // is_internal=false organisation-wide) is applied — getAllMissions would call
      // missionRepository.findAll() unfiltered and leak internal missions of other squadrons to
      // every authenticated user (MULTI_SQUADRON_PLAN.md section 1).
      pageResult =
          missionService.searchMissions(
              null,
              null,
              null,
              List.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED"),
              null,
              null,
              pageable);
    }
    return PageResponse.of(withRegisteredCounts(pageResult));
  }

  /**
   * Lightweight projection (id + label) of the missions offered by the warehouse mission picker:
   * every active mission plus the {@code COMPLETED} / {@code CANCELLED} ones from the last three
   * months. See {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService#findAllActiveReference()}.
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
  public List<de.greluc.krt.profit.basetool.backend.model.dto.MissionReferenceDto>
      lookupMissions() {
    return missionService.findAllActiveReference();
  }

  /**
   * Filtered + paged mission search, scoped to the calling member.
   *
   * <p>The outsider branch that restricted anonymous and role-less callers to {@code PLANNED}+
   * {@code ACTIVE} non-internal missions is gone with its audience (ADR-0159).
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
   * Single-mission read. A member below Logistician {@code GUEST} accounts (see {@link
   * de.greluc.krt.profit.basetool.backend.service.AuthHelperService#isMemberOrAbove()}) — are
   * blocked from internal and past missions (403) and get the strict redaction via {@link
   * MissionPeerRedactor#cleanupOutsiderMissionForPeer}. Registered members and above see the full
   * DTO.
   *
   * @param id mission id
   * @return the mission DTO
   */
  @GetMapping("/{id}")
  @Operation(summary = "Get mission by ID")
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeMission(#id)")
  @Transactional(readOnly = true)
  public MissionDto getMissionById(@PathVariable @NotNull UUID id) {
    var mission = missionService.getMissionById(id);
    var dto = missionMapper.toDto(mission);
    // REQ-SEC-007: a member below Logistician reads the roster without its PII. The two throws that
    // stood here — internal missions and terminal ones refused outright — belonged to the outsider
    // tier, whose whole audience (anonymous and role-less callers) no longer exists (ADR-0159).
    // Visibility itself is unchanged and is decided by canSeeMission above, which is where the
    // internal-mission rule always lived for members.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupMissionForPeer(dto);
    }
    return dto;
  }

  /**
   * Returns the next upcoming mission (or 204 when none). Only {@code PLANNED} / {@code ACTIVE}
   * missions are eligible — a terminal ({@code COMPLETED} / {@code CANCELLED}) mission with a
   * future planned start is never the next mission (REQ-MISSION-003). Internal missions are
   * included for every member in scope; a member below Logistician gets the same peer redaction
   * pass as {@link #getMissionById}.
   *
   * @return mission DTO or 204 No Content
   */
  @GetMapping("/next")
  @Operation(summary = "Get next upcoming mission")
  @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()")
  @Transactional(readOnly = true)
  public ResponseEntity<MissionDto> getNextMission() {
    return missionService
        .getNextMission(true)
        .map(
            m -> {
              var dto = missionMapper.toDto(m);
              if (!authHelperService.isLogisticianOrAbove()) {
                dto = missionPeerRedactor.cleanupMissionForPeer(dto);
              }
              return ResponseEntity.ok(dto);
            })
        .orElse(ResponseEntity.noContent().build());
  }

  /**
   * Creates a new mission. The caller becomes the owner via {@link MissionService#createMission}.
   * The {@link de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest} record
   * structurally excludes {@code id} / {@code version} / {@code owningSquadron} / {@code parent} /
   * {@code owner} / collections (audit finding C-3) — those are stamped server-side.
   *
   * @param request create payload
   * @return the persisted DTO
   */
  @PostMapping
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Create a new mission")
  public MissionDto createMission(
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest request) {
    return missionMapper.toDto(missionService.createMission(request));
  }

  /**
   * Attaches a new sub-mission to a parent. Sub-missions are independent missions that aggregate up
   * to the parent for finance/payout roll-ups. Uses the same {@link
   * de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest} as the top-level
   * create — {@code parent} and {@code owningSquadron} are stamped from the path-resolved parent
   * (audit finding C-3).
   *
   * @param id parent mission id
   * @param request create payload for the sub-mission
   * @return the persisted parent DTO with the new sub-mission attached
   */
  @PostMapping("/{id}/sub-missions")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @Operation(summary = "Create a sub-mission")
  public MissionDto createSubMission(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest request) {
    return missionMapper.toDto(missionService.addSubMission(id, request));
  }

  /**
   * Full-replace update. Bumps {@code Mission.version}, so any second user editing the mission
   * concurrently will get a 409 on their next save. Prefer the section patches ({@link
   * #patchMissionCore}, {@link #patchMissionSchedule}, {@link #patchMissionFlags}) for
   * multi-user-friendly edits.
   *
   * @param id mission id
   * @param request update payload (carries the expected version); structurally excludes server-
   *     managed fields ({@code id}, {@code owningSquadron}, {@code parent}, {@code owner}, …) to
   *     close the audit-finding-C-3 mass-assignment vector
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
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest request) {
    return missionMapper.toDto(missionService.updateMission(id, request));
  }

  /**
   * Patches the core header section (name, description, calendar link, status). Uses the dedicated
   * core-section version so a parallel edit of schedule/flags does not invalidate this form.
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
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionCoreRequest request) {
    return missionMapper.toDto(
        missionService.updateCoreSection(
            id,
            request.name(),
            request.description(),
            request.calendarLink(),
            request.status(),
            request.operationId(),
            request.meetingPoint(),
            request.version()));
  }

  /**
   * Patches the schedule section (meeting/planned/actual times). All times in UTC. Schedule has its
   * own version so participants, units and finances editing in parallel does not collide.
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
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionScheduleRequest
              request) {
    return missionMapper.toDto(
        missionService.updateScheduleSection(
            id,
            request.meetingTime(),
            request.plannedStartTime(),
            request.plannedEndTime(),
            request.actualStartTime(),
            request.actualEndTime(),
            request.version()));
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
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionFlagsRequest
              request) {
    return missionMapper.toDto(
        missionService.updateFlagsSection(id, request.isInternal(), request.version()));
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
   * Self-enrolment shortcut — the caller adds themselves as participant. For adding others, use
   * {@link #addParticipantPublic} or the slim {@code addParticipantSlim}.
   *
   * <p><b>The body is optional</b> ({@link JoinMissionRequest}) and carries the two answers a
   * sign-up sheet collects — the desired Funktion and the payout preference. It was added on
   * 2026-09-02 so a client with a sign-up sheet no longer has to reach for {@code
   * /participants/add} to carry them: that endpoint can name anybody and is deliberately not on the
   * API vhost's allow-list, so the Android app's sign-up was refused at the edge and never reached
   * this service at all (ADR-0154). Adding an <em>optional</em> body is the additive half of
   * REQ-API-009, which freezes this operation — a bodyless {@code POST} keeps behaving exactly as
   * it did, which is what every shipped build sends.
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
  // SecurityConfig falls through to `anyRequest().authenticated()` for this path, but the
  // explicit `isAuthenticated()` keeps the controller honest if the URL filter is later loosened
  // — anonymous reaches the handler with a null JWT and would NPE in `getUserIdFromJwt`.
  // `canSeeMission` enforces MULTI_SQUADRON_PLAN.md §1: members of another squadron may join
  // only non-internal missions, own-squadron members + admins may join anything.
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeMission(#id)")
  public MissionDto joinMission(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @NotNull UUID id,
      @RequestBody(required = false) @jakarta.validation.Valid JoinMissionRequest request) {
    // Self-enrolment only: the user comes from the token, never from the body. Everything the
    // body can say is about the caller's own row, which is why it needs no self-vs-manager check.
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
    // REQ-SEC-007. Found by the rewritten peerReadableMissionEndpointsMustRedactPii rule, and it
    // was a real leak rather than a rule artefact: joining returns the WHOLE Einsatz, roster
    // included, and the caller here is by definition an ordinary member — the one person on the
    // mission surface most likely to be below Logistician. The old rule could not see it, because
    // it selected only gates that lacked isAuthenticated() and this one has always had it.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupMissionForPeer(dto);
    }
    return dto;
  }

  /**
   * Legacy add-unit endpoint. Returns the full {@link MissionDto}. Replaced by {@link #addUnitSlim}
   * which avoids coupling the parent {@code Mission.version} into every AJAX round-trip.
   *
   * @param id mission id
   * @param request unit payload
   * @return the persisted parent DTO
   * @deprecated use {@link #addUnitSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PostMapping("/{id}/units")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/units/slim")
  @Operation(
      summary = "Add a unit to a mission (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer POST /api/v1/missions/{id}/units/slim which returns"
              + " only the updated list of units and avoids parent-coupled payloads (Option A /"
              + " multi-user concurrency).",
      deprecated = true)
  public MissionDto addUnit(
      @PathVariable @NotNull UUID id,
      @jakarta.validation.Valid @RequestBody @NotNull AddUnitRequest request) {
    return missionMapper.toDto(
        missionService.addUnitToMission(
            id,
            request.name(),
            request.shipTypeId(),
            request.shipId(),
            request.isHighValueUnit(),
            request.frequency(),
            request.responsibleUserId(),
            request.note()));
  }

  /**
   * Legacy update-unit endpoint.
   *
   * @param id mission id
   * @param unitId unit id
   * @param request unit payload
   * @return the persisted parent DTO
   * @deprecated use {@link #updateUnitSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PutMapping("/{id}/units/{unitId}")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/units/{unitId}/slim")
  @Operation(
      summary = "Update a mission unit (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer PUT /api/v1/missions/{id}/units/{unitId}/slim which"
              + " returns only the updated unit.",
      deprecated = true)
  public MissionDto updateUnit(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID unitId,
      @jakarta.validation.Valid @RequestBody @NotNull UpdateUnitRequest request) {
    return missionMapper.toDto(
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
            request.note()));
  }

  /**
   * Legacy delete-unit endpoint.
   *
   * @param id mission id
   * @param unitId unit id
   * @return the persisted parent DTO
   * @deprecated use {@link #deleteUnitSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @DeleteMapping("/{id}/units/{unitId}")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/units/{unitId}/slim")
  @Operation(
      summary = "Delete a mission unit (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer DELETE /api/v1/missions/{id}/units/{unitId}/slim"
              + " which returns 204 No Content.",
      deprecated = true)
  public MissionDto deleteUnit(@PathVariable @NotNull UUID id, @PathVariable @NotNull UUID unitId) {
    return missionMapper.toDto(missionService.removeMissionUnit(id, unitId));
  }

  /**
   * Legacy add-crew endpoint.
   *
   * @param id mission id
   * @param missionUnitId unit id
   * @param request crew payload (participant + job types)
   * @return the persisted parent DTO
   * @deprecated use {@link #addCrewSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PostMapping("/{id}/units/{missionUnitId}/crew")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/units/{missionUnitId}/crew/slim")
  @Operation(
      summary = "Add crew to a mission unit (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer POST"
              + " /api/v1/missions/{id}/units/{missionUnitId}/crew/slim which returns only the crew"
              + " list of the affected unit.",
      deprecated = true)
  public MissionDto addCrew(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID missionUnitId,
      @RequestBody @jakarta.validation.Valid @NotNull AddCrewRequest request) {
    java.util.Set<UUID> jobTypeIds =
        request.jobTypeIds() != null ? request.jobTypeIds() : java.util.Collections.emptySet();
    return missionMapper.toDto(
        missionService.addCrewToShip(id, missionUnitId, request.participantId(), jobTypeIds));
  }

  /**
   * Legacy update-crew endpoint — replaces the job-type set of a crew entry.
   *
   * @param id mission id
   * @param missionUnitId unit id
   * @param crewId crew entry id
   * @param request crew payload
   * @return the persisted parent DTO
   * @deprecated use {@link #updateCrewSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PutMapping("/{id}/units/{missionUnitId}/crew/{crewId}")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/units/{missionUnitId}/crew/{crewId}/slim")
  @Operation(
      summary = "Update crew in a mission unit (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer PUT"
              + " /api/v1/missions/{id}/units/{missionUnitId}/crew/{crewId}/slim which returns only"
              + " the updated crew entry.",
      deprecated = true)
  public MissionDto updateCrew(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID missionUnitId,
      @PathVariable @NotNull UUID crewId,
      @RequestBody @jakarta.validation.Valid @NotNull UpdateCrewRequest request) {
    java.util.Set<UUID> jobTypeIds =
        request.jobTypeIds() != null ? request.jobTypeIds() : java.util.Collections.emptySet();
    return missionMapper.toDto(
        missionService.updateCrewInShip(id, missionUnitId, crewId, request.version(), jobTypeIds));
  }

  /**
   * Legacy remove-crew endpoint.
   *
   * @param id mission id
   * @param missionUnitId unit id
   * @param crewId crew entry id
   * @return the persisted parent DTO
   * @deprecated use {@link #removeCrewSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @DeleteMapping("/{id}/units/{missionUnitId}/crew/{crewId}")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/units/{missionUnitId}/crew/{crewId}/slim")
  @Operation(
      summary = "Remove crew from a mission unit (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer DELETE"
              + " /api/v1/missions/{id}/units/{missionUnitId}/crew/{crewId}/slim which returns 204"
              + " No Content.",
      deprecated = true)
  public MissionDto removeCrew(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID missionUnitId,
      @PathVariable @NotNull UUID crewId) {
    return missionMapper.toDto(missionService.removeCrewFromShip(id, missionUnitId, crewId));
  }

  /**
   * Legacy update-participant endpoint.
   *
   * @param id mission id
   * @param participantId participant id
   * @param request participant payload (carries the expected participant version)
   * @param jwt caller's JWT (null for anonymous)
   * @return the persisted parent DTO (redacted via {@link
   *     MissionPeerRedactor#cleanupMissionForPeer} for anonymous callers, who reach this endpoint
   *     when editing a guest participant per {@code MissionSecurityService#canAccessParticipant})
   * @deprecated use {@link #updateParticipantSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PutMapping("/{id}/participants/{participantId}")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/participants/{participantId}/slim")
  @Operation(
      summary = "Update a participant (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer PUT"
              + " /api/v1/missions/{id}/participants/{participantId}/slim which returns only the"
              + " updated participant.",
      deprecated = true)
  public MissionDto updateParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody @jakarta.validation.Valid @NotNull UpdateParticipantRequest request,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {
    MissionDto dto =
        missionMapper.toDto(
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
                authentication));
    // REQ-SEC-007: a member below Logistician reads the roster without its PII.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupMissionForPeer(dto);
    }
    return dto;
  }

  /**
   * Legacy check-in endpoint. Stamps {@code startTime} on the participant.
   *
   * @param id mission id
   * @param participantId participant id
   * @param jwt caller's JWT (null for anonymous)
   * @return the persisted parent DTO (redacted for anonymous callers)
   * @deprecated use {@link #checkInParticipantSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PostMapping("/{id}/participants/{participantId}/check-in")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/participants/{participantId}/check-in/slim")
  @Operation(
      summary = "Check in a participant (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer POST"
              + " /api/v1/missions/{id}/participants/{participantId}/check-in/slim which returns"
              + " only the updated participant.",
      deprecated = true)
  public MissionDto checkInParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal Jwt jwt) {
    MissionDto dto = missionMapper.toDto(missionService.checkIn(id, participantId));
    // REQ-SEC-007: a member below Logistician reads the roster without its PII.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupMissionForPeer(dto);
    }
    return dto;
  }

  /**
   * Legacy check-out endpoint. Stamps {@code endTime} on the participant.
   *
   * @param id mission id
   * @param participantId participant id
   * @param jwt caller's JWT (null for anonymous)
   * @return the persisted parent DTO (redacted for anonymous callers)
   * @deprecated use {@link #checkOutParticipantSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PostMapping("/{id}/participants/{participantId}/check-out")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/participants/{participantId}/check-out/slim")
  @Operation(
      summary = "Check out a participant (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer POST"
              + " /api/v1/missions/{id}/participants/{participantId}/check-out/slim which returns"
              + " only the updated participant.",
      deprecated = true)
  public MissionDto checkOutParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal Jwt jwt) {
    MissionDto dto = missionMapper.toDto(missionService.checkOut(id, participantId));
    // REQ-SEC-007: a member below Logistician reads the roster without its PII.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupMissionForPeer(dto);
    }
    return dto;
  }

  /**
   * Legacy payout-preference endpoint. {@code DONATE} on any participant is sticky for the whole
   * operation (handled in the service). Anonymous guests reach this path for their own guest
   * participant via {@code MissionSecurityService#canAccessParticipant} and must receive a redacted
   * response.
   *
   * @param id mission id
   * @param participantId participant id
   * @param request payout preference payload
   * @param jwt caller's JWT (null for anonymous)
   * @return the persisted parent DTO (redacted for anonymous callers)
   * @deprecated use {@link #updatePayoutPreferenceSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PutMapping("/{id}/participants/{participantId}/payout-preference")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/participants/{participantId}/payout-preference/slim")
  @Operation(
      summary = "Update payout preference for a participant (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer PUT"
              + " /api/v1/missions/{id}/participants/{participantId}/payout-preference/slim which"
              + " returns only the updated participant.",
      deprecated = true)
  public MissionDto updatePayoutPreference(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody @jakarta.validation.Valid @NotNull UpdatePayoutPreferenceRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    MissionDto dto =
        missionMapper.toDto(
            missionService.updatePayoutPreference(id, participantId, request.preference()));
    // REQ-SEC-007: a member below Logistician reads the roster without its PII.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupMissionForPeer(dto);
    }
    return dto;
  }

  /**
   * Legacy admin add-participant endpoint (registered users only). The public counterpart with
   * guest support is {@link #addParticipantPublic}.
   *
   * @param id mission id
   * @param request add-participant payload (registered user id)
   * @return the persisted parent DTO
   * @deprecated use {@link #addParticipantSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PostMapping("/{id}/participants")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/participants/slim")
  @Operation(
      summary = "Add a participant (admin, legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer POST /api/v1/missions/{id}/participants/slim which"
              + " returns only the updated participant list.",
      deprecated = true)
  public MissionDto addParticipant(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid @NotNull AddParticipantRequest request) {
    return missionMapper.toDto(missionService.addParticipant(id, request.userId()));
  }

  /**
   * Legacy remove-participant endpoint.
   *
   * @param id mission id
   * @param participantId participant id
   * @param jwt caller's JWT (null for anonymous)
   * @return the persisted parent DTO (redacted for anonymous callers)
   * @deprecated use {@link #removeParticipantSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @DeleteMapping("/{id}/participants/{participantId}")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/participants/{participantId}/slim")
  @Operation(
      summary = "Remove a participant (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer DELETE"
              + " /api/v1/missions/{id}/participants/{participantId}/slim which returns 204 No"
              + " Content.",
      deprecated = true)
  public MissionDto removeParticipant(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal Jwt jwt) {
    MissionDto dto = missionMapper.toDto(missionService.removeParticipant(id, participantId));
    // REQ-SEC-007: a member below Logistician reads the roster without its PII.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupMissionForPeer(dto);
    }
    return dto;
  }

  /**
   * Public add-participant endpoint. Accepts either an explicit {@code userId} (autocomplete pick)
   * or a free-text {@code guestName}. Free-text names are resolved case-insensitively against the
   * user table:
   *
   * <ul>
   *   <li>unique match + authenticated caller → linked as registered participant;
   *   <li>unique match + anonymous caller → 400 (spoofing protection);
   *   <li>no match → treated as guest;
   *   <li>multiple matches → 409 (ambiguous name).
   * </ul>
   *
   * <p>Anonymous callers may never submit a {@code userId} directly.
   *
   * @param id mission id
   * @param request add-participant payload (userId XOR guestName + comment + squadron)
   * @param jwt caller's JWT (null for anonymous)
   * @return the persisted parent DTO
   */
  @PostMapping("/{id}/participants/add")
  @Operation(
      summary = "Add a participant (public)",
      description =
          "Adds a participant by explicit userId (from autocomplete) or by free-text guestName."
              + " Free-text names are resolved case-insensitively against existing users: a unique"
              + " match links the participant as a registered member; no match falls back to the"
              + " guest path; multiple matches return 409 (ambiguous name).")
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Participant added"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description =
            "Validation error or guest name reserved for a registered user (anonymous only)"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Anonymous users cannot add registered users"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "Participant name is ambiguous and matches more than one registered user")
  })
  // MULTI_SQUADRON_PLAN.md §1: "Anmelde-Sicht" is open to anonymous + cross-staffel callers only
  // for NON-internal missions; internal missions of a foreign squadron must reject sign-ups.
  // `canSeeMission` returns true for own-squadron, admin, and non-internal-anywhere — exactly
  // the matrix we need. Without this gate, an anonymous user could create a guest participant
  // on an internal mission of any squadron (the URL is `permitAll` in SecurityConfig).
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeMission(#id)")
  public MissionDto addParticipantPublic(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid @NotNull AddParticipantPublicRequest request,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {
    UUID finalUserId = request.userId();
    String finalGuestName = request.guestName();

    if (jwt != null
        && finalUserId == null
        && (finalGuestName == null || finalGuestName.isBlank())) {
      finalUserId = userService.getUserIdFromJwt(jwt);
    }

    if (jwt == null && finalUserId != null) {
      throw new AccessDeniedException("Anonymous users cannot add registered users.");
    }

    // Resolve free-text participant name to an existing registered user (case-insensitive,
    // exact match on username or displayName). This fixes the bug where an authenticated
    // squadron member typing their own name without using the autocomplete dropdown was
    // rejected with "Guest name is already taken." – now the name is transparently linked
    // to the matching user. Anonymous users may still not spoof a registered member's name.
    if (finalUserId == null && finalGuestName != null && !finalGuestName.isBlank()) {
      List<User> matches = userService.findMatchesByExactName(finalGuestName);
      if (matches.size() > 1) {
        log.debug("Participant name is ambiguous ({} matches) for mission {}", matches.size(), id);
        throw new BusinessConflictException("Participant name is ambiguous.");
      }
      if (matches.size() == 1) {
        if (jwt != null) {
          finalUserId = matches.get(0).getId();
          finalGuestName = null;
          log.debug(
              "Resolved free-text participant name to userId {} for mission {}", finalUserId, id);
        } else {
          // Anonymous user tried to add a name that belongs to a registered member -> keep spoofing
          // protection.
          throw new BadRequestException("Guest name is already taken.");
        }
      }
    }

    // H-1 (2026-05-20 audit): the legacy public add-participant let an authenticated non-manager
    // submit a foreign userId and silently add another registered member as participant. Mirror
    // the slim variant's self-vs-manager check — self-enroll always works, adding someone else
    // requires {@code canManageMission}.
    if (jwt != null && finalUserId != null) {
      UUID callerId = userService.getUserIdFromJwt(jwt);
      if ((callerId == null || !finalUserId.equals(callerId))
          && !missionSecurityService.canManageMission(id, authentication)) {
        throw new AccessDeniedException(
            "Only mission managers may add other users as participants.");
      }
    }

    MissionDto dto =
        missionMapper.toDto(
            missionService.addParticipant(
                id,
                finalUserId,
                finalGuestName,
                request.desiredJobTypeId(),
                request.comment(),
                request.orgUnitIds(),
                request.payoutPreference()));
    // H-2 / REQ-SEC-007: a member below Logistician gets the peer view — roster visible, PII
    // stripped. There used to be a stricter tier above this one for anonymous and role-less
    // callers; ADR-0159 removed that audience, so one tier is all that is left.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupMissionForPeer(dto);
    }
    return dto;
  }

  /**
   * Legacy add/update frequency endpoint — upsert by frequency-type.
   *
   * @param id mission id
   * @param request frequency payload (type + value)
   * @return the persisted parent DTO
   * @deprecated use {@link #addOrUpdateFrequencySlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PostMapping("/{id}/frequencies")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/frequencies/slim")
  @Operation(
      summary = "Add or update a frequency for a mission (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer POST /api/v1/missions/{id}/frequencies/slim which"
              + " returns only the updated frequency list.",
      deprecated = true)
  public MissionDto addOrUpdateFrequency(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid
          de.greluc.krt.profit.basetool.backend.model.dto.request.AddFrequencyRequest request) {
    return missionMapper.toDto(
        missionService.addOrUpdateMissionFrequency(id, request.frequencyTypeId(), request.value()));
  }

  /**
   * Legacy remove-frequency endpoint.
   *
   * @param id mission id
   * @param frequencyId frequency id
   * @return the persisted parent DTO
   * @deprecated use {@link #removeFrequencySlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @DeleteMapping("/{id}/frequencies/{frequencyId}")
  @PreAuthorize("@missionSecurityService.canManageMission(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/frequencies/{frequencyId}/slim")
  @Operation(
      summary = "Remove a frequency from a mission (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer DELETE"
              + " /api/v1/missions/{id}/frequencies/{frequencyId}/slim which returns 204 No"
              + " Content.",
      deprecated = true)
  public MissionDto removeFrequency(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID frequencyId) {
    return missionMapper.toDto(missionService.removeMissionFrequency(id, frequencyId));
  }

  /**
   * Legacy add-manager endpoint. Wraps the service call in try/catch with debug-level tracing to
   * aid diagnosis of intermittent test-environment failures — kept until the slim replacement
   * absorbs production load.
   *
   * @param id mission id
   * @param userId user id to add as manager
   * @return the persisted parent DTO
   * @deprecated use {@link #addManagerSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @PostMapping("/{id}/managers/{userId}")
  @PreAuthorize("@missionSecurityService.canManageManagers(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/managers/{userId}/slim")
  @Operation(
      summary = "Add a manager to a mission (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer POST /api/v1/missions/{id}/managers/{userId}/slim"
              + " which returns only the updated manager list.",
      deprecated = true)
  public MissionDto addManager(@PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    log.debug("MissionController.addManager START - id: {}, userId: {}", id, userId);
    try {
      var mission = missionService.addManager(id, userId);
      log.debug("MissionController.addManager SUCCESS - id: {}, userId: {}", id, userId);
      return missionMapper.toDto(mission);
    } catch (Exception e) {
      log.debug(
          "MissionController.addManager ERROR - id: {}, userId: {}, error: {}",
          id,
          userId,
          e.getMessage(),
          e);
      throw e;
    }
  }

  /**
   * Legacy remove-manager endpoint.
   *
   * @param id mission id
   * @param userId user id to remove from managers
   * @return the persisted parent DTO
   * @deprecated use {@link #removeManagerSlim}; sunset {@value #SLIM_DEPRECATION_SUNSET}
   */
  @Deprecated(forRemoval = true)
  @DeleteMapping("/{id}/managers/{userId}")
  @PreAuthorize("@missionSecurityService.canManageManagers(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = SLIM_DEPRECATION_SUNSET,
      replacement = "/api/v1/missions/{id}/managers/{userId}/slim")
  @Operation(
      summary = "Remove a manager from a mission (legacy, deprecated)",
      description =
          "Returns the full MissionDto. Prefer DELETE /api/v1/missions/{id}/managers/{userId}/slim"
              + " which returns 204 No Content.",
      deprecated = true)
  public MissionDto removeManager(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    log.info("Request to remove manager {} from mission {}", userId, id);
    try {
      var mission = missionService.removeManager(id, userId);
      log.info("Manager {} removed from mission {} successfully", userId, id);
      return missionMapper.toDto(mission);
    } catch (Exception e) {
      log.debug("Failed to remove manager {} from mission {}: {}", userId, id, e.getMessage(), e);
      throw e;
    }
  }

  /**
   * Legacy owner-change endpoint without optimistic lock on the ownership aggregate. Replaced by
   * {@link #updateMissionOwner} which carries a version field and does not bump {@code
   * Mission.version}.
   *
   * @param id mission id
   * @param userId new owner id
   * @return the persisted parent DTO
   * @deprecated use {@link #updateMissionOwner}; sunset 2026-10-20
   */
  @Deprecated(forRemoval = true)
  @PutMapping("/{id}/owner/{userId}")
  @PreAuthorize("@missionSecurityService.canChangeOwner(#id, authentication)")
  @de.greluc.krt.profit.basetool.backend.annotation.ApiDeprecation(
      sunset = "2026-10-20",
      replacement = "/api/v1/missions/{id}/owner")
  @Operation(
      summary = "Change the owner of a mission (legacy, deprecated)",
      description =
          "Legacy endpoint without optimistic lock on the ownership aggregate. Prefer PUT"
              + " /api/v1/missions/{id}/owner with UpdateMissionOwnerRequest (includes version) to"
              + " benefit from per-section optimistic locking that does not invalidate other users'"
              + " open forms on the same mission.",
      deprecated = true)
  public MissionDto setMissionOwnerLegacy(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID userId) {
    log.debug("MissionController.setMissionOwnerLegacy START - id: {}, userId: {}", id, userId);
    try {
      var mission = missionService.setMissionOwner(id, userId);
      log.debug("MissionController.setMissionOwnerLegacy SUCCESS - id: {}, userId: {}", id, userId);
      return missionMapper.toDto(mission);
    } catch (Exception e) {
      log.debug(
          "MissionController.setMissionOwnerLegacy ERROR - id: {}, userId: {}, error: {}",
          id,
          userId,
          e.getMessage(),
          e);
      throw e;
    }
  }

  /**
   * Owner change through the dedicated {@code MissionOwnership} aggregate. The version field in the
   * request must match the current ownership version (not {@code Mission.version}). The mission
   * version stays untouched, so concurrent edits on other sections remain valid.
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
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Owner updated"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Mission or user not found"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "Ownership version conflict (application/problem+json)")
  })
  public MissionDto updateMissionOwner(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionOwnerRequest
              request) {
    var mission = missionService.updateMissionOwner(id, request.userId(), request.version());
    return missionMapper.toDto(mission);
  }

  /**
   * Reassigns the mission's owning org unit (REQ-ORG-018 / ADR-0050). The request body carries the
   * target org-unit id (or {@code null} for an ownerless leadership mission) and the expected
   * {@code owningOrgUnitVersion} (NOT the parent {@code Mission.version}). The caller passes the
   * same {@code canChangeOwner} gate as the owner change; the service additionally validates the
   * target against the caller's assignable-org-unit scope. Re-homing does NOT bump {@code
   * Mission.version}, so other users' open forms on the same mission remain valid (Option A /
   * multi-user concurrency).
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
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Owning org unit updated"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error or unknown target org unit"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Forbidden (caller may not change owner or may not assign to target)"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Mission not found"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "owningOrgUnitVersion conflict (application/problem+json)")
  })
  public MissionDto updateMissionOwningOrgUnit(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionOwningOrgUnitRequest
              request) {
    var mission =
        missionService.updateOwningOrgUnit(id, request.owningOrgUnitId(), request.version());
    return missionMapper.toDto(mission);
  }

  /**
   * Assigns or clears the mission's party lead (Partyleiter). Reuses the participant-add resolution
   * mechanic: the caller submits either an explicit {@code userId} (from the user autocomplete) or
   * a free-text {@code guestName}. A non-blank free-text name with no {@code userId} is resolved
   * case-insensitively against registered members:
   *
   * <ul>
   *   <li>unique match → linked as a registered party lead;
   *   <li>no match → stored as a free-text/anonymous handle;
   *   <li>multiple matches → 409 (ambiguous name).
   * </ul>
   *
   * <p>Submitting neither {@code userId} nor a non-blank {@code guestName} clears the party lead.
   * Manager-gated ({@code canManageMission}), so — unlike {@link #addParticipantPublic} — there is
   * no anonymous caller and therefore no name-spoofing branch. The {@code version} in the request
   * must match the mission's current {@code partyLeadVersion}.
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
              + " multiple matches return 409, no match stores a guest handle). Submitting neither"
              + " clears the party lead. The version must match the mission's current"
              + " partyLeadVersion or 409 (application/problem+json) is returned.")
  @io.swagger.v3.oas.annotations.responses.ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Party lead updated"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Validation error"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "403",
        description = "Caller may not manage this mission"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Mission or referenced user not found"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "Ambiguous party-lead name or stale partyLeadVersion")
  })
  public MissionDto setPartyLead(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.SetPartyLeadRequest request) {
    UUID finalUserId = request.userId();
    String finalGuestName = request.guestName();

    // Reuse the participant free-text resolution: a free-text name with no explicit userId is
    // resolved case-insensitively against registered members (exact match on username or
    // displayName). A unique match links the registered user; multiple matches are ambiguous (409);
    // no match falls back to a guest handle. The caller is always a mission manager here
    // (canManageMission), so there is no anonymous-spoofing branch like in addParticipantPublic.
    if (finalUserId == null && finalGuestName != null && !finalGuestName.isBlank()) {
      List<User> matches = userService.findMatchesByExactName(finalGuestName);
      if (matches.size() > 1) {
        log.debug("Party lead name is ambiguous ({} matches) for mission {}", matches.size(), id);
        throw new BusinessConflictException("Party lead name is ambiguous.");
      }
      if (matches.size() == 1) {
        finalUserId = matches.get(0).getId();
        finalGuestName = null;
      }
    }

    return missionMapper.toDto(
        missionService.setPartyLead(id, finalUserId, finalGuestName, request.version()));
  }

  // -------------------------------------------------------------------------------------
  // Slim sub-resource endpoints (Option A / multi-user concurrency).

  // These endpoints are additive replacements for the legacy MissionDto-returning
  // sub-endpoints above. They return only the affected slim sub-DTO (or a slim list,
  // or 204 No Content) instead of the full MissionDto. This lets the frontend run
  // per-sub-aggregate DOM `data-version` synchronisation without coupling the
  // Mission parent version into every AJAX round-trip.

  // Behaviour and service-level concurrency semantics are IDENTICAL to the legacy
  // endpoints; only the response shape is slim. See ApiDeprecation annotations on
  // the legacy endpoints for the sunset date.
  // -------------------------------------------------------------------------------------

  /**
   * Locates a unit inside a mission aggregate by id, or throws {@link NotFoundException}. Used by
   * the slim endpoints to project a single sub-aggregate without re-fetching from the database.
   *
   * @param mission mission aggregate
   * @param unitId unit id to find
   * @return the matching unit
   */
  private de.greluc.krt.profit.basetool.backend.model.MissionUnit findUnit(
      de.greluc.krt.profit.basetool.backend.model.Mission mission, UUID unitId) {
    return mission.getAssignedUnits().stream()
        .filter(u -> unitId.equals(u.getId()))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Mission unit not found"));
  }

  /**
   * Locates a participant inside a mission aggregate by id, or throws {@link NotFoundException}.
   *
   * @param mission mission aggregate
   * @param participantId participant id to find
   * @return the matching participant
   */
  private de.greluc.krt.profit.basetool.backend.model.MissionParticipant findParticipant(
      de.greluc.krt.profit.basetool.backend.model.Mission mission, UUID participantId) {
    return mission.getParticipants().stream()
        .filter(p -> participantId.equals(p.getId()))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Participant not found"));
  }

  /**
   * Locates a crew entry inside a unit by id, or throws {@link NotFoundException}.
   *
   * @param unit unit aggregate
   * @param crewId crew entry id to find
   * @return the matching crew entry
   */
  private de.greluc.krt.profit.basetool.backend.model.MissionCrew findCrew(
      de.greluc.krt.profit.basetool.backend.model.MissionUnit unit, UUID crewId) {
    return unit.getCrew().stream()
        .filter(c -> crewId.equals(c.getId()))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Crew member not found"));
  }

  // --- Units ---

  /**
   * Adds a unit and returns only the updated unit list (slim). Preferred over {@link #addUnit} —
   * doesn't drag the {@code Mission.version} into the round-trip.
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
      @PathVariable @NotNull UUID id,
      @jakarta.validation.Valid @RequestBody @NotNull AddUnitRequest request) {
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
    return mission.getAssignedUnits().stream().map(missionMapper::toDto).toList();
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
      @jakarta.validation.Valid @RequestBody @NotNull UpdateUnitRequest request) {
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
    return missionMapper.toDto(findUnit(mission, unitId));
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

  // --- Ablauf steps (procedure timeline) ---

  /**
   * Appends an Ablauf step and returns the mission's full step list in order (slim). Guarded by the
   * mission's {@code stepsVersion} section counter, so editing the Ablauf never collides with a
   * concurrent core / schedule / flags edit.
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
      @PathVariable @NotNull UUID id,
      @jakarta.validation.Valid @RequestBody @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.AddMissionStepRequest request) {
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
      @jakarta.validation.Valid @RequestBody @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionStepRequest
              request) {
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
      @jakarta.validation.Valid @RequestBody @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.ReorderMissionStepsRequest
              request) {
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
      @jakarta.validation.Valid @RequestBody @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.ToggleMissionStepRequest
              request) {
    var mission = missionService.toggleStepDone(id, stepId, request.done(), request.stepsVersion());
    return toStepDtos(mission);
  }

  /**
   * Projects a mission's Ablauf steps into an ordered list of slim DTOs (by {@code orderIndex}).
   */
  private List<MissionStepDto> toStepDtos(de.greluc.krt.profit.basetool.backend.model.Mission m) {
    return m.getSteps().stream()
        .sorted(
            java.util.Comparator.comparingInt(
                de.greluc.krt.profit.basetool.backend.model.MissionStep::getOrderIndex))
        .map(missionMapper::toDto)
        .toList();
  }

  // --- Mission goals (Ziele) ---

  /**
   * Appends a goal (Ziel) and returns the mission's full goal list in order (slim). Guarded by the
   * mission's {@code objectivesVersion} section counter, so editing the goals never collides with a
   * concurrent core / schedule / flags / Ablauf edit.
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
      @jakarta.validation.Valid @RequestBody @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.AddMissionObjectiveRequest
              request) {
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
      @jakarta.validation.Valid @RequestBody @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionObjectiveRequest
              request) {
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
      @jakarta.validation.Valid @RequestBody @NotNull
          de.greluc.krt.profit.basetool.backend.model.dto.request.ReorderMissionObjectivesRequest
              request) {
    var mission =
        missionService.reorderObjectives(id, request.objectiveIds(), request.objectivesVersion());
    return toObjectiveDtos(mission);
  }

  /** Projects a mission's goals into an ordered list of slim DTOs (by {@code orderIndex}). */
  private List<MissionObjectiveDto> toObjectiveDtos(
      de.greluc.krt.profit.basetool.backend.model.Mission m) {
    return m.getObjectives().stream()
        .sorted(
            java.util.Comparator.comparingInt(
                de.greluc.krt.profit.basetool.backend.model.MissionObjective::getOrderIndex))
        .map(missionMapper::toDto)
        .toList();
  }

  /**
   * Lists the ships a unit of this mission may be crewed with: ships owned by registered
   * participants (regardless of OrgUnit, so a cross-OrgUnit participant's ship is selectable) plus
   * ships already pinned to one of the mission's units. Used by the mission detail page to populate
   * the unit ship pickers without exposing the caller's whole hangar scope. Gated by {@code
   * canManageMission} so only users who may edit the mission's units see participant ship details.
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
    return missionService.getSelectableUnitShips(id).stream().map(shipMapper::toDto).toList();
  }

  // --- Crew ---

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
      @RequestBody @jakarta.validation.Valid @NotNull AddCrewRequest request) {
    java.util.Set<UUID> jobTypeIds =
        request.jobTypeIds() != null ? request.jobTypeIds() : java.util.Collections.emptySet();
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
      @RequestBody @jakarta.validation.Valid @NotNull UpdateCrewRequest request) {
    java.util.Set<UUID> jobTypeIds =
        request.jobTypeIds() != null ? request.jobTypeIds() : java.util.Collections.emptySet();
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

  // --- Participants ---

  /**
   * Updates a participant and returns only the updated participant (slim).
   *
   * @param id mission id
   * @param participantId participant id
   * @param request participant payload (carries the expected participant version)
   * @param jwt caller's JWT (null for anonymous)
   * @return the updated participant DTO
   */
  @PutMapping("/{id}/participants/{participantId}/slim")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Update a participant (slim response)",
      description = "Updates a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto updateParticipantSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody @jakarta.validation.Valid @NotNull UpdateParticipantRequest request,
      @AuthenticationPrincipal Jwt jwt,
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
    MissionParticipantDto dto = missionMapper.toDto(findParticipant(mission, participantId));
    // The {@code cleanupParticipantForPeer} call here satisfies the ArchUnit rule {@code
    // peerReadableMissionEndpointsMustRedactPii} (audit finding C-1): the participant
    // {@code canAccessParticipant} lets anonymous reach this endpoint reaches only guest entries
    // anyway ({@code participant.user == null}), so the redaction is a no-op for the data — but
    // calling it directly is the structural guarantee that a future mapping change which surfaces
    // a non-null {@code UserDto} on a guest participant cannot leak through. The role check
    // additionally treats an authenticated role-less GUEST like an anonymous caller here.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupParticipantForPeer(dto);
    }
    return dto;
  }

  /**
   * Slim check-in. Stamps {@code startTime} on the participant.
   *
   * @param id mission id
   * @param participantId participant id
   * @param jwt caller's JWT (null for anonymous)
   * @return the updated participant DTO (redacted for anonymous callers)
   */
  @PostMapping("/{id}/participants/{participantId}/check-in/slim")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Check in a participant (slim response)",
      description =
          "Checks in a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto checkInParticipantSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal Jwt jwt) {
    var mission = missionService.checkIn(id, participantId);
    MissionParticipantDto dto = missionMapper.toDto(findParticipant(mission, participantId));
    // REQ-SEC-007: a member below Logistician gets the participant PII redaction.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupParticipantForPeer(dto);
    }
    return dto;
  }

  /**
   * Slim check-out. Stamps {@code endTime} on the participant.
   *
   * @param id mission id
   * @param participantId participant id
   * @param jwt caller's JWT (null for anonymous)
   * @return the updated participant DTO (redacted for anonymous callers)
   */
  @PostMapping("/{id}/participants/{participantId}/check-out/slim")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Check out a participant (slim response)",
      description =
          "Checks out a participant and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto checkOutParticipantSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @AuthenticationPrincipal Jwt jwt) {
    var mission = missionService.checkOut(id, participantId);
    MissionParticipantDto dto = missionMapper.toDto(findParticipant(mission, participantId));
    // REQ-SEC-007: a member below Logistician gets the participant PII redaction.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupParticipantForPeer(dto);
    }
    return dto;
  }

  /**
   * Slim payout-preference update. {@code DONATE} stays sticky for the whole operation.
   *
   * @param id mission id
   * @param participantId participant id
   * @param request payout preference payload
   * @param jwt caller's JWT (null for anonymous)
   * @return the updated participant DTO (redacted for anonymous callers)
   */
  @PutMapping("/{id}/participants/{participantId}/payout-preference/slim")
  @PreAuthorize(
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
          + " authentication)")
  @Operation(
      summary = "Update payout preference for a participant (slim response)",
      description =
          "Updates the payout preference and returns only the updated participant as a slim DTO.")
  public MissionParticipantDto updatePayoutPreferenceSlim(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull UUID participantId,
      @RequestBody @jakarta.validation.Valid @NotNull UpdatePayoutPreferenceRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    var mission = missionService.updatePayoutPreference(id, participantId, request.preference());
    MissionParticipantDto dto = missionMapper.toDto(findParticipant(mission, participantId));
    // REQ-SEC-007: a member below Logistician gets the participant PII redaction.
    if (!authHelperService.isLogisticianOrAbove()) {
      dto = missionPeerRedactor.cleanupParticipantForPeer(dto);
    }
    return dto;
  }

  /**
   * Slim add-participant — mirrors {@link #addParticipantPublic} logic with one extra access tier:
   * an authenticated, non-manager caller may always self-enroll but needs {@code canManageMission}
   * to add anyone else (raised at the HTTP boundary, not in the service). Returns only the updated
   * participant list.
   *
   * @param id mission id
   * @param request add-participant payload (userId XOR guestName + meta)
   * @param jwt caller's JWT (null for anonymous)
   * @param authentication current Spring Security authentication
   * @return the updated participant list
   */
  @PostMapping("/{id}/participants/slim")
  @Operation(
      summary = "Add a participant (slim response)",
      description =
          "Adds a participant and returns the updated participant list as slim DTOs. Mirrors the"
              + " public add-participant logic: explicit userId (autocomplete) or free-text"
              + " guestName (case-insensitive resolution against registered users). Authenticated"
              + " users may always add themselves; adding other registered users is restricted to"
              + " managers/officers/admins. Anonymous users may only add guest entries.")
  // MULTI_SQUADRON_PLAN.md §1: same gate as the legacy `/participants/add` endpoint — only
  // non-internal missions accept cross-staffel / anonymous sign-ups. Internal missions are
  // gated even though the URL is `permitAll` in SecurityConfig.
  @PreAuthorize("isAuthenticated() and @ownerScopeService.canSeeMission(#id)")
  public List<MissionParticipantDto> addParticipantSlim(
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid @NotNull AddParticipantPublicRequest request,
      @AuthenticationPrincipal Jwt jwt,
      Authentication authentication) {
    UUID finalUserId = request.userId();
    String finalGuestName = request.guestName();

    // Default self-enroll when an authenticated caller submits an empty form.
    if (jwt != null
        && finalUserId == null
        && (finalGuestName == null || finalGuestName.isBlank())) {
      finalUserId = userService.getUserIdFromJwt(jwt);
    }

    // Anonymous callers must never add a registered user directly.
    if (jwt == null && finalUserId != null) {
      throw new AccessDeniedException("Anonymous users cannot add registered users.");
    }

    // Resolve free-text guest names against registered users (case-insensitive, exact match on
    // username or displayName). Authenticated users get their name transparently linked;
    // anonymous users trying to impersonate a registered member are rejected.
    if (finalUserId == null && finalGuestName != null && !finalGuestName.isBlank()) {
      List<User> matches = userService.findMatchesByExactName(finalGuestName);
      if (matches.size() > 1) {
        throw new BusinessConflictException("Participant name is ambiguous.");
      }
      if (matches.size() == 1) {
        if (jwt != null) {
          finalUserId = matches.get(0).getId();
          finalGuestName = null;
        } else {
          throw new BadRequestException("Guest name is already taken.");
        }
      }
    }

    // If an authenticated, non-privileged caller tries to add a *different* registered user,
    // require manage-mission privileges. Self-add always stays permitted.
    if (jwt != null && finalUserId != null) {
      UUID callerId = userService.getUserIdFromJwt(jwt);
      if ((callerId == null || !finalUserId.equals(callerId))
          && !missionSecurityService.canManageMission(id, authentication)) {
        throw new AccessDeniedException(
            "Only mission managers may add other users as participants.");
      }
    }

    var mission =
        missionService.addParticipant(
            id,
            finalUserId,
            finalGuestName,
            request.desiredJobTypeId(),
            request.comment(),
            request.orgUnitIds(),
            request.payoutPreference());
    java.util.stream.Stream<MissionParticipantDto> participants =
        mission.getParticipants().stream().map(missionMapper::toDto);
    // H-5 / REQ-SEC-007: every caller below Logistician gets the peer-redacted user shape — the
    // full roster, but only the public callsign tuple (username, displayName, rank), never email or
    // real name. The ArchUnit rule {@code peerReadableMissionEndpointsMustRedactPii} statically
    // enforces this for any future endpoint returning a PII-carrying mission DTO.
    if (!authHelperService.isLogisticianOrAbove()) {
      participants = participants.map(missionPeerRedactor::cleanupParticipantForPeer);
    }
    return participants.toList();
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
      "isAuthenticated() and @missionSecurityService.canAccessParticipant(#id, #participantId,"
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
    return missionService.getUnassignedParticipants(id).stream().map(missionMapper::toDto).toList();
  }

  // --- Frequencies ---

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
      @PathVariable @NotNull UUID id,
      @RequestBody @jakarta.validation.Valid
          de.greluc.krt.profit.basetool.backend.model.dto.request.AddFrequencyRequest request) {
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
   * Adds a custom (mission-specific) frequency — a free-text label plus a value — and returns the
   * updated frequency list as slim DTOs (REQ-MISSION-014). The generic {@code DELETE
   * /{id}/frequencies/{frequencyId}/slim} above removes typed and custom rows alike.
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
      @RequestBody @jakarta.validation.Valid
          de.greluc.krt.profit.basetool.backend.model.dto.request.AddCustomFrequencyRequest
              request) {
    var mission = missionService.addCustomMissionFrequency(id, request.name(), request.value());
    return mission.getFrequencies().stream().map(missionMapper::toDto).toList();
  }

  /**
   * Updates a custom (mission-specific) frequency's label + value and returns the updated frequency
   * list as slim DTOs (REQ-MISSION-014). Optimistic-locked on the frequency row's own version; a
   * stale echo surfaces as HTTP 409.
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
      @RequestBody @jakarta.validation.Valid
          de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateCustomFrequencyRequest
              request) {
    var mission =
        missionService.updateCustomMissionFrequency(
            id, frequencyId, request.name(), request.value(), request.version());
    return mission.getFrequencies().stream().map(missionMapper::toDto).toList();
  }

  // --- Managers ---

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
   * Projects a page of missions into list rows, resolving the whole page's registration counts in
   * ONE grouped statement.
   *
   * <p>Every list row shows "{n} angemeldet". The figure lives in the mission's lazy {@code
   * participants} collection, so letting the mapper read it would be a SELECT per row — the N+1
   * REQ-DATA-003 forbids. Asking once per page keeps a 100-row page at two statements instead of
   * 101. A mission with no participants has no count row, which is the zero.
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
}
