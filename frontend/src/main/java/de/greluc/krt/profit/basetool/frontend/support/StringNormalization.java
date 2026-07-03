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

import java.text.Normalizer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Shared free-text normalization primitives for the frontend module, mirroring the backend's {@code
 * support.StringNormalization} (#906 Q6). Holds the single authoritative free-text length cap, the
 * NFC-normalize-and-length-check step, the full trim + empty-policy + normalize {@link
 * #normalize(String, int, boolean) pipeline} that {@code NormalizedStringEditor} delegates to, and
 * the {@link #blankToNull(String)} collapse the admin page controllers reuse instead of re-inlining
 * a private {@code blankToNull}/{@code emptyToNull} at each site.
 *
 * <p>The frontend keeps its own copy because the two Spring Boot modules share no library for this;
 * the semantics are identical to the backend so a value normalizes the same regardless of which
 * module bound it.
 */
public final class StringNormalization {

  /**
   * The single free-text length cap, matching the backend's {@code MAX_FREE_TEXT_LENGTH} and the
   * longest free-text column in the schema, declared once instead of as a bare {@code 8000} literal
   * at each binding site.
   */
  public static final int MAX_FREE_TEXT_LENGTH = 8000;

  /** Non-instantiable holder of static normalization helpers. */
  private StringNormalization() {}

  /**
   * NFC-normalizes {@code value} and enforces {@code maxLength}. NFC collapses Unicode combining
   * sequences into precomposed code points so equivalent spellings compare equal once persisted.
   * The caller is responsible for trimming and its own null/blank handling; {@code value} must be
   * non-null.
   *
   * @param value the already-trimmed, non-null value to canonicalize
   * @param maxLength the inclusive maximum allowed length after normalization
   * @return the NFC-normalized value
   * @throws IllegalArgumentException when the normalized value exceeds {@code maxLength}
   */
  public static String normalizeAndCap(String value, int maxLength) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException("String exceeds maximum allowed length of " + maxLength);
    }
    return normalized;
  }

  /**
   * Collapses a {@code null} or blank string to {@code null}, otherwise returns it unchanged.
   *
   * <p>"Blank" is {@link String#isBlank()} — a {@link Character#isWhitespace(int)}-based test — so
   * a value made up only of spaces, tabs or other Unicode whitespace maps to {@code null} while a
   * non-breaking space (U+00A0, deliberately not {@code isWhitespace}) counts as content. The
   * returned value is not stripped: this is the plain "empty means absent" collapse the alias /
   * blueprint admin controllers apply to optional external keys, codes and notes.
   *
   * @param value the candidate value, may be {@code null}
   * @return {@code null} when {@code value} is {@code null} or blank, otherwise {@code value}
   *     unchanged
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable String blankToNull(@Nullable String value) {
    return (value == null || value.isBlank()) ? null : value;
  }

  /**
   * Strips leading/trailing (Unicode) whitespace and collapses a {@code null} or blank result to
   * {@code null} — {@code value == null || value.isBlank() ? null : value.strip()}.
   *
   * <p>The trim-then-empty-to-{@code null} collapse the material-alias admin form applies to its
   * optional external key / code / note fields. Those values are already trimmed, NFC-normalized
   * and length-capped by the global {@code NormalizedStringEditor} before the controller sees them,
   * so this only re-asserts the "blank means cleared" invariant; standardizing on {@link
   * String#strip()} matches the backend's {@code StringNormalization.trimToNull}.
   *
   * @param value the candidate value, may be {@code null}
   * @return the stripped value, or {@code null} when {@code value} is {@code null} or blank
   */
  @Contract(value = "null -> null", pure = true)
  public static @Nullable String trimToNull(@Nullable String value) {
    return (value == null || value.isBlank()) ? null : value.strip();
  }

  /**
   * Full inbound-string pipeline: trim, optional empty-to-{@code null}, then NFC-normalize and cap.
   *
   * <p>Mirrors {@code NormalizedStringEditor}'s form-binding path exactly so the editor is a
   * one-line delegate: a {@code null} input stays {@code null}; the value is trimmed with {@link
   * String#trim()}; when {@code emptyAsNull} is set and the trimmed value is ASCII-empty it becomes
   * {@code null}; otherwise it is passed through {@link #normalizeAndCap(String, int)}.
   *
   * @param value the raw inbound value, may be {@code null}
   * @param maxLength the inclusive maximum length enforced after normalization
   * @param emptyAsNull whether a trimmed-empty value collapses to {@code null}
   * @return the trimmed, NFC-normalized, length-checked value, or {@code null}
   * @throws IllegalArgumentException when the normalized value exceeds {@code maxLength}
   */
  @Contract(value = "null, _, _ -> null", pure = true)
  public static @Nullable String normalize(
      @Nullable String value, int maxLength, boolean emptyAsNull) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (emptyAsNull && trimmed.isEmpty()) {
      return null;
    }
    return normalizeAndCap(trimmed, maxLength);
  }
}
