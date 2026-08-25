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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.MissionParticipant;
import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.UpdateMissionRequest;
import de.greluc.krt.profit.basetool.backend.model.projection.MissionParticipantCount;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.MissionParticipantService;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import de.greluc.krt.profit.basetool.backend.service.ScopePredicate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MissionServiceTest {

  @Mock private MissionRepository missionRepository;

  @Mock private UserRepository userRepository;

  @Mock private SquadronRepository squadronRepository;

  @Mock private MissionParticipantRepository missionParticipantRepository;

  @Mock private de.greluc.krt.profit.basetool.backend.service.OwnerScopeService ownerScopeService;

  @Mock private de.greluc.krt.profit.basetool.backend.service.UserService userService;

  @Mock private de.greluc.krt.profit.basetool.backend.service.AuthHelperService authHelperService;

  @Mock
  private de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipQueryService
      orgUnitMembershipQueryService;

  @Mock private AuditService auditService;

  @InjectMocks private MissionParticipantService missionParticipantService;
  @InjectMocks private MissionService missionService;

  @BeforeEach
  void wireExtractedParticipantService() {
    // MissionService delegates the participant methods to the extracted MissionParticipantService
    // (L1 step 2, #920). Wire a real instance (built from this class's mocks) into the CUT via
    // reflection, since Mockito does not inject one @InjectMocks target into another.
    ReflectionTestUtils.setField(
        missionService, "missionParticipantService", missionParticipantService);
  }

  // covers REQ-MISSION-003 — next-mission banner only considers PLANNED/ACTIVE missions
  // covers REQ-MISSION-008 — admin all-scope falls back to the organisation-wide next mission
  @Test
  void getNextMission_allowInternal_refetchesByIdThroughGraph() {
    // The limit-1 lookup is intentionally not graphed (a collection fetch + limit forces in-memory
    // pagination, HHH90003004); the service re-fetches the hit by id via the graphed findById so
    // the collections are eagerly loaded for the mapper.
    UUID id = UUID.randomUUID();
    Mission head = new Mission();
    head.setId(id);
    Mission detail = new Mission();
    detail.setId(id);
    // Admin all-scope → no org-unit narrowing → the unscoped, all-status finder.
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(missionRepository.findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc(
            any(), eq(List.of("PLANNED", "ACTIVE"))))
        .thenReturn(Optional.of(head));
    when(missionRepository.findById(id)).thenReturn(Optional.of(detail));

    Optional<Mission> result = missionService.getNextMission(true);

    assertSame(detail, result.orElseThrow(), "must return the graphed findById re-fetch");
    verify(missionRepository)
        .findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc(
            any(), eq(List.of("PLANNED", "ACTIVE")));
    verify(missionRepository).findById(id);
  }

  // covers REQ-MISSION-008 — an anonymous/membershipless caller keeps the unscoped public fallback
  @Test
  void getNextMission_guest_usesInternalFalseVariantThenRefetches() {
    UUID id = UUID.randomUUID();
    Mission head = new Mission();
    head.setId(id);
    Mission detail = new Mission();
    detail.setId(id);
    // No admin-all, no pin, no membership → unscoped fallback path; allowInternal=false → public.
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(missionRepository
            .findFirstByPlannedStartTimeAfterAndIsInternalFalseAndStatusInOrderByPlannedStartTimeAsc(
                any(), eq(List.of("PLANNED", "ACTIVE"))))
        .thenReturn(Optional.of(head));
    when(missionRepository.findById(id)).thenReturn(Optional.of(detail));

    Optional<Mission> result = missionService.getNextMission(false);

    assertSame(detail, result.orElseThrow());
    verify(missionRepository)
        .findFirstByPlannedStartTimeAfterAndIsInternalFalseAndStatusInOrderByPlannedStartTimeAsc(
            any(), eq(List.of("PLANNED", "ACTIVE")));
    verify(missionRepository).findById(id);
  }

  @Test
  void getNextMission_noUpcomingMission_returnsEmptyWithoutRefetch() {
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(missionRepository.findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc(
            any(), eq(List.of("PLANNED", "ACTIVE"))))
        .thenReturn(Optional.empty());

    Optional<Mission> result = missionService.getNextMission(true);

    assertEquals(Optional.empty(), result);
    verify(missionRepository, never()).findById(any());
  }

  // covers REQ-MISSION-008 — a member's banner is scoped to their own org units' next mission
  @Test
  void getNextMission_scopedMember_usesScopedQueryThenRefetches() {
    UUID orgA = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    Mission head = new Mission();
    head.setId(id);
    Mission detail = new Mission();
    detail.setId(id);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgA)));
    when(missionRepository.findNextScopedMission(
            any(), eq(List.of("PLANNED", "ACTIVE")), eq(true), isNull(), eq(Set.of(orgA)), any()))
        .thenReturn(List.of(head));
    when(missionRepository.findById(id)).thenReturn(Optional.of(detail));

    Optional<Mission> result = missionService.getNextMission(true);

    assertSame(detail, result.orElseThrow(), "scoped lookup must re-fetch the hit by id");
    verify(missionRepository)
        .findNextScopedMission(
            any(), eq(List.of("PLANNED", "ACTIVE")), eq(true), isNull(), eq(Set.of(orgA)), any());
    verify(missionRepository).findById(id);
    // The unscoped finders must NOT run for a scoped caller.
    verify(missionRepository, never())
        .findFirstByPlannedStartTimeAfterAndStatusInOrderByPlannedStartTimeAsc(any(), any());
  }

  // covers REQ-MISSION-008 — a pinned active org unit narrows the scoped lookup to that unit
  @Test
  void getNextMission_scopedPinned_passesActiveOrgUnitId() {
    UUID pin = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    Mission head = new Mission();
    head.setId(id);
    Mission detail = new Mission();
    detail.setId(id);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, pin, Set.of()));
    when(missionRepository.findNextScopedMission(
            any(), eq(List.of("PLANNED", "ACTIVE")), eq(true), eq(pin), eq(Set.of()), any()))
        .thenReturn(List.of(head));
    when(missionRepository.findById(id)).thenReturn(Optional.of(detail));

    Optional<Mission> result = missionService.getNextMission(true);

    assertSame(detail, result.orElseThrow());
    verify(missionRepository)
        .findNextScopedMission(
            any(), eq(List.of("PLANNED", "ACTIVE")), eq(true), eq(pin), eq(Set.of()), any());
  }

  // covers REQ-MISSION-008 — a scoped caller with no upcoming own-unit mission gets nothing
  @Test
  void getNextMission_scopedMember_noUpcoming_returnsEmptyWithoutRefetch() {
    UUID orgA = UUID.randomUUID();
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of(orgA)));
    when(missionRepository.findNextScopedMission(
            any(), eq(List.of("PLANNED", "ACTIVE")), eq(true), isNull(), eq(Set.of(orgA)), any()))
        .thenReturn(List.of());

    Optional<Mission> result = missionService.getNextMission(true);

    assertEquals(Optional.empty(), result);
    verify(missionRepository, never()).findById(any());
  }

  @Test
  void addParticipant_ShouldMatchGuestNameToExistingUserCaseInsensitive() {
    UUID missionId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    UUID userId = UUID.randomUUID();
    User existingUser = new User();
    existingUser.setId(userId);
    existingUser.setUsername("testuser");

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(userRepository.findByUsernameIgnoreCaseOrDisplayNameIgnoreCase("TestUser", "TestUser"))
        .thenReturn(Optional.of(existingUser));
    when(userRepository.findById(userId)).thenReturn(Optional.of(existingUser));
    // The resolved user has no memberships, so the participant gets no org-unit affiliation —
    // there is deliberately no IRIDIUM fallback anymore.
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    Mission result = missionService.addParticipant(missionId, null, "TestUser", null, "No comment");

    assertEquals(1, result.getParticipants().size());
    MissionParticipant participant = result.getParticipants().iterator().next();
    assertNotNull(participant.getUser(), "User should be mapped from guest name");
    assertEquals(userId, participant.getUser().getId());
    assertNull(participant.getGuestName(), "Guest name should be nullified since user was found");
    assertTrue(
        participant.getOrgUnits().isEmpty(),
        "a user with no membership must get no org-unit affiliation (no IRIDIUM fallback)");
  }

  // covers REQ-MISSION-002 — sign-up seeds the participant payout preference from the user default
  @Test
  void addParticipant_ShouldPreFillPayoutPreferenceFromUserDefault() {
    UUID missionId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("donator");
    user.setDefaultPayoutPreference(PayoutPreference.DONATE);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    Mission result = missionService.addParticipant(missionId, userId, null, null, "No comment");

    MissionParticipant participant = result.getParticipants().iterator().next();
    assertEquals(
        PayoutPreference.DONATE,
        participant.getPayoutPreference(),
        "participant payout preference must be seeded from the signing-up user's profile default");
  }

  // covers REQ-MISSION-002 — a user with no chosen default keeps the PAYOUT entity default
  @Test
  void addParticipant_ShouldKeepPayoutDefault_WhenUserHasNoDefault() {
    UUID missionId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("nopref");
    // defaultPayoutPreference deliberately left null — the user never opted in.

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    Mission result = missionService.addParticipant(missionId, userId, null, null, "No comment");

    MissionParticipant participant = result.getParticipants().iterator().next();
    assertEquals(
        PayoutPreference.PAYOUT,
        participant.getPayoutPreference(),
        "a user with no profile default keeps the PAYOUT entity default");
  }

  // The sign-up modal's explicit Auszahlungsart choice wins over the user's profile default.
  @Test
  void addParticipant_ExplicitPayoutChoice_WinsOverProfileDefault() {
    UUID missionId = UUID.randomUUID();
    Mission mission = new Mission();
    mission.setId(missionId);

    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("explicit-chooser");
    user.setDefaultPayoutPreference(PayoutPreference.DONATE);

    when(missionRepository.findById(missionId)).thenReturn(Optional.of(mission));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(orgUnitMembershipQueryService.findAllMembershipsForUser(userId)).thenReturn(List.of());

    Mission result =
        missionService.addParticipant(
            missionId, userId, null, null, "No comment", null, PayoutPreference.PAYOUT);

    MissionParticipant participant = result.getParticipants().iterator().next();
    assertEquals(
        PayoutPreference.PAYOUT,
        participant.getPayoutPreference(),
        "an explicit sign-up payout choice must override the profile default");
  }

  @Test
  void searchMissions_ShouldCallRepository() {
    String query = "Test";
    Instant start = Instant.now();
    Instant end = Instant.now().plus(1, ChronoUnit.DAYS);
    List<String> status =
        List.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELLED"); // Default expected when null passed

    // M-1: searchMissions now forces {@code isInternal=false} for anonymous callers. This
    // Mockito unit test runs with no SecurityContext (anonymous), so the service rewrites the
    // {@code null} input to {@code Boolean.FALSE} before delegating to the repository.
    Pageable pageable = PageRequest.of(0, 10);
    when(ownerScopeService.currentScopePredicate())
        .thenReturn(new ScopePredicate(false, null, Set.of()));
    when(missionRepository.searchMissions(
            query, start, end, status, Boolean.FALSE, null, false, null, Set.of(), false, pageable))
        .thenReturn(Page.empty());

    missionService.searchMissions(query, start, end, null, null, null, pageable);

    verify(missionRepository)
        .searchMissions(
            query, start, end, status, Boolean.FALSE, null, false, null, Set.of(), false, pageable);
  }

  @Test
  void updateMission_ShouldSetActualStartTime_WhenStatusChangesToActive() {
    UUID id = UUID.randomUUID();
    Mission existing = new Mission();
    existing.setId(id);
    existing.setStatus("PLANNED");
    existing.setActualStartTime(null);
    existing.setVersion(0L);

    UpdateMissionRequest details =
        new UpdateMissionRequest(
            "Test", null, null, "ACTIVE", null, null, null, null, null, null, null, 0L, null);

    when(missionRepository.findByIdForFullReplace(id)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission updated = missionService.updateMission(id, details);

    assertNotNull(updated.getActualStartTime());
  }

  @Test
  void updateMission_ShouldUseProvidedActualStartTime() {
    UUID id = UUID.randomUUID();
    Mission existing = new Mission();
    existing.setId(id);
    existing.setStatus("PLANNED");
    existing.setVersion(0L);

    Instant manualStart = Instant.now().minus(1, ChronoUnit.HOURS);
    UpdateMissionRequest details =
        new UpdateMissionRequest(
            "Test",
            null,
            null,
            "ACTIVE",
            null,
            null,
            null,
            manualStart,
            null,
            null,
            null,
            0L,
            null);

    when(missionRepository.findByIdForFullReplace(id)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission updated = missionService.updateMission(id, details);

    assertEquals(manualStart, updated.getActualStartTime());
  }

  @Test
  void updateMission_ShouldNotSetActualStartTime_WhenStatusIsActiveButAlreadyActive() {
    UUID id = UUID.randomUUID();
    Mission existing = new Mission();
    existing.setId(id);
    existing.setStatus("ACTIVE");
    existing.setActualStartTime(null);
    existing.setVersion(0L);

    UpdateMissionRequest details =
        new UpdateMissionRequest(
            "Test", null, null, "ACTIVE", null, null, null, null, null, null, null, 0L, null);

    when(missionRepository.findByIdForFullReplace(id)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission updated = missionService.updateMission(id, details);

    assertNull(updated.getActualStartTime());
  }

  @Test
  void updateMission_ShouldUpdateCalendarLink() {
    UUID id = UUID.randomUUID();
    Mission existing = new Mission();
    existing.setId(id);
    existing.setCalendarLink("old-link");
    existing.setVersion(0L);

    UpdateMissionRequest details =
        new UpdateMissionRequest(
            "Test",
            null,
            "new-link",
            "PLANNED",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            null);

    when(missionRepository.findByIdForFullReplace(id)).thenReturn(Optional.of(existing));
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission updated = missionService.updateMission(id, details);

    assertEquals("new-link", updated.getCalendarLink());
  }

  @Test
  void createMission_ShouldThrowException_WhenMeetingTimeAfterPlannedStart() {
    Instant plannedStart = Instant.now();
    CreateMissionRequest request =
        new CreateMissionRequest(
            "Test",
            null,
            null,
            null,
            plannedStart.plus(1, ChronoUnit.HOURS),
            plannedStart,
            null,
            false,
            null,
            null,
            null,
            null,
            null);

    assertThrows(IllegalArgumentException.class, () -> missionService.createMission(request));
  }

  @Test
  void createMission_ShouldThrowException_WhenPlannedStartAfterPlannedEnd() {
    Instant now = Instant.now();
    CreateMissionRequest request =
        new CreateMissionRequest(
            "Test",
            null,
            null,
            null,
            null,
            now.plus(2, ChronoUnit.HOURS),
            now.plus(1, ChronoUnit.HOURS),
            false,
            null,
            null,
            null,
            null,
            null);

    assertThrows(IllegalArgumentException.class, () -> missionService.createMission(request));
  }

  // ── Audit finding C-4: server-side stamping of owningSquadron ────────
  // Pins the create-mission stamping pipeline so a future refactor that re-introduces a
  // client-supplied owningSquadron path (e.g. by adding a `UUID owningSquadronId` field to
  // CreateMissionRequest and threading it into MissionService) breaks here. The ArchUnit rule
  // {@code missionWriteRequestDtosMustNotCarryServerManagedFields} blocks the DTO-shape side; the
  // tests below pin the service-side stamping behaviour itself.

  @Test
  void createMission_stampsOwningSquadronFromOwnerSquadron() {
    Squadron home = new Squadron();
    home.setId(UUID.randomUUID());
    User caller = new User();
    caller.setId(UUID.randomUUID());

    when(userService.getCurrentUser()).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, null)).thenReturn(home);
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission saved =
        missionService.createMission(
            new de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest(
                "Test", null, null, "PLANNED", null, null, null, false, null, null, null, null,
                null));

    assertEquals(home, saved.getOwningOrgUnit());
    assertEquals(caller, saved.getOwner());
  }

  @Test
  void createMission_membershiplessLeadershipOwner_stampsNullOwningOrgUnit() {
    // A "Bereichsleitung" user belongs to no Staffel/SK but may plan org-wide missions. The
    // nullable picker resolver returns null for such a membershipless owner (instead of 400ing), so
    // the mission persists ownerless — attributable through its owner and public unless internal.
    User caller = new User();
    caller.setId(UUID.randomUUID());

    when(userService.getCurrentUser()).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, null)).thenReturn(null);
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission saved =
        missionService.createMission(
            new de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest(
                "Bereichsleitung-Einsatz",
                null,
                null,
                "PLANNED",
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null));

    assertNull(saved.getOwningOrgUnit(), "membershipless leadership owner → ownerless mission");
    assertEquals(caller, saved.getOwner());
  }

  @Test
  void createMission_fallsBackToCurrentSquadronScopeWhenNoOwnerResolved() {
    Squadron scopeSquadron = new Squadron();
    scopeSquadron.setId(UUID.randomUUID());

    // R5.d.d branch flip: the fallback fires when getCurrentUser() returns empty, not when the
    // owner has no home Staffel. An authenticated owner without any membership now flows through
    // OwnerScopeService.resolveOrgUnitForPickerOutputNullable and resolves to a null (ownerless)
    // owner — see createMission_membershiplessLeadershipOwner_stampsNullOwningOrgUnit.
    when(userService.getCurrentUser()).thenReturn(Optional.empty());
    when(ownerScopeService.currentOrgUnit()).thenReturn(Optional.of(scopeSquadron));
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission saved =
        missionService.createMission(
            new de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest(
                "Test", null, null, "PLANNED", null, null, null, false, null, null, null, null,
                null));

    assertEquals(scopeSquadron, saved.getOwningOrgUnit());
  }

  @Test
  void createMission_honoursOwningOrgUnitIdFromTheRequestViaPickerResolver() {
    User caller = new User();
    caller.setId(UUID.randomUUID());
    Squadron picked = new Squadron();
    picked.setId(UUID.randomUUID());

    when(userService.getCurrentUser()).thenReturn(Optional.of(caller));
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(caller, picked.getId()))
        .thenReturn(picked);
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission saved =
        missionService.createMission(
            new de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest(
                "Test",
                null,
                null,
                "PLANNED",
                null,
                null,
                null,
                false,
                null,
                picked.getId(),
                null,
                null,
                null));

    assertEquals(picked, saved.getOwningOrgUnit(), "picker output must be honoured verbatim");
  }

  @Test
  void addSubMission_inheritsOwningSquadronFromParent_ignoringScopeAndCaller() {
    Squadron parentSquadron = new Squadron();
    parentSquadron.setId(UUID.randomUUID());
    UUID parentId = UUID.randomUUID();
    Mission parent = new Mission();
    parent.setId(parentId);
    parent.setOwningOrgUnit(parentSquadron);

    when(missionRepository.findById(parentId)).thenReturn(Optional.of(parent));
    when(missionRepository.save(any(Mission.class))).thenAnswer(i -> i.getArguments()[0]);

    Mission saved =
        missionService.addSubMission(
            parentId,
            new de.greluc.krt.profit.basetool.backend.model.dto.request.CreateMissionRequest(
                "Sub", null, null, "PLANNED", null, null, null, false, null, null, null, null,
                null));

    assertEquals(parentSquadron, saved.getOwningOrgUnit());
    assertEquals(parent, saved.getParent());
  }

  /**
   * The grouped count read is turned into a lookup, and an empty page never reaches the database.
   *
   * <p>REQ-MISSION-018. `IN ()` with no ids is a statement worth nothing, and Hibernate renders it
   * differently per dialect, so the empty case short-circuits rather than being sent.
   */
  @Test
  void registeredCounts_groupsPerMissionAndSkipsTheQueryForAnEmptyPage() {
    UUID crowded = UUID.randomUUID();
    UUID quiet = UUID.randomUUID();
    when(missionParticipantRepository.countByMissions(List.of(crowded, quiet)))
        .thenReturn(List.of(new MissionParticipantCount(crowded, 4L)));

    assertEquals(Map.of(crowded, 4L), missionService.registeredCounts(List.of(crowded, quiet)));

    assertEquals(Map.of(), missionService.registeredCounts(List.of()));
    verify(missionParticipantRepository, never()).countByMissions(List.of());
  }
}
