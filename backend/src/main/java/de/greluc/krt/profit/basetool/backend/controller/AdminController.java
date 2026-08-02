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

import de.greluc.krt.profit.basetool.backend.mapper.RoleMapper;
import de.greluc.krt.profit.basetool.backend.mapper.UserMapper;
import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.RoleDto;
import de.greluc.krt.profit.basetool.backend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.backend.service.RoleService;
import de.greluc.krt.profit.basetool.backend.service.UserService;
import de.greluc.krt.profit.basetool.backend.support.Roles;
import de.greluc.krt.profit.basetool.backend.web.PaginationUtil;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
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
 * REST surface for the global admin pages — role permissions and arbitrary-user attribute edits.
 * ADMIN-only at the class level.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize(Roles.HAS_ROLE_ADMIN)
@Transactional
public class AdminController {

  private final RoleService roleService;
  private final UserService userService;
  private final RoleMapper roleMapper;
  private final UserMapper userMapper;

  /**
   * Returns paged role list with whitelist-enforced sort.
   *
   * @return paged role list with whitelist-enforced sort
   */
  @GetMapping("/roles")
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
   * Replaces the permission set of a role. Permissions are re-read on every JWT authentication so
   * the change takes effect on the next user login without a server restart. Audited as {@code
   * ROLE_PERMISSIONS_CHANGED} in the "Rollen" area (REQ-AUDIT-001); the class-level transaction is
   * what lets the service write that audit row in the same transaction as the change itself.
   *
   * @param name role name
   * @param permissions new permission set
   * @return the persisted role DTO
   */
  @PutMapping("/roles/{name}/permissions")
  public RoleDto updatePermissions(
      @PathVariable @NotNull String name, @RequestBody @NotNull Set<String> permissions) {
    return roleMapper.toDto(roleService.updatePermissions(name, permissions));
  }

  /**
   * Updates a role's descriptive text.
   *
   * @param name role name
   * @param description new description text
   * @return the persisted role DTO
   */
  @PutMapping("/roles/{name}/description")
  public RoleDto updateRoleDescription(
      @PathVariable @NotNull String name, @RequestBody @NotNull String description) {
    return roleMapper.toDto(roleService.updateRoleDescription(name, description));
  }

  /**
   * Admin override of a user's editable attributes (rank, description, displayName, joinDate).
   * Carries an optimistic-lock version in the body so two admins racing on the same user surface a
   * 409 instead of silently overwriting.
   *
   * @param id user id
   * @param request typed body (note: NOT query params — keeping user values out of access logs)
   * @return the persisted user DTO
   */
  @PutMapping("/users/{id}/attributes")
  public UserDto updateUserAttributes(
      @PathVariable @NotNull UUID id,
      @RequestBody @Valid @NotNull AdminUserAttributesRequest request) {
    return userMapper.toDto(
        userService.updateUserAttributes(
            id,
            request.rank(),
            request.description(),
            request.displayName(),
            request.version(),
            request.joinDate()));
  }

  /**
   * Body for {@code PUT /api/v1/admin/users/{id}/attributes}. Moves the four user-controlled values
   * out of the query string (where they leak into access logs and browser history) into a typed,
   * validated request body.
   */
  public record AdminUserAttributesRequest(
      Integer rank,
      String description,
      String displayName,
      @jakarta.validation.constraints.NotNull Long version,
      LocalDate joinDate) {}
}
