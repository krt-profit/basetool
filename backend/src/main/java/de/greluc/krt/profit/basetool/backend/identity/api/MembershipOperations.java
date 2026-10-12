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
 */
public interface MembershipOperations {

  /**
   * The user's Staffel ids, name-sorted, primary first.
   *
   * @param userId the user whose Staffel memberships to resolve
   * @return up to two Staffel ids; possibly empty
   */
  @NotNull
  List<UUID> findStaffelMembershipOrgUnitIds(@NotNull UUID userId);

  /**
   * Makes the user's Staffel memberships equal to the desired set, within the caller's transaction.
   *
   * @param user the user to reconcile
   * @param desired the target Staffel set; an empty list removes every Staffel membership
   * @throws de.greluc.krt.profit.basetool.backend.kernel.BadRequestException on a duplicate, a
   *     third Staffel or a user holding a leadership role
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when a Staffel id is
   *     unknown
   */
  void reconcileStaffelMemberships(@NotNull User user, @NotNull List<StaffelAssignment> desired);

  /**
   * Adds the user to a Spezialkommando and sets the initial capability flags on the new row, in one
   * save and without a second audit row.
   *
   * @param specialCommandId the Spezialkommando
   * @param userId the user to add
   * @param isLogistician {@code true} to start as Logistician; {@code null} or {@code false} leaves
   *     the flag unset
   * @param isMissionManager {@code true} to start as Mission Manager; {@code null} or {@code false}
   *     leaves the flag unset
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the Spezialkommando
   *     or the user is unknown
   * @throws de.greluc.krt.profit.basetool.backend.kernel.DuplicateEntityException when the user is
   *     already a member
   */
  void addMember(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @Nullable Boolean isLogistician,
      @Nullable Boolean isMissionManager);

  /**
   * Removes the user from a Spezialkommando.
   *
   * @param specialCommandId the Spezialkommando
   * @param userId the user to remove
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the Spezialkommando
   *     is unknown or the user is not a member
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
   * @throws de.greluc.krt.profit.basetool.backend.kernel.NotFoundException when the Spezialkommando
   *     is unknown or the user is not a member
   * @throws org.springframework.orm.ObjectOptimisticLockingFailureException when the version is
   *     stale
   */
  void patchFlags(
      @NotNull UUID specialCommandId,
      @NotNull UUID userId,
      @Nullable Boolean isLogistician,
      @Nullable Boolean isMissionManager,
      @Nullable Long version);

  /**
   * Flushes the pending membership writes of the caller's transaction to the database, so a
   * constraint or optimistic-lock failure surfaces from the calling operation and not from the next
   * query. Runs the user's membership read, whose automatic flush is the one that has always ended
   * the membership delta; the rows it reads are not returned.
   *
   * @param userId the user whose memberships were changed
   */
  void flushMembershipChanges(@NotNull UUID userId);
}
