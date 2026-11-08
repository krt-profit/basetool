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

import de.greluc.krt.profit.basetool.backend.identity.internal.AssignableRole;
import de.greluc.krt.profit.basetool.backend.identity.internal.UserRoleAssignmentService;
import de.greluc.krt.profit.basetool.backend.identity.internal.UserRoleChangeRequest;
import de.greluc.krt.profit.basetool.backend.identity.internal.UserRoleHolderDto;
import de.greluc.krt.profit.basetool.backend.identity.internal.UserRoleSetDto;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the database-held roles (REQ-SEC-088): a user's role set, one role assigned or
 * withdrawn, and the holders of a role. Open to admins and the bank management; which role a caller
 * may touch is decided by the service.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('" + Roles.ADMIN + "', '" + Roles.BANK_MANAGEMENT + "')")
public class UserRoleController {

  private final UserRoleAssignmentService assignmentService;

  /**
   * Returns a user's role set as far as the caller may assign it.
   *
   * @param id the user
   * @return the held roles, the assignable roles and the role-set counter
   */
  @Operation(
      summary = "Read a user's role set",
      description =
          "The four assignable roles the user holds, limited to those the caller may assign, with"
              + " the role-set counter a change echoes.")
  @GetMapping("/{id}/roles")
  public UserRoleSetDto roleSet(@PathVariable @NotNull UUID id) {
    return assignmentService.roleSet(id);
  }

  /**
   * Assigns or withdraws one role of a user.
   *
   * @param id the user
   * @param role the role
   * @param request whether to assign it and the role-set counter the caller last saw
   * @return the role set after the change
   */
  @Operation(
      summary = "Assign or withdraw a role",
      description =
          "Admins change every role, the bank management Bank Employee only. Guarded by the"
              + " role-set counter, not by the user record's version.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Role set after the change"),
    @ApiResponse(responseCode = "403", description = "Caller may not assign this role"),
    @ApiResponse(
        responseCode = "409",
        description = "Stale role-set counter, or the last active admin would lose Admin")
  })
  @PutMapping("/{id}/roles/{role}")
  public UserRoleSetDto changeRole(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull AssignableRole role,
      @RequestBody @Valid @NotNull UserRoleChangeRequest request) {
    return assignmentService.change(id, role, request.assigned(), request.version());
  }

  /**
   * Lists the holders of a role the caller may assign.
   *
   * @param role the role
   * @return the holders with their role-set counters
   */
  @Operation(summary = "List the holders of a role")
  @GetMapping("/role-holders")
  public List<UserRoleHolderDto> holders(@RequestParam @NotNull AssignableRole role) {
    return assignmentService.holders(role);
  }
}
