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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.mapper.MissionMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JoinMissionRequest;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pure-Mockito unit tests for the parts of {@link MissionController} that the existing {@code
 * MissionControllerSecurityTest} (role gates) and {@code MissionControllerSlimEndpointsTest} (slim
 * sub-resource endpoints + RSVP branches) do NOT touch:
 *
 * <ul>
 *   <li><b>Peer redaction</b> — {@code MissionPeerRedactor#cleanupMissionForPeer} is the only path
 *       that controls what leaves the API to a member below Logistician (detected via {@code
 *       AuthHelperService#isLogisticianOrAbove()}). Pinning it (participant PII stripped to the
 *       public callsign tuple, owner and managers withheld from a caller who may not manage the
 *       mission) protects the multi-user-data-isolation guarantee in CLAUDE.md. Since 2026-09-06
 *       every mission return runs through it, not only the reads — which is why the {@code
 *       BeforeEach} below assumes a Logistician unless a case says otherwise.
 *       <p>There used to be a stricter <b>outsider</b> tier here for anonymous and role-less
 *       callers (ADR-0034), with its own access blocks (internal and terminal missions refused
 *       outright) and its own list filtering (silently restricted to {@code PLANNED}/{@code ACTIVE}
 *       non-internal). Its whole audience is gone (ADR-0159) and so are its cases.
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

  // Real redactor (not a mock) so the peer-redaction assertions exercise the actual
  // MissionPeerRedactor logic; @Spy makes @InjectMocks wire it into the controller.
  @org.mockito.Spy
  private de.greluc.krt.profit.basetool.backend.support.MissionPeerRedactor missionPeerRedactor =
      new de.greluc.krt.profit.basetool.backend.support.MissionPeerRedactor();

  @InjectMocks private MissionController controller;

  /**
   * Every mission response now runs through the peer pass on its way out (REQ-SEC-007), so a test
   * that does not say which tier its caller is in gets the redacted shape by Mockito's default
   * {@code false}. Most cases here are about ARGUMENT FORWARDING and assert the mapper's own
   * result, so the default is Logistician-and-above, for whom the pass is a no-op. The three cases
   * that are about the redaction override it, and are the reason this is {@code lenient()}.
   */
  @BeforeEach
  void assumeALogisticianUnlessACaseSaysOtherwise() {
    org.mockito.Mockito.lenient().when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
  }

  private static Jwt jwt(String sub) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(sub)
        .claim("sub", sub)
        .build();
  }

  /**
   * Build a representative MissionDto that exercises every field {@code
   * MissionPeerRedactor#cleanupMissionForPeer} touches. This is the canary input for the redaction
   * assertions further down: every "internal" or "leaks-PII" field is intentionally populated so
   * the cleanup pass has something to strip.
   */
  private static MissionDto fullMissionDto(UUID id) {
    return fullMissionDto(id, true);
  }

  /**
   * The unredacted mission the controller's mapper is stubbed to return.
   *
   * @param id the mission id
   * @param managing what {@code MissionMapper} resolved for THIS caller — {@code canEdit} and
   *     {@code canManageManagers}, which since 2026-09-06 also decide whether the peer pass keeps
   *     the mission's owner and manager list: a caller the response tells may change that list is
   *     shown it, a peer who is only reading is not (REQ-SEC-007)
   * @return a fully populated {@link MissionDto}
   */
  private static MissionDto fullMissionDto(UUID id, boolean managing) {
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
            1L);
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
        null,
        owner,
        Set.of(manager),
        managing,
        managing,
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

  /**
   * A list page resolves its registration counts in ONE grouped read, and each row gets its own.
   *
   * <p>REQ-MISSION-018 / REQ-DATA-003. The figure lives in the mission's lazy {@code participants}
   * collection, so the tempting implementation — letting the mapper read it — is a SELECT per row.
   * This pins the shape that avoids it: one call for the whole page, and a mission the grouped
   * statement returned no row for is a zero rather than a null.
   */
  @Test
  void listRows_getTheirOwnCountFromOneGroupedRead() {
    Mission crowded = new Mission();
    crowded.setId(UUID.randomUUID());
    Mission empty = new Mission();
    empty.setId(UUID.randomUUID());
    Page<Mission> page = new PageImpl<>(List.of(crowded, empty), PageRequest.of(0, 20), 2);
    when(missionService.searchMissions(
            any(), any(), any(), any(), any(), any(), any(Pageable.class)))
        .thenReturn(page);
    // Only the crowded mission has participants; the empty one produced no row at all.
    when(missionService.registeredCounts(any())).thenReturn(Map.of(crowded.getId(), 7L));

    controller.getAllMissions(null, null, null);

    ArgumentCaptor<Long> counts = ArgumentCaptor.forClass(Long.class);
    verify(missionMapper, times(2)).toListDto(any(Mission.class), counts.capture());
    assertThat(counts.getAllValues()).containsExactly(7L, 0L);
    // One grouped read for the page, not one per row.
    verify(missionService, times(1)).registeredCounts(List.of(crowded.getId(), empty.getId()));
  }

  // ── GET /api/v1/missions (anonymous filtering) ───────────────────────

  // Asserts the deprecated-for-removal MissionService.getAllMissions(Pageable) is never hit;
  // referencing it in verify(...) triggers an expected, unavoidable [removal] warning.
  @Test
  @SuppressWarnings("removal")
  void getAllMissions_routesThroughSearchMissionsForSquadronScope() {
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
            0L,
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
    when(missionMapper.toListDto(eq(m), anyLong())).thenReturn(listDto);
    // The member check that used to stand here is the @PreAuthorize gate now (REQ-SEC-052), so
    // every caller reaching the method body is one and there is no second shape to distinguish.

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

  // Three cases stood here: an outsider's list and search silently restricted to
  // PLANNED+ACTIVE non-internal missions, and a forbidden status filter answered with an
  // empty page instead of a 403. The branch they pinned is gone with its audience
  // (ADR-0159): both endpoints now carry
  // @PreAuthorize("isAuthenticated() and @authHelperService.isMemberOrAbove()"), so the
  // caller the restriction existed for is refused before the method runs. What a member may
  // see is decided in the service's org-unit scope, which OwnerScopeServiceTest covers.

  // ── GET /api/v1/missions/search (anonymous filtering / empty-after-filter) ──

  @Test
  void searchMissions_passesStatusFilterVerbatim() {
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
            0L,
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
    when(missionMapper.toListDto(eq(m), anyLong())).thenReturn(listDto);
    // Verbatim for every caller now: the status filter had one other shape, and it belonged to the
    // outsider the endpoint no longer admits (REQ-SEC-052).

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

  // ── GET /api/v1/missions/{id} (peer redaction) ───────────────────────

  @Test
  void getMissionById_logisticianCaller_returnsFullDtoUnchanged() {
    UUID id = UUID.randomUUID();
    Mission entity = new Mission();
    MissionDto full = fullMissionDto(id);
    when(missionService.getMissionById(id)).thenReturn(entity);
    when(missionMapper.toDto(entity)).thenReturn(full);
    // Logistician or above → no redaction pass. The line used to read isMemberOrAbove: the DTO
    // was full for every member and redacted only for the outsider tier. With that tier gone the
    // one surviving distinction is REQ-SEC-007's, which is drawn at Logistician.
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

    MissionDto result = controller.getMissionById(id);

    // Owner/managers/PII flow through unchanged. Pin the "isSameAs" so a future change that
    // ALWAYS redacts (e.g. as a "safety net") would surface here as a different identity.
    assertThat(result).isSameAs(full);
  }

  // Three cases stood here: an outsider refused (403) on an internal mission and on a
  // COMPLETED / CANCELLED one. Both throws lived in the controller and belonged to the
  // outsider tier. Visibility for a member has always been decided one layer up by
  // @ownerScopeService.canSeeMission(#id) — own Staffel, or any non-internal mission
  // organisation-wide — which is where the internal-mission rule still is and where
  // OwnerScopeServiceTest tests it. A terminal mission was never hidden from a member.

  @Test
  void getMissionById_peer_keepsRosterButStripsPii() {
    UUID id = UUID.randomUUID();
    Mission planned = new Mission();
    planned.setIsInternal(false);
    planned.setStatus("PLANNED");
    MissionDto full = fullMissionDto(id, false);
    when(missionService.getMissionById(id)).thenReturn(planned);
    when(missionMapper.toDto(planned)).thenReturn(full);
    // A member below Logistician — the only redacted tier left (REQ-SEC-007).
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    MissionDto result = controller.getMissionById(id);

    // Peer redaction (cleanupMissionForPeer): the roster stays, its PII does not. Owner, managers
    // and the edit flags are cleared; organisation, units, frequencies and the free-text
    // description stay visible. The description used to be hidden here — that was the outsider
    // tier's one extra field, and its audience was people outside the organisation. A peer is a
    // member of it and reads the mission's own text (ADR-0159).
    assertThat(result).isNotNull();
    assertThat(result.name()).isEqualTo("Op Foxglove");
    assertThat(result.status()).isEqualTo("PLANNED");
    assertThat(result.description()).isEqualTo("internal description");
    // Stripped: owner / managers.
    assertThat(result.owner()).isNull();
    assertThat(result.managers()).isNull();
    // NOT forced off: the flags are what the CALLER may do, forwarded rather than overwritten. A
    // MISSION_MANAGER sits below Logistician (the hierarchy puts ADMIN/OFFICER above both roles
    // but never MISSION_MANAGER above LOGISTICIAN), so forcing them would hide the management
    // controls from the Einsatz's own manager. Here they are false because this caller is only
    // READING, which is also why owner and managers are stripped above — the two travel together
    // since 2026-09-06. MissionPeerRedactorTest owns both directions of that rule.
    assertThat(result.canEdit()).isFalse();
    assertThat(result.canManageManagers()).isFalse();
    // #1138: the mission economy (inventory / refinery orders) is no longer part of MissionDto —
    // there is nothing to assert empty here; it is served member-gated at its own endpoints.
    // The participant roster IS visible to outsiders — but PII is stripped to the public callsign
    // tuple (username / displayName / rank), never email or roles.
    assertThat(result.participants()).hasSize(1);
    MissionParticipantDto rosterParticipant = result.participants().iterator().next();
    UserDto rosterUser = rosterParticipant.user();
    assertThat(rosterUser.username()).isEqualTo("alice");
    assertThat(rosterUser.email()).isNull();
    assertThat(rosterUser.roles()).isNull();
    // Payout intent and the free-text comment survive the peer tier: they were stripped for the
    // outsider (ADR-0034 / REQ-SEC-021), and among members they are what the sign-up sheet is for.
    assertThat(rosterParticipant.payoutPreference()).isEqualTo(PayoutPreference.PAYOUT);
    assertThat(rosterParticipant.comment()).isEqualTo("comment");
  }

  // ── GET /api/v1/missions/next (200 / 204 + redaction) ────────────────

  @Test
  void getNextMission_noMission_returns204() {
    when(missionService.getNextMission(true)).thenReturn(Optional.empty());

    ResponseEntity<MissionDto> response = controller.getNextMission();

    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(response.getBody()).isNull();
  }

  @Test
  void getNextMission_peer_allowInternalIsTrue_andResponseRedacted() {
    UUID id = UUID.randomUUID();
    Mission upcoming = new Mission();
    MissionDto full = fullMissionDto(id, false);
    // A member below Logistician.
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    when(missionService.getNextMission(true)).thenReturn(Optional.of(upcoming));
    when(missionMapper.toDto(upcoming)).thenReturn(full);

    ResponseEntity<MissionDto> response = controller.getNextMission();

    // allowInternal is true for every caller now. It was false for the outsider tier, whose whole
    // point was that internal missions must not surface at all; a peer is a member of the
    // organisation, is scoped by canSeeMission like anyone else, and reads the redacted DTO.
    verify(missionService).getNextMission(true);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().owner()).isNull();
  }

  @Test
  void getNextMission_logistician_dtoUnchanged() {
    UUID id = UUID.randomUUID();
    Mission upcoming = new Mission();
    MissionDto full = fullMissionDto(id);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    when(missionService.getNextMission(true)).thenReturn(Optional.of(upcoming));
    when(missionMapper.toDto(upcoming)).thenReturn(full);

    ResponseEntity<MissionDto> response = controller.getNextMission();

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isSameAs(full);
  }

  // ── POST /api/v1/missions/{id}/join ──────────────────────────────────

  @Test
  void joinMission_withoutBody_resolvesCallerFromJwt_andKeepsTheDefaultChain() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(missionId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(missionService.addParticipant(missionId, callerId, null, null, null, null, null))
        .thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);
    // Logistician, so the response is not redacted and stays identity-comparable. What an
    // ORDINARY member gets back is the subject of joinMission_member_getsTheRedactedMission().
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

    MissionDto result = controller.joinMission(jwt, missionId, null);

    // The self-enroll shortcut MUST resolve the caller from the JWT — never accept a userId
    // from the URL/body. Pin the captured argument to the JWT-derived id.
    //
    // The null body is the shipped-client case and the reason the parameter is optional:
    // REQ-API-009 freezes this operation, so a build that sends nothing must keep working, and
    // every downstream argument stays null so REQ-MISSION-002's profile-default payout chain
    // still decides.
    assertThat(result).isSameAs(dto);
    verify(missionService).addParticipant(missionId, callerId, null, null, null, null, null);
  }

  @Test
  void joinMission_withBody_carriesTheSheetsTwoAnswersAndNothingElse() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    UUID desiredJobTypeId = UUID.randomUUID();
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(missionId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(missionService.addParticipant(
            missionId, callerId, null, desiredJobTypeId, null, null, PayoutPreference.DONATE))
        .thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

    MissionDto result =
        controller.joinMission(
            jwt, missionId, new JoinMissionRequest(desiredJobTypeId, PayoutPreference.DONATE));

    // guestName, comment and orgUnitIds stay null on purpose: this body cannot name anybody but
    // the caller, which is what lets the endpoint skip the self-vs-manager check that
    // /participants/add needs (ADR-0154).
    assertThat(result).isSameAs(dto);
    verify(missionService)
        .addParticipant(
            missionId, callerId, null, desiredJobTypeId, null, null, PayoutPreference.DONATE);
  }

  @Test
  void joinMission_withHalfAnAnswer_leavesTheOtherFieldNull() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    Mission persisted = new Mission();
    MissionDto dto = fullMissionDto(missionId);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(missionService.addParticipant(
            missionId, callerId, null, null, null, null, PayoutPreference.PAYOUT))
        .thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(dto);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(true);

    // A member who picks a payout but no Funktion: the unanswered field means "no preference",
    // never "clear it".
    MissionDto result =
        controller.joinMission(
            jwt, missionId, new JoinMissionRequest(null, PayoutPreference.PAYOUT));

    assertThat(result).isSameAs(dto);
    verify(missionService)
        .addParticipant(missionId, callerId, null, null, null, null, PayoutPreference.PAYOUT);
  }

  /**
   * The defect this endpoint carried until ADR-0159: {@code joinMission} returned the mission
   * <b>unredacted</b> — roster, owner, managers and every participant's e-mail — to whoever had
   * just joined. That caller is by definition an ordinary member, the one person on the mission
   * surface most likely to sit below Logistician, so the endpoint leaked precisely to the audience
   * REQ-SEC-007 exists for. The ArchUnit rule that should have caught it selected only gates
   * WITHOUT {@code isAuthenticated()}, and this gate has always had one.
   */
  @Test
  void joinMission_member_getsTheRedactedMission() {
    Jwt jwt = jwt("alice-sub");
    UUID callerId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    Mission persisted = new Mission();
    MissionDto full = fullMissionDto(missionId, false);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(callerId);
    when(missionService.addParticipant(missionId, callerId, null, null, null, null, null))
        .thenReturn(persisted);
    when(missionMapper.toDto(persisted)).thenReturn(full);
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);

    MissionDto result = controller.joinMission(jwt, missionId, null);

    assertThat(result).isNotSameAs(full);
    assertThat(result.owner()).isNull();
    assertThat(result.managers()).isNull();
    assertThat(result.participants().iterator().next().user().email()).isNull();
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
            UUID.randomUUID(), null, null, null, null, null, null, null, null, null, 1L);
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
            "Sub", "desc", null, "PLANNED", null, null, null, false, null, null, null, null, null);
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
