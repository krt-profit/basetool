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

package de.greluc.krt.profit.basetool.backend.exchange.internal;

import org.jetbrains.annotations.NotNull;

/**
 * Cuts the display names the exchange answers to the length the published v1 schemas allow
 * (REQ-XCH-026): the database stores names of up to 255 characters, while {@code location}, {@code
 * item-ref} and the other v1 schemas accept 200.
 */
final class ExchangeNames {

  /** The longest display name the published v1 schemas allow. */
  static final int MAX_NAME = 200;

  /** Not instantiable. */
  private ExchangeNames() {}

  /**
   * Cuts a display name to the published limit, never inside a surrogate pair.
   *
   * @param name the name
   * @return the name itself when it fits, else its longest prefix of at most {@value #MAX_NAME}
   *     characters that ends on a whole code point
   */
  static @NotNull String cut(@NotNull String name) {
    if (name.length() <= MAX_NAME) {
      return name;
    }
    return name.substring(
        0, Character.isHighSurrogate(name.charAt(MAX_NAME - 1)) ? MAX_NAME - 1 : MAX_NAME);
  }
}
