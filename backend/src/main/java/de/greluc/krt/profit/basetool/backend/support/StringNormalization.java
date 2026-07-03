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

package de.greluc.krt.profit.basetool.backend.support;

import java.text.Normalizer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Shared free-text normalization primitives for inbound string fields.
 *
 * <p>Both the JSON path ({@code NormalizedStringDeserializer}) and the form-binding path ({@code
 * NormalizedStringEditor}) must land a submitted value in the same canonical form so a JSON post
 * and a form post of the identical text reach the database identically. This class holds the pieces
 * they share — the single authoritative free-text length cap, the NFC-normalize-and-length-check
 * step, the full trim + empty-policy + normalize {@link #normalize(String, int, boolean) pipeline},
 * and the two blank-collapse primitives ({@link #blankToNull(String)}, {@link #trimToNull(String)})
 * that the service layer reuses instead of re-inlining the {@code null}/blank idiom at each note
 * field. The JSON deserializer keeps its own Unicode-blank-to-{@code null} policy inline because it
 * deliberately differs from the form editor's ASCII-empty policy.
 */
public final class StringNormalization {

  /**
   * The single free-text length cap, matching the longest free-text column in the schema. Shared by
   * the JSON deserializer and the form editor so the limit is declared in exactly one place instead
   * of being repeated as a bare {@code 8000} literal at each site.
   */
  public static final int MAX_FREE_TEXT_LENGTH = 8000;

  /** Non-instantiable holder of static normalization helpers. */
  private StringNormalization() {}

  /**
   * NFC-normalizes {@code value} and enforces {@code maxLength}. NFC collapses Unicode combining
   * sequences into precomposed code points so {@code "café"} (one code point) and {@code "cafe +
   * ́"} (two code points) canonicalize to the same string and compare equal in the database. The
   * caller is responsible for trimming and for its own null/blank handling before calling this;
   * {@code value} must be non-null.
   *
   * @param value the already-trimmed, non-null value to canonicalize
   * @param maxLength the inclusive maximum allowed length after normalization
   * @return the NFC-normalized value
   * @throws IllegalArgumentException when the normalized value exceeds {@code maxLength}; {@code
   *     GlobalExceptionHandler} maps this to an HTTP 400
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
   * a value made up only of spaces, tabs or other Unicode whitespace (e.g. an em space) maps to
   * {@code null}, whereas a non-breaking space (U+00A0, deliberately not {@code isWhitespace})
   * counts as content. Unlike {@link #trimToNull(String)} the returned value is <em>not</em>
   * stripped; this is the plain "empty means absent" collapse used by importer resolution code that
   * must leave an otherwise-present value byte-for-byte intact.
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
   * {@code null}.
   *
   * <p>Equivalent to {@code value == null || value.isBlank() ? null : value.strip()}: a {@code
   * null} or whitespace-only input yields {@code null}, otherwise the {@link String#strip()
   * stripped} value. This is the canonical free-text "note" collapse. Every inbound note has
   * already been trimmed, NFC-normalized and length-capped by the global binder ({@code
   * NormalizedStringDeserializer} for JSON, {@code NormalizedStringEditor} for forms), so at the
   * service layer this only re-asserts the "blank means cleared" invariant on an already-clean
   * value — it centralizes the idiom the note setters used to inline five different ways.
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
   * {@code null}; otherwise it is passed through {@link #normalizeAndCap(String, int)}. The {@code
   * emptyAsNull} flag lets a caller choose whether a blank input reads as "absent" ({@code null})
   * or as an explicitly-cleared empty string.
   *
   * @param value the raw inbound value, may be {@code null}
   * @param maxLength the inclusive maximum length enforced after normalization
   * @param emptyAsNull whether a trimmed-empty value collapses to {@code null}
   * @return the trimmed, NFC-normalized, length-checked value, or {@code null}
   * @throws IllegalArgumentException when the normalized value exceeds {@code maxLength}; {@code
   *     GlobalExceptionHandler} maps this to an HTTP 400
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
