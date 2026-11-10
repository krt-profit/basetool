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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** Pins immutable role projections without narrowing accepted permission values. */
class RoleReadModelTest {
  @Test
  void permissionSnapshotsPreserveNullMembersAndCannotBeChangedByEitherOwner() {
    Role role = new Role();
    HashSet<String> source = new HashSet<>(Arrays.asList("known.permission", null));
    role.setPermissions(source);
    RoleDto snapshot = RoleMapper.snapshot(role);
    assertThat(snapshot.permissions()).containsExactlyInAnyOrder("known.permission", null);
    source.add("later.permission");
    assertThat(snapshot.permissions()).doesNotContain("later.permission");
    assertThatThrownBy(() -> snapshot.permissions().add("mutation"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
