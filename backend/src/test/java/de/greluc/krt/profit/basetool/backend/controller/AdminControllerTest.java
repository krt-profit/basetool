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
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RoleDto;
import de.greluc.krt.profit.basetool.backend.service.RoleService;
import java.util.List;
import java.util.Set;
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
  @Mock private RoleMapper roleMapper;

  @InjectMocks private AdminController controller;

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
}
