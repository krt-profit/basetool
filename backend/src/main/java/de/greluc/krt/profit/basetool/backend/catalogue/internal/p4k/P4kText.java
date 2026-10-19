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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/** Text decoding for localized P4K strings (REQ-DATA-025). */
final class P4kText {

  private static final String ESCAPED_LINE_BREAK = "\\n";

  /** Non-instantiable holder of static helpers. */
  private P4kText() {}

  /**
   * Turns the game localization's escaped line break, a literal backslash followed by {@code n},
   * into a real line break.
   *
   * @param value the localized text, may be {@code null}
   * @return the decoded text, or {@code null} when {@code value} is {@code null}
   */
  @Contract(value = "null -> null", pure = true)
  static @Nullable String decodeLineBreaks(@Nullable String value) {
    return value == null ? null : value.replace(ESCAPED_LINE_BREAK, "\n");
  }
}
