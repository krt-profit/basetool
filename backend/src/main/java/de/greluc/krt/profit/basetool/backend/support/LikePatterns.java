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

package de.greluc.krt.profit.basetool.backend.support;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Escapes the {@code LIKE}/{@code ILIKE} metacharacters in a user search fragment so it matches
 * literally.
 *
 * <p>Relies on PostgreSQL's default backslash escape; escape the raw fragment before wrapping it in
 * {@code %…%}.
 */
public final class LikePatterns {

  private LikePatterns() {}

  /**
   * Escapes {@code \}, {@code %} and {@code _} in a search fragment so PostgreSQL {@code
   * LIKE}/{@code ILIKE} treats them as literal characters (backslash-escaped) rather than
   * wildcards.
   *
   * @param fragment the raw user search fragment (never {@code null})
   * @return the fragment with the three metacharacters backslash-escaped
   */
  @NotNull
  public static String escape(@NotNull String fragment) {
    return fragment.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /**
   * Null-tolerant {@link #escape(String)}, where {@code null} means "no filter" and stays {@code
   * null}.
   *
   * @param fragment the raw search fragment, or {@code null} for no filter
   * @return the escaped fragment, or {@code null} when {@code fragment} is {@code null}
   */
  @Contract("null -> null; !null -> !null")
  @Nullable
  public static String escapeNullable(@Nullable String fragment) {
    return fragment == null ? null : escape(fragment);
  }

  /**
   * Convenience for the Java-built pattern sites: escapes {@code fragment} and wraps it in {@code
   * %…%} so it becomes a literal-substring {@code LIKE} pattern.
   *
   * @param fragment the raw user search fragment (never {@code null})
   * @return {@code "%" + escape(fragment) + "%"}
   */
  @NotNull
  public static String contains(@NotNull String fragment) {
    return "%" + escape(fragment) + "%";
  }
}
