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

package de.greluc.krt.profit.basetool.backend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link Roles} (S3, #909): pins the byte-identical role-code values (Keycloak realm
 * roles / {@code Role.code}) and the {@code ROLE_}-prefixing helper's exact output.
 */
class RolesTest {

  @Test
  void bareRoleCodesAreByteIdenticalToThePriorLiterals() {
    assertEquals("ADMIN", Roles.ADMIN);
    assertEquals("OFFICER", Roles.OFFICER);
    assertEquals("KRT_MEMBER", Roles.KRT_MEMBER);
    assertEquals("BANK_EMPLOYEE", Roles.BANK_EMPLOYEE);
    assertEquals("BANK_MANAGEMENT", Roles.BANK_MANAGEMENT);
    assertEquals("LOGISTICIAN", Roles.LOGISTICIAN);
    assertEquals("MISSION_MANAGER", Roles.MISSION_MANAGER);
  }

  @Test
  void authorityPrependsTheRolePrefix() {
    assertEquals("ROLE_ADMIN", Roles.authority(Roles.ADMIN));
    assertEquals("ROLE_BANK_MANAGEMENT", Roles.authority(Roles.BANK_MANAGEMENT));
  }

  @Test
  void adminOrOfficerIsTheExpectedSpelExpression() {
    assertEquals("hasAnyRole('ADMIN','OFFICER')", Roles.ADMIN_OR_OFFICER);
  }
}
