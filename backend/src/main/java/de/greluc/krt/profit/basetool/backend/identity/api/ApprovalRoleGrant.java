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
 * The role an approved registration earns: KRT Member (REQ-SEC-088). Called by {@link
 * UserRegistrationService} inside the approval's transaction; the implementation lives in {@code
 * identity.internal}.
 */
public interface ApprovalRoleGrant {

  /**
   * Gives the approved user KRT Member unless they hold it, bumping their role-set counter and
   * recording the assignment.
   *
   * @param user the persisted, just approved user
   * @param adminId the approving admin, recorded as the actor
   */
  void grantOnApproval(@NotNull User user, @NotNull UUID adminId);
}
