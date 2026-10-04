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
 * The generic problem codes of the error kernel: the handler's, the generic kinds' and the request
 * pipeline's (REQ-API-019, ADR-0235).
 *
 * <p>Each module's own codes are a {@link ProblemCode} enum in its {@code api} package. The code
 * string equals the constant's name. A constant documented as reserved is registered but not
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

  /** No media type the request accepts can carry the response. */
  NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),

  /** An unexpected failure; {@code correlationId} finds it in the log. */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

  /** A business rule refused the change. */
  BUSINESS_CONFLICT(HttpStatus.CONFLICT),

  /** The entity is still referenced and cannot be removed. */
  ENTITY_IN_USE(HttpStatus.CONFLICT),

  /** An upstream service failed. */
  EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY),

  /** A report could not be generated. */
  REPORT_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR),

  /** The caller has not accepted the current terms of use. */
  TERMS_NOT_ACCEPTED(HttpStatus.FORBIDDEN),

  /** The caller's registration awaits approval. */
  PENDING_APPROVAL(HttpStatus.FORBIDDEN),

  /** The caller holds no role. */
  NO_ROLE(HttpStatus.FORBIDDEN),

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
