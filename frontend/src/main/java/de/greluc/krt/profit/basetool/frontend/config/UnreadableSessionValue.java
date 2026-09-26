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

package de.greluc.krt.profit.basetool.frontend.config;

import org.jetbrains.annotations.NotNull;

/**
 * Sentinel for a session value that could not be deserialized, so {@link
 * SessionAttributeDiagnosticMapper} can name the affected attribute before passing on {@code null}.
 *
 * <p>Holds only class names and fixed tokens, never the exception message, which may quote session
 * payload.
 *
 * @param cause simple name of the deepest cause, e.g. {@code InvalidTypeIdException}
 * @param typeId the unresolved type id, or one of {@link #TYPE_ID_ABSENT} / {@link
 *     #TYPE_ID_NOT_A_CLASS_NAME} / {@link #NOT_APPLICABLE}
 * @param baseType the type the id was resolved against, or {@link #NOT_APPLICABLE}
 */
record UnreadableSessionValue(
    @NotNull String cause, @NotNull String typeId, @NotNull String baseType) {

  /**
   * Rendered when the stored JSON carried no type id, which means the value was written by a final
   * runtime type (a record, an immutable JDK collection) that {@code NON_FINAL} default typing
   * cannot read back.
   */
  static final String TYPE_ID_ABSENT = "absent";

  /**
   * Rendered when Jackson reported a type id that does not look like a Java class name, e.g. the
   * first element of a list stored as a bare JSON array; the id itself would be payload and is not
   * logged (REQ-OBS-004).
   */
  static final String TYPE_ID_NOT_A_CLASS_NAME = "not-a-class-name";

  /** Rendered for a field the failure does not carry, e.g. a cause that is not a Jackson one. */
  static final String NOT_APPLICABLE = "n/a";
}
