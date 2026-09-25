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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.mapper.RoleMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RoleDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.service.RoleService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pure-Mockito unit tests for {@link AdminController}, pinning the mapper call on each response
 * path so no JPA entity leaks through the REST boundary.
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

  @Mock private RoleService roleService;
  @Mock private UserService userService;
  @Mock private RoleMapper roleMapper;
  @Mock private UserMapper userMapper;

  @InjectMocks private AdminController controller;

  private static UserDto userDto(UUID id) {
    return new UserDto(
        id,
        "username",
        null,
        "Effective",
        null,
        1,
        null,
        java.util.Set.of(),
        java.util.Set.of(),
        null,
        false,
        false,
        true,
        null,
        java.util.List.of(),
        1L,
        null,
        false);
  }

  @Test
  void getAllRoles_pagesEntitiesThroughMapperIntoPageResponse() {
    Role roleA = new Role();
    Role roleB = new Role();
    RoleDto dtoA = new RoleDto(1L, "ADMIN", "Admin role", Set.of(), 1L);
    RoleDto dtoB = new RoleDto(2L, "OFFICER", "Officer role", Set.of(), 1L);
    Page<Role> page =
        new PageImpl<>(
            List.of(roleA, roleB), PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "name")), 2);
    when(roleService.getAllRoles(any(Pageable.class))).thenReturn(page);
    when(roleMapper.toDto(roleA)).thenReturn(dtoA);
    when(roleMapper.toDto(roleB)).thenReturn(dtoB);

    PageResponse<RoleDto> result = controller.getAllRoles(0, 20, "name,asc");

    assertThat(result.content()).containsExactly(dtoA, dtoB);
    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(20);
    assertThat(result.totalElements()).isEqualTo(2L);
    assertThat(result.totalPages()).isEqualTo(1);
    assertThat(result.sort()).containsExactly("name,asc");
    verify(roleService).getAllRoles(any(Pageable.class));
  }

  @Test
  void getAllRoles_withDefaults_returnsEmptyContentWhenNoRoles() {
    Page<Role> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
    when(roleService.getAllRoles(any(Pageable.class))).thenReturn(page);

    PageResponse<RoleDto> result = controller.getAllRoles(null, null, null);

    assertThat(result.content()).isEmpty();
    assertThat(result.totalElements()).isZero();
    verify(roleService).getAllRoles(any(Pageable.class));
  }

  @Test
  void updatePermissions_mapsServiceResultThroughRoleMapper() {
    Role updated = new Role();
    RoleDto dto = new RoleDto(2L, "OFFICER", "Officer role", Set.of("READ", "WRITE"), 1L);
    Set<String> newPerms = Set.of("READ", "WRITE");
    when(roleService.updatePermissions("OFFICER", newPerms)).thenReturn(updated);
    when(roleMapper.toDto(updated)).thenReturn(dto);

    RoleDto result = controller.updatePermissions("OFFICER", newPerms);

    assertThat(result).isSameAs(dto);
    verify(roleService).updatePermissions("OFFICER", newPerms);
    verify(roleMapper).toDto(updated);
  }

  @Test
  void updateRoleDescription_mapsServiceResultThroughRoleMapper() {
    Role updated = new Role();
    RoleDto dto = new RoleDto(1L, "ADMIN", "New text", Set.of(), 1L);
    when(roleService.updateRoleDescription("ADMIN", "New text")).thenReturn(updated);
    when(roleMapper.toDto(updated)).thenReturn(dto);

    RoleDto result = controller.updateRoleDescription("ADMIN", "New text");

    assertThat(result).isSameAs(dto);
    verify(roleService).updateRoleDescription("ADMIN", "New text");
  }

  @Test
  void updateUserAttributes_unpacksRequestRecordAndForwardsToService() {
    UUID userId = UUID.randomUUID();
    LocalDate joinDate = LocalDate.of(2024, 1, 15);
    AdminController.AdminUserAttributesRequest request =
        new AdminController.AdminUserAttributesRequest(
            12, "Test description", "Display name", 3L, joinDate);
    User updated = new User();
    UserDto dto = userDto(userId);
    when(userService.updateUserAttributes(
            userId, 12, "Test description", "Display name", 3L, joinDate))
        .thenReturn(updated);
    when(userMapper.toDto(updated)).thenReturn(dto);

    UserDto result = controller.updateUserAttributes(userId, request);

    assertThat(result).isSameAs(dto);
    verify(userService)
        .updateUserAttributes(userId, 12, "Test description", "Display name", 3L, joinDate);
    verify(userMapper).toDto(updated);
  }

  @Test
  void updateUserAttributes_passesNullableOptionalFieldsAsIs() {
    UUID userId = UUID.randomUUID();
    AdminController.AdminUserAttributesRequest request =
        new AdminController.AdminUserAttributesRequest(null, null, null, 1L, null);
    User updated = new User();
    UserDto dto = userDto(userId);
    when(userService.updateUserAttributes(userId, null, null, null, 1L, null)).thenReturn(updated);
    when(userMapper.toDto(updated)).thenReturn(dto);

    UserDto result = controller.updateUserAttributes(userId, request);

    assertThat(result).isSameAs(dto);
    verify(userService).updateUserAttributes(userId, null, null, null, 1L, null);
  }
}
