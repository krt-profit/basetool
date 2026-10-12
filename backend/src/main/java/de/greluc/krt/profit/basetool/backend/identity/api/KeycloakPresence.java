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

/**
 * Whether {@link AccountErasure#deleteAccount} must verify against Keycloak that the account is
 * really gone before it purges it.
 */
public enum KeycloakPresence {

  /** Verify against Keycloak that the account is gone; every admin-facing deletion. */
  ENFORCED,

  /**
   * Skip the probe: the caller removes the Keycloak user as part of the same operation, after this
   * transaction.
   */
  WAIVED_CALLER_REMOVES_THE_KEYCLOAK_USER
}
