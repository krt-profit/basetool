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

/**
 * Thrown by the service layer for caller input that is semantically invalid in a way {@code @Valid}
 * cannot express, such as a business-rule violation or a cross-field constraint.
 *
 * <p>Mapped to HTTP {@code 400} with the code {@code BAD_REQUEST} ({@link
 * AppExceptionKind#BAD_REQUEST}).
 */
public final class BadRequestException extends AppException {

  /**
   * Creates a {@code BadRequestException} with a developer-facing detail message that is also
   * passed to the client as the RFC&nbsp;7807 {@code detail} field.
   *
   * @param message human-readable description of the rejected request
   */
  public BadRequestException(String message) {
    super(AppExceptionKind.BAD_REQUEST, message);
  }

  /**
   * Creates a {@code BadRequestException} that wraps a lower-level cause. The {@code cause} is kept
   * on the server side for logging only; the response body still uses {@code message} as the {@code
   * detail}.
   *
   * @param message human-readable description of the rejected request
   * @param cause underlying failure that triggered this exception
   */
  public BadRequestException(String message, Throwable cause) {
    super(AppExceptionKind.BAD_REQUEST, message, cause);
  }
}
