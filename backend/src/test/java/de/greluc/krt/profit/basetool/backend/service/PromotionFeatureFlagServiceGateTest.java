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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.PromotionTopicMapper;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.OperationRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PromotionTopicRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies the per-squadron promotion-feature gate end-to-end through {@link OwnerScopeService} +
 * the adjacent {@link PromotionTopicService} (the rest of the promotion services follow the same
 * pattern, so one representative is enough — every gated call site uses the same {@code
 * OwnerScopeService} primitive).
 *
 * <p>What's pinned here: an admin without an active pin keeps the menu open (so they can re-enable
 * a locked-out squadron); an admin pinned to a squadron honours that squadron's flag so the pinned
 * view matches what a member would see; Officers / members of a flag-off squadron get empty reads
 * and {@link AccessDeniedException} on writes; and the squadron-toggle service method flips only
 * the flag without touching any other column.
 */
@ExtendWith(MockitoExtension.class)
class PromotionFeatureFlagServiceGateTest {

  @Mock private AuthHelperService authHelper;
  @Mock private SquadronRepository squadronRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private HttpServletRequest request;
  @Mock private MissionRepository missionRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private OperationRepository operationRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private StaffelMembershipResolver staffelMembershipResolver;

  // R2.c: the real flag-resolution logic moved from OwnerScopeService to OwnerScopeService;
  // we inject the latter directly with its repository mocks. The downstream PromotionTopicService
  // tests further down still receive the OwnerScopeService shim as a plain Mockito mock — the
  // shim's bean shape is unchanged from the caller's perspective.
  @InjectMocks private OwnerScopeService ownerScopeService;

  private static Squadron squadron(UUID id, boolean enabled) {
    Squadron s = new Squadron();
    s.setId(id);
    s.setName("Test");
    s.setShorthand("TST");
    s.setPromotionEnabled(enabled);
    return s;
  }

  @BeforeEach
  void stubMembershipLookup() {
    lenient().when(authHelper.isAdmin()).thenReturn(false);
    // readPersistentSquadronFromUser delegates the name-sorted-primary fallback to
    // StaffelMembershipResolver; back the mock with a real instance so the single-Staffel
    // resolution
    // these tests rely on runs through the real resolver.
    StaffelMembershipResolver realResolver = new StaffelMembershipResolver(squadronRepository);
    lenient()
        .when(staffelMembershipResolver.resolveNameSortedStaffelIds(any()))
        .thenAnswer(
            invocation -> realResolver.resolveNameSortedStaffelIds(invocation.getArgument(0)));
    // The resolver's single-Staffel fast path now does a cheap existsById to drop a dangling row
    // (finding #4). Every Staffel in these scenarios exists, so resolve it to present.
    lenient().when(squadronRepository.existsById(any())).thenReturn(true);

    // #922 L3 split: the promotion-flag methods on the OwnerScopeService facade delegate to
    // RequestScopeResolver. Wire a real resolver (fed the same mocks) into the facade so the
    // direct-facade gate tests exercise the real flag resolution. The orgUnitRepository / cascade
    // deps are never reached on the promotion path, so plain mocks satisfy the constructor.
    RequestScopeResolver requestScopeResolver =
        new RequestScopeResolver(
            authHelper,
            squadronRepository,
            orgUnitMembershipRepository,
            mock(de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository.class),
            mock(OrgUnitCascadeService.class),
            staffelMembershipResolver,
            request);
    ReflectionTestUtils.setField(ownerScopeService, "requestScopeResolver", requestScopeResolver);
  }

  @Test
  @DisplayName("Admin without an active pin passes the gate (re-enable a locked-out squadron)")
  void adminWithoutPinPassesGate() {
    when(authHelper.isAdmin()).thenReturn(true);
    // No active pin → request.getHeader returns null → currentSquadronId().isEmpty() → defaults
    // to true so the toggle UI stays reachable.
    assertTrue(ownerScopeService.isPromotionFeatureEnabledForCurrentScope());
    ownerScopeService.assertPromotionFeatureEnabled();
  }

  @Test
  @DisplayName("Admin pinned to a squadron with the flag ON passes the gate")
  void adminWithPinOnEnabledSquadronPassesGate() {
    UUID pinnedId = UUID.randomUUID();
    when(authHelper.isAdmin()).thenReturn(true);
    when(request.getHeader("X-Active-Org-Unit-Id")).thenReturn(pinnedId.toString());
    when(squadronRepository.findById(pinnedId)).thenReturn(Optional.of(squadron(pinnedId, true)));

    assertTrue(ownerScopeService.isPromotionFeatureEnabledForCurrentScope());
    ownerScopeService.assertPromotionFeatureEnabled();
  }

