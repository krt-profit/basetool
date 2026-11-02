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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.MembershipOperations;
import de.greluc.krt.profit.basetool.backend.identity.api.OfficerReachDirectory;
import de.greluc.krt.profit.basetool.backend.identity.api.RankOutsideRoleRangeException;
import de.greluc.krt.profit.basetool.backend.identity.api.RankRange;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserService;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.*;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceRankTest {

  @Mock private UserRepository userRepository;

  @Mock private RoleRepository roleRepository;

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private ShipRepository shipRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MembershipOperations membershipOperations;
  @Mock private OfficerReachDirectory officerReachDirectory;
  @Mock private AuditRecorder auditRecorder;

  @InjectMocks private UserService userService;

  @Test
  void updateUserAttributes_WithReach_RankAbove12_IsRefusedNamingTheLeadershipSeat() {
    UUID id = UUID.randomUUID();
    User user = userWith(id, 8, Roles.KRT_MEMBER);
    when(officerReachDirectory.holdsOfficerReach(id)).thenReturn(true);

    RankOutsideRoleRangeException thrown =
        assertThrows(
            RankOutsideRoleRangeException.class,
            () -> userService.updateUserAttributes(id, 13, null, null, 0L, null));

    assertEquals("RANK_OUTSIDE_ROLE_RANGE", thrown.code());
    assertEquals("LEADERSHIP_SEAT", thrown.getRole());
    assertEquals(12, thrown.getMaxRank());
    assertEquals("problem.rank_outside_role_range.leadership_seat.detail", thrown.getMessage());
    assertEquals(8, user.getRank());
    verify(userRepository, never()).save(any());
  }

  @Test
  void updateUserAttributes_WithReach_Rank5_Passes() {
    UUID id = UUID.randomUUID();
    userWith(id, null, Roles.KRT_MEMBER);
    when(officerReachDirectory.holdsOfficerReach(id)).thenReturn(true);

    userService.updateUserAttributes(id, 5, null, null, 0L, null);
  }

  @Test
  void updateUserAttributes_KrtMemberWithoutReach_Rank5_IsRefusedNamingKrtMember() {
    UUID id = UUID.randomUUID();
    userWith(id, null, Roles.KRT_MEMBER);

    RankOutsideRoleRangeException thrown =
        assertThrows(
            RankOutsideRoleRangeException.class,
            () -> userService.updateUserAttributes(id, 5, null, null, 0L, null));

    assertEquals(Roles.KRT_MEMBER, thrown.getRole());
    assertEquals(13, thrown.getMinRank());
    assertEquals("problem.rank_outside_role_range.krt_member.detail", thrown.getMessage());
  }

  @Test
  void updateUserAttributes_KrtMemberWithoutReach_Rank15_Passes() {
    UUID id = UUID.randomUUID();
    userWith(id, null, Roles.KRT_MEMBER);

    userService.updateUserAttributes(id, 15, null, null, 0L, null);
  }

  @Test
  void updateUserAttributes_Admin_AnyRankFrom1To20_Passes() {
    UUID id = UUID.randomUUID();
    userWith(id, null, Roles.ADMIN);

    userService.updateUserAttributes(id, 1, null, null, 0L, null);
    userService.updateUserAttributes(id, 20, null, null, 0L, null);
  }

  @Test
  void updateUserAttributes_AdminWithSeat_StaysOnTheAdminRange() {
    UUID id = UUID.randomUUID();
    userWith(id, null, Roles.ADMIN, Roles.KRT_MEMBER);

    userService.updateUserAttributes(id, 18, null, null, 0L, null);
  }

  @Test
  void updateUserAttributes_RankOutside1To20_IsRejectedForEveryRange() {
    UUID id = UUID.randomUUID();
    userWith(id, null, Roles.KRT_MEMBER);

    assertThrows(
        IllegalArgumentException.class,
        () -> userService.updateUserAttributes(id, 21, null, null, 0L, null));
    verify(userRepository, never()).save(any());
  }

  @Test
  void updateUserAttributes_AccountWithoutRoleOrSeat_IsNotRangeChecked() {
    UUID id = UUID.randomUUID();
    userWith(id, null);

    userService.updateUserAttributes(id, 3, null, null, 0L, null);
  }

  @Test
  void findRankRange_FollowsAdminThenSeatThenKrtMember() {
    UUID admin = UUID.randomUUID();
    userWith(admin, null, Roles.ADMIN);
    assertEquals(RankRange.Basis.ADMIN, userService.findRankRange(admin).orElseThrow().basis());

    UUID lead = UUID.randomUUID();
    userWith(lead, null, Roles.KRT_MEMBER);
    when(officerReachDirectory.holdsOfficerReach(lead)).thenReturn(true);
    assertEquals(RankRange.leadershipSeat(), userService.findRankRange(lead).orElseThrow());

    UUID member = UUID.randomUUID();
    userWith(member, null, Roles.KRT_MEMBER);
    assertEquals(RankRange.krtMember(), userService.findRankRange(member).orElseThrow());

    UUID nobody = UUID.randomUUID();
    userWith(nobody, null);
    assertTrue(userService.findRankRange(nobody).isEmpty());
  }

  private User userWith(UUID id, Integer rank, String... roleCodes) {
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    user.setRank(rank);
    user.setRoles(
        java.util.Arrays.stream(roleCodes).map(c -> role(c, c)).collect(Collectors.toSet()));
    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    return user;
  }

  private static Role role(String code, String name) {
    Role role = new Role();
    role.setCode(code);
    role.setName(name);
    return role;
  }
}
