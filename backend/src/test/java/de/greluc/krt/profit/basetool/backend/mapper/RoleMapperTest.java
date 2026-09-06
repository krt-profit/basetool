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

package de.greluc.krt.profit.basetool.backend.mapper;

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.dto.RoleDto;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class RoleMapperTest {

  private final RoleMapper mapper = Mappers.getMapper(RoleMapper.class);

  @Test
  void toDto_shouldMapBasicFieldsAndPermissions() {
    // Given
    Role role = new Role();
    role.setId(7L);
    role.setName("ADMIN");
    role.setDescription("Full access");
    role.setPermissions(new HashSet<>(Set.of("USER_MANAGE", "ROLE_ASSIGN")));
    role.setVersion(2L);

    // When
    RoleDto dto = mapper.toDto(role);

    // Then
    assertNotNull(dto);
    assertEquals(7L, dto.id());
    assertEquals("ADMIN", dto.name());
    assertEquals("Full access", dto.description());
    assertEquals(Set.of("USER_MANAGE", "ROLE_ASSIGN"), dto.permissions());
    assertEquals(2L, dto.version());
  }

  @Test
  void toEntity_shouldMapBasicFieldsAndPermissions() {
    // Given
    RoleDto dto =
        new RoleDto(3L, "OFFICER", "Squadron officer", Set.of("MISSION_MANAGE", "USER_MANAGE"), 1L);

    // When
    Role role = mapper.toEntity(dto);

    // Then
    assertNotNull(role);
    assertEquals(3L, role.getId());
    assertEquals("OFFICER", role.getName());
    assertEquals("Squadron officer", role.getDescription());
    assertEquals(Set.of("MISSION_MANAGE", "USER_MANAGE"), role.getPermissions());
    assertEquals(1L, role.getVersion());
  }

  @Test
  void toDto_withEmptyPermissions_shouldProduceEmptySet() {
    // Given
    Role role = new Role();
    role.setId(1L);
    role.setName("KRT Member");
    role.setPermissions(new HashSet<>());

    // When
    RoleDto dto = mapper.toDto(role);

    // Then
    assertNotNull(dto.permissions());
    assertTrue(dto.permissions().isEmpty());
  }

  @Test
  void nullSafety_shouldReturnNull_whenSourceNull() {
    assertNull(mapper.toDto(null));
    assertNull(mapper.toEntity(null));
  }
}
