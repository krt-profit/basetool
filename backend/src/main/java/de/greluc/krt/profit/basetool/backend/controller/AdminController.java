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

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.mapper.RoleMapper;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RoleDto;
import de.greluc.krt.profit.basetool.backend.service.RoleService;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import jakarta.validation.Valid;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the role administration: the role list, the permission set and the description
 * of a role. ADMIN-only at the class level.
 */
@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Transactional
public class AdminController {

  private final RoleService roleService;
  private final RoleMapper roleMapper;

  /**
   * Returns paged role list with whitelist-enforced sort.
   *
   * @return paged role list with whitelist-enforced sort
   */
  @GetMapping
  public PageResponse<RoleDto> getAllRoles(
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String sort) {
    Pageable pageable =
        PaginationUtil.createPageRequest(page, size, sort, Set.of("name", "id"), "name");
    Page<Role> p = roleService.getAllRoles(pageable);
    return PageResponse.of(p.map(roleMapper::toDto));
  }

  /**
   * Replaces the permission set of a role, effective on each user's next authentication. Audited as
   * {@code ROLE_PERMISSIONS_CHANGED} in the same transaction (REQ-AUDIT-001).
   *
   * @param name role name
   * @param permissions new permission set
   * @return the persisted role DTO
   */
  @PutMapping("/{name}/permissions")
  public RoleDto updatePermissions(
      @PathVariable @NotNull String name, @RequestBody @Valid @NotNull Set<String> permissions) {
    return roleMapper.toDto(roleService.updatePermissions(name, permissions));
  }

  /**
   * Updates a role's descriptive text.
   *
   * @param name role name
   * @param description new description text
   * @return the persisted role DTO
   */
  @PutMapping("/{name}/description")
  public RoleDto updateRoleDescription(
      @PathVariable @NotNull String name, @RequestBody @Valid @NotNull String description) {
    return roleMapper.toDto(roleService.updateRoleDescription(name, description));
  }
}
