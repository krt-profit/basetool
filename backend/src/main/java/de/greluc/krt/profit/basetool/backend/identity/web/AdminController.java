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

package de.greluc.krt.profit.basetool.backend.identity.web;

import de.greluc.krt.profit.basetool.backend.identity.internal.RoleDto;
import de.greluc.krt.profit.basetool.backend.identity.internal.RoleMapper;
import de.greluc.krt.profit.basetool.backend.identity.internal.RoleService;
import de.greluc.krt.profit.basetool.backend.kernel.PageResponse;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.PaginationUtil;
import jakarta.validation.Valid;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
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
    return PageResponse.of(roleService.getAllRoles(pageable));
  }

  /**
   * Replaces the permission set of a role, effective on each user's next authentication. Audited as
   * {@code ROLE_PERMISSIONS_CHANGED} in the same transaction (REQ-AUDIT-001).
   *
   * @param name role name
   * @param request permission set and expected section version
   * @return the persisted role DTO
   */
  @PutMapping("/{name}/permissions")
  public RoleDto updatePermissions(
      @PathVariable @NotNull String name, @RequestBody @Valid @NotNull PermissionsRequest request) {
    return roleMapper.toDto(
        roleService.updatePermissions(name, request.permissions(), request.version()));
  }

  /**
   * Updates a role's descriptive text.
   *
   * @param name role name
   * @param request description text and expected section version
   * @return the persisted role DTO
   */
  @PutMapping("/{name}/description")
  public RoleDto updateRoleDescription(
      @PathVariable @NotNull String name, @RequestBody @Valid @NotNull DescriptionRequest request) {
    return roleMapper.toDto(
        roleService.updateRoleDescription(name, request.description(), request.version()));
  }

  /**
   * A permission replacement and its section version.
   *
   * @param permissions the replacement permission set
   * @param version the expected permission-section version
   */
  public record PermissionsRequest(
      @jakarta.validation.constraints.NotNull Set<String> permissions,
      @jakarta.validation.constraints.NotNull Long version) {}

  /**
   * A role description and its section version.
   *
   * @param description the replacement description
   * @param version the expected description-section version
   */
  public record DescriptionRequest(
      @jakarta.validation.constraints.NotNull String description,
      @jakarta.validation.constraints.NotNull Long version) {}
}
