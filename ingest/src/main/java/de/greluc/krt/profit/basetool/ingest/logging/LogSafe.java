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

package de.greluc.krt.profit.basetool.ingest.logging;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Makes a client-supplied string safe to put in a log line. The gateway is the only
 * internet-reachable module and its accepted payload carries free-text provenance fields (the
 * extractor's {@code tool} / {@code toolVersion}), so anything from the request body that reaches
 * an appender must first lose the characters that could forge a second log line — a JSON string may
 * legitimately contain {@code \n}, and neither the logback pattern nor {@link PiiMasker} strips it.
 *
 * <p>Complements rather than replaces {@link PiiMasker}: the masker removes <em>secrets and
 * PII</em> from anything that reached the appender, this removes <em>structure-breaking
 * characters</em> from a value before it is handed to the logger. A value that is both sensitive
 * and client-supplied needs both.
 */
public final class LogSafe {

  /** Rendered for a {@code null} or blank input, so the log line keeps a stable field count. */
  public static final String NONE = "none";

  /** Marker appended when the value was cut, so a truncated read is not mistaken for the input. */
  private static final String TRUNCATION_MARKER = "…";

  private LogSafe() {
    // Utility holder — not instantiable.
  }

  /**
   * Returns {@code value} with every ISO control character replaced by {@code '?'} and the result
   * capped at {@code maxLength} characters, so a hostile or malformed field can neither inject a
   * newline into the log nor blow up the line length.
   *
   * @param value the client-supplied text; {@code null} or blank yields {@value #NONE}
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
      sanitised.append(Character.isISOControl(c) ? '?' : c);
    }
    if (value.length() > maxLength) {
      sanitised.append(TRUNCATION_MARKER);
    }
    return sanitised.toString();
  }
}
