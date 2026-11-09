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

package de.greluc.krt.profit.basetool.frontend.e2e;

import java.util.List;
import java.util.Map;

/**
 * The roles each account of {@code realm-export.e2e.json} holds in the backend's {@code user_roles}
 * (REQ-SEC-088); the realm itself carries no application role. {@code test-norole} holds none.
 */
final class E2eUserRoles {

  private static final String KRT_MEMBER = "KRT_MEMBER";

  /** Role codes by Keycloak username; an account missing here holds no role. */
  static final Map<String, List<String>> ROLES =
      Map.ofEntries(
          Map.entry("test-admin", List.of("ADMIN", KRT_MEMBER)),
          Map.entry("test-officer", List.of(KRT_MEMBER)),
          Map.entry("test-member", List.of(KRT_MEMBER)),
          Map.entry("test-sk", List.of(KRT_MEMBER)),
          Map.entry("test-none", List.of(KRT_MEMBER)),
          Map.entry("test-both", List.of(KRT_MEMBER)),
          Map.entry("test-bereich", List.of(KRT_MEMBER)),
          Map.entry("test-bank-employee", List.of("BANK_EMPLOYEE", KRT_MEMBER)),
          Map.entry("test-bank-management", List.of("BANK_MANAGEMENT", KRT_MEMBER)),
          Map.entry("test-bank-member", List.of("BANK_EMPLOYEE", KRT_MEMBER)),
          Map.entry("test-appoint-bl", List.of(KRT_MEMBER)),
          Map.entry("test-appoint-tgt", List.of(KRT_MEMBER)),
          Map.entry("test-exchange", List.of(KRT_MEMBER)),
          Map.entry("test-exchange-2", List.of(KRT_MEMBER)),
          Map.entry("test-exchange-3", List.of(KRT_MEMBER)),
          Map.entry("test-exchange-departed", List.of(KRT_MEMBER)));

  private E2eUserRoles() {}

  /**
   * Stores every account's roles, so the stack's first browser login already carries them.
   *
   * @param seeder the seeder that signs the accounts in and writes {@code user_roles}
   */
  static void seed(BackendSeeder seeder) {
    ROLES.forEach((username, roles) -> seeder.grantStoredRoles(username, username + "-pw", roles));
  }
}
