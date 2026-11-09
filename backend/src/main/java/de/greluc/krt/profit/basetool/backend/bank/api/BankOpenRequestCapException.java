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

package de.greluc.krt.profit.basetool.backend.bank.api;

import de.greluc.krt.profit.basetool.backend.kernel.DomainProblem;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a connected application raises a booking request for a member who already holds
 * {@code app.bank.exchange.open-request-cap} pending requests, whichever channel raised them
 * (REQ-BXCH-004).
 *
 * <p>Answers {@code 409} with code {@code BANK_OPEN_REQUEST_CAP}; nothing is raised.
 */
public final class BankOpenRequestCapException extends DomainProblem {

  /**
   * Creates the exception. The message is the i18n key of the localized detail, so the RFC 7807
   * {@code detail} reaches every client in the caller's language.
   */
  public BankOpenRequestCapException() {
    super("problem.bank_open_request_cap.detail");
  }

  /**
   * The status the refusal is answered with.
   *
   * @return {@code 409}
   */
  @NotNull
  @Override
  public HttpStatus status() {
    return BankProblemCode.BANK_OPEN_REQUEST_CAP.status();
  }

  /**
   * The stable code of the refusal.
   *
   * @return {@code BANK_OPEN_REQUEST_CAP}
   */
  @NotNull
  @Override
  public String code() {
    return BankProblemCode.BANK_OPEN_REQUEST_CAP.code();
  }

  /**
   * The label the handler's log line names the refusal by.
   *
   * @return {@code "Bank open request cap"}
   */
  @NotNull
  @Override
  public String logLabel() {
    return "Bank open request cap";
  }
}
