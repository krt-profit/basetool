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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
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

/**
 * Mockito unit tests for the org-tenancy scope-resolution behaviour of {@link RequestScopeResolver}
 * that its collaborators only ever stub out and never assert: the SQUADRON-scope <em>set</em> that
 * filters the admin user-list / search / typeahead / promotion Bewertungsmatrix ({@link
 * RequestScopeResolver#currentUserListScopeSquadronIds()}) and the whole-Bereich cascade membership
 * probe backing the bank {@code AREA_MEMBERS} view grant ({@link
 * RequestScopeResolver#currentUserIsMemberOfAreaCascade(UUID)}).
 *
 * <p>The resolver is instantiated directly with mocked collaborators — the same construction the
 * {@code OwnerScopeService} facade wires internally — so the request-scoped resolution is exercised
 * against the real production logic rather than through a stubbed facade method. The mocked {@code
 * HttpServletRequest} returns {@code null} for every attribute, so the per-request memoisation is a
 * no-op and each scenario re-runs the underlying reads deterministically.
 */
@ExtendWith(MockitoExtension.class)
class RequestScopeResolverScopeTest {

  @Mock private AuthHelperService authHelper;
  @Mock private SquadronRepository squadronRepository;
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

  @Nested
  class CurrentUserListScopeSquadronIdsTests {

    @Test
    void adminNoPin_returnsNull() {
      // Admin without an active pin: the cross-staffel unfiltered user list — null, never an empty
      // set (which would blank the whole picker).
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER)).thenReturn(null);

      assertNull(resolver.currentUserListScopeSquadronIds());
    }

    @Test
    void adminPin_returnsSingletonPin() {
      // Admin pinned to one Staffel narrows the list to exactly that Staffel.
      when(authHelper.isAdmin()).thenReturn(true);
      when(request.getHeader(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER))
          .thenReturn(SQUADRON_B_ID.toString());

      assertEquals(Set.of(SQUADRON_B_ID), resolver.currentUserListScopeSquadronIds());
    }

    @Test
    void nonAdminTwoStaffeln_noPin_returnsUnionOfBoth() {
      // REQ-ORG-017: a dual-Staffel officer without a pin sees the UNION of both Staffeln — a
      // regression to a single Staffel would hide half of their own members from the user list.
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
      // A pin that matches one of the caller's own Staffeln narrows the union to that singleton —
      // returning the full union for a pinned caller would leak the other Staffel's users.
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
      // A Staffel-less leader/guest collapses to the legacy "unfiltered" null (not an empty set),
      // so the full picker list stays visible.
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
      // A direct member of the Bereich (the Bereichsleitung) qualifies for the AREA_MEMBERS view.
      when(orgUnitRepository.findChildOrgUnitIds(BEREICH_ID)).thenReturn(List.of(CHILD_STAFFEL_ID));
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(CALLER_ID))
          .thenReturn(List.of(membership(CALLER_ID, BEREICH_ID, OrgUnitKind.BEREICH)));

      assertTrue(resolver.currentUserIsMemberOfAreaCascade(BEREICH_ID));
    }

    @Test
    void memberOfChildStaffel_true() {
      // A member of a child Staffel of the Bereich also qualifies — dropping the child lookup would
      // wrongly deny every Staffel/SK member the AREA_MEMBERS balance view and cascade limit.
      when(orgUnitRepository.findChildOrgUnitIds(BEREICH_ID)).thenReturn(List.of(CHILD_STAFFEL_ID));
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(CALLER_ID))
          .thenReturn(List.of(staffelMembership(CALLER_ID, CHILD_STAFFEL_ID)));

      assertTrue(resolver.currentUserIsMemberOfAreaCascade(BEREICH_ID));
    }

    @Test
    void memberOfUnrelatedUnit_false() {
      // A member of a Staffel that is neither the Bereich itself nor one of its children is denied
      // —
      // ignoring the bereichId scoping would leak a foreign Bereichskonto balance.
      when(orgUnitRepository.findChildOrgUnitIds(BEREICH_ID)).thenReturn(List.of(CHILD_STAFFEL_ID));
      when(authHelper.currentUserId()).thenReturn(Optional.of(CALLER_ID));
      when(orgUnitMembershipRepository.findAllByIdUserId(CALLER_ID))
          .thenReturn(List.of(staffelMembership(CALLER_ID, SQUADRON_A_ID)));

      assertFalse(resolver.currentUserIsMemberOfAreaCascade(BEREICH_ID));
    }
  }
}
