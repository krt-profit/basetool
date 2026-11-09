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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A role a login earns in the Basetool itself: the Admin role of a bootstrap subject while no
 * active admin exists (REQ-SEC-088). Called by {@link UserReconciliationService} inside the login's
 * transaction; the implementation lives in {@code identity.internal}.
 */
public interface LoginRoleGrant {

  /**
   * Takes the lock the grant is checked under, before the login reads the user, so that concurrent
   * logins of one subject run one after the other and each sees the grant of the one before.
   *
   * @param subject the Keycloak {@code sub} of the logging-in user
   */
  void lockFor(@NotNull UUID subject);

  /**
   * Adds the earned role, with its permissions loaded, to the logging-in user, unless the user
   * holds it already or nothing is due.
   *
   * @param user the logging-in user, created or loaded, not necessarily persisted yet
   * @return {@code true} when a role was added and the caller must persist the user and then call
   *     {@link #recordGrant}
   */
  boolean grantIfDue(@NotNull User user);

  /**
   * Bumps the role-set counter of a user {@link #grantIfDue} gave a role and records the grant.
   *
   * @param user the persisted user
   */
  void recordGrant(@NotNull User user);
}
