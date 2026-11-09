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

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalRoleGrant;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives an approved registration the KRT Member role in the approval's transaction (REQ-SEC-088),
 * recorded like an assignment by the approving admin.
 */
@Component
@RequiredArgsConstructor
public class ApprovalMemberGrant implements ApprovalRoleGrant {

  private final RoleSetWriter writer;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void grantOnApproval(@NotNull User user, @NotNull UUID adminId) {
    if (RoleSetWriter.holds(user, AssignableRole.KRT_MEMBER)) {
      return;
    }
    writer.write(user, AssignableRole.KRT_MEMBER, true, user.getRolesVersion(), adminId);
  }
}
