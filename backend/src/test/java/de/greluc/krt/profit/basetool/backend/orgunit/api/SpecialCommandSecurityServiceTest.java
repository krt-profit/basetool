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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.List;
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
 * Mockito unit tests for {@link SpecialCommandSecurityService}: {@code canManageMembers} passes an
 * admin always, an anonymous caller never and a lead of this SK only for that SK, only with {@code
 * KRT Member} and only while the SK is active; {@code canManageMember} keeps a non-admin lead off
 * their own row and off a lead's row, while an admin writes every row (REQ-ORG-005, REQ-ROLE-004,
 * REQ-SEC-015, REQ-ORG-032).
 */
@ExtendWith(MockitoExtension.class)
class SpecialCommandSecurityServiceTest {

  private static final UUID SC_ALPHA = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID OTHER_ID = UUID.randomUUID();

  @Mock private AuthHelperService authHelperService;
  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private OrgUnitRepository orgUnitRepository;

  @InjectMocks private SpecialCommandSecurityService securityService;

  private Authentication authenticatedMember;

  @BeforeEach
  void setUp() {
    authenticatedMember =
        new UsernamePasswordAuthenticationToken(
            "member", null, List.of(new SimpleGrantedAuthority("ROLE_KRT_MEMBER")));
    lenient().when(orgUnitRepository.existsByIdAndActiveTrue(SC_ALPHA)).thenReturn(true);
  }

  /**
   * Stubs the caller as {@link #USER_ID}, a non-admin with or without {@code KRT Member}.
   *
   * @param krtMember whether the caller holds {@code KRT Member}
   */
  private void callerHoldsKrtMember(boolean krtMember) {
    lenient().when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(USER_ID));
    when(authHelperService.holdsKrtMember()).thenReturn(krtMember);
  }

  /**
   * Stubs the user's membership row on {@link #SC_ALPHA}.
   *
   * @param userId the member
   * @param role the rank of the row
   */
  private void rowOnAlpha(UUID userId, MembershipRole role) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setRole(role);
    when(membershipRepository.findById(new OrgUnitMembershipId(userId, SC_ALPHA)))
        .thenReturn(Optional.of(membership));
  }

  @Test
  void anonymousAuth_returnsFalse() {
    Authentication anon =
        new AnonymousAuthenticationToken(
            "anonymous", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))) {
          @Override
          public boolean isAuthenticated() {
            return false;
          }
        };

    assertFalse(securityService.canManageMembers(SC_ALPHA, anon));
    assertFalse(securityService.canManageMember(SC_ALPHA, OTHER_ID, anon));
  }

  @Test
  void nullAuth_returnsFalse() {
    assertFalse(securityService.canManageMembers(SC_ALPHA, null));
    assertFalse(securityService.canManageMember(SC_ALPHA, OTHER_ID, null));
  }

  @Test
  void admin_alwaysPasses_evenWithoutMembership() {
    when(authHelperService.isAdmin()).thenReturn(true);

    assertTrue(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_leadOfThisSc_passes() {
    callerHoldsKrtMember(true);
    rowOnAlpha(USER_ID, MembershipRole.SK_LEAD);

    assertTrue(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_leadOfADeactivatedSc_managesNothing() {
    callerHoldsKrtMember(true);
    rowOnAlpha(USER_ID, MembershipRole.SK_LEAD);
    when(orgUnitRepository.existsByIdAndActiveTrue(SC_ALPHA)).thenReturn(false);

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
    assertFalse(securityService.canManageMember(SC_ALPHA, OTHER_ID, authenticatedMember));
  }

  @Test
  void nonAdmin_leadOfThisScWithoutKrtMember_returnsFalse() {
    callerHoldsKrtMember(false);
    OrgUnitMembership lead = new OrgUnitMembership();
    lead.setRole(MembershipRole.SK_LEAD);
    lenient()
        .when(membershipRepository.findById(new OrgUnitMembershipId(USER_ID, SC_ALPHA)))
        .thenReturn(Optional.of(lead));

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
    assertFalse(securityService.canManageMember(SC_ALPHA, OTHER_ID, authenticatedMember));
  }

  @Test
  void nonAdmin_memberOfThisScButNotLead_returnsFalse() {
    callerHoldsKrtMember(true);
    rowOnAlpha(USER_ID, MembershipRole.MEMBER);

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_leadOfDifferentSc_doesNotCarryOver() {
    callerHoldsKrtMember(true);
    when(membershipRepository.findById(new OrgUnitMembershipId(USER_ID, SC_ALPHA)))
        .thenReturn(Optional.empty());

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
  }

  @Test
  void nonAdmin_noCurrentUserId_returnsFalse() {
    lenient().when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertFalse(securityService.canManageMembers(SC_ALPHA, authenticatedMember));
    assertFalse(securityService.canManageMember(SC_ALPHA, OTHER_ID, authenticatedMember));
  }

  @Test
  void canManageMember_leadOnAPlainMembersRow_passes() {
    callerHoldsKrtMember(true);
    rowOnAlpha(USER_ID, MembershipRole.SK_LEAD);
    rowOnAlpha(OTHER_ID, MembershipRole.MEMBER);

    assertTrue(securityService.canManageMember(SC_ALPHA, OTHER_ID, authenticatedMember));
  }

  @Test
  void canManageMember_leadOnTheirOwnRow_returnsFalse() {
    lenient().when(authHelperService.isAdmin()).thenReturn(false);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(USER_ID));
    lenient().when(authHelperService.holdsKrtMember()).thenReturn(true);
    OrgUnitMembership lead = new OrgUnitMembership();
    lead.setRole(MembershipRole.SK_LEAD);
    lenient()
        .when(membershipRepository.findById(new OrgUnitMembershipId(USER_ID, SC_ALPHA)))
        .thenReturn(Optional.of(lead));

    assertFalse(securityService.canManageMember(SC_ALPHA, USER_ID, authenticatedMember));
  }

  @Test
  void canManageMember_leadOnACoLeadsRow_returnsFalse() {
    callerHoldsKrtMember(true);
    rowOnAlpha(USER_ID, MembershipRole.SK_LEAD);
    rowOnAlpha(OTHER_ID, MembershipRole.SK_LEAD);

    assertFalse(securityService.canManageMember(SC_ALPHA, OTHER_ID, authenticatedMember));
  }

  @Test
  void canManageMember_leadOnAMissingRow_passesSoTheWriteAnswersNotFound() {
    callerHoldsKrtMember(true);
    rowOnAlpha(USER_ID, MembershipRole.SK_LEAD);
    when(membershipRepository.findById(new OrgUnitMembershipId(OTHER_ID, SC_ALPHA)))
        .thenReturn(Optional.empty());

    assertTrue(securityService.canManageMember(SC_ALPHA, OTHER_ID, authenticatedMember));
  }

  @Test
  void canManageMember_plainMember_returnsFalse() {
    callerHoldsKrtMember(true);
    rowOnAlpha(USER_ID, MembershipRole.MEMBER);

    assertFalse(securityService.canManageMember(SC_ALPHA, OTHER_ID, authenticatedMember));
  }

  @Test
  void canManageMember_admin_writesEveryRowTheirOwnAndALeadsIncluded() {
    when(authHelperService.isAdmin()).thenReturn(true);

    assertTrue(securityService.canManageMember(SC_ALPHA, OTHER_ID, authenticatedMember));
    assertTrue(securityService.canManageMember(SC_ALPHA, USER_ID, authenticatedMember));
  }
}
