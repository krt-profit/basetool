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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the officer-reach gates of {@link HangarAccessPolicy}. */
@ExtendWith(MockitoExtension.class)
class HangarAccessPolicyOfficerGatesTest {

  @Mock private OwnerScopeService ownerScopeService;
  @Mock private ShipRepository shipRepository;

  private HangarAccessPolicy policy() {
    return new HangarAccessPolicy(ownerScopeService, shipRepository);
  }

  @Test
  void ownerDetailsAndFittedReset_FollowTheOfficerReachOfTheActiveContext() {
    when(ownerScopeService.canActAsOfficerInActiveContext()).thenReturn(true);

    assertTrue(policy().canSeeOwnerDetails());
    assertTrue(policy().canResetFitted());
  }

  @Test
  void ownerDetailsAndFittedReset_AreRefusedWithoutOfficerReachInTheActiveContext() {
    when(ownerScopeService.canActAsOfficerInActiveContext()).thenReturn(false);

    assertFalse(policy().canSeeOwnerDetails());
    assertFalse(policy().canResetFitted());
  }
}
