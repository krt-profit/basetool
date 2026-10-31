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

package de.greluc.krt.profit.basetool.backend.orgunit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserDto;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit tests for the caller-dependent projection of {@link UserMapper#toDto}. */
class UserMapperRedactionTest {

  private final UUID callerId = UUID.randomUUID();
  private AuthHelperService authHelper;
  private UserMapper mapper;
  private User other;

  @BeforeEach
  void setUp() {
    mapper = Mappers.getMapper(UserMapper.class);
    authHelper = mock(AuthHelperService.class);
    ReflectionTestUtils.setField(
        mapper,
        "callerUserProjection",
        new de.greluc.krt.profit.basetool.backend.scope.api.AdminOrSelfUserProjection(authHelper));
    ReflectionTestUtils.setField(
        mapper, "membershipRepository", mock(OrgUnitMembershipRepository.class));
    ReflectionTestUtils.setField(
        mapper,
        "staffelMembershipResolver",
        new StaffelMembershipResolver(
            mock(SquadronRepository.class), mock(OrgUnitRepository.class)));
    lenient().when(authHelper.currentUserId()).thenReturn(Optional.of(callerId));
    other = new User();
    other.setId(UUID.randomUUID());
    other.setUsername("bob");
    other.setDisplayName("Bob");
    other.setDescription("about me");
    Role role = new Role();
    role.setName("ROLE_X");
    role.setPermissions(Set.of("P"));
    other.setRoles(Set.of(role));
  }

  @Test
  void nonAdminSeesAnotherMemberAsPeer() {
    UserDto dto = mapper.toDto(other);

    assertThat(dto.roles()).isNull();
    assertThat(dto.permissions()).isNull();
    assertThat(dto.username()).isNull();
    assertThat(dto.description()).isEqualTo("about me");
    assertThat(dto.effectiveName()).isNotNull();
  }

  @Test
  void nonAdminSeesTheUsernameOfAMemberWithoutDisplayName() {
    other.setDisplayName(null);

    assertThat(mapper.toDto(other).username()).isEqualTo("bob");
  }

  @Test
  void adminSeesTheFullRecord() {
    lenient().when(authHelper.isAdmin()).thenReturn(true);

    UserDto dto = mapper.toDto(other);

    assertThat(dto.roles()).containsExactly("ROLE_X");
    assertThat(dto.username()).isEqualTo("bob");
  }

  @Test
  void nonAdminSeesTheOwnRecordInFull() {
    other.setId(callerId);

    assertThat(mapper.toDto(other).roles()).containsExactly("ROLE_X");
  }

  @Test
  void withoutAProjectionTheMapperFailsClosedToThePeerShape() {
    ReflectionTestUtils.setField(mapper, "callerUserProjection", null);

    UserDto dto = mapper.toDto(other);

    assertThat(dto.roles()).isNull();
    assertThat(dto.permissions()).isNull();
  }
}
