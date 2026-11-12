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

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.notification.api.RoleRecipientDirectory;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The identity module's {@link RoleRecipientDirectory}, reading the role catalogue and the synced
 * {@code user_roles} mirror.
 */
@Service
@RequiredArgsConstructor
public class UserRoleRecipientDirectory implements RoleRecipientDirectory {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull RoleState lockRoleState(@NotNull Set<UUID> recipients) {
    if (recipients.isEmpty()) {
      return new RoleState(Set.of(), Set.of());
    }
    userRepository.lockRoleSetsForNotice(recipients);
    return new RoleState(
        userRepository.findRoleHoldersAmong(recipients, Roles.KRT_MEMBER),
        userRepository.findAnyRoleHoldersAmong(recipients, Roles.BANK_STAFF_ROLES));
  }

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
