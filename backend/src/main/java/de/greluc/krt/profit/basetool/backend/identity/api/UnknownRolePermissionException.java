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

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/** Rejects a role permission outside the closed vocabulary (REQ-SEC-088). */
public final class UnknownRolePermissionException extends DomainProblem {

  /** Creates a refusal without disclosing submitted permission values. */
  public UnknownRolePermissionException() {
    super("problem.unknown_role_permission.detail");
  }

  @NotNull
  @Override
  public HttpStatus status() {
    return IdentityProblemCode.UNKNOWN_ROLE_PERMISSION.status();
  }

  @NotNull
  @Override
  public String code() {
    return IdentityProblemCode.UNKNOWN_ROLE_PERMISSION.code();
  }

  @NotNull
  @Override
  public String logLabel() {
    return "Unknown role permission";
  }
}
