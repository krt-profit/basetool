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

/**
 * Thrown when withdrawing the Admin role would leave no active admin (REQ-SEC-088).
 *
 * <p>Answers {@code 409} with code {@code LAST_ADMIN}; nothing is written.
 */
public final class LastAdminException extends DomainProblem {

  /** The bundle key the localised {@code detail} resolves from. */
  private static final String DETAIL_KEY = "problem.last_admin.detail";

  /** Creates the exception; its {@code detail} is the localised text of {@link #DETAIL_KEY}. */
  public LastAdminException() {
    super(DETAIL_KEY);
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 409}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return IdentityProblemCode.LAST_ADMIN.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code LAST_ADMIN}
   */
  @NotNull
  @Override
  public String code() {
    return IdentityProblemCode.LAST_ADMIN.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Last admin"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Last admin";
  }
}
