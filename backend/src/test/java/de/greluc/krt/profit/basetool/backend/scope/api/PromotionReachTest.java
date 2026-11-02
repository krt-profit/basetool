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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PromotionReach}: promotion reads, actions and navigation count only the
 * units with promotion enabled, a seat in a disabled unit counts for nothing, and the admin is not
 * narrowed (REQ-PROMO-004, REQ-SEC-015).
 */
@ExtendWith(MockitoExtension.class)
class PromotionReachTest {

  private static final UUID ENABLED = UUID.randomUUID();
  private static final UUID DISABLED = UUID.randomUUID();
  private static final UUID BEREICH = UUID.randomUUID();

  @Mock private RequestScopeResolver requestScopeResolver;
  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitRepository orgUnitRepository;

  @InjectMocks private PromotionReach reach;

  @BeforeEach
  void onlyTheEnabledSquadronHasPromotion() {
    lenient()
        .when(orgUnitRepository.findPromotionEnabledIdsAmong(anyCollection()))
        .thenAnswer(invocation -> {
          Collection<UUID> ids = invocation.getArgument(0);
          return ids.contains(ENABLED) ? List.of(ENABLED) : List.<UUID>of();
        });
  }

  @Test
  void managementScope_seatInAnEnabledAndADisabledUnit_keepsTheEnabledOne() {
    when(requestScopeResolver.currentOfficerReadScope()).thenReturn(Set.of(ENABLED, DISABLED));

    assertThat(reach.currentManagementScope()).containsExactly(ENABLED);
  }

  @Test
  void managementScope_seatOnlyInADisabledUnit_isEmpty() {
    when(requestScopeResolver.currentOfficerReadScope()).thenReturn(Set.of(DISABLED));

    assertThat(reach.currentManagementScope()).isEmpty();
  }

  @Test
  void managementScope_aBereichSeatReachesTheEnabledSquadronsBelowIt() {
    when(requestScopeResolver.currentOfficerReadScope()).thenReturn(Set.of(BEREICH, ENABLED));

    assertThat(reach.currentManagementScope()).containsExactly(ENABLED);
  }

  @Test
  void managementScope_withoutReach_doesNotQuery() {
    when(requestScopeResolver.currentOfficerReadScope()).thenReturn(Set.of());

    assertThat(reach.currentManagementScope()).isEmpty();
    verify(orgUnitRepository, never()).findPromotionEnabledIdsAmong(anyCollection());
  }

  @Test
  void managementScope_adminIsNotNarrowedAndKeepsAPinOnADisabledUnit() {
    when(authHelper.isAdmin()).thenReturn(true);
    when(requestScopeResolver.currentOfficerReadScope()).thenReturn(Set.of(DISABLED));

    assertThat(reach.currentManagementScope()).containsExactly(DISABLED);
  }

  @Test
  void managementScope_unrestrictedAdminScopeStaysUnrestricted() {
    when(requestScopeResolver.currentOfficerReadScope()).thenReturn(null);

    assertThat(reach.currentManagementScope()).isNull();
  }

  @Test
  void manageInActiveContext_pinnedToAnEnabledUnitInReach_isTrue() {
    Set<UUID> seats = Set.of(ENABLED, DISABLED);
    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(seats);
    when(requestScopeResolver.narrowToPin(seats)).thenReturn(Set.of(ENABLED));

    assertThat(reach.canManageInActiveContext()).isTrue();
  }

  @Test
  void manageInActiveContext_pinnedToADisabledUnitInReach_isFalse() {
    Set<UUID> seats = Set.of(ENABLED, DISABLED);
    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(seats);
    when(requestScopeResolver.narrowToPin(seats)).thenReturn(Set.of(DISABLED));

    assertThat(reach.canManageInActiveContext()).isFalse();
  }

  @Test
  void manageInActiveContext_pinOutOfReach_isFalse() {
    Set<UUID> seats = Set.of(ENABLED);
    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(seats);
    when(requestScopeResolver.narrowToPin(seats)).thenReturn(Set.of());

    assertThat(reach.canManageInActiveContext()).isFalse();
  }

  @Test
  void manageInActiveContext_adminIsTrueWithoutLookingAtTheSeats() {
    when(authHelper.isAdmin()).thenReturn(true);

    assertThat(reach.canManageInActiveContext()).isTrue();
    verify(requestScopeResolver, never()).currentOfficerOrgUnitIds();
  }

  @Test
  void manageAnywhere_followsTheEnabledUnitsOfTheSeatsWhateverIsPinned() {
    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(Set.of(DISABLED, ENABLED));
    assertThat(reach.canManageAnywhere()).isTrue();

    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(Set.of(DISABLED));
    assertThat(reach.canManageAnywhere()).isFalse();

    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(Set.of());
    assertThat(reach.canManageAnywhere()).isFalse();
  }

  @Test
  void manageAnywhere_adminIsTrue() {
    when(authHelper.isAdmin()).thenReturn(true);

    assertThat(reach.canManageAnywhere()).isTrue();
  }

  @Test
  void useAnywhere_countsMembershipAndSeatsInEnabledUnits() {
    when(requestScopeResolver.currentMemberOrgUnitIds()).thenReturn(Set.of(ENABLED));
    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(Set.of());
    assertThat(reach.canUseAnywhere()).isTrue();

    when(requestScopeResolver.currentMemberOrgUnitIds()).thenReturn(Set.of(DISABLED));
    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(Set.of(ENABLED));
    assertThat(reach.canUseAnywhere()).isTrue();
  }

  @Test
  void useAnywhere_onlyDisabledUnits_isFalse() {
    when(requestScopeResolver.currentMemberOrgUnitIds()).thenReturn(Set.of(DISABLED));
    when(requestScopeResolver.currentOfficerOrgUnitIds()).thenReturn(Set.of(DISABLED, BEREICH));

    assertThat(reach.canUseAnywhere()).isFalse();
  }

  @Test
  void useAnywhere_adminIsTrue() {
    when(authHelper.isAdmin()).thenReturn(true);

    assertThat(reach.canUseAnywhere()).isTrue();
  }

  @Test
  void isEnabled_followsTheUnitsFlag() {
    assertThat(reach.isEnabled(ENABLED)).isTrue();
    assertThat(reach.isEnabled(DISABLED)).isFalse();
  }
}
