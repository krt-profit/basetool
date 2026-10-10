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

import de.greluc.krt.profit.basetool.backend.exception.ProblemCode;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * The problem codes of the bank (REQ-API-019, ADR-0235).
 *
 * <p>The code string equals the constant's name. A constant documented as reserved is registered
 * but not emitted today.
 */
@RequiredArgsConstructor
public enum BankProblemCode implements ProblemCode {

  /** The booking would overdraw the account. */
  BANK_OVERDRAFT(HttpStatus.CONFLICT),

  /** Reserved, not emitted: a holder's custody would go negative (ADR-0039). */
  BANK_HOLDER_OVERDRAFT(HttpStatus.CONFLICT),

  /** The account still holds a balance. */
  BANK_ACCOUNT_NOT_EMPTY(HttpStatus.CONFLICT),

  /** The account is closed. */
  BANK_ACCOUNT_CLOSED(HttpStatus.CONFLICT),

  /** The grantee lacks the bank role the grant needs. */
  BANK_GRANTEE_MISSING_ROLE(HttpStatus.CONFLICT),

  /** Source and target are the same account. */
  BANK_SELF_TRANSFER(HttpStatus.CONFLICT),

  /** The transaction was reversed already. */
  BANK_ALREADY_REVERSED(HttpStatus.CONFLICT),

  /** The holder is inactive. */
  BANK_HOLDER_INACTIVE(HttpStatus.CONFLICT),

  /** The transaction cannot be reversed. */
  BANK_NOT_REVERSIBLE(HttpStatus.CONFLICT),

  /** The booking request is no longer pending. */
  BANK_REQUEST_NOT_PENDING(HttpStatus.CONFLICT),

  /** The booking request was approved already. */
  BANK_REQUEST_ALREADY_APPROVED(HttpStatus.CONFLICT),

  /** The account has pending booking requests. */
  BANK_ACCOUNT_HAS_PENDING_REQUESTS(HttpStatus.CONFLICT),

  /** The booking needs the owner's approval first. */
  BANK_OWNER_APPROVAL_REQUIRED(HttpStatus.CONFLICT),

  /** Reserved, not emitted: the cartel approval answers {@code 202} instead (ADR-0109). */
  BANK_CARTEL_APPROVAL_REQUIRED(HttpStatus.CONFLICT),

  /** A split booking has no targets. */
  BANK_SPLIT_NO_TARGETS(HttpStatus.CONFLICT),

  /** A split share would be too small. */
  BANK_SPLIT_TOO_SMALL(HttpStatus.CONFLICT),

  /** The booking needs a justification. */
  BANK_JUSTIFICATION_REQUIRED(HttpStatus.CONFLICT),

  /** The transfer fee exceeds the amount. */
  BANK_FEE_EXCEEDS_AMOUNT(HttpStatus.CONFLICT);

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
