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

package de.greluc.krt.profit.basetool.backend.kernel;

import org.jetbrains.annotations.Nullable;

/**
 * Thrown when an insert or update would violate a uniqueness constraint the service layer checks
 * explicitly, such as a duplicate Keycloak {@code sub}.
 *
 * <p>Mapped to HTTP {@code 409} with the code {@code DUPLICATE_ENTITY}.
 */
public final class DuplicateEntityException extends AppException {

  private final @Nullable String field;

  /**
   * Creates a {@code DuplicateEntityException} with a description of the duplicate.
   *
   * @param message human-readable description naming the entity and the duplicated identifier
   */
  public DuplicateEntityException(String message) {
    super(AppExceptionKind.DUPLICATE_ENTITY, message);
    field = null;
  }

  /**
   * Creates a uniqueness conflict attributed to a single input field.
   *
   * @param message localized message key or safe description
   * @param field the input field with the conflicting value
   * @param cause the failed write
   */
  public DuplicateEntityException(String message, String field, Throwable cause) {
    super(AppExceptionKind.DUPLICATE_ENTITY, message, cause);
    this.field = field;
  }

  /**
   * Returns the field to highlight for this uniqueness conflict.
   *
   * @return the field name, or null for a conflict without an input field
   */
  public @Nullable String field() {
    return field;
  }
}
