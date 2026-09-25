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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
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
  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;

  @InjectMocks private UserService userService;

  @Test
  void updateUserAttributes_Officer_InvalidRank_ShouldThrow() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    Role officerRole = new Role();
    officerRole.setName("OFFICER");
    user.setRoles(Set.of(officerRole));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    assertThrows(
        IllegalArgumentException.class,
        () -> userService.updateUserAttributes(id, 13, null, null, 0L, null));
  }

  @Test
  void updateUserAttributes_Officer_ValidRank_ShouldPass() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    Role officerRole = new Role();
    officerRole.setName("OFFICER");
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
    Role memberRole = new Role();
    memberRole.setName("KRT_MEMBER");
    user.setRoles(Set.of(memberRole));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    assertThrows(
        IllegalArgumentException.class,
        () -> userService.updateUserAttributes(id, 5, null, null, 0L, null));
  }

  @Test
  void updateUserAttributes_SquadronMember_ValidRank_ShouldPass() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setVersion(0L);
    Role memberRole = new Role();
    memberRole.setName("KRT_MEMBER");
    user.setRoles(Set.of(memberRole));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));

    userService.updateUserAttributes(id, 15, null, null, 0L, null);
  }
}
