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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.mapper.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionCoreRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionFlagsRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.PatchMissionScheduleRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.SetPartyLeadRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionOwnerRequest;
import de.greluc.krt.profit.basetool.backend.service.MissionSecurityService;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pure-Mockito unit tests for the parts of {@link MissionController} that the existing {@code
 * MissionControllerSecurityTest} (role gates) and {@code MissionControllerSlimEndpointsTest} (slim
 * sub-resource endpoints + RSVP branches) do NOT touch:
 *
 * <ul>
 *   <li><b>Outsider redaction</b> in {@code getMissionById} / {@code getNextMission} — {@code
 *       MissionController#cleanupOutsiderMissionForGuest} is the only path that controls what
 *       leaves the API for a mission outsider (anonymous OR authenticated role-less GUEST, detected
 *       via {@code AuthHelperService#isMemberOrAbove()}). Pinning the outsider redaction (the
 *       free-text description hidden, participant PII stripped to the public callsign tuple, owner
 *       / managers / internal inventory+refinery cleared, and — per ADR-0034 — each participant's
 *       payoutPreference + free-text comment stripped, while organisation, the participant roster,
 *       units and frequencies stay visible) protects the multi-user-data-isolation guarantee in
 *       CLAUDE.md.
 *   <li><b>Outsider access blocks</b>: internal missions → 403, completed/cancelled missions → 403
 *       (a past mission must not leak its participant list to a public viewer).
 *   <li><b>Outsider list/search filtering</b> — {@code getAllMissions} / {@code searchMissions}
 *       silently restrict outsiders to {@code PLANNED}+{@code ACTIVE} non-internal missions. The
 *       "outsider passes a forbidden status" path returns an empty page (not 403) so the UI
 *       degrades silently.
 *   <li><b>Section patches</b> ({@code patchMissionCore}, {@code patchMissionSchedule}, {@code
 *       patchMissionFlags}) unpack each request record into the service's positional argument list.
 *       The argument order is the spot where a copy-paste during refactor would silently swap
 *       fields (e.g. {@code name} and {@code description}).
 *   <li><b>Versioned owner change</b> — {@code updateMissionOwner} forwards the ownership-aggregate
 *       version, not the parent {@code Mission.version}. Mixing those up reintroduces the bug that
 *       the dedicated aggregate was created to solve.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MissionControllerLifecycleTest {

  @Mock private MissionService missionService;
  @Mock private UserService userService;
  @Mock private MissionMapper missionMapper;
  @Mock private UserMapper userMapper;
  @Mock private MissionSecurityService missionSecurityService;
  @Mock private de.greluc.krt.profit.basetool.backend.service.AuthHelperService authHelperService;

  @InjectMocks private MissionController controller;

  private static Jwt jwt(String sub) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(sub)
        .claim("sub", sub)
        .build();
  }

  /**
   * Build a representative MissionDto that exercises every field {@link
   * MissionController#cleanupMissionForGuest} touches. This is the canary input for the redaction
   * assertions further down: every "internal" or "leaks-PII" field is intentionally populated so
   * the cleanup pass has something to strip.
   */
  private static MissionDto fullMissionDto(UUID id) {
    UserReferenceDto owner =
        new UserReferenceDto(
            UUID.randomUUID(), "owner.handle", "Owner Display", "Owner Effective", 12);
    UserReferenceDto manager =
        new UserReferenceDto(
            UUID.randomUUID(), "manager.handle", "Manager Display", "Manager Effective", 5);
    UserDto user =
        new UserDto(
            UUID.randomUUID(),
            "alice",
            "Alice Display",
            "Alice Effective",
            "alice@example.com",
            12,
            "internal description",
            Set.of("ROLE_KRT_MEMBER"),
            Set.of("MISSION_READ"),
            UUID.randomUUID(),
            true,
            false,
            true,
            null,
            java.util.List.of(),
            1L,
            java.time.LocalDate.of(2024, 1, 1),
            false);
    MissionParticipantDto participant =
        new MissionParticipantDto(
            UUID.randomUUID(),
            user,
            null,
            null,
            null,
            null,
            "comment",
            null,
            null,
            de.greluc.krt.profit.basetool.backend.model.PayoutPreference.PAYOUT,
            1L,
            null);
    return new MissionDto(
        id,
        "Op Foxglove",
        "internal description",
        "https://example.com/cal",
        "PLANNED",
        Instant.parse("2026-05-01T10:00:00Z"),
        Instant.parse("2026-05-01T12:00:00Z"),
        null,
        Instant.parse("2026-05-01T14:00:00Z"),
        null,
        false,
        Set.of(participant),
        List.of(),
        List.of(),
        Set.of(),
        List.of(),
        List.of(),
        null,
        owner,
        Set.of(manager),
        true,
        true,
        9L,
        4L, // coreVersion
        5L, // scheduleVersion
        6L, // flagsVersion
        1,
        1,
        null,
        null,
        null,
        null,
        0L,
        List.of(), // steps
        0L, // stepsVersion
        List.of(), // objectives
        0L, // objectivesVersion
        null); // meetingPoint
  }

  // ── GET /api/v1/missions (anonymous filtering) ───────────────────────

  // Asserts the deprecated-for-removal MissionService.getAllMissions(Pageable) is never hit;
  // referencing it in verify(...) triggers an expected, unavoidable [removal] warning.
  @Test
  @SuppressWarnings("removal")
  void getAllMissions_authenticatedCaller_routesThroughSearchMissionsForSquadronScope() {
    Mission m = new Mission();
    MissionListDto listDto =
        new MissionListDto(
            UUID.randomUUID(),
            "Op",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            1L);
    Page<Mission> page = new PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
    // Post-fix #1: authenticated callers now go through searchMissions so the service-layer
    // squadron filter (owning OR is_internal=false) is applied. getAllMissions/findAll without
    // a scope would leak internal missions of foreign squadrons.
    when(missionService.searchMissions(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class)))
        .thenReturn(page);
    when(missionMapper.toListDto(m)).thenReturn(listDto);
    // Registered member (or above) → full scoped search, no anonymous status restriction.
    when(authHelperService.isMemberOrAbove()).thenReturn(true);

    PageResponse<MissionListDto> result = controller.getAllMissions(0, 20, null);

    assertThat(result.content()).containsExactly(listDto);
    verify(missionService)
        .searchMissions(
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            any(Pageable.class));
    // The legacy unfiltered getAllMissions() path must never be hit for authenticated callers.
    verify(missionService, never()).getAllMissions(any(Pageable.class));
  }

  @Test
  void getAllMissions_anonymousCaller_restrictedToPlannedAndActiveNonInternal() {
    Mission m = new Mission();
    MissionListDto listDto =
        new MissionListDto(
            UUID.randomUUID(),
            "Op",
            null,
            null,
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            1L);
    Page<Mission> page = new PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
    when(missionService.searchMissions(
            eq(null),
            eq(null),
            eq(null),
            eq(List.of("PLANNED", "ACTIVE")),
            eq(false),
            eq(null),
            any(Pageable.class)))
        .thenReturn(page);
    when(missionMapper.toListDto(m)).thenReturn(listDto);
    // Outsider = anonymous OR authenticated role-less GUEST (isMemberOrAbove == false).
    when(authHelperService.isMemberOrAbove()).thenReturn(false);

    PageResponse<MissionListDto> result = controller.getAllMissions(0, 20, null);

    // Outsiders MUST be silently restricted to PLANNED+ACTIVE non-internal missions — this is the
    // only place where the "guests do not see completed/cancelled/internal" rule is enforced on the
    // list endpoint. Pin the exact argument shape so a refactor that "simplifies" the outsider
    // branch cannot silently widen the guest view.
    assertThat(result.content()).containsExactly(listDto);
    verify(missionService)
        .searchMissions(
            eq(null),
            eq(null),
            eq(null),
            eq(List.of("PLANNED", "ACTIVE")),
            eq(false),
            eq(null),
            any(Pageable.class));
  }

  // ── GET /api/v1/missions/search (anonymous filtering / empty-after-filter) ──

  @Test
  void searchMissions_anonymous_emptyAfterFilter_returnsEmptyPageWithoutHittingService() {
    // Outsider = anonymous OR authenticated role-less GUEST (isMemberOrAbove == false).
    when(authHelperService.isMemberOrAbove()).thenReturn(false);
    PageResponse<MissionListDto> result =
        controller.searchMissions(
            null, null, null, List.of("COMPLETED"), null, 0, 20, null); // guest wants COMPLETED

    // Guest passed a status that is not in the allow-list. The controller MUST return an empty
    // page (not 403, not "everything-anyway") and MUST NOT hit the service — pinning this
    // protects the "UI degrades silently for guests" contract. A regression that falls through
    // to the service would either leak completed missions OR fail with NPE on the empty filter.
    assertThat(result.content()).isEmpty();
    assertThat(result.totalElements()).isZero();
    verify(missionService, never())
        .searchMissions(any(), any(), any(), any(), any(), any(), any(Pageable.class));
  }

  @Test
  void searchMissions_anonymous_statusFilterIntersectedWithAllowList() {
    Mission m = new Mission();
    MissionListDto listDto =
        new MissionListDto(
            UUID.randomUUID(),
            "Op",
            null,
            null,
            "ACTIVE",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            1L);
    Page<Mission> page = new PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
    when(missionService.searchMissions(
            eq(null),
            eq(null),
            eq(null),
            eq(List.of("ACTIVE")),
            eq(false),
            eq(null),
            any(Pageable.class)))
        .thenReturn(page);
    when(missionMapper.toListDto(m)).thenReturn(listDto);
    // Outsider = anonymous OR authenticated role-less GUEST (isMemberOrAbove == false).
    when(authHelperService.isMemberOrAbove()).thenReturn(false);

    PageResponse<MissionListDto> result =
        controller.searchMissions(
            null, null, null, List.of("ACTIVE", "COMPLETED"), null, 0, 20, null);

    // Guest passed ["ACTIVE", "COMPLETED"]; intersection with the allow-list ["PLANNED","ACTIVE"]
    // is ["ACTIVE"]. Pin the intersection result — a refactor that swapped intersect for union
    // would leak COMPLETED missions to anonymous viewers.
    assertThat(result.content()).containsExactly(listDto);
    verify(missionService)
        .searchMissions(
            eq(null),
            eq(null),
            eq(null),
            eq(List.of("ACTIVE")),
            eq(false),
            eq(null),
            any(Pageable.class));
  }

  @Test
  void searchMissions_authenticated_passesStatusFilterVerbatim() {
    Mission m = new Mission();
    MissionListDto listDto =
        new MissionListDto(
            UUID.randomUUID(),
            "Op",
            null,
            null,
            "COMPLETED",
            null,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            1L);
    Page<Mission> page = new PageImpl<>(List.of(m), PageRequest.of(0, 20), 1);
    Instant start = Instant.parse("2026-04-01T00:00:00Z");
    Instant end = Instant.parse("2026-06-01T00:00:00Z");
    UUID operationId = UUID.randomUUID();
    when(missionService.searchMissions(
            eq("foo"),
            eq(start),
            eq(end),
            eq(List.of("COMPLETED")),
            eq(null), // not 'false' — authenticated callers see internals too
            eq(operationId),
            any(Pageable.class)))
        .thenReturn(page);
    when(missionMapper.toListDto(m)).thenReturn(listDto);
    // Registered member (or above) → status filter passes through verbatim, internals included.
    when(authHelperService.isMemberOrAbove()).thenReturn(true);

    PageResponse<MissionListDto> result =
        controller.searchMissions(
            "foo", start, end, List.of("COMPLETED"), operationId, 0, 20, null);

    assertThat(result.content()).containsExactly(listDto);
    verify(missionService)
        .searchMissions(
            eq("foo"),
            eq(start),
            eq(end),
            eq(List.of("COMPLETED")),
            eq(null),
            eq(operationId),
            any(Pageable.class));
  }

  // ── GET /api/v1/missions/{id} (guest blocks + redaction) ─────────────

  @Test
  void getMissionById_authenticatedCaller_returnsFullDtoUnchanged() {
    UUID id = UUID.randomUUID();
    Mission entity = new Mission();
    MissionDto full = fullMissionDto(id);
    when(missionService.getMissionById(id)).thenReturn(entity);
    when(missionMapper.toDto(entity)).thenReturn(full);
    // Registered member (or above) → not an outsider → full DTO, no redaction.
    when(authHelperService.isMemberOrAbove()).thenReturn(true);

    MissionDto result = controller.getMissionById(id);

    // Member caller — owner/managers/PII flow through unchanged. Pin the "isSameAs" so a future
    // change that ALWAYS goes through the outsider redaction (e.g. as a "safety net") would surface
    // here as a different identity.
    assertThat(result).isSameAs(full);
  }

  @Test
  void getMissionById_guest_internalMission_throws403() {
    UUID id = UUID.randomUUID();
    Mission internal = new Mission();
    internal.setIsInternal(true);
    internal.setStatus("PLANNED");
    when(missionService.getMissionById(id)).thenReturn(internal);
    when(authHelperService.isMemberOrAbove()).thenReturn(false); // outsider (anonymous or GUEST)

    try {
      controller.getMissionById(id);
      org.junit.jupiter.api.Assertions.fail("Expected AccessDeniedException");
    } catch (AccessDeniedException expected) {
      // ok
    }

    // Critical: the mapper MUST NOT be called for forbidden guest reads, otherwise an exception
    // inside the mapper would leak data via the exception message in some edge case.
    verify(missionMapper, never()).toDto(any(Mission.class));
  }

  @Test
  void getMissionById_guest_completedMission_throws403() {
    UUID id = UUID.randomUUID();
    Mission completed = new Mission();
    completed.setIsInternal(false);
    completed.setStatus("COMPLETED");
    when(missionService.getMissionById(id)).thenReturn(completed);
    when(authHelperService.isMemberOrAbove()).thenReturn(false); // outsider (anonymous or GUEST)

    try {
      controller.getMissionById(id);
      org.junit.jupiter.api.Assertions.fail("Expected AccessDeniedException");
    } catch (AccessDeniedException expected) {
      // ok
    }
    verify(missionMapper, never()).toDto(any(Mission.class));
  }

  @Test
  void getMissionById_guest_cancelledMission_throws403() {
    UUID id = UUID.randomUUID();
    Mission cancelled = new Mission();
    cancelled.setIsInternal(false);
    cancelled.setStatus("CANCELLED");
    when(missionService.getMissionById(id)).thenReturn(cancelled);
    when(authHelperService.isMemberOrAbove()).thenReturn(false); // outsider (anonymous or GUEST)

    try {
      controller.getMissionById(id);
      org.junit.jupiter.api.Assertions.fail("Expected AccessDeniedException");
    } catch (AccessDeniedException expected) {
      // ok
    }
    verify(missionMapper, never()).toDto(any(Mission.class));
  }

  @Test
  void getMissionById_outsider_planned_keepsRosterButHidesDescriptionAndPii() {
    UUID id = UUID.randomUUID();
    Mission planned = new Mission();
    planned.setIsInternal(false);
    planned.setStatus("PLANNED");
    MissionDto full = fullMissionDto(id);
    when(missionService.getMissionById(id)).thenReturn(planned);
    when(missionMapper.toDto(planned)).thenReturn(full);
    // Outsider = anonymous OR authenticated role-less GUEST.
    when(authHelperService.isMemberOrAbove()).thenReturn(false);

    MissionDto result = controller.getMissionById(id);

    // Outsider redaction (cleanupOutsiderMissionForGuest): on top of the member-peer redaction the
    // free-text description is hidden and — per ADR-0034 / REQ-SEC-021 — each participant's
    // payoutPreference and free-text comment are stripped. Organisation, the participant roster
    // (public callsign tuple), units and frequencies stay visible (explicit product decision);
    // participant PII and owner / managers / internal economy are still stripped, and the finance
    // ledger stays member-only on its own endpoints.
    assertThat(result).isNotNull();
    assertThat(result.name()).isEqualTo("Op Foxglove");
    assertThat(result.status()).isEqualTo("PLANNED");
    // The only field hidden from outsiders beyond the peer redaction.
    assertThat(result.description()).isNull();
    // Still stripped: owner / managers / edit flags / internal economy.
    assertThat(result.owner()).isNull();
    assertThat(result.managers()).isNull();
    assertThat(result.canEdit()).isFalse();
    assertThat(result.canManageManagers()).isFalse();
    assertThat(result.inventoryEntries()).isEmpty();
    assertThat(result.refineryOrders()).isEmpty();
    // The participant roster IS visible to outsiders — but PII is stripped to the public callsign
    // tuple (username / displayName / rank), never email or roles.
    assertThat(result.participants()).hasSize(1);
    MissionParticipantDto rosterParticipant = result.participants().iterator().next();
    UserDto rosterUser = rosterParticipant.user();
    assertThat(rosterUser.username()).isEqualTo("alice");
    assertThat(rosterUser.email()).isNull();
    assertThat(rosterUser.roles()).isNull();
    // ADR-0034 / REQ-SEC-021: payout intent + free-text comment are NOT exposed to outsiders (the
    // fixture sets payoutPreference=PAYOUT and comment="comment"; both come back null).
    assertThat(rosterParticipant.payoutPreference()).isNull();
    assertThat(rosterParticipant.comment()).isNull();
  }

  // ── GET /api/v1/missions/next (200 / 204 + redaction) ────────────────

  @Test
  void getNextMission_noMission_returns204() {
    when(authHelperService.isMemberOrAbove()).thenReturn(false); // outsider
    when(missionService.getNextMission(false)).thenReturn(Optional.empty());

    ResponseEntity<MissionDto> response = controller.getNextMission();

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
  }

  @Test
  void getNextMission_guest_allowInternalIsFalse_andResponseRedacted() {
    UUID id = UUID.randomUUID();
    Mission upcoming = new Mission();
    MissionDto full = fullMissionDto(id);
    when(authHelperService.isMemberOrAbove()).thenReturn(false); // outsider (anonymous or GUEST)
    when(missionService.getNextMission(false)).thenReturn(Optional.of(upcoming));
    when(missionMapper.toDto(upcoming)).thenReturn(full);

    ResponseEntity<MissionDto> response = controller.getNextMission();

    // allowInternal=false MUST be passed to the service for an outsider — otherwise the service
    // might surface an internal mission that the cleanup pass would only blank out a few fields of.
    // Pin both the boolean AND the post-cleanup redacted owner.
    verify(missionService).getNextMission(false);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().owner()).isNull();
  }

  @Test
  void getNextMission_member_allowInternalIsTrue_andDtoUnchanged() {
    UUID id = UUID.randomUUID();
    Mission upcoming = new Mission();
    MissionDto full = fullMissionDto(id);
    when(authHelperService.isMemberOrAbove()).thenReturn(true); // registered member or above
    when(missionService.getNextMission(true)).thenReturn(Optional.of(upcoming));
    when(missionMapper.toDto(upcoming)).thenReturn(full);

    ResponseEntity<MissionDto> response = controller.getNextMission();

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(full);
  }

  // ── POST /api/v1/missions/{id}/join ──────────────────────────────────

  @Test
  void joinMission_resolvesCallerFromJwt_andDelegatesToService() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(missionId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(missionService.addParticipant(missionId, callerId)).thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.joinMission(jwt, missionId);

    // The self-enroll shortcut MUST resolve the caller from the JWT — never accept a userId
    // from the URL/body. Pin the captured argument to the JWT-derived id.
    assertThat(result).isSameAs(dto);
    verify(missionService).addParticipant(missionId, callerId);
  }

  // ── PATCH /api/v1/missions/{id}/core ─────────────────────────────────

  @Test
  void patchMissionCore_unpacksRequestRecordInDocumentedArgumentOrder() {
    UUID id = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    PatchMissionCoreRequest request =
        new PatchMissionCoreRequest(
            "New name", "New description", "https://cal", "PLANNED", operationId, 5L, null);
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(missionService.updateCoreSection(
            id, "New name", "New description", "https://cal", "PLANNED", operationId, null, 5L))
        .thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.patchMissionCore(id, request);

    // The positional service call is the spot where a copy-paste during refactor would
    // silently swap arguments of identical type (name and description are both Strings; the
    // section version is a Long that could collide with other longs in scope). The verify-call
    // pins the EXACT argument order — including {@code operationId} as part of the core section —
    // so a regression surfaces here instead of in production data.
    assertThat(result).isSameAs(dto);
    verify(missionService)
        .updateCoreSection(
            id, "New name", "New description", "https://cal", "PLANNED", operationId, null, 5L);
  }

  // ── PATCH /api/v1/missions/{id}/schedule ─────────────────────────────

  @Test
  void patchMissionSchedule_unpacksAllFiveTimestampsAndVersion() {
    UUID id = UUID.randomUUID();
    Instant meeting = Instant.parse("2026-05-01T09:00:00Z");
    Instant plannedStart = Instant.parse("2026-05-01T10:00:00Z");
    Instant plannedEnd = Instant.parse("2026-05-01T12:00:00Z");
    Instant actualStart = Instant.parse("2026-05-01T10:05:00Z");
    Instant actualEnd = Instant.parse("2026-05-01T12:10:00Z");
    PatchMissionScheduleRequest request =
        new PatchMissionScheduleRequest(
            meeting, plannedStart, plannedEnd, actualStart, actualEnd, 3L);
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(missionService.updateScheduleSection(
            id, meeting, plannedStart, plannedEnd, actualStart, actualEnd, 3L))
        .thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.patchMissionSchedule(id, request);

    // The five Instant fields are functionally interchangeable from a type perspective — only
    // their positional order distinguishes them. Verify-call pins it. (Notice: ALL timestamps
    // are UTC Instants per CLAUDE.md — the test does not mix LocalDateTime in.)
    assertThat(result).isSameAs(dto);
    verify(missionService)
        .updateScheduleSection(id, meeting, plannedStart, plannedEnd, actualStart, actualEnd, 3L);
  }

  // ── PATCH /api/v1/missions/{id}/flags ────────────────────────────────

  @Test
  void patchMissionFlags_unpacksIsInternalAndVersion() {
    UUID id = UUID.randomUUID();
    PatchMissionFlagsRequest request = new PatchMissionFlagsRequest(true, 2L);
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(missionService.updateFlagsSection(id, true, 2L)).thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.patchMissionFlags(id, request);

    assertThat(result).isSameAs(dto);
    verify(missionService).updateFlagsSection(id, true, 2L);
  }

  // ── PUT /api/v1/missions/{id}/owner (versioned) ──────────────────────

  @Test
  void updateMissionOwner_forwardsOwnershipAggregateVersion_notMissionVersion() {
    UUID id = UUID.randomUUID();
    UUID newOwnerId = UUID.randomUUID();
    // The version here is the *ownership* aggregate version, NOT Mission.version. A test that
    // accidentally pinned Mission.version (e.g. 9L from the fullMissionDto helper) would silently
    // mask a regression where the controller forwards the wrong version. Use a deliberately
    // distinct value (42L) that is unlike anything else in the test setup.
    UpdateMissionOwnerRequest request = new UpdateMissionOwnerRequest(newOwnerId, 42L);
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(missionService.updateMissionOwner(id, newOwnerId, 42L)).thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.updateMissionOwner(id, request);

    assertThat(result).isSameAs(dto);
    verify(missionService).updateMissionOwner(id, newOwnerId, 42L);
  }

  // ── PUT /api/v1/missions/{id}/owner/{userId} (legacy) ────────────────

  // Deliberately invokes the deprecated-for-removal MissionController.setMissionOwnerLegacy to pin
  // its no-version behaviour; the [removal] warning is expected and unavoidable here.
  @Test
  @SuppressWarnings("removal")
  void setMissionOwnerLegacy_doesNotForwardAnyVersion() {
    UUID id = UUID.randomUUID();
    UUID newOwnerId = UUID.randomUUID();
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(missionService.setMissionOwner(id, newOwnerId)).thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.setMissionOwnerLegacy(id, newOwnerId);

    // The legacy endpoint deliberately has NO version field — that is the exact reason the
    // {@code /owner} version-checked endpoint exists alongside it. Pin the (mission-id,
    // user-id) two-arg shape so a future "let's add a version param to be safe" change to the
    // legacy endpoint surfaces here as a compile error.
    assertThat(result).isSameAs(dto);
    verify(missionService).setMissionOwner(id, newOwnerId);
    verify(missionService, never()).updateMissionOwner(any(), any(), any());
  }

  // ── GET /api/v1/missions/{id}/participants/unassigned ────────────────

  @Test
  void getUnassignedParticipants_mapsServiceListThroughMapper() {
    UUID id = UUID.randomUUID();
    MissionParticipant raw = new MissionParticipant();
    MissionParticipantDto dto =
        new MissionParticipantDto(
            UUID.randomUUID(), null, null, null, null, null, null, null, null, null, 1L, null);
    when(missionService.getUnassignedParticipants(id)).thenReturn(List.of(raw));
    when(missionMapper.toDto(raw)).thenReturn(dto);

    List<MissionParticipantDto> result = controller.getUnassignedParticipants(id);

    assertThat(result).containsExactly(dto);
    verify(missionMapper).toDto(raw);
  }

  // ── createSubMission forwards request → service → DTO ───────────────

  @Test
  void createSubMission_forwardsCreateRequestToServiceAndMapsResult() {
    UUID parentId = UUID.randomUUID();
    CreateMissionRequest request =
        new CreateMissionRequest(
            "Sub", "desc", null, "PLANNED", null, null, null, false, null, null, null);
    Mission persistedParent = new Mission();
    MissionDto parentDto = fullMissionDto(parentId);
    when(missionService.addSubMission(parentId, request)).thenReturn(persistedParent);
    when(missionMapper.toDto(persistedParent)).thenReturn(parentDto);

    MissionDto result = controller.createSubMission(parentId, request);

    // Audit finding C-3 migration: the controller no longer maps a full MissionDto into a fresh
    // Mission entity (that path enabled the id/version/owningSquadron mass-assignment vector). It
    // now forwards the dedicated CreateMissionRequest record straight to the service and only
    // round-trips back through toDto on the response. The mapper.toEntity(MissionDto) overload was
    // deleted; this test pins the new, narrower contract.
    assertThat(result).isSameAs(parentDto);
    verify(missionService).addSubMission(parentId, request);
    verify(missionMapper).toDto(persistedParent);
  }

  // ── DELETE /api/v1/missions/{id} ─────────────────────────────────────

  @Test
  void deleteMission_returns204_andDelegatesToService() {
    UUID id = UUID.randomUUID();

    ResponseEntity<Void> response = controller.deleteMission(id);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    verify(missionService).deleteMission(id);
  }

  // ── PUT /api/v1/missions/{id}/party-lead ─────────────────────────────

  @Test
  void setPartyLead_explicitUserId_isForwardedWithoutNameResolution() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    SetPartyLeadRequest request = new SetPartyLeadRequest(userId, null, 3L);
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(missionService.setPartyLead(id, userId, null, 3L)).thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.setPartyLead(id, request);

    assertThat(result).isSameAs(dto);
    // An explicit autocomplete pick must NOT go through the free-text resolution path.
    verify(userService, never()).findMatchesByExactName(any());
    verify(missionService).setPartyLead(id, userId, null, 3L);
  }

  @Test
  void setPartyLead_freeTextName_uniqueMatch_isResolvedToUserId() {
    UUID id = UUID.randomUUID();
    UUID resolvedId = UUID.randomUUID();
    User matched = new User();
    matched.setId(resolvedId);
    SetPartyLeadRequest request = new SetPartyLeadRequest(null, "Alice", 1L);
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(userService.findMatchesByExactName("Alice")).thenReturn(List.of(matched));
    when(missionService.setPartyLead(id, resolvedId, null, 1L)).thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.setPartyLead(id, request);

    assertThat(result).isSameAs(dto);
    // Same mechanic as the participant add: a free-text name with a single member match is linked
    // as a registered party lead and the guest handle is dropped.
    verify(missionService).setPartyLead(id, resolvedId, null, 1L);
  }

  @Test
  void setPartyLead_freeTextName_noMatch_isStoredAsGuestHandle() {
    UUID id = UUID.randomUUID();
    SetPartyLeadRequest request = new SetPartyLeadRequest(null, "Stranger", 0L);
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(userService.findMatchesByExactName("Stranger")).thenReturn(List.of());
    when(missionService.setPartyLead(id, null, "Stranger", 0L)).thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.setPartyLead(id, request);

    assertThat(result).isSameAs(dto);
    // No registered member matches the free text -> kept as an anonymous guest handle.
    verify(missionService).setPartyLead(id, null, "Stranger", 0L);
  }

  @Test
  void setPartyLead_freeTextName_ambiguous_throws409_andDoesNotPersist() {
    UUID id = UUID.randomUUID();
    SetPartyLeadRequest request = new SetPartyLeadRequest(null, "Sam", 0L);
    User a = new User();
    a.setId(UUID.randomUUID());
    User b = new User();
    b.setId(UUID.randomUUID());
    when(userService.findMatchesByExactName("Sam")).thenReturn(List.of(a, b));

    try {
      controller.setPartyLead(id, request);
      org.junit.jupiter.api.Assertions.fail("Expected BusinessConflictException");
    } catch (BusinessConflictException expected) {
      // ok — an ambiguous name surfaces as 409 before any persistence happens.
    }

    verify(missionService, never()).setPartyLead(any(), any(), any(), any());
  }

  @Test
  void setPartyLead_emptySubmission_clearsPartyLead() {
    UUID id = UUID.randomUUID();
    SetPartyLeadRequest request = new SetPartyLeadRequest(null, null, 4L);
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(id);
    when(missionService.setPartyLead(id, null, null, 4L)).thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);

    MissionDto result = controller.setPartyLead(id, request);

    assertThat(result).isSameAs(dto);
    // Neither a userId nor a guest name -> no resolution, the service clears the party lead.
    verify(userService, never()).findMatchesByExactName(any());
    verify(missionService).setPartyLead(id, null, null, 4L);
  }
}
