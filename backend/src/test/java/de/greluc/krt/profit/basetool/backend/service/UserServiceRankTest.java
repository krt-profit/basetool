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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.hangar.api.ShipRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.MembershipOperations;
import de.greluc.krt.profit.basetool.backend.identity.api.RankOutsideRoleRangeException;
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
import java.util.Set;
import java.util.UUID;
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
  @Mock private AuditRecorder auditRecorder;

  @InjectMocks private UserService userService;

  @Test
  void updateUserAttributes_Officer_InvalidRank_ShouldThrow() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    Role officerRole = role(Roles.OFFICER, "Officer");
    user.setRoles(Set.of(officerRole));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    RankOutsideRoleRangeException thrown =
        assertThrows(
            RankOutsideRoleRangeException.class,
            () -> userService.updateUserAttributes(id, 13, null, null, 0L, null));
    assertEquals("RANK_OUTSIDE_ROLE_RANGE", thrown.code());
    assertEquals("OFFICER", thrown.getRole());
    assertEquals("problem.rank_outside_role_range.officer.detail", thrown.getMessage());
    verify(userRepository, never()).save(any());
  }

  @Test
  void updateUserAttributes_FormerLeadStillOfficer_Rank20_NamesTheOfficerRole() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    user.setRank(8);
    Role officerRole = role(Roles.OFFICER, "Officer");
    Role memberRole = role(Roles.KRT_MEMBER, "KRT Member");
    user.setRoles(Set.of(officerRole, memberRole));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    RankOutsideRoleRangeException thrown =
        assertThrows(
            RankOutsideRoleRangeException.class,
            () -> userService.updateUserAttributes(id, 20, null, null, 0L, null));
    assertEquals("OFFICER", thrown.getRole());
    assertEquals(12, thrown.getMaxRank());
    assertEquals(8, user.getRank());
  }

  @Test
  void updateUserAttributes_Officer_ValidRank_ShouldPass() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    Role officerRole = role(Roles.OFFICER, "Officer");
    user.setRoles(Set.of(officerRole));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    userService.updateUserAttributes(id, 5, null, null, 0L, null);
  }

  @Test
  void updateUserAttributes_SquadronMember_InvalidRank_ShouldThrow() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    Role memberRole = role(Roles.KRT_MEMBER, "KRT Member");
    user.setRoles(Set.of(memberRole));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    RankOutsideRoleRangeException thrown =
        assertThrows(
            RankOutsideRoleRangeException.class,
            () -> userService.updateUserAttributes(id, 5, null, null, 0L, null));
    assertEquals("KRT_MEMBER", thrown.getRole());
    assertEquals("problem.rank_outside_role_range.member.detail", thrown.getMessage());
  }

  @Test
  void updateUserAttributes_SquadronMember_ValidRank_ShouldPass() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    Role memberRole = role(Roles.KRT_MEMBER, "KRT Member");
    user.setRoles(Set.of(memberRole));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    userService.updateUserAttributes(id, 15, null, null, 0L, null);
  }

  @Test
  void updateUserAttributes_SeededMemberRole_Rank12_IsRefusedAndNotSaved() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    user.setRank(15);
    user.setRoles(Set.of(role(Roles.KRT_MEMBER, "KRT Member")));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    RankOutsideRoleRangeException thrown =
        assertThrows(
            RankOutsideRoleRangeException.class,
            () -> userService.updateUserAttributes(id, 12, null, null, 0L, null));
    assertEquals(Roles.KRT_MEMBER, thrown.getRole());
    assertEquals(15, user.getRank());
    verify(userRepository, never()).save(any());
  }

  @Test
  void updateUserAttributes_SeededMemberRole_Rank21_IsRejected() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    user.setRoles(Set.of(role(Roles.KRT_MEMBER, "KRT Member")));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    assertThrows(
        IllegalArgumentException.class,
        () -> userService.updateUserAttributes(id, 21, null, null, 0L, null));
    verify(userRepository, never()).save(any());
  }

  private static Role role(String code, String name) {
    Role role = new Role();
    role.setCode(code);
    role.setName(name);
    return role;
  }
}
