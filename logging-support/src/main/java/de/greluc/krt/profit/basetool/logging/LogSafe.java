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
 * Makes a client- or user-supplied string safe to put in a log line, in all three applications.
 *
 * <p>Who types the text differs per module, and none of them is harmless. In the <b>ingest</b>
 * gateway — the only internet-reachable module — it is the desktop extractor's free-text provenance
 * fields ({@code tool} / {@code toolVersion}). In the <b>backend</b> and the <b>frontend</b> it is
 * an authenticated squadron member, or a guest holding an edit link, typing into a search box, a
 * filter field or a form input. Such text is echoed into log lines (a rejected search term, a
 * validation failure, a relayed backend error), and without this guard a pasted newline followed by
 * a fabricated {@code ERROR ---} prefix would read as a genuine second log line while someone
 * triages an incident (CWE-117). A JSON string may legitimately contain {@code \n}, and neither the
 * logback pattern nor {@link PiiMasker} strips it.
 *
 * <p>Complements the maskers instead of replacing either. {@link PiiMasker} removes <em>secrets and
 * PII</em> from a line that already reached the appender; the backend's and frontend's {@code
 * LogMasker} redacts a <em>known-sensitive value</em> at the call site; this removes
 * <em>structure-breaking characters</em> from free text before it is handed to the logger. A value
 * that is both sensitive and user-supplied needs a masker <em>and</em> this.
 *
 * <p>Sanitising does not make a forbidden value loggable: REQ-OBS-004 still bans callsigns, names,
 * e-mail addresses, tokens and client IPs outright, whatever they were run through first.
 *
 * <p>This class has no dependency of its own, so the backend's ADR-0047 package-cycle rule — which
 * used to pin the backend copy to its {@code support} leaf — is satisfied from outside the backend
 * altogether (ADR-0205).
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
   * Returns {@code value} with every line-breaking character replaced by {@code '?'} and the result
   * capped at {@code maxLength} characters, so a hostile or malformed field can neither inject a
   * newline into the log nor blow up the line length.
   *
   * <p>Line-breaking covers every ISO control character <em>plus</em> {@link #LINE_SEPARATOR} and
   * {@link #PARAGRAPH_SEPARATOR}, which {@link Character#isISOControl(char)} does not classify as
   * controls although several log consumers break a line on them — see the two field Javadocs.
   *
   * @param value the untrusted input text; {@code null} or blank yields {@value #NONE}
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
   * Reports whether {@code c} could end the current log line for some consumer and let the rest of
   * the value read as a forged next line, covering the ISO controls plus the two Unicode separators
   * {@link Character#isISOControl(char)} leaves out.
   *
   * @param c the character to classify
   * @return {@code true} if {@code c} must be replaced before the value reaches an appender
   */
  @Contract(pure = true)
  private static boolean isLineBreaking(char c) {
    return Character.isISOControl(c) || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR;
  }
}
