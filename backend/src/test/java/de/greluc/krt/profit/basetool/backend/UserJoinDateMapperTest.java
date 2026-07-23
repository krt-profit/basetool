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

import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapperImpl;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class UserJoinDateMapperTest {

  private UserMapper userMapper;

  @BeforeEach
  void setUp() {
    // Post-R9 D3 (V101): UserMapper derives squadron + flags from org_unit_membership — wire the
    // membership repository plus the StaffelMembershipResolver collaborator since this test does
    // not
    // load a Spring context.
    userMapper = new UserMapperImpl();
    ReflectionTestUtils.setField(
        userMapper, "membershipRepository", Mockito.mock(OrgUnitMembershipRepository.class));
    ReflectionTestUtils.setField(
        userMapper,
        "staffelMembershipResolver",
        new de.greluc.krt.profit.basetool.backend.support.StaffelMembershipResolver(
            Mockito.mock(SquadronRepository.class),
            Mockito.mock(
                de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository.class)));
  }

  @Test
  void shouldMapJoinDate_WhenSet() {
    // Given
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("mapper_test");
    user.setRank(1);
    LocalDate joinDate = LocalDate.of(2023, 7, 4);
    user.setJoinDate(joinDate);

    // When
    UserDto dto = userMapper.toDto(user);

    // Then
    assertThat(dto.joinDate()).isEqualTo(joinDate);
  }

  @Test
  void shouldMapJoinDate_AsNull_WhenNotSet() {
    // Given
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("mapper_test_null");
    user.setRank(1);
    user.setJoinDate(null);

    // When
    UserDto dto = userMapper.toDto(user);

    // Then
    assertThat(dto.joinDate()).isNull();
  }
}
