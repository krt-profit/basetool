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

package de.greluc.krt.profit.basetool.backend.exception;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * Every problem code the backend emits, outside the exchange's frozen registry (REQ-API-019,
 * ADR-0235).
 *
 * <p>One kernel enum for now; the per-module enums of ADR-0235 split it when the modules exist. The
 * code string equals the constant's name. A constant documented as reserved is registered but not
 * emitted today.
 */
@RequiredArgsConstructor
public enum CoreProblemCode implements ProblemCode {

  /** A concurrent edit changed the row since the client read it. */
  OPTIMISTIC_LOCK(HttpStatus.CONFLICT),

  /** A row lock could not be taken in time. */
  PESSIMISTIC_LOCK(HttpStatus.CONFLICT),

  /** The caller may not do this. */
  ACCESS_DENIED(HttpStatus.FORBIDDEN),

  /** The request carries no valid token. */
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),

  /** A request body failed bean validation; {@code fieldErrors} names the fields. */
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST),

  /** A method parameter failed bean validation; {@code fieldErrors} names the parameters. */
  CONSTRAINT_VIOLATION(HttpStatus.BAD_REQUEST),

  /** The entity exists already. */
  DUPLICATE_ENTITY(HttpStatus.CONFLICT),

  /** An argument was refused by the domain. */
  ILLEGAL_ARGUMENT(HttpStatus.BAD_REQUEST),

  /** The request is malformed, or a client error without a code of its own. */
  BAD_REQUEST(HttpStatus.BAD_REQUEST),

  /** A path or query parameter has the wrong type. */
  TYPE_MISMATCH(HttpStatus.BAD_REQUEST),

  /** The database refused the write as inconsistent. */
  DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT),

  /** The resource does not exist or is not visible to the caller. */
  NOT_FOUND(HttpStatus.NOT_FOUND),

  /** The path does not accept this verb. */
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

  /** The request body's media type is not accepted. */
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

  /** An unexpected failure; {@code correlationId} finds it in the log. */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

  /** A business rule refused the change. */
  BUSINESS_CONFLICT(HttpStatus.CONFLICT),

  /** An allocation exceeds what the stock holds. */
  OVER_ALLOCATION(HttpStatus.UNPROCESSABLE_CONTENT),

  /** A production booking exceeds what the order's stock holds. */
  PRODUCTION_ALLOCATION(HttpStatus.UNPROCESSABLE_CONTENT),

  /** The write needs an owning org unit. */
  OWNER_ORG_UNIT_REQUIRED(HttpStatus.BAD_REQUEST),

  /** The write needs the caller to be a participant of the mission. */
  MISSION_PARTICIPANT_REQUIRED(HttpStatus.BAD_REQUEST),

  /** The entity is still referenced and cannot be removed. */
  ENTITY_IN_USE(HttpStatus.CONFLICT),

  /** An upstream service failed. */
  EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY),

  /** A report could not be generated. */
  REPORT_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),

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
  BANK_FEE_EXCEEDS_AMOUNT(HttpStatus.CONFLICT),

  /** The caller has not accepted the current terms of use. */
  TERMS_NOT_ACCEPTED(HttpStatus.FORBIDDEN),

  /** The caller's registration awaits approval. */
  PENDING_APPROVAL(HttpStatus.FORBIDDEN),

  /** The caller holds no role. */
  NO_ROLE(HttpStatus.FORBIDDEN),

  /** A relayed on-behalf-of request failed the acting-member checks. */
  ACTING_MEMBER_REFUSED(HttpStatus.FORBIDDEN),

  /** The identity provider or the server is unavailable; retry later. */
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

  /** A rate limit was exceeded; the rate-limit headers say when to retry. */
  RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),

  /** The request body exceeds the path's size limit. */
  REQUEST_BODY_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),

  /** Reserved, not emitted yet: the path was retired by a hard-cut wave; update the app (D-11). */
  APP_UPDATE_REQUIRED(HttpStatus.GONE);

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
