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

package de.greluc.krt.profit.basetool.backend.promotion.internal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.AccessGateService;
import de.greluc.krt.profit.basetool.backend.scope.api.PromotionReach;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for the officer-reach checks of {@link PromotionAccessPolicy}: reach in the owning
 * squadron of the written data and over the evaluated member (REQ-SEC-015).
 */
@ExtendWith(MockitoExtension.class)
class PromotionAccessPolicyOfficerReachTest {

  private static final UUID SQUADRON = UUID.randomUUID();
  private static final UUID OTHER_SQUADRON = UUID.randomUUID();
  private static final UUID MEMBER = UUID.randomUUID();

  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AccessGateService accessGateService;
  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  @Mock private PromotionReach promotionReach;

  @InjectMocks private PromotionAccessPolicy policy;

  @Test
  void assertOfficerIn_passesWithReachInTheOwningSquadron() {
    when(accessGateService.canActAsOfficerIn(SQUADRON)).thenReturn(true);
    when(promotionReach.isEnabled(SQUADRON)).thenReturn(true);

    assertThatCode(() -> policy.assertOfficerIn(SQUADRON)).doesNotThrowAnyException();
  }

  @Test
  void assertOfficerIn_refusesWithoutReachInTheOwningSquadron() {
    when(accessGateService.canActAsOfficerIn(SQUADRON)).thenReturn(false);

    assertThatThrownBy(() -> policy.assertOfficerIn(SQUADRON))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void assertOfficerIn_refusesAnOfficerOfASquadronWithPromotionOff() {
    when(accessGateService.canActAsOfficerIn(SQUADRON)).thenReturn(true);
    when(promotionReach.isEnabled(SQUADRON)).thenReturn(false);

    assertThatThrownBy(() -> policy.assertOfficerIn(SQUADRON))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void assertOfficerIn_passesAnAdminForASquadronWithPromotionOff() {
    when(accessGateService.canActAsOfficerIn(SQUADRON)).thenReturn(true);
    when(authHelper.isAdmin()).thenReturn(true);

    assertThatCode(() -> policy.assertOfficerIn(SQUADRON)).doesNotThrowAnyException();
    verifyNoInteractions(promotionReach);
  }

  @Test
  void assertOfficerOverMember_passesForAnAdminWithoutLookingUpTheMember() {
    when(authHelper.isAdmin()).thenReturn(true);

    assertThatCode(() -> policy.assertOfficerOverMember(MEMBER)).doesNotThrowAnyException();
    verifyNoInteractions(orgUnitMembershipQueryService, requestScopeResolver, accessGateService);
  }

  @Test
  void assertOfficerOverMember_passesWithReachInOneOfTheMembersStaffeln() {
    when(authHelper.isAdmin()).thenReturn(false);
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(MEMBER))
        .thenReturn(List.of(OTHER_SQUADRON, SQUADRON));
    when(accessGateService.canActAsOfficerIn(OTHER_SQUADRON)).thenReturn(false);
    when(accessGateService.canActAsOfficerIn(SQUADRON)).thenReturn(true);
    when(promotionReach.isEnabled(SQUADRON)).thenReturn(true);

    assertThatCode(() -> policy.assertOfficerOverMember(MEMBER)).doesNotThrowAnyException();
  }

  @Test
  void assertOfficerOverMember_refusesWithoutReachInAnyOfTheMembersStaffeln() {
    when(authHelper.isAdmin()).thenReturn(false);
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(MEMBER))
        .thenReturn(List.of(OTHER_SQUADRON));
    when(accessGateService.canActAsOfficerIn(OTHER_SQUADRON)).thenReturn(false);

    assertThatThrownBy(() -> policy.assertOfficerOverMember(MEMBER))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void assertOfficerOverMember_refusesWhenTheOnlyReachedStaffelHasPromotionOff() {
    when(authHelper.isAdmin()).thenReturn(false);
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(MEMBER))
        .thenReturn(List.of(SQUADRON));
    when(accessGateService.canActAsOfficerIn(SQUADRON)).thenReturn(true);
    when(promotionReach.isEnabled(SQUADRON)).thenReturn(false);

    assertThatThrownBy(() -> policy.assertOfficerOverMember(MEMBER))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void assertOfficerOverMember_refusesAMemberWithoutAnyStaffel() {
    when(authHelper.isAdmin()).thenReturn(false);
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(MEMBER))
        .thenReturn(List.of());

    assertThatThrownBy(() -> policy.assertOfficerOverMember(MEMBER))
        .isInstanceOf(AccessDeniedException.class);
  }
}
