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

package de.greluc.krt.profit.basetool.backend.notification.api;

import org.jetbrains.annotations.NotNull;

/** The independent paths by which the recipient is entitled to retain a notification. */
public enum NotificationRoleAudience {
  /** A path independent of membership and bank employee eligibility. */
  DIRECT,
  /** A path through KRT Member, such as an org-unit seat or capability flag. */
  MEMBER,
  /** A path through a live bank-staff grant. */
  BANK_STAFF,
  /** Separate membership and bank-staff paths to the same notice. */
  MEMBER_OR_BANK_STAFF;

  /**
   * Combines two independently sufficient paths.
   *
   * @param other another delivery path
   * @return the combined audience
   */
  @NotNull
  public NotificationRoleAudience union(@NotNull NotificationRoleAudience other) {
    if (this == DIRECT || other == DIRECT) {
      return DIRECT;
    }
    return this == other ? this : MEMBER_OR_BANK_STAFF;
  }

  /**
   * Checks whether at least one delivery path is still available.
   *
   * @param member whether the recipient holds KRT Member
   * @param bankStaff whether the recipient still has bank-staff eligibility
   * @return whether the notice remains eligible
   */
  public boolean admits(boolean member, boolean bankStaff) {
    return switch (this) {
      case DIRECT -> true;
      case MEMBER -> member;
      case BANK_STAFF -> bankStaff;
      case MEMBER_OR_BANK_STAFF -> member || bankStaff;
    };
  }
}
