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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Who trades, governs, manages a raising unit's side or reads the report (REQ-PUR-003, REQ-PUR-021,
 * REQ-PUR-023).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingAccessPolicyTest {

  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID AREA = UUID.randomUUID();

  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelperService;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingDelegateRepository delegateRepository;

  private PurchasingAccessPolicy policy;

  @BeforeEach
  void setUp() {
    policy =
        new PurchasingAccessPolicy(
            ownerScopeService, authHelperService, settingsService, delegateRepository);
  }

  private PurchasingDeal dealOf(UUID delegate) {
    PurchasingDeal deal = new PurchasingDeal();
    deal.setId(UUID.randomUUID());
    deal.setDelegateUserId(delegate);
    deal.setRaisingOrgUnitId(UNIT);
    return deal;
  }

  private void noLeadership() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(ownerScopeService.currentUserIsOlMember()).thenReturn(false);
    when(settingsService.governingAreaId()).thenReturn(Optional.empty());
  }

  private void noBankRole() {
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE)))
        .thenReturn(false);
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(false);
  }

  private Authentication authenticationWith(GrantedAuthority... authorities) {
    return new UsernamePasswordAuthenticationToken("caller", "n/a", List.of(authorities));
  }

  @Test
  void anAdminGovernsWithoutAnyScopeLookup() {
    when(authHelperService.isAdmin()).thenReturn(true);

    assertThat(policy.isGoverningLeadership()).isTrue();

    verifyNoInteractions(ownerScopeService, settingsService);
  }

  @Test
  void anOrganisationsleitungMemberGoverns() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(ownerScopeService.currentUserIsOlMember()).thenReturn(true);

    assertThat(policy.isGoverningLeadership()).isTrue();
  }

  @Test
  void theLeaderOfTheGoverningBereichGovernsAndAnyoneElseDoesNot() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(ownerScopeService.currentUserIsOlMember()).thenReturn(false);
    when(settingsService.governingAreaId()).thenReturn(Optional.of(AREA));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(AREA, MembershipRole.BEREICHSLEITER))
        .thenReturn(true);

    assertThat(policy.isGoverningLeadership()).isTrue();
  }

  @Test
  void aCallerWhoLeadsNoGoverningBereichAndHasNoGoverningAreaDoesNotGovern() {
    noLeadership();

    assertThat(policy.isGoverningLeadership()).isFalse();
  }

  @Test
  void aCallerWhoLeadsOtherUnitsOnlyDoesNotGovern() {
    when(authHelperService.isAdmin()).thenReturn(false);
    when(ownerScopeService.currentUserIsOlMember()).thenReturn(false);
    when(settingsService.governingAreaId()).thenReturn(Optional.of(AREA));
    when(ownerScopeService.currentUserHoldsRoleOnOrgUnit(AREA, MembershipRole.BEREICHSLEITER))
        .thenReturn(false);

    assertThat(policy.isGoverningLeadership()).isFalse();
  }

  @Test
  void anAppointmentCountsOnlyWhileItIsNeitherRevokedNorExpired() {
    PurchasingDelegate active = new PurchasingDelegate();
    active.setValidUntil(Instant.now().plus(1, ChronoUnit.DAYS));
    PurchasingDelegate openEnded = new PurchasingDelegate();
    PurchasingDelegate expired = new PurchasingDelegate();
    expired.setValidUntil(Instant.now().minus(1, ChronoUnit.DAYS));
    PurchasingDelegate revoked = new PurchasingDelegate();
    revoked.setRevokedAt(Instant.now().minus(1, ChronoUnit.HOURS));
    UUID activeUser = UUID.randomUUID();
    UUID openEndedUser = UUID.randomUUID();
    UUID expiredUser = UUID.randomUUID();
    UUID revokedUser = UUID.randomUUID();
    UUID unknownUser = UUID.randomUUID();
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(activeUser))
        .thenReturn(Optional.of(active));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(openEndedUser))
        .thenReturn(Optional.of(openEnded));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(expiredUser))
        .thenReturn(Optional.of(expired));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(revokedUser))
        .thenReturn(Optional.of(revoked));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(unknownUser))
        .thenReturn(Optional.empty());

    assertThat(policy.isActiveDelegate(activeUser)).isTrue();
    assertThat(policy.isActiveDelegate(openEndedUser)).isTrue();
    assertThat(policy.isActiveDelegate(expiredUser)).isFalse();
    assertThat(policy.isActiveDelegate(revokedUser)).isFalse();
    assertThat(policy.isActiveDelegate(unknownUser)).isFalse();
  }

  @Test
  void theCallerIsNoActiveDelegateWhenNobodyIsSignedIn() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertThat(policy.isActiveDelegate()).isFalse();
    assertThat(policy.canCreateDeals()).isFalse();

    verifyNoInteractions(delegateRepository);
  }

  @Test
  void onlyAnActiveDelegateCreatesDeals() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(CALLER))
        .thenReturn(Optional.of(new PurchasingDelegate()));

    assertThat(policy.canCreateDeals()).isTrue();
  }

  @Test
  void theDelegateOfADealReadsItWithoutFurtherChecks() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));

    assertThat(policy.canReadDeal(dealOf(CALLER))).isTrue();

    verifyNoInteractions(ownerScopeService, settingsService);
  }

  @Test
  void theLeadOrLogisticianOfTheRaisingUnitReadsTheDeal() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    noLeadership();
    noBankRole();
    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "LOGISTICIAN")).thenReturn(true);

    assertThat(policy.canReadDeal(dealOf(UUID.randomUUID()))).isTrue();
  }

  @Test
  void bankStaffReadTheDealBehindAPurposeBoundRequest() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    noLeadership();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE))).thenReturn(true);

    assertThat(policy.canReadDeal(dealOf(UUID.randomUUID()))).isTrue();
  }

  @Test
  void aStrangerCannotReadTheDeal() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    noLeadership();
    noBankRole();
    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "LOGISTICIAN")).thenReturn(false);

    assertThat(policy.canReadDeal(dealOf(UUID.randomUUID()))).isFalse();
  }

  @Test
  void bankManagementCountsAsBankStaffAndOtherRolesDoNot() {
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE)))
        .thenReturn(false);
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT)))
        .thenReturn(true);
    assertThat(policy.isBankStaff()).isTrue();

    noBankRole();
    assertThat(policy.isBankStaff()).isFalse();
  }

  @Test
  void theDelegateOfTheDealCancelsAndTradesItEvenWhenNoLongerActive() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    PurchasingDeal deal = dealOf(CALLER);

    assertThat(policy.canTradeDeal(deal)).isTrue();
    assertThat(policy.canCancelDeal(deal)).isTrue();
    assertThat(policy.isDealDelegate(deal)).isTrue();

    verifyNoInteractions(delegateRepository);
  }

  @Test
  void theGoverningLeadershipCancelsADealButDoesNotTradeIt() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(authHelperService.isAdmin()).thenReturn(true);
    PurchasingDeal deal = dealOf(UUID.randomUUID());

    assertThat(policy.canCancelDeal(deal)).isTrue();
    assertThat(policy.canTradeDeal(deal)).isFalse();
  }

  @Test
  void anotherMemberNeitherCancelsNorTradesTheDeal() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    noLeadership();
    PurchasingDeal deal = dealOf(UUID.randomUUID());

    assertThat(policy.canCancelDeal(deal)).isFalse();
    assertThat(policy.canTradeDeal(deal)).isFalse();
  }

  @Test
  void aSignedOutCallerIsNeverTheDealDelegate() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    assertThat(policy.isDealDelegate(dealOf(CALLER))).isFalse();
  }

  @Test
  void deliveriesAreAcceptedByTheUnitManagerOfTheRaisingUnitOnly() {
    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "LOGISTICIAN")).thenReturn(true);
    assertThat(policy.canAcceptDeliveries(dealOf(UUID.randomUUID()))).isTrue();

    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "LOGISTICIAN")).thenReturn(false);
    assertThat(policy.canAcceptDeliveries(dealOf(UUID.randomUUID()))).isFalse();
  }

  @Test
  void theGoverningLeadershipPausesAnyScope() {
    when(authHelperService.isAdmin()).thenReturn(true);

    assertThat(policy.canPause(null)).isTrue();
    assertThat(policy.canPause(UNIT)).isTrue();
  }

  @Test
  void theLeadOfAUnitPausesOnlyThatUnitAndNotTheWholeOrganisation() {
    noLeadership();
    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "OFFICER")).thenReturn(false);
    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "LEAD")).thenReturn(true);

    assertThat(policy.canPause(UNIT)).isTrue();
    assertThat(policy.canPause(null)).isFalse();
  }

  @Test
  void aMemberWhoLeadsNothingPausesNothing() {
    noLeadership();
    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "OFFICER")).thenReturn(false);
    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "LEAD")).thenReturn(false);

    assertThat(policy.canPause(UNIT)).isFalse();
  }

  @Test
  void aStaffelleiterLeadsHisUnit() {
    when(ownerScopeService.hasRoleInOrgUnit(UNIT, "OFFICER")).thenReturn(true);

    assertThat(policy.leadsUnit(UNIT)).isTrue();
  }

  @Test
  void managedUnitsAreTheLogisticianGrantsOfTheCallerOnly() {
    UUID other = UUID.randomUUID();
    when(authHelperService.currentAuthentication())
        .thenReturn(
            Optional.of(
                authenticationWith(
                    new OrgUnitContextualAuthority("LOGISTICIAN", UNIT),
                    new OrgUnitContextualAuthority("OFFICER", other),
                    new SimpleGrantedAuthority("ROLE_LOGISTICIAN"))));

    assertThat(policy.managedUnitIds()).containsExactly(UNIT);
  }

  @Test
  void noAuthenticationManagesNoUnit() {
    when(authHelperService.currentAuthentication()).thenReturn(Optional.empty());

    assertThat(policy.managedUnitIds()).isEmpty();
  }

  @Test
  void anActiveDelegateReadsTheReportAndThePartnerNotes() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(CALLER))
        .thenReturn(Optional.of(new PurchasingDelegate()));

    assertThat(policy.canReadReport()).isTrue();
    assertThat(policy.canReadPartnerNotes()).isTrue();
    assertThat(policy.canMaintainPartners()).isTrue();
  }

  @Test
  void aLogisticianOfAnyUnitReadsTheReportButNotThePartnerNotes() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(CALLER))
        .thenReturn(Optional.empty());
    noLeadership();
    noBankRole();
    when(authHelperService.currentAuthentication())
        .thenReturn(
            Optional.of(authenticationWith(new OrgUnitContextualAuthority("LOGISTICIAN", UNIT))));

    assertThat(policy.canReadReport()).isTrue();
    assertThat(policy.canReadPartnerNotes()).isFalse();
  }

  @Test
  void aMemberWithoutAnyPurchasingRoleReadsNoReport() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(CALLER))
        .thenReturn(Optional.empty());
    noLeadership();
    noBankRole();
    when(authHelperService.currentAuthentication()).thenReturn(Optional.of(authenticationWith()));

    assertThat(policy.canReadReport()).isFalse();
  }

  @Test
  void bankStaffReadTheReport() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(CALLER))
        .thenReturn(Optional.empty());
    noLeadership();
    when(authHelperService.hasReachableRole(Roles.authority(Roles.BANK_EMPLOYEE))).thenReturn(true);

    assertThat(policy.canReadReport()).isTrue();
  }

  @Test
  void onlyTheGoverningLeadershipAppointsDelegates() {
    when(authHelperService.isAdmin()).thenReturn(true);

    assertThat(policy.canAppointDelegates()).isTrue();
  }
}
