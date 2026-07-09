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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.metrics.ScheduledJob;
import de.greluc.krt.profit.basetool.backend.metrics.TaskMetrics;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserSyncResultDto;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.service.UserSyncService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Pure-method unit tests for {@link UserController}. Coverage was 39% before this file. The key
 * behaviours under test:
 *
 * <ul>
 *   <li>{@code /me} endpoints derive the caller id from the JWT — never from the URL — so callers
 *       cannot impersonate someone else.
 *   <li>Each admin endpoint forwards the path id and request DTO fields verbatim to the service;
 *       the controller does not silently drop / transform values.
 *   <li>The {@code lookup} endpoint returns reference DTOs directly from the service — no mapper
 *       involvement, so the email/sensitive fields cannot accidentally leak.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

  @Mock private UserService userService;
  @Mock private UserMapper userMapper;
  @Mock private de.greluc.krt.profit.basetool.backend.service.AuthHelperService authHelperService;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;
  @Mock private UserSyncService userSyncService;
  @Mock private TaskMetrics taskMetrics;
  @Mock private Jwt jwt;

  @InjectMocks private UserController controller;

  private static final UUID CALLER_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    // Audit finding H-4: UserController now redacts peer PII for non-Officer callers via
    // {@code authHelperService.isLogisticianOrAbove()}. The existing delegation/contract tests
    // below were written before that gate and assume "controller hands the DTO through as-is".
    // Default the gate to {@code true} (officer view) here so those tests keep asserting the
    // delegation invariants; the dedicated H-4 tests override the stub with {@code false}.
    org.mockito.Mockito.lenient().when(authHelperService.isLogisticianOrAbove()).thenReturn(true);
    // Audit finding H-3 (2026-05-20): {@link UserController#getUserById} additionally checks the
    // squadron-scope of the target user — a non-admin caller asking for a foreign-squadron user
    // (or a squadron-less account: admins, guests) is always given the peer-redacted shape, even
    // when their role would otherwise unlock full PII. Default {@code isAdmin} to {@code true}
    // for the delegation tests so they keep asserting the "controller hands the DTO through
    // as-is" invariant; the H-3 cross-squadron tests below flip it to {@code false}.
    org.mockito.Mockito.lenient().when(authHelperService.isAdmin()).thenReturn(true);
  }

  // ── GET / (list) ────────────────────────────────────────────────────────

  @Test
  void getAllUsers_wrapsServicePageIntoPageResponse() {
    User entity = new User();
    UserDto dto = mockDto(UUID.randomUUID());
    when(userService.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(dto);

    PageResponse<UserDto> resp = controller.getAllUsers(0, 50, null);

    assertEquals(1, resp.totalElements());
    assertSame(dto, resp.content().getFirst());
  }

  // ── POST /sync (admin-triggered manual Keycloak user sync) ────────────────

  @Test
  void syncUsersNow_runsTheReconciliationThroughTaskMetricsAndReturnsTheCount() {
    when(taskMetrics.recordCountingRethrow(eq(ScheduledJob.USER_SYNC), any())).thenReturn(42);

    UserSyncResultDto result = controller.syncUsersNow();

    assertEquals(42, result.syncedCount());
    verify(taskMetrics).recordCountingRethrow(eq(ScheduledJob.USER_SYNC), any());
  }

  @Test
  void syncUsersNow_propagatesASyncFailureRatherThanSwallowingIt() {
    when(taskMetrics.recordCountingRethrow(eq(ScheduledJob.USER_SYNC), any()))
        .thenThrow(new IllegalStateException("keycloak down"));

    // The manual endpoint must surface a failure (→ RFC 7807), unlike the swallowing scheduled
    // path.
    assertThrows(IllegalStateException.class, () -> controller.syncUsersNow());
  }

  // ── GET /lookup ─────────────────────────────────────────────────────────

  @Test
  void lookupUsers_returnsReferenceListDirectlyFromService() {
    // The reference DTO intentionally hides email / rank / description so
    // a regression that routed through userMapper would leak fields.
    List<UserReferenceDto> refs =
        List.of(
            new UserReferenceDto(UUID.randomUUID(), "alice", "Alice", "Alice", 5),
            new UserReferenceDto(UUID.randomUUID(), "bob", "Bob", "Bob", 4));
    when(userService.findAllReference()).thenReturn(refs);

    List<UserReferenceDto> result = controller.lookupUsers();

    assertSame(refs, result);
    verifyNoInteractions(userMapper);
  }

  // ── GET /search ─────────────────────────────────────────────────────────

  @Test
  void searchUsers_forwardsQueryToService() {
    when(userService.searchByUsername(eq("ali"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    controller.searchUsers("ali", null, null, null);

    verify(userService).searchByUsername(eq("ali"), any(Pageable.class));
  }

  @Test
  void searchUsers_wrapsServicePageIntoPageResponse() {
    User entity = new User();
    UserDto dto = mockDto(UUID.randomUUID());
    when(userService.searchByUsername(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(dto);

    PageResponse<UserDto> resp = controller.searchUsers("x", 0, 50, null);

    assertEquals(1, resp.content().size());
  }

  // ── GET /{id} ───────────────────────────────────────────────────────────

  @Test
  void getUserById_delegatesAndMaps() {
    UUID id = UUID.randomUUID();
    User entity = new User();
    UserDto dto = mockDto(id);
    when(userService.findById(id)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(dto);

    UserDto result = controller.getUserById(id);

    assertSame(dto, result);
  }

  // ── Audit finding H-4: peer redaction for non-Officer callers ───────────

  @Test
  void getUserById_nonOfficerCaller_redactsPii() {
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    UUID id = UUID.randomUUID();
    User entity = new User();
    UserDto fullDto = fullPiiUserDto(id);
    when(userService.findById(id)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    UserDto result = controller.getUserById(id);

    assertNotSame(fullDto, result);
    assertEquals(id, result.id());
    assertEquals("bob.callsign", result.username());
    assertEquals("Bob Display", result.displayName());
    assertNull(result.email(), "peer view must not expose email");
    assertNull(result.roles(), "peer view must not expose roles");
    assertNull(result.permissions(), "peer view must not expose permissions");
  }

  @Test
  void searchUsers_nonOfficerCaller_redactsEveryRowPii() {
    when(authHelperService.isLogisticianOrAbove()).thenReturn(false);
    UUID id = UUID.randomUUID();
    User entity = new User();
    UserDto fullDto = fullPiiUserDto(id);
    when(userService.searchByUsername(any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(entity)));
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    PageResponse<UserDto> resp = controller.searchUsers("bob", 0, 50, null);

    UserDto redacted = resp.content().getFirst();
    assertNull(redacted.email());
  }

  // ── Audit finding H-3 (2026-05-20): cross-squadron isolation on getUserById ────────────

  @Test
  void getUserById_officerFromForeignSquadron_redactsPii() {
    // Officer of squadron A asking for a user that lives in squadron B. Without H-3 the
    // role-based gate {@code isLogisticianOrAbove()} would unlock full PII; with H-3 the
    // squadron-scope check overrides that for non-admin callers.
    when(authHelperService.isAdmin()).thenReturn(false);
    UUID userId = UUID.randomUUID();
    UUID foreignSquadronId = UUID.randomUUID();
    when(authHelperService.canSeeSquadron(foreignSquadronId)).thenReturn(false);

    User entity = new User();
    entity.setId(userId);
    // REQ-ORG-017: the home Staffel(n) come from the membership service (the PII gate ORs across
    // all of the target's Staffeln).
    when(orgUnitMembershipService.findStaffelMembershipOrgUnitIds(userId))
        .thenReturn(java.util.List.of(foreignSquadronId));
    UserDto fullDto = fullPiiUserDto(userId);
    when(userService.findById(userId)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    UserDto result = controller.getUserById(userId);

    // Slim peer view: callsign + displayName remain, PII fields are wiped — same shape as the
    // existing peer-redaction path.
    assertEquals("bob.callsign", result.username());
    assertNull(result.email(), "cross-squadron non-admin must not see email");
    assertNull(result.joinDate(), "cross-squadron non-admin must not see joinDate");
  }

  @Test
  void getUserById_unassignedUser_redactsPiiForNonAdmin() {
    // Squadron-less users (admins, freshly-imported guests) are always treated as
    // cross-squadron for non-admin callers — full PII on those rows stays admin-only.
    when(authHelperService.isAdmin()).thenReturn(false);
    UUID userId = UUID.randomUUID();
    User entity = new User();
    entity.setId(userId);
    // REQ-ORG-017: "no Staffel" surfaces as an empty list from the membership lookup.
    when(orgUnitMembershipService.findStaffelMembershipOrgUnitIds(userId))
        .thenReturn(java.util.List.of());
    UserDto fullDto = fullPiiUserDto(userId);
    when(userService.findById(userId)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    UserDto result = controller.getUserById(userId);

    assertNull(result.email());
  }

  @Test
  void getUserById_sameSquadronOfficer_keepsPii() {
    // Officer of squadron A asking for a user in squadron A: H-3 must NOT block — they need the
    // PII for moderation / payouts inside their own squadron.
    when(authHelperService.isAdmin()).thenReturn(false);
    UUID userId = UUID.randomUUID();
    UUID sharedSquadronId = UUID.randomUUID();
    when(authHelperService.canSeeSquadron(sharedSquadronId)).thenReturn(true);

    User entity = new User();
    entity.setId(userId);
    // REQ-ORG-017: same-squadron lookup goes through the membership service (ORs across Staffeln).
    when(orgUnitMembershipService.findStaffelMembershipOrgUnitIds(userId))
        .thenReturn(java.util.List.of(sharedSquadronId));
    UserDto fullDto = fullPiiUserDto(userId);
    when(userService.findById(userId)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(fullDto);

    UserDto result = controller.getUserById(userId);

    assertSame(fullDto, result, "same-squadron officer must see the original DTO unredacted");
  }

  private static UserDto fullPiiUserDto(UUID id) {
    return new UserDto(
        id,
        "bob.callsign",
        "Bob Display",
        "Bob",
        "bob@example.invalid",
        5,
        "some desc",
        java.util.Set.of("ROLE_KRT_MEMBER"),
        java.util.Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        false);
  }

  // ── GET /me ─────────────────────────────────────────────────────────────

  @Test
  void getCurrentUser_resolvesIdFromJwt_neverFromURL() {
    // SECURITY: the /me endpoint must derive its target id from the JWT,
    // never from a request parameter. A regression here lets any caller
    // request another user's profile.
    User entity = new User();
    // The self endpoint re-adds the caller's OWN email on top of the (email-free) mapper output.
    entity.setEmail("me@example.invalid");
    UserDto dto = mockDto(CALLER_ID);
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(userService.findById(CALLER_ID)).thenReturn(entity);
    when(userMapper.toDto(entity)).thenReturn(dto);

    UserDto result = controller.getCurrentUser(jwt);

    assertNotNull(result);
    assertEquals(CALLER_ID, result.id());
    // A user always sees their own email in their own profile (re-added by withSelfEmail).
    assertEquals("me@example.invalid", result.email());
    verify(userService).getUserIdFromJwt(jwt);
    verify(userService).findById(CALLER_ID);
  }

  // ── PUT /me/description ─────────────────────────────────────────────────

  @Test
  void updateMyDescription_resolvesIdFromJwt_andForwardsAllFields() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);

    UserController.UserDescriptionRequest req = new UserController.UserDescriptionRequest();
    req.setDescription("Pilot extraordinaire");
    req.setDisplayName("Ace");
    req.setVersion(2L);

    User updated = new User();
    updated.setEmail("me@example.invalid");
    UserDto dto = mockDto(CALLER_ID);
    when(userService.updateUserDescription(CALLER_ID, "Pilot extraordinaire", "Ace", 2L))
        .thenReturn(updated);
    when(userMapper.toDto(updated)).thenReturn(dto);

    UserDto result = controller.updateMyDescription(jwt, req);

    assertNotNull(result);
    assertEquals(CALLER_ID, result.id());
    assertEquals("me@example.invalid", result.email());
    verify(userService).updateUserDescription(CALLER_ID, "Pilot extraordinaire", "Ace", 2L);
  }

  // ── GET/PUT /me/payout-preference ────────────────────────────────────────

  @Test
  void getMyPayoutPreference_resolvesIdFromJwt_andReturnsPreferenceAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    User me = new User();
    me.setId(CALLER_ID);
    me.setDefaultPayoutPreference(PayoutPreference.DONATE);
    me.setVersion(4L);
    when(userService.findById(CALLER_ID)).thenReturn(me);

    UserController.MyPayoutPreferenceResponse result = controller.getMyPayoutPreference(jwt);

    assertEquals(PayoutPreference.DONATE, result.defaultPayoutPreference());
    assertEquals(4L, result.version());
  }

  @Test
  void getMyPayoutPreference_returnsNullPreference_whenUserNeverChose() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    User me = new User();
    me.setId(CALLER_ID);
    me.setVersion(1L);
    when(userService.findById(CALLER_ID)).thenReturn(me);

    UserController.MyPayoutPreferenceResponse result = controller.getMyPayoutPreference(jwt);

    assertNull(
        result.defaultPayoutPreference(), "no explicit choice surfaces as a null preference");
  }

  @Test
  void updateMyPayoutPreference_resolvesIdFromJwt_andForwardsPreferenceAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    UserController.MyPayoutPreferenceRequest req =
        new UserController.MyPayoutPreferenceRequest(PayoutPreference.DONATE, 2L);
    User updated = new User();
    updated.setId(CALLER_ID);
    updated.setDefaultPayoutPreference(PayoutPreference.DONATE);
    updated.setVersion(3L);
    when(userService.updateUserDefaultPayoutPreference(CALLER_ID, PayoutPreference.DONATE, 2L))
        .thenReturn(updated);

    UserController.MyPayoutPreferenceResponse result =
        controller.updateMyPayoutPreference(jwt, req);

    assertEquals(PayoutPreference.DONATE, result.defaultPayoutPreference());
    assertEquals(3L, result.version());
    verify(userService).updateUserDefaultPayoutPreference(CALLER_ID, PayoutPreference.DONATE, 2L);
  }

  // ── GET/PUT /me/blueprint-sharing (REQ-INV-018) ─────────────────────────

  @Test
  void getMyBlueprintSharing_resolvesIdFromJwt_andReturnsFlagAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    User me = new User();
    me.setId(CALLER_ID);
    me.setShareBlueprintsGlobally(true);
    me.setVersion(4L);
    when(userService.findById(CALLER_ID)).thenReturn(me);

    UserController.MyBlueprintSharingResponse result = controller.getMyBlueprintSharing(jwt);

    assertTrue(result.shareBlueprintsGlobally());
    assertEquals(4L, result.version());
  }

  @Test
  void updateMyBlueprintSharing_resolvesIdFromJwt_andForwardsFlagAndVersion() {
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    UserController.MyBlueprintSharingRequest req =
        new UserController.MyBlueprintSharingRequest(true, 2L);
    User updated = new User();
    updated.setId(CALLER_ID);
    updated.setShareBlueprintsGlobally(true);
    updated.setVersion(3L);
    when(userService.updateUserShareBlueprintsGlobally(CALLER_ID, true, 2L)).thenReturn(updated);

    UserController.MyBlueprintSharingResponse result =
        controller.updateMyBlueprintSharing(jwt, req);

    assertTrue(result.shareBlueprintsGlobally());
    assertEquals(3L, result.version());
    verify(userService).updateUserShareBlueprintsGlobally(CALLER_ID, true, 2L);
  }

  // ── PUT /me/read-announcement/{id} ──────────────────────────────────────

  @Test
  void updateReadAnnouncement_resolvesIdFromJwt_andForwardsAnnouncementId() {
    UUID announcementId = UUID.randomUUID();
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    User updated = new User();
    updated.setEmail("me@example.invalid");
    when(userService.updateReadAnnouncement(CALLER_ID, announcementId)).thenReturn(updated);
    UserDto dto = mockDto(CALLER_ID);
    when(userMapper.toDto(updated)).thenReturn(dto);

    UserDto result = controller.updateReadAnnouncement(jwt, announcementId);

    assertNotNull(result);
    assertEquals(CALLER_ID, result.id());
    assertEquals("me@example.invalid", result.email());
    verify(userService).updateReadAnnouncement(CALLER_ID, announcementId);
  }

  // ── PUT /{id}/attributes ────────────────────────────────────────────────

  @Test
  void updateUserAttributes_forwardsAllFieldsToService() {
    UUID id = UUID.randomUUID();

    UserController.UserAttributesRequest req = new UserController.UserAttributesRequest();
    req.setRank(7);
    req.setDescription("desc");
    req.setDisplayName("name");
    req.setVersion(3L);
    req.setJoinDate(LocalDate.of(2024, 1, 15));

    User updated = new User();
    UserDto dto = mockDto(id);
    when(userService.updateUserAttributes(id, 7, "desc", "name", 3L, LocalDate.of(2024, 1, 15)))
        .thenReturn(updated);
    when(userMapper.toDto(updated)).thenReturn(dto);

    UserDto result = controller.updateUserAttributes(id, req);

    assertSame(dto, result);
    verify(userService).updateUserAttributes(id, 7, "desc", "name", 3L, LocalDate.of(2024, 1, 15));
  }

  @Test
  void updateUserAttributes_withNullJoinDate_forwardsNull() {
    UUID id = UUID.randomUUID();
    UserController.UserAttributesRequest req = new UserController.UserAttributesRequest();
    req.setRank(3);
    req.setVersion(1L);
    req.setJoinDate(null);

    when(userService.updateUserAttributes(eq(id), eq(3), any(), any(), eq(1L), eq(null)))
        .thenReturn(new User());
    when(userMapper.toDto(any())).thenReturn(mockDto(id));

    controller.updateUserAttributes(id, req);

    verify(userService).updateUserAttributes(id, 3, null, null, 1L, null);
  }

  // ── DELETE /{id} ────────────────────────────────────────────────────────

  @Test
  void deleteUser_delegatesIdToService() {
    UUID id = UUID.randomUUID();

    controller.deleteUser(id);

    verify(userService).deleteUser(id);
    verifyNoMoreInteractions(userService, userMapper);
  }

  // ── GET /{id}/memberships ───────────────────────────────────────────────

  @Test
  void getUserMemberships_delegatesToService() {
    UUID userId = UUID.randomUUID();
    OrgUnitMembershipOptionDto option =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "IRIDIUM", "IRI", OrgUnitKind.SQUADRON, false);
    when(orgUnitMembershipService.listOptionsForUser(userId)).thenReturn(List.of(option));

    List<OrgUnitMembershipOptionDto> result = controller.getUserMemberships(userId, false);

    assertEquals(1, result.size());
    assertSame(option, result.getFirst());
    verify(orgUnitMembershipService).listOptionsForUser(userId);
  }

  @Test
  void getUserMemberships_allKinds_delegatesToDirectMembershipOptions() {
    // REQ-BANK-044: the bank counterparty picker asks for all four kinds via allKinds=true.
    UUID userId = UUID.randomUUID();
    OrgUnitMembershipOptionDto bereich =
        new OrgUnitMembershipOptionDto(
            UUID.randomUUID(), "Bereich Logistik", "LOG", OrgUnitKind.BEREICH, false);
    when(orgUnitMembershipService.listDirectMembershipOptions(userId)).thenReturn(List.of(bereich));

    List<OrgUnitMembershipOptionDto> result = controller.getUserMemberships(userId, true);

    assertSame(bereich, result.getFirst());
    verify(orgUnitMembershipService).listDirectMembershipOptions(userId);
  }

  @Test
  void getUserMemberships_emptyResult_returnsEmptyList() {
    UUID userId = UUID.randomUUID();
    when(orgUnitMembershipService.listOptionsForUser(userId)).thenReturn(List.of());

    List<OrgUnitMembershipOptionDto> result = controller.getUserMemberships(userId, false);

    assertTrue(result.isEmpty());
  }

  @Test
  void getMyOrgUnitIds_derivesCallerFromJwt_andDelegatesToService() {
    java.util.Set<UUID> ids = java.util.Set.of(UUID.randomUUID(), UUID.randomUUID());
    when(userService.getUserIdFromJwt(jwt)).thenReturn(CALLER_ID);
    when(orgUnitMembershipService.findDirectMembershipOrgUnitIds(CALLER_ID)).thenReturn(ids);

    java.util.Set<UUID> result = controller.getMyOrgUnitIds(jwt);

    // The caller id is taken from the JWT (never an URL path), and the id set is handed through.
    assertSame(ids, result);
    verify(userService).getUserIdFromJwt(jwt);
    verify(orgUnitMembershipService).findDirectMembershipOrgUnitIds(CALLER_ID);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static UserDto mockDto(UUID id) {
    return new UserDto(
        id,
        "u",
        "U",
        "U",
        "u@example.com",
        5,
        "desc",
        java.util.Set.of(),
        java.util.Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        false);
  }
}
