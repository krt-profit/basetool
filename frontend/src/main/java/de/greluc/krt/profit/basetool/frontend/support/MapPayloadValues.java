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

import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Coerces loosely typed values from JSON AJAX payload maps in the admin controllers. Each method
 * name states its fallback for an absent or unparseable value: {@code null}, {@code false} or
 * {@code 0L}.
 */
public final class MapPayloadValues {

  private MapPayloadValues() {}

  /**
   * Coerces a payload value to its string form, mapping an absent value to {@code null} (rather
   * than an empty string) so "field omitted" stays distinguishable downstream.
   *
   * @param value the raw payload value, or {@code null}
   * @return {@code String.valueOf(value)}, or {@code null} when {@code value} is {@code null}
   */
  public static @Nullable String stringOrNull(@Nullable Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * Coerces a payload value to a {@link UUID}, tolerating an absent or malformed value by returning
   * {@code null} instead of throwing.
   *
   * @param value the raw payload value, or {@code null}
   * @return the parsed UUID, or {@code null} for an absent / malformed value
   */
  @Contract("null -> null")
  public static @Nullable UUID uuidOrNull(@Nullable Object value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(String.valueOf(value));
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * Coerces a payload value to a boolean flag, treating an absent value as {@code false}. A {@link
   * Boolean} passes through directly; anything else is parsed via {@link Boolean#parseBoolean}.
   *
   * @param value the raw payload value, or {@code null}
   * @return the boolean value, {@code false} when absent
   */
  public static boolean booleanOrFalse(@Nullable Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  /**
   * Coerces a payload value to a {@link Long}, treating an absent or unparseable value as {@code
   * 0L}. A {@link Number} is narrowed via {@link Number#longValue()}; anything else is parsed via
   * {@link Long#parseLong}.
   *
   * @param value the raw payload value, or {@code null}
   * @return the long value, {@code 0L} when absent or unparseable
   */
  public static Long longOrZero(@Nullable Object value) {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (Exception ignored) {
      return 0L;
    }
  }
}
