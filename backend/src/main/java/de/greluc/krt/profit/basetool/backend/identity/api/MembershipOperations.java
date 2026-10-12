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

package de.greluc.krt.profit.basetool.backend.identity.api;

import de.greluc.krt.profit.basetool.backend.model.User;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The org-unit membership operations the identity module's user administration needs, owned by
 * identity and implemented by the org-unit module (plan §5.3).
 *
 * <p>Every method forwards one call verbatim: the implementation adds no transaction, no check and
 * no side effect, so the transaction, the audit rows and the exceptions are those of the org-unit
 * services behind it. The returned rows are the managed instances of the caller's transaction.
 *
 * <p>The row type is a parameter, because the membership entity still belongs to the org-unit
 * module; the identity module names it only where it holds the concrete type.
 *
 * @param <M> the membership row type
 */
public interface MembershipOperations<M> {

  /**
   * The user's Staffel ids, name-sorted, primary first.
   *
   * @param userId the user whose Staffel memberships to resolve
   * @return up to two Staffel ids; possibly empty
   */
  @NotNull
  List<UUID> findStaffelMembershipOrgUnitIds(@NotNull UUID userId);

  /**
   * Every membership row of the user, Staffel first, then Spezialkommandos by name.
   *
   * @param userId the user whose memberships to list
   * @return the membership rows; possibly empty
   */
  @NotNull
  List<M> findAllMembershipsForUser(@NotNull UUID userId);

  /**
   * Makes the user's Staffel memberships equal to the desired set, within the caller's transaction.
   *
   * @param user the user to reconcile
   * @param desired the target Staffel set; an empty list removes every Staffel membership
   * @throws de.greluc.krt.profit.basetool.backend.exception.BadRequestException on a duplicate, a
   *     third Staffel or a user holding a leadership role
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when a Staffel id is
   *     unknown
   */
  void reconcileStaffelMemberships(@NotNull User user, @NotNull List<StaffelAssignment> desired);

  /**
   * Adds the user to a Spezialkommando.
   *
   * @param specialCommandId the Spezialkommando
   * @param userId the user to add
   * @return the persisted membership row, managed in the caller's transaction
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the
   *     Spezialkommando or the user is unknown
   * @throws de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException when the user
   *     is already a member
   */
  @NotNull
  M addMember(@NotNull UUID specialCommandId, @NotNull UUID userId);

  /**
   * Removes the user from a Spezialkommando.
   *
   * @param specialCommandId the Spezialkommando
   * @param userId the user to remove
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the
   *     Spezialkommando is unknown or the user is not a member
   */
  void removeMember(@NotNull UUID specialCommandId, @NotNull UUID userId);

  /**
   * Updates the capability flags of a Spezialkommando membership, guarded by its row version.
   *
   * @param specialCommandId the Spezialkommando
   * @param userId the member
   * @param isLogistician the new Logistician flag, or {@code null} to leave it unchanged
   * @param isMissionManager the new Mission Manager flag, or {@code null} to leave it unchanged
   * @param version the row version the caller expects
   * @return the persisted membership row with the bumped version
   * @throws de.greluc.krt.profit.basetool.backend.exception.NotFoundException when the
   *     Spezialkommando is unknown or the user is not a member
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  @NotNull
  M patchFlags(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @Nullable Boolean isLogistician,
      @Nullable Boolean isMissionManager,
      @Nullable Long version);
}
