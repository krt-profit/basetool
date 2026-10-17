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

import de.greluc.krt.profit.basetool.backend.kernel.ProblemCode;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/** The problem codes of the identity module (REQ-API-019, ADR-0235). */
@RequiredArgsConstructor
public enum IdentityProblemCode implements ProblemCode {

  /** The member rank does not fit the member's stored Keycloak role (REQ-ROLE-007). */
  RANK_OUTSIDE_ROLE_RANGE(HttpStatus.BAD_REQUEST);

  /** The status the code is answered with. */
  private final HttpStatus status;

  /**
   * Returns the wire value, which is the constant's name.
   *
   * @return the code string
   */
  @NotNull
  @Override
  public String code() {
    return name();
  }

  /**
   * Returns the status the code is answered with.
   *
   * @return the HTTP status
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return status;
  }
}