  @Test
  @DisplayName(
      "Admin pinned to a squadron with the flag OFF respects the pin and fails the gate "
          + "(regression: previously bypassed unconditionally for admins)")
  void adminWithPinOnDisabledSquadronHonoursPin() {
    UUID pinnedId = UUID.randomUUID();
    when(authHelper.isAdmin()).thenReturn(true);
    when(request.getHeader("X-Active-Org-Unit-Id")).thenReturn(pinnedId.toString());
    when(squadronRepository.findById(pinnedId)).thenReturn(Optional.of(squadron(pinnedId, false)));

    assertFalse(ownerScopeService.isPromotionFeatureEnabledForCurrentScope());
    AccessDeniedException ex =
        assertThrows(
            AccessDeniedException.class, () -> ownerScopeService.assertPromotionFeatureEnabled());
    assertTrue(ex.getMessage().toLowerCase().contains("promotion"));
  }

  @Test
  @DisplayName("Non-admin with squadron flag ON passes the gate")
  void nonAdminWithFlagOnPassesGate() {
    UUID userId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    when(authHelper.currentUserId()).thenReturn(Optional.of(userId));
    // Post-R9 D3 (V101): home Staffel via org_unit_membership.
    when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(staffelMembership(userId, squadronId)));
    when(squadronRepository.findById(squadronId))
        .thenReturn(Optional.of(squadron(squadronId, true)));

