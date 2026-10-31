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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PromotionAccessPolicy#readScope(PromotionReadView)}: the management view
 * never reaches beyond the caller's officer reach (REQ-ROLE-007).
 */
@ExtendWith(MockitoExtension.class)
class PromotionAccessPolicyReadScopeTest {

  private static final UUID STAFFEL_A = UUID.randomUUID();
  private static final UUID STAFFEL_B = UUID.randomUUID();

  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuthHelperService authHelper;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  @InjectMocks private PromotionAccessPolicy policy;

  @Test
  void management_seatInA_memberOfB_readsAOnly() {
    when(ownerScopeService.currentOfficerReadScope()).thenReturn(Set.of(STAFFEL_A));

    assertThat(policy.readScope(PromotionReadView.MANAGEMENT))
        .contains(new PromotionReadScope(Set.of(STAFFEL_A)));
  }

  @Test
  void management_withoutReach_readsNothing() {
    when(ownerScopeService.currentOfficerReadScope()).thenReturn(Set.of());

    assertThat(policy.readScope(PromotionReadView.MANAGEMENT)).isEmpty();
  }

  @Test
  void management_unrestrictedAdmin_readsEverySquadron() {
    when(ownerScopeService.currentOfficerReadScope()).thenReturn(null);

    assertThat(policy.readScope(PromotionReadView.MANAGEMENT))
        .contains(new PromotionReadScope(null));
  }

  @Test
  void management_featureOffInTheActiveSquadron_readsNothing() {
    Squadron off = new Squadron();
    off.setPromotionEnabled(false);
    when(ownerScopeService.currentSquadron()).thenReturn(Optional.of(off));

    assertThat(policy.readScope(PromotionReadView.MANAGEMENT)).isEmpty();
  }

  @Test
  void member_readsTheActiveSquadronOnly() {
    when(authHelper.isAdmin()).thenReturn(false);
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.of(STAFFEL_B));

    assertThat(policy.readScope(PromotionReadView.MEMBER))
        .contains(new PromotionReadScope(Set.of(STAFFEL_B)));
  }

  @Test
  void member_withoutAnySquadron_readsNothing() {
    when(authHelper.isAdmin()).thenReturn(false);
    when(ownerScopeService.currentSquadronId()).thenReturn(Optional.empty());

    assertThat(policy.readScope(PromotionReadView.MEMBER)).isEmpty();
  }
}
