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

import de.greluc.krt.profit.basetool.backend.identity.api.LoginRoleGrant;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a bootstrap subject of {@link BootstrapAdminProperties} the Admin role at login while no
 * other active admin exists, the emergency access of REQ-SEC-088.
 *
 * <p>Runs in the login's reconciliation transaction: {@link #grantIfDue} adds the role to the
 * loaded user, and {@link #recordGrant} bumps the role-set counter and audits the grant once the
 * row is persisted.
 */
@Component
@RequiredArgsConstructor
public class BootstrapAdminGrant implements LoginRoleGrant {

  private final BootstrapAdminProperties properties;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final RoleChangeRecorder recorder;

  /**
   * Adds the Admin role to a bootstrap subject who lacks it while no other active admin exists. The
   * Admin role row is locked first, as for a withdrawal of Admin.
   *
   * @param user the logging-in user, created or loaded, not necessarily persisted yet
   * @return {@code true} when the role was added and the caller must persist and then call {@link
   *     #recordGrant}
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean grantIfDue(@NotNull User user) {
    if (!properties.isBootstrapSubject(user.getId()) || holdsAdmin(user)) {
      return false;
    }
    Optional<Role> admin = roleRepository.lockByCode(AssignableRole.ADMIN.getCode());
    if (admin.isEmpty()
        || userRepository.countOtherActiveHolders(AssignableRole.ADMIN.getCode(), user.getId())
            > 0) {
      return false;
    }
    user.getRoles().add(admin.get());
    return true;
  }

  /**
   * Bumps the role-set counter of a persisted user {@link #grantIfDue} gave Admin, and records the
   * grant as {@code USER_ROLE_ASSIGNED} with {@code source=bootstrap}.
   *
   * @param user the persisted user
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordGrant(@NotNull User user) {
    userRepository.bumpRolesVersion(user.getId());
    user.setRolesVersion(user.getRolesVersion() + 1);
    recorder.bootstrapGranted(user);
  }

  private static boolean holdsAdmin(User user) {
    return user.getRoles().stream()
        .anyMatch(role -> AssignableRole.ADMIN.getCode().equals(role.getCode()));
  }
}
