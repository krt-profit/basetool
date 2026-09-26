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

package de.greluc.krt.profit.basetool.frontend.logging;

import de.greluc.krt.profit.basetool.logging.PiiMaskingPatternLayout;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Masks sensitive and PII values (e-mail, identifier, token, phone number, Keycloak {@code sub}) at
 * the call site before they are logged; complements {@link PiiMaskingPatternLayout}.
 *
 * <p>All methods are null-safe and never throw; they return a placeholder on blank or malformed
 * input.
 */
public final class LogMasker {

  /** Placeholder used when the input is {@code null} or blank. */
  public static final String NULL_PLACEHOLDER = "<null>";

  /** Replacement used when a value is completely hidden. */
  public static final String FULL_MASK = "***";

  private LogMasker() {}

  /**
   * Masks an e-mail address keeping only its first character and full domain ({@code a***@x.com}).
   */
  @Contract(pure = true)
  public static @NotNull String maskEmail(@Nullable String email) {
    if (isBlank(email)) {
      return NULL_PLACEHOLDER;
    }
    int at = email.indexOf('@');
    if (at <= 0 || at == email.length() - 1) {
      return FULL_MASK;
    }
    char first = email.charAt(0);
    return first + "***" + email.substring(at);
  }

  /** Masks an identifier keeping the first 2 and last 2 characters (short ids are fully masked). */
  @Contract(pure = true)
  public static @NotNull String maskId(@Nullable Object id) {
    if (id == null) {
      return NULL_PLACEHOLDER;
    }
    String value = id.toString();
    if (isBlank(value)) {
      return NULL_PLACEHOLDER;
    }
    if (value.length() < 5) {
      return FULL_MASK;
    }
    return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
  }

  /** Masks a token keeping only its first 4 characters; short tokens are fully masked. */
  @Contract(pure = true)
  public static @NotNull String maskToken(@Nullable String token) {
    if (isBlank(token)) {
      return NULL_PLACEHOLDER;
    }
    if (token.length() <= 8) {
      return FULL_MASK;
    }
    return token.substring(0, 4) + "***";
  }

  /** Masks a phone number keeping only its last two digits ({@code ***42}). */
  @Contract(pure = true)
  public static @NotNull String maskPhone(@Nullable String phone) {
    if (isBlank(phone)) {
      return NULL_PLACEHOLDER;
    }
    String digits = phone.replaceAll("\\D", "");
    if (digits.length() < 3) {
      return FULL_MASK;
    }
    return "***" + digits.substring(digits.length() - 2);
  }

  /** Generic fallback - replaces the value entirely with {@code ***(len=N)}. */
  @Contract(pure = true)
  public static @NotNull String mask(@Nullable Object value) {
    if (value == null) {
      return NULL_PLACEHOLDER;
    }
    String s = value.toString();
    if (isBlank(s)) {
      return NULL_PLACEHOLDER;
    }
    return "***(len=" + s.length() + ")";
  }

  @Contract(value = "null -> true", pure = true)
  private static boolean isBlank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
