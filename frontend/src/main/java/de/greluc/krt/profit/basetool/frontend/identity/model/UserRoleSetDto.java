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

package de.greluc.krt.profit.basetool.frontend.identity.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A user's assignable roles as {@code /api/v1/users/{id}/roles} returns them for the caller
 * (REQ-SEC-088).
 *
 * @param userId the user
 * @param roles the held roles among those the caller may assign
 * @param assignable the roles the caller may assign and withdraw
 * @param version the role-set counter a change echoes
 */
@DtoMirror
public record UserRoleSetDto(
    @NotNull UUID userId,
    @NotNull List<AssignableRole> roles,
    @NotNull List<AssignableRole> assignable,
    long version) {

  /**
   * Whether the user holds a role.
   *
   * @param role the role
   * @return {@code true} when {@link #roles} contains it
   */
  public boolean holds(@NotNull AssignableRole role) {
    return roles.contains(role);
  }
}
