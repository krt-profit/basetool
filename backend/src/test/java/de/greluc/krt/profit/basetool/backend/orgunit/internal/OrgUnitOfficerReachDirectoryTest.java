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

package de.greluc.krt.profit.basetool.backend.orgunit.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link OrgUnitOfficerReachDirectory}: reach is a seat held with KRT Member. */
@ExtendWith(MockitoExtension.class)
class OrgUnitOfficerReachDirectoryTest {

  private static final UUID USER_ID = UUID.randomUUID();

  @Mock private OrgUnitMembershipRepository membershipRepository;
  @Mock private UserRepository userRepository;

  @InjectMocks private OrgUnitOfficerReachDirectory directory;

  @Test
  void seatWithKrtMember_holdsReach() {
    when(membershipRepository.existsLeadershipSeat(USER_ID)).thenReturn(true);
    when(userRepository.hasAnyRoleCode(USER_ID, List.of(Roles.KRT_MEMBER))).thenReturn(true);

    assertTrue(directory.holdsOfficerReach(USER_ID));
  }

  @Test
  void seatWithoutKrtMember_holdsNoReach() {
    when(membershipRepository.existsLeadershipSeat(USER_ID)).thenReturn(true);
    when(userRepository.hasAnyRoleCode(USER_ID, List.of(Roles.KRT_MEMBER))).thenReturn(false);

    assertFalse(directory.holdsOfficerReach(USER_ID));
  }

  @Test
  void krtMemberWithoutSeat_holdsNoReachAndSkipsTheRoleLookup() {
    when(membershipRepository.existsLeadershipSeat(USER_ID)).thenReturn(false);

    assertFalse(directory.holdsOfficerReach(USER_ID));
    verifyNoInteractions(userRepository);
  }
}
