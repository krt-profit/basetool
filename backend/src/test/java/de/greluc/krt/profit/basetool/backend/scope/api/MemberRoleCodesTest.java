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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.Role;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The role codes a member holds come from the stable role code, not the display label. */
class MemberRoleCodesTest {

  @Test
  void aRenamedDisplayLabelKeepsTheCode() {
    Role member = new Role();
    member.setCode(Roles.KRT_MEMBER);
    member.setName("Kartell-Mitglied");
    member.setPermissions(Set.of());
    User user = new User();
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setRoles(new HashSet<>(Set.of(member)));

    assertThat(MemberRoleCodes.of(user, List.of())).containsExactly(Roles.KRT_MEMBER);
    assertThat(MemberRoleCodes.codeOf(member)).isEqualTo(Roles.KRT_MEMBER);
  }
}
