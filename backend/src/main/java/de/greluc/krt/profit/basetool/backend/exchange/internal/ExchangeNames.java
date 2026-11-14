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

import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * Cuts the display names the exchange answers to the length the published v1 schemas allow, and
 * finds what a cut name a client sends back stands for (REQ-XCH-026): the database stores names of
 * up to 255 characters, while {@code location}, {@code item-ref} and the other v1 schemas accept
 * 200.
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

  /**
   * Finds the entries a name a client sent back stands for as the cut of their longer name. Only a
   * name of {@value #MAX_NAME} characters, or one fewer where the cut stopped before a surrogate
   * pair, can be one; a caller tries the exact name first.
   *
   * @param sent the name the client sent
   * @param startingWith reads the entries whose full name starts with a prefix
   * @param nameOf an entry's full name
   * @param <T> the entry type
   * @return the entries longer than the limit whose cut name is {@code sent}; empty when {@code
   *     sent} cannot be a cut name
   */
  static <T> @NotNull List<T> cutTo(
      @NotNull String sent,
      @NotNull Function<String, ? extends Collection<T>> startingWith,
      @NotNull Function<T, String> nameOf) {
    if (sent.length() < MAX_NAME - 1 || sent.length() > MAX_NAME) {
      return List.of();
    }
    return startingWith.apply(sent).stream()
        .filter(entry -> {
          String full = nameOf.apply(entry);
          return full.length() > MAX_NAME && cut(full).equals(sent);
        })
        .toList();
  }
}
