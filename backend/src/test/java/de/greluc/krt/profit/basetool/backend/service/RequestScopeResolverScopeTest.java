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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

/**
 * Unit tests for {@link RequestScopeResolver#currentUserListScopeSquadronIds()} and {@link
 * RequestScopeResolver#currentUserIsMemberOfAreaCascade(UUID)}, using a resolver built from mocks
 * with request memoisation disabled.
 */
@ExtendWith(MockitoExtension.class)
class RequestScopeResolverScopeTest {

  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private OrgUnitCascadeService orgUnitCascadeService;
  @Mock private StaffelMembershipResolver staffelMembershipResolver;
  @Mock private HttpServletRequest request;

  @InjectMocks private RequestScopeResolver resolver;

  private static final UUID CALLER_ID = UUID.randomUUID();
  private static final UUID SQUADRON_A_ID = UUID.randomUUID();
  private static final UUID SQUADRON_B_ID = UUID.randomUUID();

  /** Returns a Staffel membership row pointing the given user at the given org unit. */
  private static OrgUnitMembership staffelMembership(UUID userId, UUID orgUnitId) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, orgUnitId));
    m.setKind(OrgUnitKind.SQUADRON);
    return m;
  }

  /** Returns a membership row of the given kind pointing the given user at the given org unit. */
  private static OrgUnitMembership membership(UUID userId, UUID orgUnitId, OrgUnitKind kind) {
    OrgUnitMembership m = new OrgUnitMembership();
    m.setId(new OrgUnitMembershipId(userId, orgUnitId));
    m.setKind(kind);
    return m;
  }

  /**
   * REQ-SEC-052 / ADR-0159: a scope question asked by a caller with no identity has no honest
   * answer, so {@code currentScopePredicate()} refuses it instead of inventing one.
   */
  @Nested
  class UnauthenticatedCallerTests {

    @Test
    void currentScopePredicate_unauthenticatedCaller_throwsRatherThanScopingToNothing() {
      when(authHelper.isAuthenticated()).thenReturn(false);

      AuthenticationCredentialsNotFoundException thrown =
          assertThrows(
              AuthenticationCredentialsNotFoundException.class, resolver::currentScopePredicate);

      assertTrue(thrown.getMessage().contains("REQ-SEC-052"));
    }

    @Test
    void currentScopePredicate_authenticatedCaller_stillAnswers() {
      when(authHelper.isAuthenticated()).thenReturn(true);
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertTrue(resolver.currentScopePredicate().adminAllScope());
    }
  }

  @Nested
  class CurrentUserListScopeSquadronIdsTests {

    @Test
    void adminNoPin_returnsNull() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertNull(resolver.currentUserListScopeSquadronIds());
    }

    @Test
    void adminPin_returnsSingletonPin() {
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      assertEquals(Set.of(SQUADRON_B_ID), resolver.currentUserListScopeSquadronIds());
    }

    @Test
    void nonAdminTwoStaffeln_noPin_returnsUnionOfBoth() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(CALLER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(
              List.of(
                  staffelMembership(CALLER_ID, SQUADRON_A_ID),
                  staffelMembership(CALLER_ID, SQUADRON_B_ID)));
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertEquals(
          Set.of(SQUADRON_A_ID, SQUADRON_B_ID), resolver.currentUserListScopeSquadronIds());
    }

    @Test
    void nonAdminTwoStaffeln_matchingPin_returnsSingleton() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(CALLER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(
              List.of(
                  staffelMembership(CALLER_ID, SQUADRON_A_ID),
                  staffelMembership(CALLER_ID, SQUADRON_B_ID)));
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      assertEquals(Set.of(SQUADRON_B_ID), resolver.currentUserListScopeSquadronIds());
    }

    @Test
    void nonAdminNoStaffel_returnsNull() {
      when(authHelper.isAdmin()).thenReturn(false);
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserIdAndKind(CALLER_ID, OrgUnitKind.SQUADRON))
          .thenReturn(List.of());

      assertNull(resolver.currentUserListScopeSquadronIds());
    }
  }

  @Nested
  class CurrentUserIsMemberOfAreaCascadeTests {

    private static final UUID BEREICH_ID = UUID.randomUUID();
    private static final UUID CHILD_STAFFEL_ID = UUID.randomUUID();

    @Test
    void memberOfBereichItself_true() {
      when(orgUnitRepository.findChildOrgUnitIds(BEREICH_ID)).thenReturn(List.of(CHILD_STAFFEL_ID));
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(CALLER_ID))
          .thenReturn(List.of(membership(CALLER_ID, BEREICH_ID, OrgUnitKind.BEREICH)));

      assertTrue(resolver.currentUserIsMemberOfAreaCascade(BEREICH_ID));
    }

    @Test
    void memberOfChildStaffel_true() {
      when(orgUnitRepository.findChildOrgUnitIds(BEREICH_ID)).thenReturn(List.of(CHILD_STAFFEL_ID));
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(CALLER_ID))
          .thenReturn(List.of(staffelMembership(CALLER_ID, CHILD_STAFFEL_ID)));

      assertTrue(resolver.currentUserIsMemberOfAreaCascade(BEREICH_ID));
    }

    @Test
    void memberOfUnrelatedUnit_false() {
      when(orgUnitRepository.findChildOrgUnitIds(BEREICH_ID)).thenReturn(List.of(CHILD_STAFFEL_ID));
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(CALLER_ID))
          .thenReturn(List.of(staffelMembership(CALLER_ID, SQUADRON_A_ID)));

      assertFalse(resolver.currentUserIsMemberOfAreaCascade(BEREICH_ID));
    }
  }
}
