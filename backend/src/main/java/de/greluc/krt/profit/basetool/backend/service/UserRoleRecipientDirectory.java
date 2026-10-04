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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.Role;
import de.greluc.krt.profit.basetool.backend.notification.api.RoleRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.repository.RoleRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The identity module's {@link RoleRecipientDirectory}, reading the role catalogue and the synced
 * {@code user_roles} mirror.
 */
@Service
@RequiredArgsConstructor
public class UserRoleRecipientDirectory implements RoleRecipientDirectory {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;

  /**
   * Every holder of a global role.
   *
   * @param roleCode the stable role code, for example {@code ADMIN}
   * @return the holders' user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> holdersOfRole(@NotNull String roleCode) {
    return userRepository.findUserIdsByRoleCode(roleCode);
  }

  /**
   * The holders of a global role who are members of one org unit.
   *
   * @param roleCode the stable role code
   * @param orgUnitId the org unit the holders must belong to
   * @return the matching user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> holdersOfRoleInOrgUnit(@NotNull String roleCode, @NotNull UUID orgUnitId) {
    return userRepository.findUserIdsByRoleCodeAndOrgUnitMembership(roleCode, orgUnitId);
  }

  /**
   * Looks a role code up in the role catalogue, ignoring case.
   *
   * @param roleCode the code as submitted
   * @return the catalogue's spelling of the code, or empty when no role carries it
   */
  @Override
  @NotNull
  public Optional<String> catalogueRoleCode(@NotNull String roleCode) {
    return roleRepository.findByCodeIgnoreCase(roleCode).map(Role::getCode);
  }
}
