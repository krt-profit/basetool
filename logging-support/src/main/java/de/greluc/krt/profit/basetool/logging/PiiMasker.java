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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Regex-based PII and secret masking behind {@link PiiMaskingPatternLayout} and {@link
 * PiiMaskingLogstashEncoder} in all three applications (REQ-OBS-004, ADR-0205).
 *
 * <ul>
 *   <li>JWTs become {@code JWT_***}.
 *   <li>E-mail addresses become {@code ***@***.***}.
 *   <li>Values after {@code bearer}, {@code token}, {@code session-id} or {@code authorization}
 *       followed by a separator become {@code ***}.
 * </ul>
 *
 * <p>Replacements contain no quotes or backslashes, so masked JSON stays valid.
 */
public final class PiiMasker {

  private static final String JWT_PATTERN =
      "(eyJ[a-zA-Z0-9_-]{5,}\\.eyJ[a-zA-Z0-9_-]{5,}\\.[a-zA-Z0-9_-]{5,})";
  private static final String EMAIL_PATTERN =
      "([a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]{1,64}+@(?:[a-zA-Z0-9-]++\\.)++[a-zA-Z]{2,})";
  private static final String KEYWORD_TOKEN_PATTERN =
      "(?i)(bearer\\s+|(?:token|session[-_]?id|authorization)"
          + "(?:\\s*[:=]\\s*|\\s+)(?:bearer\\s+)?)([a-zA-Z0-9\\-_\\.+/=]+)";

  private static final Pattern PII_PATTERN =
      Pattern.compile(JWT_PATTERN + "|" + EMAIL_PATTERN + "|" + KEYWORD_TOKEN_PATTERN);

  private PiiMasker() {}

  /**
   * Replaces all detected PII and secret occurrences with fixed placeholders.
   *
   * @param input the raw log line or serialized JSON to scrub; may be {@code null}
   * @return the masked text, or the same {@code input} instance when it is {@code null}, empty or
   *     PII-free
   */
  @Contract(value = "null -> null; !null -> !null", pure = true)
  public static @Nullable String mask(@Nullable String input) {
    if (input == null || input.isEmpty()) {
      return input;
    }

    Matcher matcher = PII_PATTERN.matcher(input);
    if (!matcher.find()) {
      return input;
    }

    matcher.reset();
    StringBuilder sb = new StringBuilder(input.length());
    while (matcher.find()) {
      if (matcher.group(1) != null) {
        matcher.appendReplacement(sb, "JWT_***");
      } else if (matcher.group(2) != null) {
        matcher.appendReplacement(sb, "***@***.***");
      } else if (matcher.group(3) != null && matcher.group(4) != null) {
        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(3)) + "***");
      }
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
