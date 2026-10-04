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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link UserService#isCrossSquadronForNonAdmin(UUID)}. */
@ExtendWith(MockitoExtension.class)
class UserServiceCrossSquadronTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID STAFFEL_A = UUID.randomUUID();
  private static final UUID STAFFEL_B = UUID.randomUUID();

  @Mock private AuthHelperService authHelperService;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;

  @InjectMocks private UserService userService;

  @Test
  void anAdminIsNeverCrossSquadron() {
    when(authHelperService.isAdmin()).thenReturn(true);

    assertFalse(userService.isCrossSquadronForNonAdmin(USER_ID));
    verifyNoInteractions(orgUnitMembershipQueryService, ownerScopeService);
  }

  @Test
  void aUserWithoutAStaffelIsCrossSquadron() {
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(USER_ID))
        .thenReturn(List.of());

    assertTrue(userService.isCrossSquadronForNonAdmin(USER_ID));
    verifyNoInteractions(ownerScopeService);
  }

  @Test
  void aUserInForeignStaffelnOnlyIsCrossSquadron() {
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(USER_ID))
        .thenReturn(List.of(STAFFEL_A, STAFFEL_B));

    assertTrue(userService.isCrossSquadronForNonAdmin(USER_ID));
  }

  @Test
  void oneVisibleStaffelIsEnough() {
    when(orgUnitMembershipQueryService.findStaffelMembershipOrgUnitIds(USER_ID))
        .thenReturn(List.of(STAFFEL_A, STAFFEL_B));
    when(ownerScopeService.canSeeSquadron(STAFFEL_A)).thenReturn(false);
    when(ownerScopeService.canSeeSquadron(STAFFEL_B)).thenReturn(true);

    assertFalse(userService.isCrossSquadronForNonAdmin(USER_ID));
  }
}
