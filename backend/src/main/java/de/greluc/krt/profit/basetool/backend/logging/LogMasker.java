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

package de.greluc.krt.profit.basetool.backend.logging;

import de.greluc.krt.profit.basetool.logging.PiiMaskingPatternLayout;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for masking sensitive / PII values at call-sites before they are handed to a logger.
 *
 * <p>This complements {@link PiiMaskingPatternLayout}, which masks well-known patterns on the
 * logback pattern layer as a last line of defense. {@code LogMasker} is the preferred approach
 * whenever a developer explicitly logs a known-sensitive value (e-mail, identifier, token, phone
 * number, Keycloak {@code sub}), because it keeps the information redacted even if the layout is
 * ever replaced or reconfigured.
 *
 * <p>All methods are null-safe and never throw on blank / malformed input – they simply return a
 * placeholder so that log statements do not risk NPEs.
 */
public final class LogMasker {

  /** Placeholder used when the input is {@code null} or blank. */
  public static final String NULL_PLACEHOLDER = "<null>";

  /** Replacement used when a value is completely hidden. */
  public static final String FULL_MASK = "***";

  private LogMasker() {}

  /**
   * Masks an e-mail address so that only the first character of the local part and the full domain
   * remain, e.g. {@code "alice@example.com" -> "a***@example.com"}.
   *
   * <p>If the input is not a valid e-mail (no {@code @}), the value is fully masked.
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
    String local = email.substring(0, at);
    String domain = email.substring(at);
    char first = local.charAt(0);
    return first + "***" + domain;
  }

  /**
   * Masks an identifier (database PK, Keycloak {@code sub}, UUID, correlation-id, ...) so that only
   * the first and last two characters remain, e.g. {@code "123456789" -> "12***89"}.
   *
   * <p>Values shorter than 5 characters are fully masked to avoid re-identification.
   */
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

  /**
   * Masks any token-like secret (JWT, API key, session id). The result never contains more than the
   * first 4 characters plus a fixed suffix, to keep the log useful for correlation while not
   * leaking the secret.
   */
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

  /**
   * Masks a phone number so that only the last two digits remain, e.g. {@code "+49 170 1234567" ->
   * "***67"}. Non-digit characters are stripped before masking.
   */
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

  /**
   * Generic fallback – fully hides the value but keeps length information (useful to detect
   * truncation/encoding issues without leaking content).
   */
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
