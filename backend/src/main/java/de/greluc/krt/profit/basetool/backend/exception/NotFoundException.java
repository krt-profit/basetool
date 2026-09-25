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
 * Indicates that a requested domain entity does not exist; mapped to {@code 404 Not Found}.
 *
 * <p>Handled by its own {@code GlobalExceptionHandler} method rather than the generic {@link
 * AppException} dispatch; its accessors delegate to {@link AppExceptionKind#NOT_FOUND}.
 */
public final class NotFoundException extends AppException {

  /**
   * Creates a {@code NotFoundException} with a description of the missing entity.
   *
   * @param message human-readable description naming the entity type and identifier; surfaces as
   *     the RFC&nbsp;7807 {@code detail}
   */
  public NotFoundException(String message) {
    super(AppExceptionKind.NOT_FOUND, message);
  }

  /**
   * Creates a {@code NotFoundException} that wraps a lower-level cause (e.g. a downstream lookup
   * that itself raised an exception). The {@code cause} is kept on the server for logging only.
   *
   * @param message human-readable description naming the entity type and identifier
   * @param cause underlying failure
   */
  public NotFoundException(String message, Throwable cause) {
    super(AppExceptionKind.NOT_FOUND, message, cause);
  }
}
