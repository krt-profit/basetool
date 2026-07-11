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
 * Escapes the SQL {@code LIKE}/{@code ILIKE} wildcard metacharacters ({@code %}, {@code _}) and the
 * escape character itself ({@code \}) in a user-supplied search fragment, so the fragment matches
 * <em>literally</em> instead of the user's {@code %}/{@code _} acting as wildcards (security review
 * — LIKE-pattern injection, not SQL injection: the term is always a bound parameter).
 *
 * <p>Relies on PostgreSQL's default {@code LIKE} escape character, the backslash, which applies to
 * the bound <em>parameter value</em> (not to the SQL string literal, so it is independent of {@code
 * standard_conforming_strings}); no explicit {@code ESCAPE} clause is therefore needed in the
 * queries. Escape the raw fragment with {@link #escape(String)} before it is wrapped in {@code
 * %…%}, whether the wrapping happens in Java or in JPQL via {@code CONCAT('%', :q, '%')}. The
 * backslash must be escaped first, or it would double-escape the {@code %}/{@code _} escapes.
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
   * Null-tolerant {@link #escape(String)} for the JPQL-{@code CONCAT} search sites, where a {@code
   * null} search term means "no filter" ({@code CAST(:q AS string) IS NULL}) and must stay {@code
   * null}.
   *
   * @param fragment the raw user search fragment, or {@code null} for no filter
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
