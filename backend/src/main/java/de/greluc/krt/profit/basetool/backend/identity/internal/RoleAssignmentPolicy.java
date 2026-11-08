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

package de.greluc.krt.profit.basetool.backend.identity.internal;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Who may assign which role (REQ-SEC-088): an admin every role, the bank management Bank Employee
 * only, nobody else any.
 */
@Component
@RequiredArgsConstructor
public class RoleAssignmentPolicy {

  private final AuthHelperService authHelperService;

  /**
   * Whether the caller may assign and withdraw a role.
   *
   * @param role the role
   * @return {@code true} for an admin, and for the bank management on {@link
   *     AssignableRole#BANK_EMPLOYEE}
   */
  public boolean mayAssign(@NotNull AssignableRole role) {
    if (authHelperService.isAdmin()) {
      return true;
    }
    return role == AssignableRole.BANK_EMPLOYEE
        && authHelperService.hasReachableRole(Roles.authority(Roles.BANK_MANAGEMENT));
  }

  /**
   * The roles the caller may assign and withdraw.
   *
   * @return those roles in {@link AssignableRole} order, possibly empty
   */
  @NotNull
  @Unmodifiable
  public List<AssignableRole> assignableRoles() {
    return Arrays.stream(AssignableRole.values()).filter(this::mayAssign).toList();
  }

  /**
   * The roles the caller may assign and withdraw, refusing a caller who may assign none.
   *
   * @return those roles in {@link AssignableRole} order, never empty
   * @throws AccessDeniedException when the caller may assign no role
   */
  @NotNull
  @Unmodifiable
  public List<AssignableRole> requireAssignableRoles() {
    List<AssignableRole> assignable = assignableRoles();
    if (assignable.isEmpty()) {
      throw new AccessDeniedException("The caller may assign no role");
    }
    return assignable;
  }

  /**
   * Refuses a caller who may not assign a role.
   *
   * @param role the role
   * @throws AccessDeniedException when {@link #mayAssign} is {@code false}
   */
  public void requireMayAssign(@NotNull AssignableRole role) {
    if (!mayAssign(role)) {
      throw new AccessDeniedException("The caller may not assign the role " + role.getCode());
    }
  }
}
