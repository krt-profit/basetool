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
 * Unit test for {@link Permissions}: pins the permission-string values that exist as {@code
 * role_permissions} rows.
 */
class PermissionsTest {

  @Test
  void valuesAreByteIdenticalToThePriorLiterals() {
    assertEquals("HANGAR_READ", Permissions.HANGAR_READ);
    assertEquals("HANGAR_WRITE", Permissions.HANGAR_WRITE);
    assertEquals("MISSION_READ", Permissions.MISSION_READ);
    assertEquals("MISSION_WRITE", Permissions.MISSION_WRITE);
    assertEquals("MISSION_MANAGE", Permissions.MISSION_MANAGE);
    assertEquals("USER_MANAGE", Permissions.USER_MANAGE);
    assertEquals("ROLE_MANAGE", Permissions.ROLE_MANAGE);
  }
}
