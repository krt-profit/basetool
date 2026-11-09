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

import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one write path of a persisted user's role set (REQ-SEC-088): changes the set, moves the
 * role-set counter, mirrors it on the entity and leaves the traces through {@link
 * RoleChangeRecorder}.
 */
@Component
@RequiredArgsConstructor
public class RoleSetWriter {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final RoleChangeRecorder recorder;

  /**
   * Whether a user's stored roles include a role.
   *
   * @param user the user
   * @param role the role
   * @return {@code true} when the user holds it
   */
  public static boolean holds(@NotNull User user, @NotNull AssignableRole role) {
    return user.getRoles().stream().anyMatch(held -> role.getCode().equals(held.getCode()));
  }

  /**
   * Assigns or withdraws one role of a persisted user whose counter is still {@code
   * expectedVersion}, and records the change.
   *
   * @param user the persisted user
   * @param role the role
   * @param assigned {@code true} to assign, {@code false} to withdraw
   * @param expectedVersion the role-set counter the change was decided on
   * @param actorId the member who changed it, or {@code null} when unknown
   * @throws ObjectOptimisticLockingFailureException when the counter moved on
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void write(
      @NotNull User user,
      @NotNull AssignableRole role,
      boolean assigned,
      long expectedVersion,
      @Nullable UUID actorId) {
    if (userRepository.bumpRolesVersionIfMatches(user.getId(), expectedVersion) == 0) {
      throw new ObjectOptimisticLockingFailureException(User.class, user.getId());
    }
    user.setRolesVersion(expectedVersion + 1);
    if (assigned) {
      user.getRoles()
          .add(Entities.require(roleRepository.findByCode(role.getCode()), "Role not found"));
    } else {
      user.getRoles().removeIf(held -> role.getCode().equals(held.getCode()));
    }
    recorder.changed(user, role, assigned, actorId);
  }

  /**
   * Moves the counter of a user whose role set was changed before the row was saved, which may have
   * created it, and mirrors it on the entity.
   *
   * @param user the user, now saved
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void bumpAfterSave(@NotNull User user) {
    userRepository.bumpRolesVersion(user.getId());
    user.setRolesVersion(user.getRolesVersion() + 1);
  }
}
