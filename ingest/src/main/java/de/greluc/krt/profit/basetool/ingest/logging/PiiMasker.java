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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based PII / secret masking for the ingest gateway's prod JSON log sink (used by {@link
 * PiiMaskingLogstashEncoder}). Mirrors the backend/frontend {@code PiiMasker} classes so all three
 * modules scrub the same patterns (REQ-OBS-004; each module keeps its own copy per the established
 * no-shared-module convention). Introduced by epic #936 Phase 1: the ingest prod appender
 * previously used the stock, unmasked {@code LogstashEncoder} — the last unmasked log stream in the
 * system, and a prerequisite for shipping ingest logs to Loki in Phase 2.
 *
 * <p>Patterns:
 *
 * <ul>
 *   <li>JWTs (three Base64URL segments separated by dots, header prefix {@code eyJ}) -&gt; {@code
 *       JWT_***}.
 *   <li>RFC 5322-ish e-mail addresses -&gt; {@code ***@***.***}.
 *   <li>Values introduced by the keywords {@code bearer}, {@code token}, {@code session-id} or
 *       {@code authorization} keep the keyword and replace the trailing value with {@code ***}.
 * </ul>
 *
 * <p>All replacements are alphanumeric only, never quotes or backslashes, so applying the masker on
 * top of a serialized JSON document leaves the JSON syntactically valid.
 */
public final class PiiMasker {

  private static final String JWT_PATTERN =
      "(eyJ[a-zA-Z0-9_-]{5,}\\.eyJ[a-zA-Z0-9_-]{5,}\\.[a-zA-Z0-9_-]{5,})";
  // Domain uses possessive label groups (?:label\.)++TLD so adjacent quantifiers cannot overlap —
  // avoids the O(n^2) backtracking the previous [a-zA-Z0-9.-]+\.[a-zA-Z]{2,} exhibited on long
  // no-TLD '@'-strings, which run on every log line (security audit L5).
  private static final String EMAIL_PATTERN =
      "([a-zA-Z0-9_!#$%&'*+/=?`{|}~^.-]+@(?:[a-zA-Z0-9-]++\\.)++[a-zA-Z]{2,})";
  // Value class includes the standard-base64 alphabet (+, /, =) so a base64 secret logged next to
  // one of these keywords is masked in full, not truncated at the first +/=/ (security audit L6).
  private static final String KEYWORD_TOKEN_PATTERN =
      "(?i)(bearer\\s+|token\\s*[:=]?\\s*|session[-_]?id\\s*[:=]?\\s*"
          + "|authorization\\s*[:=]?\\s*(?:bearer\\s+)?)([a-zA-Z0-9\\-_\\.+/=]+)";

  private static final Pattern PII_PATTERN =
      Pattern.compile(JWT_PATTERN + "|" + EMAIL_PATTERN + "|" + KEYWORD_TOKEN_PATTERN);

  private PiiMasker() {}

  /**
   * Returns {@code input} with all detected PII / secret occurrences replaced by fixed
   * placeholders. {@code null}, empty and PII-free inputs are returned as-is.
   *
   * @param input the raw log line or serialized JSON document to scrub; may be {@code null}.
   * @return the masked text, or {@code input} unchanged when it is {@code null} / empty / PII-free.
   */
  public static String mask(String input) {
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
