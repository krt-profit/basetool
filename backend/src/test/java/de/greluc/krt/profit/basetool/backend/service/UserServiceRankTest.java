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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @ParameterizedTest
  @ValueSource(ints = {1, 5, 12, 13, 20})
  void updateUserAttributes_KrtMember_TakesAnyRankFrom1To20(int rank) {
    UUID id = UUID.randomUUID();
    User user = userWith(id, 8, Roles.KRT_MEMBER);

    userService.updateUserAttributes(id, rank, null, null, 0L, null);

    assertEquals(rank, user.getRank());
  }

  @Test
  void updateUserAttributes_AccountWithoutRole_TakesAnyRankFrom1To20() {
    UUID id = UUID.randomUUID();
    User user = userWith(id, null);

    userService.updateUserAttributes(id, 3, null, null, 0L, null);

    assertEquals(3, user.getRank());
  }

  @Test
  void updateUserAttributes_Admin_TakesAnyRankFrom1To20() {
    UUID id = UUID.randomUUID();
    User user = userWith(id, null, Roles.ADMIN);

    userService.updateUserAttributes(id, 17, null, null, 0L, null);

    assertEquals(17, user.getRank());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 21})
  void updateUserAttributes_RankOutside1To20_IsRejectedForEveryAccount(int rank) {
    UUID id = UUID.randomUUID();
    User user = userWith(id, 8);

    assertThrows(
        IllegalArgumentException.class,
        () -> userService.updateUserAttributes(id, rank, null, null, 0L, null));
    assertEquals(8, user.getRank());
    verify(userRepository, never()).save(any());
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
