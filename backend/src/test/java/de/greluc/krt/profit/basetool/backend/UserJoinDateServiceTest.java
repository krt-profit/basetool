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

package de.greluc.krt.profit.basetool.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.OrgUnitMembershipService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserJoinDateServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private OrgUnitMembershipService orgUnitMembershipService;

  @InjectMocks private UserService userService;

  @Test
  void shouldSetJoinDate_WhenProvided() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setUsername("testuser");
    user.setRank(1);
    LocalDate joinDate = LocalDate.of(2024, 3, 15);

    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User result = userService.updateUserAttributes(id, 1, null, null, null, joinDate);

    assertThat(result.getJoinDate()).isEqualTo(joinDate);
  }

  @Test
  void shouldClearJoinDate_WhenNullProvided() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setUsername("testuser");
    user.setRank(1);
    user.setJoinDate(LocalDate.of(2022, 1, 1));

    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User result = userService.updateUserAttributes(id, 1, null, null, null, null);

    assertThat(result.getJoinDate()).isNull();
  }

  @Test
  void shouldUpdateJoinDate_WhenChanged() {
    UUID id = UUID.randomUUID();
    User user = new User();
    user.setId(id);
    user.setUsername("testuser");
    user.setRank(1);
    user.setJoinDate(LocalDate.of(2020, 6, 1));
    LocalDate newDate = LocalDate.of(2024, 12, 31);

    when(userRepository.findById(id)).thenReturn(Optional.of(user));
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User result = userService.updateUserAttributes(id, 1, null, null, null, newDate);

    assertThat(result.getJoinDate()).isEqualTo(newDate);
  }
}
