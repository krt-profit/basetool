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

package de.greluc.krt.profit.basetool.frontend.support;

import java.util.Collection;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Validates request parameters the frontend relays into backend URIs (REQ-SEC-051).
 *
 * <p>Every relayed value is either bound to a type that cannot carry URI syntax ({@link UUID},
 * {@link java.time.Instant}) or narrowed by one of these checks; invalid values are rejected, not
 * escaped.
 */
public final class RelayParams {

  /**
   * A Spring {@code Pageable} sort specification: a property path, optionally followed by a
   * direction. Bounded at 64 characters because the longest sort property in either module's DTOs
   * is well under that, and an unbounded pattern would let a caller push an arbitrarily long value
   * into the relayed query string.
   */
  private static final Pattern SORT_SPEC =
      Pattern.compile("[A-Za-z0-9_.]{1,64}(?:,(?:asc|desc|ASC|DESC))?");

  /** Non-instantiable holder of static relay-parameter checks. */
  private RelayParams() {}

  /**
   * Parses a relayed identifier into a {@link UUID}, mapping anything unparseable to {@code null}.
   *
   * @param raw the raw parameter value, may be {@code null} or blank
   * @return the parsed identifier, or {@code null} when {@code raw} is absent, blank or not a UUID
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable UUID uuidOrNull(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.strip());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Returns {@code raw} when it is a member of {@code allowed}, otherwise {@code null} ("no
   * filter").
   *
   * @param raw the raw parameter value, may be {@code null} or blank
   * @param allowed the permitted values; exact, case-sensitive match
   * @return {@code raw} when allowed, otherwise {@code null}
   */
  @Contract(value = "null, _ -> null", pure = true)
  public static @Nullable String oneOfOrNull(
      @Nullable String raw, @NotNull Collection<String> allowed) {
    if (raw == null || raw.isBlank() || !allowed.contains(raw)) {
      return null;
    }
    return raw;
  }

  /**
   * Returns {@code raw} when it is a well-formed Spring sort specification ({@code property} or
   * {@code property,asc|desc}), otherwise {@code null}; the backend whitelists the property itself.
   *
   * @param raw the raw parameter value, may be {@code null} or blank
   * @return {@code raw} when it is a well-formed sort specification, otherwise {@code null}
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable String sortSpecOrNull(@Nullable String raw) {
    if (raw == null || raw.isBlank() || !SORT_SPEC.matcher(raw).matches()) {
      return null;
    }
    return raw;
  }
}
