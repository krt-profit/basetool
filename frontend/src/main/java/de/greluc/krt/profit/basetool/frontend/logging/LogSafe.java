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

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Makes a user-supplied string safe to put in a log line.
 *
 * <p>Frontend counterpart of the backend {@code LogSafe}, kept module-local by the same
 * no-shared-module convention as {@link PiiMasker} and {@link LogMasker}. The realistic actor here
 * is an <em>authenticated squadron member</em> — or a guest holding an edit link — typing into a
 * search box, a filter field or a form input, not the open internet. That is precisely why the
 * guard is needed rather than optional: the UI layer echoes such text into log lines (a failed form
 * submit, a rejected filter, a backend error relayed to the user), and a member could otherwise
 * paste a newline followed by a fabricated {@code ERROR ---} prefix and have it read as a genuine
 * second log line while someone triages an incident (CWE-117). Neither the logback pattern nor the
 * maskers strip a {@code \n}.
 *
 * <p>Complements the two maskers instead of replacing either. {@link PiiMasker} removes <em>secrets
 * and PII</em> from a line that already reached the appender; {@link LogMasker} redacts a
 * <em>known-sensitive value</em> at the call site; this removes <em>structure-breaking
 * characters</em> from free text before it is handed to the logger. A value that is both sensitive
 * and user-supplied needs a masker <em>and</em> this.
 *
 * <p>Sanitising does not make a forbidden value loggable: REQ-OBS-004 still bans callsigns, names,
 * e-mail addresses, tokens and client IPs outright, whatever they were run through first.
 */
public final class LogSafe {
  // >>> LOGSAFE-MIRROR BEGIN
  // Everything between the two MIRROR markers is a hand-maintained mirror and must stay
  // byte-identical in the backend, frontend and ingest copies of this class; only the package line
  // and the class Javadoc above are module-local. The no-shared-module convention (the same one
  // PiiMasker and LogMasker follow) rules out extracting it, and the backend copy is additionally
  // pinned to the support leaf by the ADR-0047 cycle rule — so LogSafeTest compares the three
  // regions mechanically instead, and an edit to one copy that is not propagated fails the build.

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
  private static final char LINE_SEPARATOR = '\u2028'; // U+2028 LINE SEPARATOR

  /**
   * Unicode PARAGRAPH SEPARATOR (U+2029). Same blind spot as {@link #LINE_SEPARATOR}: not an ISO
   * control, so {@link Character#isISOControl(char)} misses it, yet a line break for the same
   * consumers.
   */
  private static final char PARAGRAPH_SEPARATOR = '\u2029'; // U+2029 PARAGRAPH SEPARATOR

  private LogSafe() {
    // Utility holder — not instantiable.
  }

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
  // <<< LOGSAFE-MIRROR END
}
