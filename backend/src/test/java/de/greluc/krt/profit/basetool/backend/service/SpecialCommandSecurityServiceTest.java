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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Mockito unit tests for {@link SpecialCommandSecurityService#canManageMembers(UUID,
 * Authentication)}. Pins the three accepted paths (admin always passes, anonymous always denied,
 * lead-of-this-SK passes for that SK only) plus the relevant negative cases (non-lead member, lead
 * of a different SK, no membership row).
 */
@ExtendWith(MockitoExtension.class)
class SpecialCommandSecurityServiceTest {

  @Mock private AuthHelperService authHelperService;
  @Mock private OrgUnitMembershipRepository membershipRepository;

  @InjectMocks private SpecialCommandSecurityService securityService;

  private static final UUID SC_ALPHA = UUID.randomUUID();
  private static final UUID SC_BRAVO = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  private Authentication authenticatedMember;

  @BeforeEach
  void setUp() {
    authenticatedMember =
        new UsernamePasswordAuthenticationToken(
            "member", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
  }

  @Test
  void anonymousAuth_returnsFalse() {
    Authentication anon =
        new AnonymousAuthenticationToken(
            "anonymous",
            "anonymousUser",
            java.util.List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))) {
          @Override
          public boolean isAuthenticated() {
            return false;
          }
        };

    assertFalse(securityService.canManageMembers(SC_ALPHA, anon));
  }

  @Test
  void nullAuth_returnsFalse() {
    assertFalse(securityService.canManageMembers(SC_ALPHA, null));
  }

  @Test
  void admin_alwaysPasses_evenWithoutMembership() {
    when(authHelperService.isAdmin()).thenReturn(true);

    assertTrue(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_leadOfThisSc_passes() {
    OrgUnitMembership lead = new OrgUnitMembership();
    lead.setRole(MembershipRole.SK_LEAD);
    lenient().when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(membershipRepository.findById(new OrgUnitMembershipId(USER_ID, SC_ALPHA)))
        .thenReturn(Optional.of(lead));

    assertTrue(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_memberOfThisScButNotLead_returnsFalse() {
    OrgUnitMembership notLead = new OrgUnitMembership();
    notLead.setRole(MembershipRole.MEMBER);
    lenient().when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(membershipRepository.findById(new OrgUnitMembershipId(USER_ID, SC_ALPHA)))
        .thenReturn(Optional.of(notLead));

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_leadOfDifferentSc_doesNotCarryOver() {
    lenient().when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(membershipRepository.findById(new OrgUnitMembershipId(USER_ID, SC_ALPHA)))
        .thenReturn(Optional.empty());

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_noMembershipAtAll_returnsFalse() {
    lenient().when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(membershipRepository.findById(new OrgUnitMembershipId(USER_ID, SC_ALPHA)))
        .thenReturn(Optional.empty());

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_noCurrentUserId_returnsFalse() {
    lenient().when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }
}
