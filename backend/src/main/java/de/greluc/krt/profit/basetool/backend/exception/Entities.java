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

import java.util.Optional;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;

/**
 * Fetch-or-throw helper for repository lookups: returns the value or throws a {@link
 * NotFoundException} (HTTP 404) with a caller-supplied message.
 *
 * <p>The message is always caller-supplied, never derived from the type, because {@code
 * GlobalExceptionHandler} may resolve it as an i18n key.
 */
public final class Entities {

  /** Non-instantiable static-helper holder. */
  private Entities() {}

  /**
   * Returns the value of {@code optional}, or throws {@link NotFoundException} with the constant
   * {@code message} when it is empty.
   *
   * @param optional the repository-lookup result to unwrap
   * @param message the RFC&nbsp;7807 {@code detail} raised when the entity is absent
   * @param <T> the entity type
   * @return the contained value
   * @throws NotFoundException when {@code optional} is empty
   */
  public static <T> @NotNull T require(@NotNull Optional<T> optional, @NotNull String message) {
    return optional.orElseThrow(() -> new NotFoundException(message));
  }

  /**
   * Returns the value of {@code optional}, or throws {@link NotFoundException} with a lazily built
   * {@code message} when it is empty.
   *
   * @param optional the repository-lookup result to unwrap
   * @param message supplies the RFC&nbsp;7807 {@code detail}; invoked only on a miss
   * @param <T> the entity type
   * @return the contained value
   * @throws NotFoundException when {@code optional} is empty
   */
  public static <T> @NotNull T require(
      @NotNull Optional<T> optional, @NotNull Supplier<String> message) {
    return optional.orElseThrow(() -> new NotFoundException(message.get()));
  }
}