    assertTrue(ownerScopeService.isPromotionFeatureEnabledForCurrentScope());
    ownerScopeService.assertPromotionFeatureEnabled();
  }

  @Test
  @DisplayName("Non-admin with squadron flag OFF fails the gate and assert throws 403")
  void nonAdminWithFlagOffFailsGate() {
    UUID userId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    when(authHelper.currentUserId()).thenReturn(Optional.of(userId));
    when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(staffelMembership(userId, squadronId)));
    when(squadronRepository.findById(squadronId))
        .thenReturn(Optional.of(squadron(squadronId, false)));

    assertFalse(ownerScopeService.isPromotionFeatureEnabledForCurrentScope());
    AccessDeniedException ex =
        assertThrows(
            AccessDeniedException.class, () -> ownerScopeService.assertPromotionFeatureEnabled());
    assertTrue(ex.getMessage().toLowerCase().contains("promotion"));
  }

  private static OrgUnitMembership staffelMembership(UUID userId, UUID squadronId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, squadronId));
    m.setKind(OrgUnitKind.SQUADRON);
    return m;
  }

  @Test
  @DisplayName("Non-admin without an effective squadron defaults to enabled")
  void nonAdminWithoutSquadronDefaultsToEnabled() {
    when(authHelper.currentUserId()).thenReturn(Optional.empty());
    assertTrue(ownerScopeService.isPromotionFeatureEnabledForCurrentScope());
  }

  @Test
  @DisplayName("hasPromotionReadAccess: an admin always has access (all-scopes or pinned)")
  void hasPromotionReadAccess_adminTrue() {
    when(authHelper.isAdmin()).thenReturn(true);
    assertTrue(ownerScopeService.hasPromotionReadAccess());
  }

  @Test
  @DisplayName("hasPromotionReadAccess: a non-admin with a home squadron has access")
  void hasPromotionReadAccess_nonAdminWithSquadronTrue() {
    UUID userId = UUID.randomUUID();
    UUID squadronId = UUID.randomUUID();
    when(authHelper.currentUserId()).thenReturn(Optional.of(userId));
    when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(userId, OrgUnitKind.SQUADRON))
        .thenReturn(List.of(staffelMembership(userId, squadronId)));
    assertTrue(ownerScopeService.hasPromotionReadAccess());
  }

  @Test
  @DisplayName(
      "hasPromotionReadAccess: a squadron-less non-admin has NO access — every promotion read"
          + " short-circuits to empty so they never see another squadron's system")
  void hasPromotionReadAccess_squadronlessNonAdminFalse() {
    when(authHelper.currentUserId()).thenReturn(Optional.empty());
    assertFalse(ownerScopeService.hasPromotionReadAccess());
  }

  @Test
  @DisplayName("PromotionTopicService.list short-circuits to empty page when flag is OFF")
  void topicList_returnsEmptyWhenDisabled() {
    PromotionTopicRepository topicRepository = mock(PromotionTopicRepository.class);
    PromotionTopicMapper mapper = mock(PromotionTopicMapper.class);
    OwnerScopeService scopeStub = mock(OwnerScopeService.class);
    PromotionTopicService service =
        new PromotionTopicService(topicRepository, mapper, scopeStub, mock(AuditService.class));
    when(scopeStub.isPromotionFeatureEnabledForCurrentScope()).thenReturn(false);
    Pageable pageable = PageRequest.of(0, 20);

    Page<?> result = service.list(pageable);

    assertEquals(0, result.getTotalElements());
    verify(topicRepository, never()).findAllScoped(any(), any(Pageable.class));
  }

  @Test
  @DisplayName("PromotionTopicService.listAll short-circuits to empty list when flag is OFF")
  void topicListAll_returnsEmptyWhenDisabled() {
    PromotionTopicRepository topicRepository = mock(PromotionTopicRepository.class);
    PromotionTopicMapper mapper = mock(PromotionTopicMapper.class);
    OwnerScopeService scopeStub = mock(OwnerScopeService.class);
    PromotionTopicService service =
        new PromotionTopicService(topicRepository, mapper, scopeStub, mock(AuditService.class));
    when(scopeStub.isPromotionFeatureEnabledForCurrentScope()).thenReturn(false);

    assertTrue(service.listAll().isEmpty());
    verify(topicRepository, never()).findAllScoped(any());
  }

  @Test
  @DisplayName("PromotionTopicService.create throws AccessDenied when flag is OFF")
  void topicCreate_throwsWhenDisabled() {
    PromotionTopicRepository topicRepository = mock(PromotionTopicRepository.class);
    PromotionTopicMapper mapper = mock(PromotionTopicMapper.class);
    OwnerScopeService scopeStub = mock(OwnerScopeService.class);
    PromotionTopicService service =
        new PromotionTopicService(topicRepository, mapper, scopeStub, mock(AuditService.class));
    doThrow(new AccessDeniedException("disabled")).when(scopeStub).assertPromotionFeatureEnabled();

    assertThrows(
        AccessDeniedException.class,
        () -> service.create(new PromotionTopicWriteRequest("Name", null, 0, null)));
    verify(topicRepository, never()).save(any());
  }

  @Test
  @DisplayName("PromotionTopicService.update throws AccessDenied when flag is OFF — no load/save")
  void topicUpdate_throwsWhenDisabled() {
    PromotionTopicRepository topicRepository = mock(PromotionTopicRepository.class);
    PromotionTopicMapper mapper = mock(PromotionTopicMapper.class);
    OwnerScopeService scopeStub = mock(OwnerScopeService.class);
    PromotionTopicService service =
        new PromotionTopicService(topicRepository, mapper, scopeStub, mock(AuditService.class));
    doThrow(new AccessDeniedException("disabled")).when(scopeStub).assertPromotionFeatureEnabled();

    assertThrows(
        AccessDeniedException.class,
        () ->
            service.update(
                UUID.randomUUID(), new PromotionTopicWriteRequest("Renamed", null, 0, 0L)));
    verify(topicRepository, never()).findById(any());
  }

  @Test
  @DisplayName("PromotionTopicService.delete throws AccessDenied when flag is OFF")
  void topicDelete_throwsWhenDisabled() {
    PromotionTopicRepository topicRepository = mock(PromotionTopicRepository.class);
    PromotionTopicMapper mapper = mock(PromotionTopicMapper.class);
    OwnerScopeService scopeStub = mock(OwnerScopeService.class);
    PromotionTopicService service =
        new PromotionTopicService(topicRepository, mapper, scopeStub, mock(AuditService.class));
    doThrow(new AccessDeniedException("disabled")).when(scopeStub).assertPromotionFeatureEnabled();

    assertThrows(AccessDeniedException.class, () -> service.delete(UUID.randomUUID()));
    verify(topicRepository, never()).findById(any());
  }

  @Test
  @DisplayName("SquadronService.setPromotionEnabled flips only the flag and persists")
  void squadronToggle_flipsOnlyFlag() {
    SquadronRepository repository = mock(SquadronRepository.class);
    SquadronService service = new SquadronService(repository);
    UUID id = UUID.randomUUID();
    Squadron entity = squadron(id, true);
    entity.setName("Original");
    entity.setShorthand("ORG");
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(repository.save(any(Squadron.class))).thenAnswer(inv -> inv.getArgument(0));

    Squadron updated = service.setPromotionEnabled(id, false);

    assertFalse(updated.isPromotionEnabled());
    assertEquals("Original", updated.getName(), "Toggle must not touch the name");
    assertEquals("ORG", updated.getShorthand(), "Toggle must not touch the shorthand");
  }

  @Test
  @DisplayName("SquadronService.setPromotionEnabled raises 404 for unknown squadron id")
  void squadronToggle_rejectsUnknownId() {
    SquadronRepository repository = mock(SquadronRepository.class);
    SquadronService service = new SquadronService(repository);
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.setPromotionEnabled(id, false));
  }

  @Test
  @DisplayName("SquadronService.setPromotionEnabled propagates optimistic-lock failures")
  void squadronToggle_propagatesOptimisticLockFailure() {
    SquadronRepository repository = mock(SquadronRepository.class);
    SquadronService service = new SquadronService(repository);
    UUID id = UUID.randomUUID();
    Squadron entity = squadron(id, true);
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(repository.save(any(Squadron.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(Squadron.class, id));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.setPromotionEnabled(id, false));
  }
}
