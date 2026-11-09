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

import de.greluc.krt.profit.basetool.backend.identity.api.LastAdminException;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.events.MemberDepartedEvent;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the writes of {@code user_roles} for the four assignable roles (REQ-SEC-088, ADR-0355).
 *
 * <p>Every change is guarded by the user's role-set counter, which the row {@code @Version} does
 * not share, so a role change never collides with an edit of the user record. Who may assign which
 * role is decided by {@link RoleAssignmentPolicy}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserRoleAssignmentService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final RoleAssignmentPolicy policy;
  private final RoleSetWriter writer;
  private final AuthHelperService authHelperService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Returns a user's role set as far as the caller may assign it.
   *
   * @param userId the user
   * @return the held roles the caller may assign, those roles and the role-set counter
   * @throws org.springframework.security.access.AccessDeniedException when the caller may assign no
   *     role
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException for an unknown user
   */
  @NotNull
  public UserRoleSetDto roleSet(@NotNull UUID userId) {
    List<AssignableRole> assignable = policy.requireAssignableRoles();
    User user = requireUser(userId);
    return toDto(user, assignable, user.getRolesVersion());
  }

  /**
   * Assigns or withdraws one role. A request that leaves the role set as it is writes nothing.
   *
   * <p>Withdrawing Admin from the last active admin is refused; withdrawing KRT Member publishes a
   * {@link MemberDepartedEvent}.
   *
   * @param userId the user
   * @param role the role
   * @param assigned {@code true} to assign, {@code false} to withdraw
   * @param expectedVersion the role-set counter the caller last saw
   * @return the role set after the change
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign the role
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException for an unknown user
   * @throws ObjectOptimisticLockingFailureException when the counter moved on (409)
   * @throws LastAdminException when the change would leave no active admin (409)
   */
  @Transactional
  @NotNull
  public UserRoleSetDto change(
      @NotNull UUID userId, @NotNull AssignableRole role, boolean assigned, long expectedVersion) {
    policy.requireMayAssign(role);
    User user = requireUser(userId);
    OptimisticLock.checkRequired(user.getRolesVersion(), expectedVersion, User.class, userId);
    if (RoleSetWriter.holds(user, role) == assigned) {
      return toDto(user, policy.assignableRoles(), expectedVersion);
    }
    if (!assigned && role == AssignableRole.ADMIN) {
      requireAnotherActiveAdmin(userId);
    }
    writer.write(
        user, role, assigned, expectedVersion, authHelperService.currentUserId().orElse(null));
    if (!assigned && role == AssignableRole.KRT_MEMBER) {
      eventPublisher.publishEvent(
          new MemberDepartedEvent(userId, MemberDepartedEvent.REASON_MEMBER_ROLE_WITHDRAWN));
    }
    return toDto(user, policy.assignableRoles(), user.getRolesVersion());
  }

  /**
   * Lists the holders of a role the caller may assign.
   *
   * @param role the role
   * @return the holders with their role-set counters, ordered by username
   * @throws org.springframework.security.access.AccessDeniedException when the caller may not
   *     assign the role
   */
  @NotNull
  public List<UserRoleHolderDto> holders(@NotNull AssignableRole role) {
    policy.requireMayAssign(role);
    return userRepository.findAllByRoleCode(role.getCode()).stream()
        .map(user ->
            new UserRoleHolderDto(user.getId(), user.getEffectiveName(), user.getRolesVersion()))
        .toList();
  }

  /**
   * Refuses the withdrawal of Admin when no other active admin would remain. The Admin role row is
   * locked first, so concurrent withdrawals are checked one after the other.
   *
   * @param userId the admin about to lose the role
   * @throws LastAdminException when no other active admin exists
   */
  private void requireAnotherActiveAdmin(@NotNull UUID userId) {
    roleRepository.lockByCode(AssignableRole.ADMIN.getCode());
    if (userRepository.countOtherActiveHolders(AssignableRole.ADMIN.getCode(), userId) == 0) {
      throw new LastAdminException();
    }
  }

  private User requireUser(UUID userId) {
    return Entities.require(userRepository.findById(userId), "User not found");
  }

  private static UserRoleSetDto toDto(
      User user, List<AssignableRole> assignable, long rolesVersion) {
    List<AssignableRole> held =
        assignable.stream().filter(role -> RoleSetWriter.holds(user, role)).toList();
    return new UserRoleSetDto(user.getId(), held, assignable, rolesVersion);
  }
}
