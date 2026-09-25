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

package de.greluc.krt.profit.basetool.logging;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Makes a client- or user-supplied string safe to log by removing line-breaking characters and
 * bounding its length (CWE-117).
 *
 * <p>Complements {@link PiiMasker}, which removes secrets and PII; a sensitive user-supplied value
 * needs both. Sanitizing does not make a value forbidden by REQ-OBS-004 loggable.
 */
public final class LogSafe {

  /** Rendered for a {@code null} or blank input, so the log line keeps a stable field count. */
  public static final String NONE = "none";

  /** Marker appended when the value was cut, so a truncated read is not mistaken for the input. */
  private static final String TRUNCATION_MARKER = "…";

  /**
   * Unicode LINE SEPARATOR (U+2028). Replaced alongside the ISO controls even though {@link
   * Character#isISOControl(char)} returns {@code false} for it: several log viewers, JSON parsers
   * and JavaScript-based log consumers treat it as a line terminator, so leaving it in would reopen
   * the very forging vector this class exists to close — just against a different reader.
   */
  private static final char LINE_SEPARATOR = '\u2028';

  /**
   * Unicode PARAGRAPH SEPARATOR (U+2029). Same blind spot as {@link #LINE_SEPARATOR}: not an ISO
   * control, so {@link Character#isISOControl(char)} misses it, yet a line break for the same
   * consumers.
   */
  private static final char PARAGRAPH_SEPARATOR = '\u2029';

  private LogSafe() {}

  /**
   * Replaces every line-breaking character, including {@link #LINE_SEPARATOR} and {@link
   * #PARAGRAPH_SEPARATOR}, with {@code '?'} and caps the result at {@code maxLength} characters.
   *
   * @param value the untrusted input; {@code null} or blank yields {@value #NONE}
   * @param maxLength the maximum number of characters to keep; must be positive
   * @return a single-line, length-bounded rendering safe to log
   */
  @Contract(pure = true)
  public static @NotNull String text(@Nullable String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return NONE;
    }
    String cut = value.length() > maxLength ? value.substring(0, maxLength) : value;
    StringBuilder sanitised = new StringBuilder(cut.length());
    for (int i = 0; i < cut.length(); i++) {
      char c = cut.charAt(i);
      sanitised.append(isLineBreaking(c) ? '?' : c);
    }
    if (value.length() > maxLength) {
      sanitised.append(TRUNCATION_MARKER);
    }
    return sanitised.toString();
  }

  /**
   * Whether {@code c} can break a log line: an ISO control character or one of the two Unicode
   * separators {@link Character#isISOControl(char)} excludes.
   *
   * @param c the character to classify
   * @return {@code true} if {@code c} must be replaced before logging
   */
  @Contract(pure = true)
  private static boolean isLineBreaking(char c) {
    return Character.isISOControl(c) || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR;
  }
}
