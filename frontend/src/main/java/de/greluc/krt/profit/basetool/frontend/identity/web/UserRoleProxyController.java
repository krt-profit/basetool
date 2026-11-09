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

package de.greluc.krt.profit.basetool.frontend.identity.web;

import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.model.AssignableRole;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserRoleChangeRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserRoleSetDto;
import jakarta.validation.Valid;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AJAX proxy of the role writes ({@code /api/proxy/users/{id}/roles/**}, REQ-SEC-088) for the
 * admin's member page and the bank grant management. Who may assign which role is decided by the
 * backend.
 */
@RestController
@RequestMapping("/api/proxy/users/{id}/roles/{role}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class UserRoleProxyController {

  /** Reads and writes the role sets on the backend. */
  private final IdentityBackendClient identityClient;

  /**
   * Forwards one role toggle guarded by the role-set counter the page shows.
   *
   * @param id the user
   * @param role the role
   * @param request whether to assign it and the role-set counter the caller last saw
   * @return the role set after the change, or {@code null} for a bodyless answer
   */
  @Nullable
  @PutMapping
  public UserRoleSetDto changeRole(
      @PathVariable @NotNull UUID id,
      @PathVariable @NotNull AssignableRole role,
      @RequestBody @Valid @NotNull UserRoleChangeRequest request) {
    return identityClient.changeUserRole(id, role, request);
  }

  /**
   * Assigns a role to a user picked from a search, who has no role set on the page yet: reads the
   * user's current counter and assigns against it, so a concurrent change still answers 409.
   *
   * @param id the user
   * @param role the role
   * @return the role set after the change, or {@code null} for a bodyless answer
   */
  @Nullable
  @PostMapping
  public UserRoleSetDto assignRole(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull AssignableRole role) {
    UserRoleSetDto current =
        Objects.requireNonNull(identityClient.userRoles(id), "role set read without a body");
    return identityClient.changeUserRole(
        id, role, new UserRoleChangeRequest(true, current.version()));
  }
}
